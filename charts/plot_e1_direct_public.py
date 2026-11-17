import csv
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


ROOT = Path(__file__).resolve().parents[1]
RESULTS = ROOT / "experiments" / "voucherhub_evaluation" / "summary" / "e1_direct_public_summary.csv"
OUT = ROOT / "experiments" / "voucherhub_evaluation" / "figures"
OUT.mkdir(parents=True, exist_ok=True)

plt.rcParams.update({
    "font.family": "DejaVu Sans", "pdf.fonttype": 42, "ps.fonttype": 42,
    "axes.titlesize": 13, "axes.labelsize": 12, "xtick.labelsize": 10,
    "ytick.labelsize": 10, "legend.fontsize": 11, "lines.linewidth": 2.4,
    "lines.markersize": 7,
})

STYLE = {
    "accio": {"label": "Accio", "color": "#968BBE", "marker": "D", "linestyle": "--"},
    "vh-cp": {"label": "VH-CP", "color": "#456990", "marker": "o", "linestyle": "-"},
    "vh-vp": {"label": "VH-VP", "color": "#48C0AA", "marker": "s", "linestyle": "-"},
}

MAIN = [
    ("same_city_1to1", "(a) Same-city, 1-to-1"),
    ("same_city_1to3", "(b) Same-city, 1-to-3"),
    ("same_city_3to1", "(c) Same-city, 3-to-1"),
    ("regional_1to1", "(d) Regional, 1-to-1"),
    ("regional_1to3", "(e) Regional, 1-to-3"),
    ("regional_3to1", "(f) Regional, 3-to-1"),
    ("global_1to1", "(g) Global, 1-to-1"),
    ("global_1to3", "(h) Global, 1-to-3"),
    ("global_3to1", "(i) Global, 3-to-1"),
]


def load_values():
    values = {}
    with RESULTS.open(newline="", encoding="utf-8") as stream:
        for row in csv.DictReader(stream):
            if row["status"] == "complete":
                values[(row["topology"], int(row["payments"]), row["protocol"])] = (
                    float(row["mean_ms"]) / 1000.0
                )
    return values


def main_figure(values):
    xs = [10, 20, 40, 60, 80, 100]
    protocols = ["accio", "vh-cp", "vh-vp"]
    fig, axes = plt.subplots(3, 3, figsize=(15.2, 11.2))
    fig.subplots_adjust(top=0.92, bottom=0.07, left=0.07, right=0.985,
                        hspace=0.34, wspace=0.18)
    handles = None
    labels = None
    for ax, (topology, title) in zip(axes.flat, MAIN):
        for protocol in protocols:
            style = STYLE[protocol]
            ax.plot(xs, [values[(topology, x, protocol)] for x in xs],
                    color=style["color"], marker=style["marker"],
                    linestyle=style["linestyle"], label=style["label"],
                    markeredgecolor="white", markeredgewidth=0.8)
        ax.set_title(title, pad=8)
        ax.set_xlabel("Number of Payments")
        ax.set_ylabel("Total Completion Time (s)")
        ax.set_xticks(xs)
        ax.set_ylim(bottom=0)
        ax.grid(True, color="#D7D7D7", linewidth=0.8)
        ax.set_axisbelow(True)
        for spine in ax.spines.values():
            spine.set_linewidth(1.1)
        if handles is None:
            handles, labels = ax.get_legend_handles_labels()
    legend = fig.legend(handles, labels, loc="upper center",
                        ncol=3, frameon=True, fancybox=False, framealpha=0.95,
                        bbox_to_anchor=(0.5, 0.99))
    legend.get_frame().set_edgecolor("#777777")
    pdf = OUT / "figure1_direct_public_completion_time.pdf"
    png = OUT / "figure1_direct_public_completion_time.png"
    fig.savefig(pdf, bbox_inches="tight")
    fig.savefig(png, dpi=240, bbox_inches="tight")
    plt.close(fig)
    print(pdf)
    print(png)


def scalability_figure(values):
    regions = [("same_city", "Same-city"), ("regional", "Regional"), ("global", "Global")]
    protocols = ["accio", "vh-cp", "vh-vp"]
    fig, axes = plt.subplots(2, 3, figsize=(14.8, 7.8))
    fig.subplots_adjust(top=0.86, bottom=0.10, left=0.07, right=0.985,
                        hspace=0.34, wspace=0.18)
    handles = None
    labels = None
    for column, (prefix, region_label) in enumerate(regions):
        for row, direction in enumerate(["1to", "to1"]):
            ax = axes[row, column]
            for protocol in protocols:
                style = STYLE[protocol]
                if direction == "1to":
                    topologies = [f"{prefix}_1to1", f"{prefix}_1to3", f"{prefix}_1to5"]
                else:
                    topologies = [f"{prefix}_1to1", f"{prefix}_3to1", f"{prefix}_5to1"]
                ax.plot([1, 3, 5], [values[(topology, 20, protocol)] for topology in topologies],
                        color=style["color"], marker=style["marker"], label=style["label"],
                        markeredgecolor="white", markeredgewidth=0.8)
            relation = "One payer to payees" if row == 0 else "Payers to one payee"
            ax.set_title(f"{region_label}: {relation}", pad=8)
            ax.set_xlabel("Number of Payees" if row == 0 else "Number of Payers")
            ax.set_ylabel("Total Completion Time (s)")
            ax.set_xticks([1, 3, 5])
            ax.set_ylim(bottom=0)
            ax.grid(True, color="#D7D7D7", linewidth=0.8)
            ax.set_axisbelow(True)
            if handles is None:
                handles, labels = ax.get_legend_handles_labels()
    legend = fig.legend(handles, labels, loc="upper center", ncol=3, frameon=True,
                        fancybox=False, framealpha=0.95, bbox_to_anchor=(0.5, 0.985))
    legend.get_frame().set_edgecolor("#777777")
    pdf = OUT / "figure1_direct_public_scalability.pdf"
    png = OUT / "figure1_direct_public_scalability.png"
    fig.savefig(pdf, bbox_inches="tight")
    fig.savefig(png, dpi=240, bbox_inches="tight")
    plt.close(fig)
    print(pdf)
    print(png)


if __name__ == "__main__":
    data = load_values()
    main_figure(data)
    scalability_figure(data)
