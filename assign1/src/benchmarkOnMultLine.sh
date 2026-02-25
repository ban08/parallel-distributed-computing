#!/bin/bash
# =============================================================================
# Benchmark: OnMultLine (Part 1.2) — Line multiplication (i-k-j loop order)
# Languages: C++ and Java
# Both:      1024 to 3072, step 512
# C++ only:  4096 to 10240, step 2048
# CSV includes: perf counters + GFlop/s  (GFlop/s = 2*n^3 / (time * 1e9))
# =============================================================================
set -e

SIZES_BOTH=(1024 1536 2048 2560 3072)
SIZES_CPP_EXTRA=(4096 6144 8192 10240)
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

CSV_FILE=$(ls -t "$RESULTS_DIR"/benchmark_onmultline_*.csv 2>/dev/null | head -n1)
RESULTS_FILE=$(ls -t "$RESULTS_DIR"/benchmark_onmultline_*.txt 2>/dev/null | head -n1)

if [[ -z "$CSV_FILE" ]]; then
    CSV_FILE="$RESULTS_DIR/benchmark_onmultline_${TIMESTAMP}.csv"
    echo "$HEADER" > "$CSV_FILE"
fi
if [[ -z "$RESULTS_FILE" ]]; then
    RESULTS_FILE="$RESULTS_DIR/benchmark_onmultline_${TIMESTAMP}.txt"
fi

# --- Run separator ---
echo "# RUN $TIMESTAMP" >> "$CSV_FILE"

# --- Log Header ---
echo "" >> "$RESULTS_FILE"
echo "=================================================================" | tee -a "$RESULTS_FILE"
echo " Benchmark: OnMultLine (Part 1.2)  —  RUN $TIMESTAMP"            | tee -a "$RESULTS_FILE"
echo " Date: $(date)"                                                   | tee -a "$RESULTS_FILE"
echo " Both:      ${SIZES_BOTH[*]}"                                     | tee -a "$RESULTS_FILE"
echo " C++ extra: ${SIZES_CPP_EXTRA[*]}"                                | tee -a "$RESULTS_FILE"
echo "=================================================================" | tee -a "$RESULTS_FILE"

# --- Compilation ---
echo "" | tee -a "$RESULTS_FILE"
echo "Compiling C++ (-O2) and Java ..." | tee -a "$RESULTS_FILE"
g++ -O2 -o matrix_cpp matrixproduct.cpp
javac MatrixProduct.java
echo "Done." | tee -a "$RESULTS_FILE"

# --- Helpers ---
extract_time() { grep -oP 'Time:\s+\K[0-9]+\.[0-9]+' <<< "$1" || echo "N/A"; }

extract_perf_value() {
    local val=$(echo "$1" | grep -w "$2" | head -n1 | awk '{print $1}' | tr -d ',')
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
    echo "----- $LANG | OnMultLine | ${SIZE}x${SIZE} -----" | tee -a "$RESULTS_FILE"

    # Write menu input to a temp file (more reliable than piping for JVM)
    local TMPINPUT=$(mktemp)
    local TMPPERF_B=$(mktemp)
    local TMPPERF_E=$(mktemp)
    printf '%b' "$MENU_INPUT" > "$TMPINPUT"

    echo "  perf stat (basic) ..." | tee -a "$RESULTS_FILE"
    PROG_OUT_B=$(perf stat -e "$PERF_EVENTS" -o "$TMPPERF_B" -- $CMD < "$TMPINPUT" 2>&1) || true

    echo "  perf stat (extended) ..." | tee -a "$RESULTS_FILE"
    PROG_OUT_E=$(perf stat -e "$PERF_EVENTS_EXT" -o "$TMPPERF_E" -- $CMD < "$TMPINPUT" 2>&1) || true

    # Combine program stdout with perf stats for parsing
    local OUT_B="$PROG_OUT_B"$'\n'"$(cat "$TMPPERF_B")"
    local OUT_E="$PROG_OUT_E"$'\n'"$(cat "$TMPPERF_E")"

    local T=$(extract_time "$OUT_B")
    local GF=$(calc_gflops "$SIZE" "$T")
    echo "  Time: ${T}s | GFlop/s: ${GF}" | tee -a "$RESULTS_FILE"

    local LINE="$LANG,$SIZE,$T,$GF"
    for ev in "${EVENTS_BASIC[@]}"; do LINE="$LINE,$(extract_perf_value "$OUT_B" "$ev")"; done
    for ev in "${EVENTS_EXT[@]}"; do LINE="$LINE,$(extract_perf_value "$OUT_E" "$ev")"; done
    echo "$LINE" >> "$CSV_FILE"

    echo "" >> "$RESULTS_FILE"
    echo "  [perf basic]:" >> "$RESULTS_FILE"
    cat "$TMPPERF_B" >> "$RESULTS_FILE"
    echo "  [perf extended]:" >> "$RESULTS_FILE"
    cat "$TMPPERF_E" >> "$RESULTS_FILE"

    rm -f "$TMPINPUT" "$TMPPERF_B" "$TMPPERF_E"
}

# --- Run ---
echo "" | tee -a "$RESULTS_FILE"
echo "===== C++ OnMultLine (1024..3072) =====" | tee -a "$RESULTS_FILE"
for S in "${SIZES_BOTH[@]}"; do run_benchmark "C++" "./matrix_cpp" "$S" "2\n${S}\n0\n"; done

echo "" | tee -a "$RESULTS_FILE"
echo "===== C++ OnMultLine (4096..10240) =====" | tee -a "$RESULTS_FILE"
for S in "${SIZES_CPP_EXTRA[@]}"; do run_benchmark "C++" "./matrix_cpp" "$S" "2\n${S}\n0\n"; done

echo "" | tee -a "$RESULTS_FILE"
echo "===== Java onMultLine (1024..3072) =====" | tee -a "$RESULTS_FILE"
for S in "${SIZES_BOTH[@]}"; do run_benchmark "Java" "java MatrixProduct" "$S" "2\n${S}\n0\n"; done

# --- Summary ---
echo "" | tee -a "$RESULTS_FILE"
echo "=================================================================" | tee -a "$RESULTS_FILE"
echo "                 SUMMARY — OnMultLine (Part 1.2)"                  | tee -a "$RESULTS_FILE"
echo "=================================================================" | tee -a "$RESULTS_FILE"
printf "%-6s | %-12s | %-12s | %-12s | %-12s\n" "Size" "C++ Time" "C++ GF/s" "Java Time" "Java GF/s" | tee -a "$RESULTS_FILE"
echo "-------|--------------|--------------|--------------|-------------" | tee -a "$RESULTS_FILE"

declare -A CT CG JT JG
while IFS=',' read -r lang size time gf rest; do
    [[ "$lang" == "Language" || "$lang" == \#* ]] && continue
    [[ "$lang" == "C++" ]] && CT[$size]=$time && CG[$size]=$gf
    [[ "$lang" == "Java" ]] && JT[$size]=$time && JG[$size]=$gf
done < "$CSV_FILE"

ALL_SIZES=("${SIZES_BOTH[@]}" "${SIZES_CPP_EXTRA[@]}")
for S in "${ALL_SIZES[@]}"; do
    printf "%-6s | %-12s | %-12s | %-12s | %-12s\n" "$S" "${CT[$S]:-N/A}" "${CG[$S]:-N/A}" "${JT[$S]:-N/A}" "${JG[$S]:-N/A}" | tee -a "$RESULTS_FILE"
done

echo "" | tee -a "$RESULTS_FILE"
echo "CSV: $CSV_FILE" | tee -a "$RESULTS_FILE"
echo "Done!" | tee -a "$RESULTS_FILE"
