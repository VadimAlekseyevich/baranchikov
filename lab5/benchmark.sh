#!/usr/bin/env sh
set -eu
# Independent scale-one-layer-at-a-time benchmark. CSV includes measured successes, errors,
# throughput and latency. All points use the same read workload and same thread count.
cd "$(dirname "$0")"
export BENCH_REQUESTS="${BENCH_REQUESTS:-500}"
export BENCH_THREADS="${BENCH_THREADS:-8}"
OUTPUT="${1:-benchmark-results.csv}"
printf 'layer,replicas,threads,requests,completed,errors,rps,p50_ms,p95_ms\n' > "$OUTPUT"
docker compose up -d --build --scale facade=1 --scale business=1 --scale storage=1 facade
for layer in facade business storage; do
  for replicas in 1 2 3; do
    docker compose up -d --scale facade=1 --scale business=1 --scale storage=1 facade
    case "$layer" in
      facade) docker compose up -d --scale facade="$replicas" facade ;;
      business) docker compose up -d --scale business="$replicas" business ;;
      storage) docker compose up -d --scale storage="$replicas" storage ;;
    esac
    sleep 4
    echo "Benchmarking $layer replicas=$replicas ..."
    # Capture result only if benchmark succeeded; otherwise exit nonzero (no fabricated values).
    result=$(docker compose run --rm -T -e BENCH_REQUESTS="$BENCH_REQUESTS" -e BENCH_THREADS="$BENCH_THREADS" benchmark | grep '^RESULT,' | tail -1)
    test -n "$result"
    printf '%s,%s,%s\n' "$layer" "$replicas" "$(printf '%s' "$result" | cut -d, -f2-)" >> "$OUTPUT"
  done
done
docker compose up -d --scale facade=1 --scale business=1 --scale storage=1 facade
echo "Written $OUTPUT (real measured results)."
