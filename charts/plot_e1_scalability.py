import csv
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt


ROOT = Path(__file__).resolve().parents[1]
RESULTS = ROOT / "experiments" / "voucherhub_evaluation" / "summary" / "e1_summary.csv"
OUT_DIR = ROOT / "experiments" / "voucherhub_evaluation" / "figures"
OUT_DIR.mkdir(parents=True, exist_ok=True)

plt.rcParams.update(
    {
        "font.family": "DejaVu Sans",
        "pdf.fonttype": 42,
        "ps.fonttype": 42,
        "axes.titlesize": 13,
        "axes.labelsize": 12,
        "xtick.labelsize": 11,
        "ytick.labelsize": 10,
        "legend.fontsize": 12,
    }
)

STYLE = {
    "accio": ("Accio", "#968BBE", "//"),
    "vh-cp": ("VH-CP", "#456990", ""),
    "vh-vp": ("VH-VP", "#48C0AA", ".."),
}

PANELS = [
    ("same_city_1to5", "(a) Same-city, 1-to-5"),
    ("regional_1to5", "(b) Regional, 1-to-5"),
    ("global_1to5", "(c) Global, 1-to-5"),
    ("same_city_5to1", "(d) Same-city, 5-to-1"),
    ("regional_5to1", "(e) Regional, 5-to-1"),
    ("global_5to1", "(f) Global, 5-to-1"),
]


def load_values():
    values = {}
    with RESULTS.open(newline="", encoding="utf-8") as stream:
        for row in csv.DictReader(stream):
            if row["status"] == "complete" and int(row["payments"]) == 20:
                values[(row["topology"], row["protocol"])] = float(row["trimmed_mean_ms"]) / 1000.0
    return values


def main():
    values = load_values()
    protocols = ["accio", "vh-cp", "vh-vp"]
    fig, axes = plt.subplots(2, 3, figsize=(14.8, 7.8), constrained_layout=True)
    legend_handles = []
    for ax, (topology, title) in zip(axes.flat, PANELS):
        heights = [values.get((topology, protocol), 0.0) for protocol in protocols]
        bars = []
        for index, protocol in enumerate(protocols):
            label, color, hatch = STYLE[protocol]
            bar = ax.bar(index, heights[index], width=0.62, color=color, edgecolor="#404040",
                         linewidth=0.8, hatch=hatch, label=label)
            bars.append(bar[0])
        if not legend_handles:
            legend_handles = bars
        ax.set_title(title, pad=8)
        ax.set_ylabel("Total Completion Time (s)")
        ax.set_xticks(range(3), [STYLE[p][0] for p in protocols])
        ax.set_ylim(bottom=0)
        ax.grid(axis="y", color="#D7D7D7", linewidth=0.8)
        ax.set_axisbelow(True)
        for spine in ax.spines.values():
            spine.set_linewidth(1.1)

    legend = fig.legend(legend_handles, [STYLE[p][0] for p in protocols], loc="upper center",
                        ncol=3, frameon=True, fancybox=False, framealpha=0.95,
                        bbox_to_anchor=(0.5, 1.04))
    legend.get_frame().set_edgecolor("#777777")
    pdf_out = OUT_DIR / "figure1_scalability_n5.pdf"
    png_out = OUT_DIR / "figure1_scalability_n5.png"
    fig.savefig(pdf_out, bbox_inches="tight")
    fig.savefig(png_out, dpi=240, bbox_inches="tight")
    plt.close(fig)
    print(pdf_out)
    print(png_out)


if __name__ == "__main__":
    main()
