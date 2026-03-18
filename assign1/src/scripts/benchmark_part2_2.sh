#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/benchmark_common.sh"

compile_all
setup_multi_core_env
OUT_CSV="$RESULTS_DIR/part2_2_raw.csv"
[[ -f "$OUT_CSV" ]] || csv_header_common > "$OUT_CSV"
SIZE=8192
SERIAL_TIME="$("$CPP_BIN" 2 "$SIZE" | awk -F= '/^TIME_SECONDS=/{print $2; exit}')"

for threads in 4 8 12 16 20 24; do
  for variant in base simd collapse; do
    case "$variant" in
      base) op=6; extra=""; bsize="" ;;
      simd) op=8; extra=""; bsize="" ;;
      collapse) op=9; extra="$COLLAPSE_BLOCK"; bsize="$COLLAPSE_BLOCK" ;;
    esac

    for run in $(seq 1 "$REPEATS"); do
      ts="$(date +%Y-%m-%dT%H:%M:%S)"
      perf_file="$RESULTS_DIR/.perf_p2_2_${variant}_t${threads}_run${run}.txt"
      if [[ -n "$extra" ]]; then
        run_perf_capture "$perf_file" "$CPP_BIN" "$op" "$SIZE" "$threads" "$extra"
        out="$("$CPP_BIN" "$op" "$SIZE" "$threads" "$extra")"
      else
        run_perf_capture "$perf_file" "$CPP_BIN" "$op" "$SIZE" "$threads"
        out="$("$CPP_BIN" "$op" "$SIZE" "$threads")"
      fi
      t="$(extract_time_seconds "$out")"
      gf="$(calc_gflops "$SIZE" "$t")"
      sp="$(calc_speedup "$SERIAL_TIME" "$t")"
      echo "$ts,2,part2_2,$variant,C++,$SIZE,$threads,$bsize,${run},$t,$gf,$sp,,\
$(perf_value "$perf_file" task-clock),$(perf_value "$perf_file" instructions),$(perf_value "$perf_file" cycles),\
$(perf_value "$perf_file" cache-references),$(perf_value "$perf_file" cache-misses),$(perf_value "$perf_file" L1-dcache-loads),\
$(perf_value "$perf_file" L1-dcache-load-misses),$(perf_value "$perf_file" LLC-loads),$(perf_value "$perf_file" LLC-load-misses),\
$(perf_value "$perf_file" mem_load_retired.l1_miss),$(perf_value "$perf_file" mem_load_retired.l2_miss)" | tr -d '\n' >> "$OUT_CSV"
      echo >> "$OUT_CSV"
    done
  done
done

echo "[DONE] $OUT_CSV"
