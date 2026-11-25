#!/usr/bin/env python3
"""Crash a primary, verify Sentinel promotion, app recovery and replica rejoin."""
import argparse
import concurrent.futures
import datetime
import hashlib
import json
from pathlib import Path
import subprocess
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
REDIS_NODES = ("redis-1", "redis-2", "redis-3")
SENTINEL_NODES = ("sentinel-1", "sentinel-2", "sentinel-3")
KEY_PREFIX = "queue:{sentinel-test}"
KEY_SUFFIXES = ("sequence", "waiting", "waiting-expiry", "admitted", "processing",
                "tickets", "users", "next-admission-at")
SCRIPT_SHA = hashlib.sha1((ROOT / "src/main/resources/redis/reservation-queue.lua").read_bytes()).hexdigest()


def request(port, path, body=None, headers=None, timeout=5):
    started = time.monotonic()
    outgoing = urllib.request.Request(
        f"http://127.0.0.1:{port}{path}",
        None if body is None else json.dumps(body).encode(),
        {"Content-Type": "application/json", **(headers or {})},
    )
    try:
        with urllib.request.urlopen(outgoing, timeout=timeout) as response:
            status, raw = response.status, response.read()
    except urllib.error.HTTPError as response:
        status, raw = response.code, response.read()
    except OSError as exception:
        return {"status": 0, "error": str(exception), "elapsedMs": (time.monotonic() - started) * 1000}
    return {"status": status, "body": json.loads(raw) if raw else None,
            "elapsedMs": round((time.monotonic() - started) * 1000, 2)}


def wait_until(action, description, timeout=45):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            value = action()
            if value:
                return value
        except (subprocess.CalledProcessError, OSError):
            pass
        time.sleep(0.1)
    raise RuntimeError(f"Timeout: {description}")


class SentinelExperiment:
    def __init__(self, project):
        self.compose = ["docker", "compose", "-p", project, "-f", str(ROOT / "docker-compose.sentinel.yml")]
        self.current_phase = None

    def command(self, arguments, *, input=None):
        return subprocess.run(arguments, cwd=ROOT, input=input, text=True, check=True, capture_output=True, timeout=180)

    def run(self, *arguments, input=None):
        return self.command(self.compose + list(arguments), input=input).stdout.strip()

    def redis(self, service, *arguments, input=None):
        port = "26379" if service.startswith("sentinel-") else "6379"
        return self.run("exec", "-T", service, "redis-cli", "--raw", "-p", port, *arguments, input=input)

    def primary(self, sentinel="sentinel-1"):
        address = self.redis(sentinel, "SENTINEL", "get-master-addr-by-name", "open-rush").splitlines()
        if len(address) != 2 or address[0] not in REDIS_NODES:
            raise RuntimeError(f"Unexpected Sentinel primary address: {address}")
        return address[0]

    def replication(self, service):
        return dict(line.split(":", 1) for line in self.redis(service, "INFO", "replication").splitlines() if ":" in line)

    def application_identity(self):
        return {
            service: self.command(["docker", "inspect", "--format", "{{.State.StartedAt}} {{.RestartCount}}",
                                   self.run("ps", "-q", service)]).stdout.strip()
            for service in ("app-1", "app-2")
        }

    def barrier(self, primary):
        # WAIT는 동일 연결의 쓰기를 기다린다. Fixture 뒤에 SET한 후 같은 연결에서 WAIT한다.
        result = self.redis(primary, input=f"SET sentinel-fixture-barrier {time.time_ns()}\nWAIT 2 5000\n")
        assert result.splitlines() == ["OK", "2"], result

    def check_replica(self, replica, primary):
        state = self.replication(replica)
        return state.get("role") == "slave" and state.get("master_host") == primary and state.get("master_link_status") == "up"

    def seed(self):
        self.run("exec", "-T", "postgres", "psql", "-Xq", "-v", "ON_ERROR_STOP=1", "-v", "requests=10",
                 "-U", "benchmark", "-d", "open_rush_benchmark", input=(ROOT / "benchmarks/seed.sql").read_text())

    def verify_database(self):
        return self.run("exec", "-T", "postgres", "psql", "-Xq", "-v", "ON_ERROR_STOP=1",
                        "-v", "requests=10", "-v", "scenario=queue", "-U", "benchmark", "-d", "open_rush_benchmark",
                        input=(ROOT / "benchmarks/verify.sql").read_text())

    def phase(self, number, *, missing_sentinel=None):
        primary_sentinel = "sentinel-2" if missing_sentinel else "sentinel-1"
        old_primary = self.primary(primary_sentinel)
        self.redis(old_primary, "DEL", *[f"{KEY_PREFIX}:{suffix}" for suffix in KEY_SUFFIXES])
        users = (number * 10 + 1, number * 10 + 2)
        first = request(18201, "/reservation-queue", {"userId": users[0]})
        second = request(18202, "/reservation-queue", {"userId": users[1]})
        assert first["status"] == 200 and first["body"]["status"] == "ADMITTED", first
        assert second["status"] == 200 and second["body"]["status"] == "WAITING", second
        tickets = (first["body"]["ticketId"], second["body"]["ticketId"])
        self.barrier(old_primary)
        before_identity = self.application_identity()
        caches_before = {node: self.redis(node, "SCRIPT", "EXISTS", SCRIPT_SHA) for node in REDIS_NODES}
        report = {"oldPrimary": old_primary, "missingSentinel": missing_sentinel, "fixtureTickets": tickets,
                  "scriptCacheBefore": caches_before, "samples": []}
        self.current_phase = report
        stop = threading.Event()
        futures = []
        executor = concurrent.futures.ThreadPoolExecutor(max_workers=16)
        started_at = time.monotonic()

        def probe(index):
            port = 18201 + (index // 2) % 2
            path = "/reservation-queue" if index % 2 == 0 else f"/reservation-queue/{tickets[1]}"
            body = {"userId": 100000 + number * 10000 + index} if index % 2 == 0 else None
            result = request(port, path, body)
            return {"offsetMs": round((time.monotonic() - started_at) * 1000), "port": port,
                    "operation": "join" if body else "status", **result}

        def schedule_probes():
            index = 0
            while not stop.is_set():
                futures.append(executor.submit(probe, index))
                index += 1
                stop.wait(0.1)

        producer = threading.Thread(target=schedule_probes, daemon=True)
        producer.start()
        try:
            time.sleep(1)
            if missing_sentinel:
                self.run("kill", "-s", "SIGKILL", missing_sentinel)
            crash_started = time.monotonic()
            self.run("kill", "-s", "SIGKILL", old_primary)

            def promoted():
                current = self.primary(primary_sentinel)
                return current if current != old_primary and self.replication(current).get("role") == "master" else None

            new_primary = wait_until(promoted, "automatic primary promotion")
            report["newPrimary"] = new_primary
            report["promotionMs"] = round((time.monotonic() - crash_started) * 1000, 2)
            wait_until(lambda: int(self.replication(new_primary).get("connected_slaves", 0)) >= 1,
                       "promoted primary has a replica for write admission")
            for port in (18201, 18202):
                wait_until(lambda: request(port, "/reservation-queue", {"userId": users[0]}).get("body", {}).get("ticketId") == tickets[0],
                           f"application {port} reconnects and preserves first ticket")
            report["applicationRecoveryMs"] = round((time.monotonic() - crash_started) * 1000, 2)
            report["scriptCachedAfter"] = self.redis(new_primary, "SCRIPT", "EXISTS", SCRIPT_SHA)
            assert report["scriptCachedAfter"] == "1", report

            # 정상 소비 후 404는 장애가 아니므로 관찰 구간을 끝내고 예약을 생성한다.
            stop.set()
            producer.join(timeout=5)
            executor.shutdown(wait=True)
            for index, user_id in enumerate(users):
                ticket = tickets[index]
                wait_until(lambda: request(18201, f"/reservation-queue/{ticket}").get("body", {}).get("status") == "ADMITTED",
                           "preserved ticket admission")
                seat_id = (number - 1) * 2 + index + 1
                result = request(18202, "/reservations",
                    {"userId": user_id, "performanceId": 1 if seat_id % 2 else 2, "seatIds": [seat_id]},
                    {"X-Queue-Ticket": ticket, "Idempotency-Key": f"sentinel-{number}-{user_id}"})
                assert result["status"] == 201, result
                report.setdefault("reservations", []).append(result)

            new_join = request(18201, "/reservation-queue", {"userId": 900000 + number})
            assert new_join["status"] == 200, new_join
            assert self.redis(new_primary, "HEXISTS", f"{KEY_PREFIX}:users", str(900000 + number)) == "1"
            report["newWriteAfterPromotion"] = new_join
            self.run("start", old_primary)
            wait_until(lambda: self.check_replica(old_primary, new_primary), "former primary rejoins as replica", timeout=60)
            wait_until(lambda: int(self.replication(new_primary).get("connected_slaves", 0)) == 2,
                       "both replicas synchronize", timeout=60)
            self.barrier(new_primary)
            report["returnedNode"] = self.replication(old_primary)
            report["persistedReplicaConfiguration"] = [
                line for line in self.run("exec", "-T", old_primary, "cat", "/data/redis.conf").splitlines()
                if line.startswith("replicaof ")
            ]
            assert report["persistedReplicaConfiguration"] == [f"replicaof {new_primary} 6379"], report
            if missing_sentinel:
                self.run("start", missing_sentinel)
            wait_until(lambda: all(self.primary(node) == new_primary for node in SENTINEL_NODES),
                       "all Sentinels agree on promoted primary")
            report["sentinelAgreement"] = {node: self.primary(node) for node in SENTINEL_NODES}
            report["quorum"] = self.redis("sentinel-1", "SENTINEL", "CKQUORUM", "open-rush")
            assert report["quorum"].startswith("OK"), report
            report["applicationsUnchanged"] = before_identity == self.application_identity()
            assert report["applicationsUnchanged"], report
            report["dbVerification"] = self.verify_database()
        finally:
            stop.set()
            producer.join(timeout=5)
            executor.shutdown(wait=True)
            report["samples"] = [future.result() for future in futures]
            report["httpStatuses"] = {
                str(status): sum(sample["status"] == status for sample in report["samples"])
                for status in {sample["status"] for sample in report["samples"]}
            }
            for sample in report["samples"]:
                assert sample["status"] in (200, 503), sample
                if sample["status"] == 503:
                    assert sample["body"]["code"] == "QUEUE_UNAVAILABLE", sample
        report["passed"] = True
        print(json.dumps({key: report[key] for key in (
            "oldPrimary", "newPrimary", "missingSentinel", "promotionMs", "applicationRecoveryMs",
            "httpStatuses", "applicationsUnchanged", "passed")}), flush=True)
        return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--keep", action="store_true", help="Keep this experiment's containers and volumes for inspection")
    options = parser.parse_args()
    for port in (18201, 18202):
        if request(port, "/actuator/health")["status"] != 0:
            raise RuntimeError(f"Port {port} is already serving an HTTP application")
    stamp = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=9))).strftime("%Y%m%d-%H%M%S")
    project = f"open-rush-sentinel-test-{stamp.lower()}"
    output = ROOT / "build/benchmark" / f"sentinel-{stamp}"
    output.mkdir(parents=True)
    experiment = SentinelExperiment(project)
    report = {"project": project, "phases": []}
    try:
        experiment.command([str(ROOT / "gradlew"), "bootJar"])
        experiment.run("up", "-d", "--wait")
        for port in (18201, 18202):
            wait_until(lambda: request(port, "/actuator/health")["status"] == 200,
                       f"application {port} startup", timeout=120)
        primary = experiment.primary()
        wait_until(lambda: int(experiment.replication(primary).get("connected_slaves", 0)) == 2,
                   "initial two replicas")
        experiment.seed()
        report["phases"].append(experiment.phase(1))
        report["phases"].append(experiment.phase(2, missing_sentinel="sentinel-1"))
        report["passed"] = True
        print("Sentinel failover and replica rejoin: PASS", flush=True)
    finally:
        if experiment.current_phase is not None and not any(
                phase is experiment.current_phase for phase in report["phases"]):
            report["phases"].append(experiment.current_phase)
        (output / "results.json").write_text(json.dumps(report, indent=2))
        try:
            (output / "containers.log").write_text(experiment.run("logs", "--no-color", "--timestamps"))
            (output / "containers.json").write_text(experiment.run("ps", "-a", "--format", "json"))
        finally:
            if not options.keep:
                experiment.run("down", "--volumes")
        print(f"Results: {output}", flush=True)


if __name__ == "__main__":
    main()
