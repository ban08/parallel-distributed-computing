#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

"$SCRIPT_DIR/benchmark_part1_onmult.sh"
"$SCRIPT_DIR/benchmark_part1_onmultline.sh"
"$SCRIPT_DIR/benchmark_part1_onmultblock.sh"
"$SCRIPT_DIR/benchmark_part2_1.sh"
"$SCRIPT_DIR/benchmark_part2_2.sh"

echo "[DONE] All benchmark scripts completed. Results are under doc/results."
