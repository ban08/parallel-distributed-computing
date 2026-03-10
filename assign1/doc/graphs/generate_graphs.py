#!/usr/bin/env python3
"""
Automated graph generation for CPD Assignment 1.
Reads all CSV files under the results directory, averages duplicate benchmark entries,
and produces performance graphs (GFlop/s vs Matrix Size) plus cache-miss comparisons.
"""

import os
import glob
import pandas as pd
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
import numpy as np

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
RESULTS_DIR = os.path.join(SCRIPT_DIR, "../results")
OUTPUT_DIR = SCRIPT_DIR

# Columns relevant to memory-hierarchy analysis (drop page-faults, cache-misses,
# cache-references — they are OS-level or generic duplicates of LLC counters).
DROP_COLS = ["page-faults", "cache-misses", "cache-references"]

# ── 1. Load and categorise all CSVs ──────────────────────────────────────────

def load_all_csvs():
    """Load all CSV files and categorise them by benchmark type."""
    csv_files = sorted(glob.glob(os.path.join(RESULTS_DIR, "benchmark_*.csv")))

    frames = {"onmult": [], "onmultline": [], "onmultblock": []}

    for f in csv_files:
        basename = os.path.basename(f).lower()
        df = pd.read_csv(f, comment='#')
        df.columns = df.columns.str.strip()
        # Drop irrelevant columns (silently ignore if missing)
        df = df.drop(columns=[c for c in DROP_COLS if c in df.columns])

        if "onmultblock" in basename:
            frames["onmultblock"].append(df)
        elif "onmultline" in basename:
            frames["onmultline"].append(df)
        elif "onmult" in basename:
            frames["onmult"].append(df)

    return frames


def average_duplicates(frames):
    """Concatenate frames per benchmark type and average duplicates."""
    averaged = {}

    for key, dfs in frames.items():
        if not dfs:
            continue
        combined = pd.concat(dfs, ignore_index=True)

        if key == "onmultblock":
            group_cols = ["Language", "Size", "BlockSize"]
        else:
            group_cols = ["Language", "Size"]

        numeric_cols = combined.select_dtypes(include=[np.number]).columns.tolist()
        avg = combined.groupby(group_cols, as_index=False)[numeric_cols].mean()

        # Recompute GFlops: 2*n^3 / time / 1e9
        avg["GFlops"] = (2.0 * avg["Size"].astype(float) ** 3) / avg["Time_seconds"] / 1e9
        averaged[key] = avg

    return averaged


# ── 2. Plotting helpers ──────────────────────────────────────────────────────

COLORS = {
    "C++": "#1f77b4",
    "Java": "#ff7f0e",
}
MARKERS = {
    "C++": "o",
    "Java": "s",
}


def nice_sizes(sizes):
    """Return tick labels like '1024', '2048', ..."""
    return [str(int(s)) for s in sizes]


def save(fig, name):
    path = os.path.join(OUTPUT_DIR, name)
    fig.savefig(path, dpi=200, bbox_inches="tight")
    plt.close(fig)
    print(f"  Saved: {path}")


# ── 3. Graph functions ───────────────────────────────────────────────────────

def plot_onmult(data):
    """Graph 1: Basic multiplication (OnMult) – C++ vs Java, 1024–3072."""
    if data is None or data.empty:
        print("  [skip] No OnMult data.")
        return

    fig, ax = plt.subplots(figsize=(9, 5))
    for lang, grp in data.groupby("Language"):
        grp = grp.sort_values("Size")
        ax.plot(grp["Size"], grp["GFlops"],
                marker=MARKERS.get(lang, "^"), color=COLORS.get(lang, None),
                linewidth=2, markersize=7, label=lang)

    ax.set_xlabel("Matrix Size (n × n)", fontsize=12)
    ax.set_ylabel("Performance (GFlop/s)", fontsize=12)
    ax.set_title("OnMult – Basic Matrix Multiplication", fontsize=14)
    ax.set_xticks(sorted(data["Size"].unique()))
    ax.set_xticklabels(nice_sizes(sorted(data["Size"].unique())), rotation=45)
    ax.legend(fontsize=11)
    ax.grid(True, alpha=0.3)
    save(fig, "graph_onmult.png")


def plot_onmultline_small(data):
    """Graph 2: Line multiplication – C++ vs Java, 1024–3072."""
    if data is None or data.empty:
        print("  [skip] No OnMultLine data.")
        return

    small = data[data["Size"] <= 3072]
    if small.empty:
        print("  [skip] No OnMultLine data for sizes ≤ 3072.")
        return

    fig, ax = plt.subplots(figsize=(9, 5))
    for lang, grp in small.groupby("Language"):
        grp = grp.sort_values("Size")
        ax.plot(grp["Size"], grp["GFlops"],
                marker=MARKERS.get(lang, "^"), color=COLORS.get(lang, None),
                linewidth=2, markersize=7, label=lang)

    ax.set_xlabel("Matrix Size (n × n)", fontsize=12)
    ax.set_ylabel("Performance (GFlop/s)", fontsize=12)
    ax.set_title("OnMultLine – Line-based Multiplication (1024–3072)", fontsize=14)
    ax.set_xticks(sorted(small["Size"].unique()))
    ax.set_xticklabels(nice_sizes(sorted(small["Size"].unique())), rotation=45)
    ax.legend(fontsize=11)
    ax.grid(True, alpha=0.3)
    save(fig, "graph_onmultline_small.png")


def plot_onmultline_large(data):
    """Graph 3: Line multiplication – C++ only, 4096–10240."""
    if data is None or data.empty:
        print("  [skip] No OnMultLine data.")
        return

    large = data[data["Size"] >= 4096]
    if large.empty:
        print("  [skip] No OnMultLine data for sizes ≥ 4096.")
        return

    fig, ax = plt.subplots(figsize=(9, 5))
    for lang, grp in large.groupby("Language"):
        grp = grp.sort_values("Size")
        ax.plot(grp["Size"], grp["GFlops"],
                marker=MARKERS.get(lang, "^"), color=COLORS.get(lang, None),
                linewidth=2, markersize=7, label=lang)

    ax.set_xlabel("Matrix Size (n × n)", fontsize=12)
    ax.set_ylabel("Performance (GFlop/s)", fontsize=12)
    ax.set_title("OnMultLine – Line-based Multiplication (4096–10240, C++)", fontsize=14)
    ax.set_xticks(sorted(large["Size"].unique()))
    ax.set_xticklabels(nice_sizes(sorted(large["Size"].unique())), rotation=45)
    ax.legend(fontsize=11)
    ax.grid(True, alpha=0.3)
    save(fig, "graph_onmultline_large.png")


def plot_onmultblock(data):
    """Graph 4: Block multiplication – C++ with different block sizes."""
    if data is None or data.empty:
        print("  [skip] No OnMultBlock data.")
        return

    fig, ax = plt.subplots(figsize=(9, 5))
    for bs, grp in data.groupby("BlockSize"):
        grp = grp.sort_values("Size")
        ax.plot(grp["Size"], grp["GFlops"],
                marker="o", linewidth=2, markersize=7,
                label=f"Block {int(bs)}")

    ax.set_xlabel("Matrix Size (n × n)", fontsize=12)
    ax.set_ylabel("Performance (GFlop/s)", fontsize=12)
    ax.set_title("OnMultBlock – Block-based Multiplication (C++)", fontsize=14)
    ax.set_xticks(sorted(data["Size"].unique()))
    ax.set_xticklabels(nice_sizes(sorted(data["Size"].unique())), rotation=45)
    ax.legend(fontsize=11)
    ax.grid(True, alpha=0.3)
    save(fig, "graph_onmultblock.png")


def plot_combined_comparison(onmult_data, onmultline_data, onmultblock_data):
    """Graph 5: Combined comparison – all C++ algorithms on overlapping sizes."""
    fig, ax = plt.subplots(figsize=(11, 6))
    plotted = False

    # OnMult C++
    if onmult_data is not None and not onmult_data.empty:
        cpp = onmult_data[onmult_data["Language"] == "C++"].sort_values("Size")
        if not cpp.empty:
            ax.plot(cpp["Size"], cpp["GFlops"],
                    marker="o", linewidth=2, markersize=7,
                    label="OnMult (C++)", color="#d62728")
            plotted = True

    # OnMultLine C++
    if onmultline_data is not None and not onmultline_data.empty:
        cpp = onmultline_data[onmultline_data["Language"] == "C++"].sort_values("Size")
        if not cpp.empty:
            ax.plot(cpp["Size"], cpp["GFlops"],
                    marker="s", linewidth=2, markersize=7,
                    label="OnMultLine (C++)", color="#1f77b4")
            plotted = True

    # OnMultBlock C++ (one line per block size)
    if onmultblock_data is not None and not onmultblock_data.empty:
        block_colors = {128: "#2ca02c", 256: "#9467bd", 512: "#8c564b"}
        for bs, grp in onmultblock_data.groupby("BlockSize"):
            grp = grp.sort_values("Size")
            ax.plot(grp["Size"], grp["GFlops"],
                    marker="^", linewidth=2, markersize=7,
                    label=f"OnMultBlock B={int(bs)} (C++)",
                    color=block_colors.get(int(bs), None))
            plotted = True

    if not plotted:
        print("  [skip] No data for combined comparison.")
        plt.close(fig)
        return

    ax.set_xlabel("Matrix Size (n × n)", fontsize=12)
    ax.set_ylabel("Performance (GFlop/s)", fontsize=12)
    ax.set_title("Combined Comparison – All C++ Algorithms", fontsize=14)
    ax.legend(fontsize=10, loc="best")
    ax.grid(True, alpha=0.3)
    save(fig, "graph_combined_comparison.png")


def plot_onmult_vs_onmultline_cpp(onmult_data, onmultline_data):
    """Graph 6: OnMult vs OnMultLine for C++ on small sizes (1024–3072)."""
    fig, ax = plt.subplots(figsize=(9, 5))
    plotted = False

    if onmult_data is not None and not onmult_data.empty:
        cpp = onmult_data[(onmult_data["Language"] == "C++") & (onmult_data["Size"] <= 3072)].sort_values("Size")
        if not cpp.empty:
            ax.plot(cpp["Size"], cpp["GFlops"],
                    marker="o", linewidth=2, markersize=7,
                    label="OnMult (C++)", color="#d62728")
            plotted = True

    if onmultline_data is not None and not onmultline_data.empty:
        cpp = onmultline_data[(onmultline_data["Language"] == "C++") & (onmultline_data["Size"] <= 3072)].sort_values("Size")
        if not cpp.empty:
            ax.plot(cpp["Size"], cpp["GFlops"],
                    marker="s", linewidth=2, markersize=7,
                    label="OnMultLine (C++)", color="#1f77b4")
            plotted = True

    if not plotted:
        print("  [skip] No data for OnMult vs OnMultLine comparison.")
        plt.close(fig)
        return

    ax.set_xlabel("Matrix Size (n × n)", fontsize=12)
    ax.set_ylabel("Performance (GFlop/s)", fontsize=12)
    ax.set_title("OnMult vs OnMultLine – C++ (1024–3072)", fontsize=14)
    ax.legend(fontsize=11)
    ax.grid(True, alpha=0.3)
    save(fig, "graph_onmult_vs_onmultline.png")


def plot_cache_misses(onmult_data, onmultline_data, onmultblock_data):
    """Graph 7: L1 and L2 data-cache misses across all C++ algorithms.

    Produces two sub-graphs (side by side):
      - Left:  L1 dcache load misses  (L1-dcache-load-misses)
      - Right: LLC load misses         (LLC-load-misses)
    Each sub-graph shows one line per algorithm variant.
    Data that is still NaN (not yet collected) is silently skipped.
    """

    # Collect series: list of (label, sizes, l1_misses, llc_misses)
    series = []

    # -- OnMult C++ --
    if onmult_data is not None and not onmult_data.empty:
        cpp = onmult_data[onmult_data["Language"] == "C++"].sort_values("Size")
        if not cpp.empty:
            series.append(("OnMult", cpp))

    # -- OnMultLine C++ --
    if onmultline_data is not None and not onmultline_data.empty:
        cpp = onmultline_data[onmultline_data["Language"] == "C++"].sort_values("Size")
        if not cpp.empty:
            series.append(("OnMultLine", cpp))

    # -- OnMultBlock C++ (one series per block size) --
    if onmultblock_data is not None and not onmultblock_data.empty:
        for bs, grp in onmultblock_data.groupby("BlockSize"):
            grp = grp.sort_values("Size")
            series.append((f"OnMultBlock B={int(bs)}", grp))

    if not series:
        print("  [skip] No data for cache-miss comparison.")
        return

    # Determine which cache-miss columns are available and have data
    l1_col = "L1-dcache-load-misses"
    llc_col = "LLC-load-misses"

    has_l1 = any(l1_col in s[1].columns and s[1][l1_col].notna().any() for s in series)
    has_llc = any(llc_col in s[1].columns and s[1][llc_col].notna().any() for s in series)

    if not has_l1 and not has_llc:
        print("  [skip] Cache-miss columns are all NaN — graph will be generated once perf data is collected.")
        return

    ncols = int(has_l1) + int(has_llc)
    fig, axes = plt.subplots(1, ncols, figsize=(6 * ncols + 2, 5))
    if ncols == 1:
        axes = [axes]

    markers_cycle = ["o", "s", "^", "D", "v", "P"]

    ax_idx = 0
    if has_l1:
        ax = axes[ax_idx]; ax_idx += 1
        for i, (label, df) in enumerate(series):
            if l1_col in df.columns:
                valid = df[df[l1_col].notna()]
                if not valid.empty:
                    ax.plot(valid["Size"], valid[l1_col],
                            marker=markers_cycle[i % len(markers_cycle)],
                            linewidth=2, markersize=7, label=label)
        ax.set_xlabel("Matrix Size (n × n)", fontsize=12)
        ax.set_ylabel("L1 Data-Cache Load Misses", fontsize=12)
        ax.set_title("L1 D-Cache Misses (C++)", fontsize=14)
        ax.legend(fontsize=9, loc="best")
        ax.grid(True, alpha=0.3)
        ax.ticklabel_format(axis='y', style='scientific', scilimits=(0, 0))

    if has_llc:
        ax = axes[ax_idx]; ax_idx += 1
        for i, (label, df) in enumerate(series):
            if llc_col in df.columns:
                valid = df[df[llc_col].notna()]
                if not valid.empty:
                    ax.plot(valid["Size"], valid[llc_col],
                            marker=markers_cycle[i % len(markers_cycle)],
                            linewidth=2, markersize=7, label=label)
        ax.set_xlabel("Matrix Size (n × n)", fontsize=12)
        ax.set_ylabel("LLC (L3) Load Misses", fontsize=12)
        ax.set_title("Last-Level Cache Misses (C++)", fontsize=14)
        ax.legend(fontsize=9, loc="best")
        ax.grid(True, alpha=0.3)
        ax.ticklabel_format(axis='y', style='scientific', scilimits=(0, 0))

    fig.tight_layout()
    save(fig, "graph_cache_misses.png")


# ── 4. Main ──────────────────────────────────────────────────────────────────

def main():
    print("=" * 60)
    print("CPD Assignment 1 – Automated Graph Generation")
    print("=" * 60)

    # Load
    print("\n[1/3] Loading CSV files...")
    frames = load_all_csvs()
    for k, v in frames.items():
        print(f"  {k}: {len(v)} file(s)")

    # Average
    print("\n[2/3] Averaging duplicate entries...")
    data = average_duplicates(frames)
    for k, v in data.items():
        print(f"  {k}: {len(v)} rows")

    # Print summary tables
    for k, v in data.items():
        print(f"\n  ── {k} ──")
        print(v.to_string(index=False))

    # Plot
    print("\n[3/3] Generating graphs...")
    plot_onmult(data.get("onmult"))
    plot_onmultline_small(data.get("onmultline"))
    plot_onmultline_large(data.get("onmultline"))
    plot_onmultblock(data.get("onmultblock"))
    plot_combined_comparison(data.get("onmult"), data.get("onmultline"), data.get("onmultblock"))
    plot_onmult_vs_onmultline_cpp(data.get("onmult"), data.get("onmultline"))
    plot_cache_misses(data.get("onmult"), data.get("onmultline"), data.get("onmultblock"))

    print("\nDone! All graphs saved to:", OUTPUT_DIR)


if __name__ == "__main__":
    main()
