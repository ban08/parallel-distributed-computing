#!/bin/bash
# =============================================================================
# Benchmark: OnMultBlock (Part 1.3) — Block-oriented multiplication (C++ only)
# Sizes:       4096 to 10240, step 2048
# Block sizes: 128, 256, 512
# CSV includes: perf counters + GFlop/s  (GFlop/s = 2*n^3 / (time * 1e9))
# =============================================================================
set -e

SIZES=(4096 6144 8192 10240)
BLOCK_SIZES=(128 256 512)
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
HEADER="Language,Size,BlockSize,Time_seconds,GFlops"
for ev in "${EVENTS_BASIC[@]}"; do HEADER="$HEADER,$ev"; done
for ev in "${EVENTS_EXT[@]}"; do HEADER="$HEADER,$ev"; done

CSV_FILE=$(ls -t "$RESULTS_DIR"/benchmark_onmultblock_*.csv 2>/dev/null | head -n1)
RESULTS_FILE=$(ls -t "$RESULTS_DIR"/benchmark_onmultblock_*.txt 2>/dev/null | head -n1)

if [[ -z "$CSV_FILE" ]]; then
    CSV_FILE="$RESULTS_DIR/benchmark_onmultblock_${TIMESTAMP}.csv"
    echo "$HEADER" > "$CSV_FILE"
fi
if [[ -z "$RESULTS_FILE" ]]; then
    RESULTS_FILE="$RESULTS_DIR/benchmark_onmultblock_${TIMESTAMP}.txt"
fi

# --- Run separator ---
echo "# RUN $TIMESTAMP" >> "$CSV_FILE"

# --- Log Header ---
echo "" >> "$RESULTS_FILE"
echo "=================================================================" | tee -a "$RESULTS_FILE"
echo " Benchmark: OnMultBlock (Part 1.3)  —  RUN $TIMESTAMP"            | tee -a "$RESULTS_FILE"
echo " Date: $(date)"                                                   | tee -a "$RESULTS_FILE"
echo " Sizes:       ${SIZES[*]}"                                        | tee -a "$RESULTS_FILE"
echo " Block sizes: ${BLOCK_SIZES[*]}"                                  | tee -a "$RESULTS_FILE"
echo "=================================================================" | tee -a "$RESULTS_FILE"

# --- Compilation ---
echo "" | tee -a "$RESULTS_FILE"
echo "Compiling C++ (-O2) ..." | tee -a "$RESULTS_FILE"
g++ -O2 -o matrix_cpp matrixproduct.cpp
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

run_block_benchmark() {
    local SIZE="$1" BK="$2"

    echo "" | tee -a "$RESULTS_FILE"
    echo "----- C++ | OnMultBlock | ${SIZE}x${SIZE} | block=${BK} -----" | tee -a "$RESULTS_FILE"

    # Menu: option 3, size, block size, then exit
    local TMPINPUT=$(mktemp)
    local TMPPERF_B=$(mktemp)
    local TMPPERF_E=$(mktemp)
    printf '3\n%s\n%s\n0\n' "$SIZE" "$BK" > "$TMPINPUT"

    echo "  perf stat (basic) ..." | tee -a "$RESULTS_FILE"
    PROG_OUT_B=$(perf stat -e "$PERF_EVENTS" -o "$TMPPERF_B" -- ./matrix_cpp < "$TMPINPUT" 2>&1) || true

    echo "  perf stat (extended) ..." | tee -a "$RESULTS_FILE"
    PROG_OUT_E=$(perf stat -e "$PERF_EVENTS_EXT" -o "$TMPPERF_E" -- ./matrix_cpp < "$TMPINPUT" 2>&1) || true

    # Combine program stdout with perf stats for parsing
    local OUT_B="$PROG_OUT_B"$'\n'"$(cat "$TMPPERF_B")"
    local OUT_E="$PROG_OUT_E"$'\n'"$(cat "$TMPPERF_E")"

    local T=$(extract_time "$OUT_B")
    local GF=$(calc_gflops "$SIZE" "$T")
    echo "  Time: ${T}s | GFlop/s: ${GF}" | tee -a "$RESULTS_FILE"

    local LINE="C++,$SIZE,$BK,$T,$GF"
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

# --- Run all combinations ---
for BK in "${BLOCK_SIZES[@]}"; do
    echo "" | tee -a "$RESULTS_FILE"
    echo "=================================================================" | tee -a "$RESULTS_FILE"
    echo "         C++ OnMultBlock — block size = ${BK}"                     | tee -a "$RESULTS_FILE"
    echo "=================================================================" | tee -a "$RESULTS_FILE"

    for S in "${SIZES[@]}"; do
        run_block_benchmark "$S" "$BK"
    done
done

# --- Summary Table ---
echo "" | tee -a "$RESULTS_FILE"
echo "=================================================================" | tee -a "$RESULTS_FILE"
echo "               SUMMARY — OnMultBlock (Part 1.3)"                   | tee -a "$RESULTS_FILE"
echo "=================================================================" | tee -a "$RESULTS_FILE"
printf "%-6s | %-6s | %-12s | %-12s\n" "Size" "Block" "Time (s)" "GFlop/s" | tee -a "$RESULTS_FILE"
echo "-------|--------|--------------|-------------" | tee -a "$RESULTS_FILE"

# Read CSV and print summary
tail -n +2 "$CSV_FILE" | grep -v '^#' | while IFS=',' read -r lang size bk time gf rest; do
    printf "%-6s | %-6s | %-12s | %-12s\n" "$size" "$bk" "$time" "$gf" | tee -a "$RESULTS_FILE"
done

echo "" | tee -a "$RESULTS_FILE"
echo "CSV: $CSV_FILE" | tee -a "$RESULTS_FILE"
echo "Done!" | tee -a "$RESULTS_FILE"
