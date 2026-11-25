#!/usr/bin/env python3
"""Dedicated local queue load and Redis outage verification; no development DB writes."""
import argparse
import concurrent.futures
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
COMPOSE = ["docker", "compose", "-p", "open-rush-distributed-benchmark",
           "-f", str(ROOT / "benchmarks/compose.yaml")]
KEY_SUFFIXES = ["sequence", "waiting", "waiting-expiry", "admitted", "processing",
                "tickets", "users", "next-admission-at"]


def command(arguments, **kwargs):
    return subprocess.run(arguments, cwd=ROOT, text=True, check=True, **kwargs)


def database(sql=None, *, script=None, requests=1):
    arguments = COMPOSE + ["exec", "-T", "postgres", "psql", "-XAt", "-v", "ON_ERROR_STOP=1",
                           "-U", "benchmark", "-d", "open_rush_benchmark"]
    if script:
        arguments += ["-v", f"requests={requests}", "-v", "scenario=queue"]
        sql = (ROOT / "benchmarks" / script).read_text()
    return command(arguments, input=sql, capture_output=True).stdout.strip()


def redis(*arguments):
    return command(COMPOSE + ["exec", "-T", "redis", "redis-cli", "--raw", *arguments],
                   capture_output=True).stdout.strip()


def request(port, path, body=None, headers=None, timeout=5):
    encoded = None if body is None else json.dumps(body).encode()
    outgoing = urllib.request.Request(f"http://127.0.0.1:{port}{path}", encoded,
                                     {"Content-Type": "application/json", **(headers or {})})
    started = time.monotonic()
    try:
        with urllib.request.urlopen(outgoing, timeout=timeout) as response:
            status, raw = response.status, response.read()
    except urllib.error.HTTPError as response:
        status, raw = response.code, response.read()
    return {"status": status, "body": json.loads(raw) if raw else None,
            "elapsedMs": round((time.monotonic() - started) * 1000, 2)}


def wait_until(action, description, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if action():
                return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(0.1)
    raise RuntimeError(f"Timeout: {description}")


class Applications:
    def __init__(self, output, count, *, fault=False, max_in_flight=32):
        self.processes = []
        self.logs = []
        self.ports = [18101 + index for index in range(count)]
        self.prefix = f"queue:{{load-{output.parent.name}-{output.name}}}"
        output.mkdir(parents=True, exist_ok=True)
        java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else "java"
        for index, port in enumerate(self.ports):
            log = (output / f"app-{index + 1}.log").open("w")
            self.logs.append(log)
            arguments = [java, "-Xms256m", "-Xmx512m", "-jar",
                         str(ROOT / "build/libs/open-rush-0.0.1-SNAPSHOT.jar"),
                         "--spring.profiles.active=benchmark", "--server.address=127.0.0.1", f"--server.port={port}",
                         "--spring.datasource.url=jdbc:postgresql://127.0.0.1:55432/open_rush_benchmark",
                         "--spring.datasource.username=benchmark", "--spring.datasource.password=benchmark",
                         "--spring.data.redis.url=redis://127.0.0.1:56379",
                         "--reservation.expiration.enabled=false",
                         "--queue.enabled=true", f"--queue.key-prefix={self.prefix}",
                         f"--queue.admissions-per-second={20 if fault else 10000}",
                         f"--queue.max-in-flight={1 if fault else max_in_flight}",
                         "--queue.capacity=10000", "--queue.permit-ttl-ms=60000",
                         f"--queue.processing-ttl-ms={5000 if fault else 30000}",
                         f"--spring.datasource.hikari.maximum-pool-size={16 // count}",
                         f"--spring.datasource.hikari.minimum-idle={16 // count}",
                         f"--spring.datasource.hikari.data-source-properties.ApplicationName=queue-load-node-{index + 1}"]
            self.processes.append(subprocess.Popen(arguments, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT))
        try:
            for port in self.ports:
                wait_until(lambda: request(port, "/actuator/health")["status"] == 200,
                           f"application {port} startup", timeout=60)
        except BaseException:
            self.close()
            raise

    def reset(self, requests):
        database(script="seed.sql", requests=requests)
        redis("DEL", *[f"{self.prefix}:{suffix}" for suffix in KEY_SUFFIXES])

    def close(self):
        for process in self.processes:
            if process.poll() is None:
                process.terminate()
        for process in self.processes:
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        for log in self.logs:
            log.close()


def run_load(applications, output, rate, duration, label, virtual_users):
    applications.reset(rate * duration)
    snapshots = []
    stopped = threading.Event()

    def monitor():
        while not stopped.wait(1):
            snapshot = {"time": time.time(), "instances": []}
            for port in applications.ports:
                values = {}
                for metric in ("hikaricp.connections.active", "hikaricp.connections.pending"):
                    try:
                        response = request(port, f"/actuator/metrics/{metric}", timeout=2)
                        values[metric] = response["body"].get("measurements")
                    except (OSError, TypeError, AttributeError):
                        values[metric] = None
                snapshot["instances"].append(values)
            snapshots.append(snapshot)

    thread = threading.Thread(target=monitor, daemon=True)
    thread.start()
    summary = output / f"{label}.json"
    arguments = ["k6", "run", "--quiet", "-e",
                 "BASE_URLS=" + ",".join(f"http://127.0.0.1:{port}" for port in applications.ports),
                 "-e", f"RATE={rate}", "-e", f"DURATION_SECONDS={duration}",
                 "-e", f"VUS={virtual_users}", "-e", f"SUMMARY={summary}",
                 str(ROOT / "benchmarks/distributed-queue.js")]
    try:
        with (output / f"{label}.log").open("w") as log:
            result = subprocess.run(arguments, cwd=ROOT, text=True, stdout=log, stderr=subprocess.STDOUT)
    finally:
        stopped.set()
        thread.join(timeout=10)

    report = json.loads(summary.read_text())
    wait_until(lambda: int(database("SELECT count(*) FROM pg_stat_activity "
               "WHERE application_name LIKE 'queue-load-node-%' AND state <> 'idle';")) == 0,
               "application database work completion", timeout=15)
    persisted = int(database("SELECT count(*) FROM reservations;"))
    report.update({"k6ExitCode": result.returncode, "persisted": persisted, "virtualUsers": virtual_users,
                   "dbVerification": database(script="verify.sql", requests=rate * duration),
                   "queueRemaining": {
                       "tickets": int(redis("HLEN", f"{applications.prefix}:tickets")),
                       "users": int(redis("HLEN", f"{applications.prefix}:users")),
                       **{state: int(redis("ZCARD", f"{applications.prefix}:{state}"))
                          for state in ("waiting", "admitted", "processing")},
                   }, "poolSamples": snapshots})
    report["clientAndDatabaseMatch"] = persisted == report["completed"]
    summary.write_text(json.dumps(report, indent=2))
    print(json.dumps({key: report[key] for key in (
        "instances", "targetRate", "started", "completed", "failureRate", "dropped",
        "reservationP95", "journeyP95", "persisted", "k6ExitCode")}), flush=True)
    if not report["clientAndDatabaseMatch"]:
        print("Client/DB count mismatch: investigate uncertain commits", flush=True)
    return report


def run_fault(output):
    applications = Applications(output, 2, fault=True)
    events = {}
    lock_process = None
    executor = concurrent.futures.ThreadPoolExecutor(max_workers=1)
    redis_stopped = False
    try:
        applications.reset(10)
        first = request(18101, "/reservation-queue", {"userId": 1})
        second = request(18102, "/reservation-queue", {"userId": 2})
        assert first["body"]["status"] == "ADMITTED", first
        assert second["body"]["status"] == "WAITING", second
        first_ticket, second_ticket = first["body"]["ticketId"], second["body"]["ticketId"]
        events["beforeOutage"] = {"first": first, "second": second}
        lock_process = subprocess.Popen(COMPOSE + ["exec", "-T", "-e", "PGAPPNAME=redis-fault-lock",
            "postgres", "psql", "-XAt", "-v", "ON_ERROR_STOP=1", "-U", "benchmark", "-d", "open_rush_benchmark"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        lock_process.stdin.write("BEGIN; SELECT id FROM performance_seats WHERE id=1 FOR NO KEY UPDATE;\n\\echo LOCKED\n")
        lock_process.stdin.flush()
        while lock_process.stdout.readline().strip() != "LOCKED":
            if lock_process.poll() is not None:
                raise RuntimeError("Could not acquire benchmark seat lock")

        body = {"userId": 1, "performanceId": 1, "seatIds": [1]}
        headers = {"X-Queue-Ticket": first_ticket, "Idempotency-Key": "redis-outage-reservation"}
        pending = executor.submit(request, 18102, "/reservations", body, headers, 15)
        wait_until(lambda: int(database("SELECT count(*) FROM pg_stat_activity WHERE cardinality(pg_blocking_pids(pid)) > 0;")) > 0,
                   "reservation blocked on owned seat", timeout=3)
        events["processingBeforeOutage"] = redis("HGET", f"{applications.prefix}:tickets", first_ticket)
        command(COMPOSE + ["stop", "redis"], capture_output=True)
        redis_stopped = True
        lock_process.stdin.write("COMMIT;\n\\q\n")
        lock_process.stdin.flush()
        lock_process.wait(timeout=5)
        events["inFlightReservation"] = pending.result(timeout=10)
        assert events["inFlightReservation"]["status"] == 201, events
        reservation_id = events["inFlightReservation"]["body"]["reservationId"]
        events["duringOutage"] = {
            "join": request(18101, "/reservation-queue", {"userId": 3}),
            "status": request(18102, f"/reservation-queue/{second_ticket}"),
            "consume": request(18101, "/reservations", {"userId": 2, "performanceId": 2, "seatIds": [2]},
                               {"X-Queue-Ticket": second_ticket}),
            "health": request(18101, "/actuator/health"),
            "liveness": request(18101, "/actuator/health/liveness"),
            "readiness": request(18101, "/actuator/health/readiness"),
            "readReservation": request(18102, f"/reservations/{reservation_id}?userId=1"),
            "replay": request(18101, "/reservations", body, {"Idempotency-Key": "redis-outage-reservation"}),
        }
        for name in ("join", "status", "consume"):
            assert events["duringOutage"][name]["status"] == 503, events
            assert events["duringOutage"][name]["body"]["code"] == "QUEUE_UNAVAILABLE", events
        assert events["duringOutage"]["readReservation"]["status"] == 200, events
        assert events["duringOutage"]["replay"]["status"] == 201, events

        with concurrent.futures.ThreadPoolExecutor(max_workers=40) as outage_executor:
            responses = list(outage_executor.map(
                lambda index: request(18101 + index % 2, "/reservation-queue", {"userId": 10000 + index}, timeout=10),
                range(100),
            ))
        durations = sorted(response["elapsedMs"] for response in responses)
        events["outageConcurrentRegistrations"] = {
            "requests": len(responses), "concurrency": 40,
            "statuses": {str(status): sum(response["status"] == status for response in responses)
                         for status in {response["status"] for response in responses}},
            "p95Ms": durations[94], "maxMs": max(durations),
        }
        assert all(response["status"] == 503 and response["body"]["code"] == "QUEUE_UNAVAILABLE"
                   for response in responses), events
        resumed_at = time.monotonic()
        command(COMPOSE + ["start", "redis"], capture_output=True)
        redis_stopped = False
        wait_until(lambda: request(18101, "/actuator/health")["status"] == 200,
                   "Redis reconnection and health recovery")
        wait_until(lambda: request(18102, f"/reservation-queue/{second_ticket}")["body"].get("status") == "ADMITTED",
                   "abandoned processing permit reclamation")
        events["afterRecovery"] = {
            "recoveryMs": round((time.monotonic() - resumed_at) * 1000, 2),
            "oldTicket": request(18101, f"/reservation-queue/{first_ticket}"),
            "waitingTicket": request(18102, f"/reservation-queue/{second_ticket}"),
            "scriptCached": redis("SCRIPT", "EXISTS", hashlib.sha1(
                (ROOT / "src/main/resources/redis/reservation-queue.lua").read_bytes()).hexdigest()),
            "failedRegistrationsPresentAfterRecovery": len([
                value for value in redis("HMGET", f"{applications.prefix}:users", "3",
                                         *[str(10000 + index) for index in range(100)]).splitlines() if value
            ]),
            "persisted": int(database("SELECT count(*) FROM reservations;")),
            "dbVerification": database(script="verify.sql", requests=10),
        }
        assert events["afterRecovery"]["oldTicket"]["status"] == 404, events
        assert events["afterRecovery"]["persisted"] == 1, events
        assert events["afterRecovery"]["scriptCached"] == "1", events
        events["passed"] = True
        print("Redis outage verification: PASS", flush=True)
    finally:
        if redis_stopped:
            command(COMPOSE + ["start", "redis"], capture_output=True)
        if lock_process and lock_process.poll() is None:
            lock_process.communicate("ROLLBACK;\n\\q\n", timeout=5)
        executor.shutdown(wait=True)
        applications.close()
        (output / "fault.json").write_text(json.dumps(events, indent=2))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--instances", default="1,2,4")
    parser.add_argument("--rates", default="100,300,600")
    parser.add_argument("--duration", type=int, default=30)
    parser.add_argument("--vus", type=int, default=2000)
    parser.add_argument("--max-in-flight", type=int, default=32)
    parser.add_argument("--warmup-duration", type=int, default=30)
    parser.add_argument("--fault-only", action="store_true")
    parser.add_argument("--skip-fault", action="store_true")
    options = parser.parse_args()
    instances = [int(value) for value in options.instances.split(",")]
    rates = [int(value) for value in options.rates.split(",")]
    if (any(value not in (1, 2, 4) for value in instances) or min(rates) <= 0 or options.duration <= 0
            or options.vus <= 0 or options.warmup_duration <= 0 or not 1 <= options.max_in_flight <= 10000):
        parser.error("instances must be 1,2,4; rates and duration must be positive")
    for port in (18101, 18102, 18103, 18104):
        try:
            request(port, "/actuator/health")
        except (OSError, urllib.error.URLError):
            continue
        raise RuntimeError(f"Port {port} is already in use")

    stamp = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9))).strftime("%Y%m%d-%H%M%S")
    output = ROOT / "build/benchmark" / f"distributed-{stamp}"
    output.mkdir(parents=True)
    environment = {
        "commit": command(["git", "rev-parse", "HEAD"], capture_output=True).stdout.strip(),
        "docker": command(["docker", "info", "--format", "{{.NCPU}} CPUs / {{.MemTotal}} bytes"], capture_output=True).stdout.strip(),
        "k6": command(["k6", "version"], capture_output=True).stdout.strip(),
        "java": command(["java", "-version"], capture_output=True).stderr.strip(),
        "options": vars(options), "databaseConnectionsTotal": 16, "admissionRate": 10000,
        "maxInFlight": options.max_in_flight,
    }
    (output / "environment.json").write_text(json.dumps(environment, indent=2))
    initial_services = command(COMPOSE + ["ps", "--status", "running", "--services"], capture_output=True).stdout.split()
    reports = []
    try:
        command(COMPOSE + ["up", "-d", "--wait"])
        command([str(ROOT / "gradlew"), "bootJar"])
        if not options.fault_only:
            for count in instances:
                topology_output = output / f"instances-{count}"
                applications = Applications(topology_output, count, max_in_flight=options.max_in_flight)
                try:
                    run_load(applications, topology_output, 100 * count, options.warmup_duration, "warmup", options.vus)
                    for rate in rates:
                        reports.append(run_load(applications, topology_output, rate, options.duration, f"rate-{rate}", options.vus))
                finally:
                    applications.close()
        if not options.skip_fault:
            run_fault(output / "redis-fault")
    finally:
        (output / "results.json").write_text(json.dumps(reports, indent=2))
        newly_started = [service for service in ("redis", "postgres") if service not in initial_services]
        if newly_started:
            command(COMPOSE + ["stop", *newly_started], capture_output=True)
        print(f"Results: {output}", flush=True)


if __name__ == "__main__":
    main()
