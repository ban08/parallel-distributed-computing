#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/benchmark_common.sh"

compile_all
setup_single_core_env
CPU="$(pick_taskset_cpu)"
OUT_CSV="$RESULTS_DIR/part1_onmultline_raw.csv"
[[ -f "$OUT_CSV" ]] || csv_header_common > "$OUT_CSV"

for lang in C++ Java; do
  for size in 1024 1536 2048 2560 3072; do
    for run in $(seq 1 "$REPEATS"); do
      ts="$(date +%Y-%m-%dT%H:%M:%S)"
      perf_file="$RESULTS_DIR/.perf_p1_onmultline_${lang}_${size}_run${run}.txt"
      if [[ "$lang" == "C++" ]]; then
        run_perf_capture "$perf_file" taskset -c "$CPU" "$CPP_BIN" 2 "$size"
        out="$(taskset -c "$CPU" "$CPP_BIN" 2 "$size")"
      else
        run_perf_capture "$perf_file" taskset -c "$CPU" java -Xmx"$JAVA_HEAP" -cp "$JAVA_CP" MatrixProduct 2 "$size"
        out="$(taskset -c "$CPU" java -Xmx"$JAVA_HEAP" -cp "$JAVA_CP" MatrixProduct 2 "$size")"
      fi
      t="$(extract_time_seconds "$out")"
      gf="$(calc_gflops "$size" "$t")"
      echo "$ts,1,onmultline,base,$lang,$size,1,,${run},$t,$gf,,,\
$(perf_value "$perf_file" task-clock),$(perf_value "$perf_file" instructions),$(perf_value "$perf_file" cycles),\
$(perf_value "$perf_file" cache-references),$(perf_value "$perf_file" cache-misses),$(perf_value "$perf_file" L1-dcache-loads),\
$(perf_value "$perf_file" L1-dcache-load-misses),$(perf_value "$perf_file" LLC-loads),$(perf_value "$perf_file" LLC-load-misses),\
$(perf_value "$perf_file" mem_load_retired.l1_miss),$(perf_value "$perf_file" mem_load_retired.l2_miss)" | tr -d '\n' >> "$OUT_CSV"
      echo >> "$OUT_CSV"
    done
  done
done

for size in 4096 6144 8192 10240; do
  for run in $(seq 1 "$REPEATS"); do
    ts="$(date +%Y-%m-%dT%H:%M:%S)"
    perf_file="$RESULTS_DIR/.perf_p1_onmultline_cpp_large_${size}_run${run}.txt"
    run_perf_capture "$perf_file" taskset -c "$CPU" "$CPP_BIN" 2 "$size"
    out="$(taskset -c "$CPU" "$CPP_BIN" 2 "$size")"
    t="$(extract_time_seconds "$out")"
    gf="$(calc_gflops "$size" "$t")"
    echo "$ts,1,onmultline,base,C++,$size,1,,${run},$t,$gf,,,\
$(perf_value "$perf_file" task-clock),$(perf_value "$perf_file" instructions),$(perf_value "$perf_file" cycles),\
$(perf_value "$perf_file" cache-references),$(perf_value "$perf_file" cache-misses),$(perf_value "$perf_file" L1-dcache-loads),\
$(perf_value "$perf_file" L1-dcache-load-misses),$(perf_value "$perf_file" LLC-loads),$(perf_value "$perf_file" LLC-load-misses),\
$(perf_value "$perf_file" mem_load_retired.l1_miss),$(perf_value "$perf_file" mem_load_retired.l2_miss)" | tr -d '\n' >> "$OUT_CSV"
    echo >> "$OUT_CSV"
  done
done

echo "[DONE] $OUT_CSV"
