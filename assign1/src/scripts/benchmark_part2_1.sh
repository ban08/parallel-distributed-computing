#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/benchmark_common.sh"

compile_all
setup_multi_core_env
OUT_CSV="$RESULTS_DIR/part2_1_raw.csv"
[[ -f "$OUT_CSV" ]] || csv_header_common > "$OUT_CSV"
THREADS=4

serial_time_for() {
  local op="$1" size="$2"
  "$CPP_BIN" "$op" "$size" | awk -F= '/^TIME_SECONDS=/{print $2; exit}'
}

for size in 1024 1536 2048 2560 3072; do
  serial_onmult="$(serial_time_for 1 "$size")"
  serial_line="$(serial_time_for 2 "$size")"

  for variant in onmult_p1 onmult_p2 onmultline_p1 onmultline_p2; do
    case "$variant" in
      onmult_p1) op=4; base_serial="$serial_onmult" ;;
      onmult_p2) op=5; base_serial="$serial_onmult" ;;
      onmultline_p1) op=6; base_serial="$serial_line" ;;
      onmultline_p2) op=7; base_serial="$serial_line" ;;
    esac

    for run in $(seq 1 "$REPEATS"); do
      ts="$(date +%Y-%m-%dT%H:%M:%S)"
      perf_file="$RESULTS_DIR/.perf_p2_1_${variant}_${size}_run${run}.txt"
      run_perf_capture "$perf_file" "$CPP_BIN" "$op" "$size" "$THREADS"
      out="$("$CPP_BIN" "$op" "$size" "$THREADS")"
      t="$(extract_time_seconds "$out")"
      gf="$(calc_gflops "$size" "$t")"
      sp="$(calc_speedup "$base_serial" "$t")"
      ef="$(calc_efficiency "$sp" "$THREADS")"
      echo "$ts,2,part2_1,$variant,C++,$size,$THREADS,,${run},$t,$gf,$sp,$ef,\
$(perf_value "$perf_file" task-clock),$(perf_value "$perf_file" instructions),$(perf_value "$perf_file" cycles),\
$(perf_value "$perf_file" cache-references),$(perf_value "$perf_file" cache-misses),$(perf_value "$perf_file" L1-dcache-loads),\
$(perf_value "$perf_file" L1-dcache-load-misses),$(perf_value "$perf_file" LLC-loads),$(perf_value "$perf_file" LLC-load-misses),\
$(perf_value "$perf_file" mem_load_retired.l1_miss),$(perf_value "$perf_file" mem_load_retired.l2_miss)" | tr -d '\n' >> "$OUT_CSV"
      echo >> "$OUT_CSV"
    done
  done
done

echo "[DONE] $OUT_CSV"
