#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose=(docker compose -f "$repo_dir/benchmarks/compose.yaml")
scenario="${1:-all}"
requests="${REQUESTS:-1000}"
users="${VUS:-16}"
warmup_requests="${WARMUP_REQUESTS:-500}"
arrival_seconds="${ARRIVAL_SECONDS:-2}"
admission_rate="${QUEUE_ADMISSIONS_PER_SECOND:-20}"
max_in_flight="${QUEUE_MAX_IN_FLIGHT:-16}"
hold_timeout_ms="${HOLD_TIMEOUT_MS:-1000}"
db_pool_size="${BENCH_DB_POOL_SIZE:-16}"

case "$scenario" in
  all) scenarios=(low hot quota) ;;
  low|hot|quota|rehold) scenarios=("$scenario") ;;
  queue|queue-baseline)
    scenarios=("$scenario")
    requests="${REQUESTS:-120}"
    users="${VUS:-$requests}"
    warmup_requests="${WARMUP_REQUESTS:-6}"
    ;;
  *) echo "Usage: $0 [all|low|hot|quota|rehold|queue-baseline|queue]" >&2; exit 1 ;;
esac

for value in "$requests" "$users" "$warmup_requests" "$arrival_seconds" "$admission_rate" "$max_in_flight" "$hold_timeout_ms" "$db_pool_size"; do
  if [[ ! "$value" =~ ^[1-9][0-9]*$ ]] || (( ${#value} > 9 )); then
    echo 'REQUESTS, VUS and WARMUP_REQUESTS must be positive integers (up to 9 digits).' >&2
    exit 1
  fi
done

app_args=(--spring.profiles.active=benchmark)
warmup_scenario=low
if [[ "$scenario" == queue || "$scenario" == queue-baseline ]]; then
  if (( db_pool_size < 2 )); then
    echo 'BENCH_DB_POOL_SIZE must be at least 2 so the application can run Flyway migrations.' >&2
    exit 1
  fi
  warmup_scenario="$scenario"
  app_args+=("--spring.datasource.hikari.maximum-pool-size=$db_pool_size"
    "--spring.datasource.hikari.minimum-idle=$db_pool_size")
  if [[ "$scenario" == queue ]]; then
    app_args+=(--reservation.queue.enabled=true "--reservation.queue.admissions-per-second=$admission_rate"
      "--reservation.queue.max-in-flight=$max_in_flight")
  fi
fi
if [[ "$scenario" == rehold ]]; then
  if (( requests < 3 || warmup_requests < 3 )); then
    echo 'rehold requires at least 3 iterations in both REQUESTS and WARMUP_REQUESTS.' >&2
    exit 1
  fi
  warmup_scenario=rehold
  app_args+=(--reservation.benchmark.rehold-enabled=true --reservation.hold-duration-ms=5000
    --reservation.expiration.enabled=true --reservation.expiration.fixed-delay=250ms
    --reservation.expiration.initial-delay=0s)
fi

for dependency in docker k6 curl java; do
  command -v "$dependency" >/dev/null || { echo "Required: $dependency" >&2; exit 1; }
done

if curl --silent --output /dev/null --max-time 2 http://localhost:18080/actuator/health; then
  echo 'Port 18080 is already serving an API. Stop it before running the benchmark.' >&2
  exit 1
fi

output_dir="$repo_dir/build/benchmark/$(TZ=Asia/Seoul date +%Y%m%d-%H%M%S)"
mkdir -p "$output_dir"
existing_database="$("${compose[@]}" ps --status running -q postgres)"
app_pid=''

stop_app() {
  if [[ -n "$app_pid" ]]; then
    kill "$app_pid" 2>/dev/null || true
    wait "$app_pid" 2>/dev/null || true
    app_pid=''
  fi
}

cleanup() {
  stop_app
  if [[ -z "$existing_database" ]]; then
    "${compose[@]}" down >/dev/null
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

"${compose[@]}" up -d --wait
"$repo_dir/gradlew" -p "$repo_dir" bootJar
bench_java=java
if [[ -n "${JAVA_HOME:-}" ]]; then bench_java="$JAVA_HOME/bin/java"; fi
"$bench_java" -Xms512m -Xmx512m -jar "$repo_dir/build/libs/open-rush-0.0.1-SNAPSHOT.jar" \
  "${app_args[@]}" >"$output_dir/application.log" 2>&1 &
app_pid=$!

ready=false
for attempt in {1..60}; do
  if ! kill -0 "$app_pid" 2>/dev/null; then break; fi
  if grep -q 'Started OpenRushApplication' "$output_dir/application.log" && \
      curl --fail --silent --max-time 2 http://localhost:18080/actuator/health >/dev/null; then
    ready=true
    break
  fi
  sleep 1
done
if [[ "$ready" != true ]]; then
  cat "$output_dir/application.log" >&2
  exit 1
fi

database() {
  "${compose[@]}" exec -T postgres psql -Xq -v ON_ERROR_STOP=1 \
    -U benchmark -d open_rush_benchmark "$@"
}

run_case() {
  local name="$1" count="$2" label="$3"
  database -v requests="$count" <"$repo_dir/benchmarks/seed.sql"
  local script=reservations.js verifier=verify.sql seconds="$arrival_seconds" timeout_ms="$hold_timeout_ms"
  if [[ "$name" == rehold ]]; then
    script=rehold.js
    verifier=verify-rehold.sql
  elif [[ "$name" == queue || "$name" == queue-baseline ]]; then
    script=queue.js
    if [[ "$label" == warmup ]]; then
      seconds=2
      timeout_ms=15000
    fi
  fi
  k6 run --quiet -e CASE="$name" -e REQUESTS="$count" -e VUS="$users" \
    -e ARRIVAL_SECONDS="$seconds" -e HOLD_TIMEOUT_MS="$timeout_ms" -e RUN_LABEL="$label" \
    -e QUEUE_ADMISSIONS_PER_SECOND="$admission_rate" -e QUEUE_MAX_IN_FLIGHT="$max_in_flight" \
    -e BENCH_DB_POOL_SIZE="$db_pool_size" \
    -e SUMMARY="$output_dir/$label.json" "$repo_dir/benchmarks/$script"
  if [[ "$script" == queue.js && "$label" != warmup ]]; then
    if kill -0 "$app_pid" 2>/dev/null; then
      echo 'Application process after load: RUNNING'
    else
      echo 'Application process after load: EXITED'
    fi
    stop_app
  fi
  database -v scenario="$name" -v requests="$count" <"$repo_dir/benchmarks/$verifier"
}

echo 'Warmup'
run_case "$warmup_scenario" "$warmup_requests" warmup
for name in "${scenarios[@]}"; do
  run_case "$name" "$requests" "$name"
done
echo "Results: $output_dir"
