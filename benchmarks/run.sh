#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose=(docker compose -f "$repo_dir/benchmarks/compose.yaml")
scenario="${1:-all}"
requests="${REQUESTS:-1000}"
users="${VUS:-16}"
warmup_requests="${WARMUP_REQUESTS:-500}"

case "$scenario" in
  all) scenarios=(low hot quota) ;;
  low|hot|quota) scenarios=("$scenario") ;;
  *) echo "Usage: $0 [all|low|hot|quota]" >&2; exit 1 ;;
esac

for value in "$requests" "$users" "$warmup_requests"; do
  if [[ ! "$value" =~ ^[1-9][0-9]*$ ]] || (( ${#value} > 9 )); then
    echo 'REQUESTS, VUS and WARMUP_REQUESTS must be positive integers (up to 9 digits).' >&2
    exit 1
  fi
done

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

cleanup() {
  if [[ -n "$app_pid" ]]; then
    kill "$app_pid" 2>/dev/null || true
    wait "$app_pid" 2>/dev/null || true
  fi
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
  --spring.profiles.active=benchmark >"$output_dir/application.log" 2>&1 &
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
  k6 run --quiet -e CASE="$name" -e REQUESTS="$count" -e VUS="$users" \
    -e SUMMARY="$output_dir/$label.json" "$repo_dir/benchmarks/reservations.js"
  database -v scenario="$name" -v requests="$count" <"$repo_dir/benchmarks/verify.sql"
}

echo 'Warmup'
run_case low "$warmup_requests" warmup
for name in "${scenarios[@]}"; do
  run_case "$name" "$requests" "$name"
done
echo "Results: $output_dir"
