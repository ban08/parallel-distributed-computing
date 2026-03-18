#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$SRC_DIR/.." && pwd)"
RESULTS_DIR="${RESULTS_DIR:-$ROOT_DIR/doc/results}"

CPP_SRC="$SRC_DIR/matrixproduct.cpp"
CPP_BIN="$SRC_DIR/matrixproduct"
JAVA_SRC="$SRC_DIR/MatrixProduct.java"
JAVA_CP="$SRC_DIR"

REPEATS="${REPEATS:-5}"
COLLAPSE_BLOCK="${COLLAPSE_BLOCK:-256}"
JAVA_HEAP="${JAVA_HEAP:-4g}"

mkdir -p "$RESULTS_DIR"

compile_all() {
  echo "[INFO] Compiling C++ and Java sources..."
  g++ -O2 -fopenmp "$CPP_SRC" -o "$CPP_BIN"
  javac "$JAVA_SRC"
}

setup_single_core_env() {
  export OMP_NUM_THREADS=1
  export OMP_DYNAMIC=false
  export OMP_PROC_BIND=true
  export OMP_PLACES=cores
}

setup_multi_core_env() {
  export OMP_DYNAMIC=false
  export OMP_PROC_BIND=true
  export OMP_PLACES=cores
}

pick_taskset_cpu() {
  if command -v nproc >/dev/null 2>&1; then
    local n
    n=$(nproc)
    if [[ "$n" -gt 1 ]]; then
      echo 1
      return
    fi
  fi
  echo 0
}

run_cpp() {
  local op="$1" size="$2"
  shift 2
  "$CPP_BIN" "$op" "$size" "$@"
}

run_java() {
  local op="$1" size="$2"
  java -Xmx"$JAVA_HEAP" -cp "$JAVA_CP" MatrixProduct "$op" "$size"
}

extract_time_seconds() {
  local out="$1"
  printf '%s\n' "$out" | awk -F= '/^TIME_SECONDS=/{print $2; exit}'
}

run_perf_capture() {
  local outfile="$1"
  shift
  perf stat -x ';' -e \
    instructions,cycles,task-clock,cache-references,cache-misses,\
L1-dcache-loads,L1-dcache-load-misses,LLC-loads,LLC-load-misses,\
mem_load_retired.l1_miss,mem_load_retired.l2_miss \
    -o "$outfile" -- "$@"
}

_perf_value_from_file() {
  local file="$1" event="$2"
  awk -F';' -v ev="$event" '
    $3 ~ ("(^|/)" ev "(/|$)") {
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", $1)
      if ($1 ~ /^[0-9.,]+$/) {
        gsub(/,/, "", $1)
        print $1
        exit
      }
    }
  ' "$file"
}

perf_value() {
  local file="$1" event="$2"
  local v
  v=$(_perf_value_from_file "$file" "cpu_core/${event}")
  if [[ -n "${v:-}" ]]; then
    echo "$v"
    return
  fi
  v=$(_perf_value_from_file "$file" "$event")
  echo "${v:-}"
}

calc_gflops() {
  local n="$1" t="$2"
  awk -v n="$n" -v t="$t" 'BEGIN { if (t>0) printf "%.6f", (2*n*n*n)/(t*1e9); else print "" }'
}

calc_speedup() {
  local serial="$1" parallel="$2"
  awk -v s="$serial" -v p="$parallel" 'BEGIN { if (s>0 && p>0) printf "%.6f", s/p; else print "" }'
}

calc_efficiency() {
  local speedup="$1" threads="$2"
  awk -v sp="$speedup" -v th="$threads" 'BEGIN { if (sp>0 && th>0) printf "%.6f", sp/th; else print "" }'
}

csv_header_common() {
  echo "timestamp,part,experiment,variant,language,size,threads,block_size,run,time_seconds,gflops,speedup,efficiency,task_clock,instructions,cycles,cache_references,cache_misses,L1_dcache_loads,L1_dcache_load_misses,LLC_loads,LLC_load_misses,mem_load_retired_l1_miss,mem_load_retired_l2_miss"
}
