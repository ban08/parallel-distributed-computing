#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/benchmark_common.sh"

compile_all
setup_single_core_env
CPU="$(pick_taskset_cpu)"
OUT_CSV="$RESULTS_DIR/part1_onmultblock_raw.csv"
[[ -f "$OUT_CSV" ]] || csv_header_common > "$OUT_CSV"

for b in 128 256 512; do
  for size in 4096 6144 8192 10240; do
    for run in $(seq 1 "$REPEATS"); do
      ts="$(date +%Y-%m-%dT%H:%M:%S)"
      perf_file="$RESULTS_DIR/.perf_p1_onmultblock_b${b}_${size}_run${run}.txt"
      run_perf_capture "$perf_file" taskset -c "$CPU" "$CPP_BIN" 3 "$size" "$b"
      out="$(taskset -c "$CPU" "$CPP_BIN" 3 "$size" "$b")"
      t="$(extract_time_seconds "$out")"
      gf="$(calc_gflops "$size" "$t")"
      echo "$ts,1,onmultblock,base,C++,$size,1,$b,${run},$t,$gf,,,\
$(perf_value "$perf_file" task-clock),$(perf_value "$perf_file" instructions),$(perf_value "$perf_file" cycles),\
$(perf_value "$perf_file" cache-references),$(perf_value "$perf_file" cache-misses),$(perf_value "$perf_file" L1-dcache-loads),\
$(perf_value "$perf_file" L1-dcache-load-misses),$(perf_value "$perf_file" LLC-loads),$(perf_value "$perf_file" LLC-load-misses),\
$(perf_value "$perf_file" mem_load_retired.l1_miss),$(perf_value "$perf_file" mem_load_retired.l2_miss)" | tr -d '\n' >> "$OUT_CSV"
      echo >> "$OUT_CSV"
    done
  done
done

echo "[DONE] $OUT_CSV"
