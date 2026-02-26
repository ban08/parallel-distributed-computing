#!/bin/bash
# =============================================================================
# Benchmark: OnMult (Part 1.1) — Basic line-by-column multiplication
# Languages: C++ and Java
# Sizes: 1024 to 3072, step 512
# CSV includes: perf counters + GFlop/s  (GFlop/s = 2*n^3 / (time * 1e9))
# =============================================================================
set -e

SIZES=(1024 1536 2048 2560 3072)
RESULTS_DIR="../doc/results"
mkdir -p "$RESULTS_DIR"

TIMESTAMP=$(date +%Y%m%d_%H%M%S)

# --- Perf Events ---
EVENTS_BASIC=(task-clock instructions cycles L1-dcache-load-misses L1-dcache-loads LLC-load-misses LLC-loads)
EVENTS_EXT=(mem_load_retired.l1_miss mem_load_retired.l2_miss)

join_by() { local d=$1; shift; echo -n "$1"; shift; printf "%s" "${@/#/$d}"; }
PERF_EVENTS=$(join_by , "${EVENTS_BASIC[@]}")
PERF_EVENTS_EXT=$(join_by , "${EVENTS_EXT[@]}")

# --- Find or create CSV / TXT ---
HEADER="Language,Size,Time_seconds,GFlops"
for ev in "${EVENTS_BASIC[@]}"; do HEADER="$HEADER,$ev"; done
for ev in "${EVENTS_EXT[@]}"; do HEADER="$HEADER,$ev"; done

CSV_FILE=$(ls -t "$RESULTS_DIR"/benchmark_onmult_*.csv 2>/dev/null | grep -v onmultline | grep -v onmultblock | head -n1)
RESULTS_FILE=$(ls -t "$RESULTS_DIR"/benchmark_onmult_*.txt 2>/dev/null | grep -v onmultline | grep -v onmultblock | head -n1)

if [[ -z "$CSV_FILE" ]]; then
    CSV_FILE="$RESULTS_DIR/benchmark_onmult_${TIMESTAMP}.csv"
    echo "$HEADER" > "$CSV_FILE"
fi
if [[ -z "$RESULTS_FILE" ]]; then
    RESULTS_FILE="$RESULTS_DIR/benchmark_onmult_${TIMESTAMP}.txt"
fi

# --- Run separator ---
echo "# RUN $TIMESTAMP" >> "$CSV_FILE"

# --- Log Header ---
echo "" >> "$RESULTS_FILE"
echo "=================================================================" | tee -a "$RESULTS_FILE"
echo " Benchmark: OnMult (Part 1.1)  —  RUN $TIMESTAMP"               | tee -a "$RESULTS_FILE"
echo " Date: $(date)"                                                   | tee -a "$RESULTS_FILE"
echo " Sizes: ${SIZES[*]}"                                              | tee -a "$RESULTS_FILE"
echo "=================================================================" | tee -a "$RESULTS_FILE"

# --- Compilation ---
echo "" | tee -a "$RESULTS_FILE"
echo "Compiling C++ (-O2) and Java ..." | tee -a "$RESULTS_FILE"
g++ -O2 -o matrix_cpp matrixproduct.cpp
javac MatrixProduct.java
echo "Done." | tee -a "$RESULTS_FILE"

# --- Helpers ---
extract_time_from_file() {
    # Grep the Time line directly from a file (avoids shell variable issues)
    local val=$(grep -oP 'Time:\s+\K[0-9]+[.,][0-9]+' "$1" 2>/dev/null | head -1)
    if [[ -n "$val" ]]; then
        echo "${val/,/.}"
    else
        echo "N/A"
    fi
}

extract_perf_value_from_file() {
    # $1 = perf output file, $2 = event name
    local val=$(grep -w "$2" "$1" 2>/dev/null | head -n1 | awk '{print $1}' | tr -d ',')
    [[ "$val" =~ ^[0-9]+(\.[0-9]+)?$ ]] && echo "$val" || echo ""
}

calc_gflops() {
    local n=$1 t=$2
    if [[ "$t" == "N/A" || -z "$t" ]]; then echo "N/A"; return; fi
    python3 -c "n=$n; t=$t; print(f'{2.0*n**3/(t*1e9):.4f}')" 2>/dev/null || echo "N/A"
}

run_benchmark() {
    local LANG="$1" CMD="$2" SIZE="$3" MENU_INPUT="$4"

    echo "" | tee -a "$RESULTS_FILE"
    echo "----- $LANG | OnMult | ${SIZE}x${SIZE} -----" | tee -a "$RESULTS_FILE"

    # Temp files
    local TMPINPUT=$(mktemp)
    local TMPPROG_B=$(mktemp)
    local TMPPROG_E=$(mktemp)
    local TMPPERF_B=$(mktemp)
    local TMPPERF_E=$(mktemp)
    printf '%b' "$MENU_INPUT" > "$TMPINPUT"

    echo "  perf stat (basic) ..." | tee -a "$RESULTS_FILE"
    LC_ALL=C perf stat -e "$PERF_EVENTS" -o "$TMPPERF_B" -- $CMD < "$TMPINPUT" > "$TMPPROG_B" 2>&1 || true

    echo "  perf stat (extended) ..." | tee -a "$RESULTS_FILE"
    LC_ALL=C perf stat -e "$PERF_EVENTS_EXT" -o "$TMPPERF_E" -- $CMD < "$TMPINPUT" > "$TMPPROG_E" 2>&1 || true

    # Extract time directly from program output file (not via shell variable)
    local T=$(extract_time_from_file "$TMPPROG_B")
    local GF=$(calc_gflops "$SIZE" "$T")
    echo "  Time: ${T}s | GFlop/s: ${GF}" | tee -a "$RESULTS_FILE"

    # Build CSV line — extract perf values directly from perf output files
    local LINE="$LANG,$SIZE,$T,$GF"
    for ev in "${EVENTS_BASIC[@]}"; do LINE="$LINE,$(extract_perf_value_from_file "$TMPPERF_B" "$ev")"; done
    for ev in "${EVENTS_EXT[@]}"; do LINE="$LINE,$(extract_perf_value_from_file "$TMPPERF_E" "$ev")"; done
    echo "$LINE" >> "$CSV_FILE"

    echo "" >> "$RESULTS_FILE"
    echo "  [program output]:" >> "$RESULTS_FILE"
    cat "$TMPPROG_B" >> "$RESULTS_FILE"
    echo "  [perf basic]:" >> "$RESULTS_FILE"
    cat "$TMPPERF_B" >> "$RESULTS_FILE"
    echo "  [perf extended]:" >> "$RESULTS_FILE"
    cat "$TMPPERF_E" >> "$RESULTS_FILE"

    rm -f "$TMPINPUT" "$TMPPROG_B" "$TMPPROG_E" "$TMPPERF_B" "$TMPPERF_E"
}

# --- Run ---
echo "" | tee -a "$RESULTS_FILE"
echo "===== C++ OnMult =====" | tee -a "$RESULTS_FILE"
for S in "${SIZES[@]}"; do run_benchmark "C++" "./matrix_cpp" "$S" "1\n${S}\n0\n"; done

echo "" | tee -a "$RESULTS_FILE"
echo "===== Java onMult =====" | tee -a "$RESULTS_FILE"
for S in "${SIZES[@]}"; do run_benchmark "Java" "java -Xmx4g -cp . MatrixProduct" "$S" "1\n${S}\n0\n"; done

# --- Summary ---
echo "" | tee -a "$RESULTS_FILE"
echo "=================================================================" | tee -a "$RESULTS_FILE"
echo "                   SUMMARY — OnMult (Part 1.1)"                    | tee -a "$RESULTS_FILE"
echo "=================================================================" | tee -a "$RESULTS_FILE"
printf "%-6s | %-12s | %-12s | %-12s | %-12s\n" "Size" "C++ Time" "C++ GF/s" "Java Time" "Java GF/s" | tee -a "$RESULTS_FILE"
echo "-------|--------------|--------------|--------------|-------------" | tee -a "$RESULTS_FILE"

declare -A CT CG JT JG
while IFS=',' read -r lang size time gf rest; do
    [[ "$lang" == "Language" || "$lang" == \#* ]] && continue
    [[ "$lang" == "C++" ]] && CT[$size]=$time && CG[$size]=$gf
    [[ "$lang" == "Java" ]] && JT[$size]=$time && JG[$size]=$gf
done < "$CSV_FILE"

for S in "${SIZES[@]}"; do
    printf "%-6s | %-12s | %-12s | %-12s | %-12s\n" "$S" "${CT[$S]:-N/A}" "${CG[$S]:-N/A}" "${JT[$S]:-N/A}" "${JG[$S]:-N/A}" | tee -a "$RESULTS_FILE"
done

echo "" | tee -a "$RESULTS_FILE"
echo "CSV: $CSV_FILE" | tee -a "$RESULTS_FILE"
echo "Done!" | tee -a "$RESULTS_FILE"
