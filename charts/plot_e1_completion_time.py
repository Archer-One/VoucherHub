import csv
from pathlib import Path

import matplotlib

matplotlib.use("Agg")

import matplotlib.pyplot as plt
from matplotlib.ticker import MaxNLocator


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
        "axes.labelsize": 13,
        "xtick.labelsize": 10,
        "ytick.labelsize": 10,
        "legend.fontsize": 12,
        "lines.linewidth": 2.4,
        "lines.markersize": 6.8,
    }
)

STYLE = {
    "accio": {"label": "Accio", "color": "#968BBE", "marker": "D", "linestyle": "--"},
    "vh-cp": {"label": "VH-CP", "color": "#456990", "marker": "o", "linestyle": "-"},
    "vh-vp": {"label": "VH-VP", "color": "#48C0AA", "marker": "s", "linestyle": "-"},
}

PANELS = [
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


def load_data():
    values = {}
    with RESULTS.open(newline="", encoding="utf-8") as stream:
        for row in csv.DictReader(stream):
            if row["status"] != "complete":
                continue
            values[(row["topology"], int(row["payments"]), row["protocol"])] = (
                float(row["trimmed_mean_ms"]) / 1000.0
            )
    return values


def main():
    values = load_data()
    # The current reproduction request measures exactly N=20. Derive the
    # available x values so the same plotting code also remains reusable if
    # more payment counts are collected later.
    xs = sorted({payments for (_, payments, _) in values})
    fig, axes = plt.subplots(3, 3, figsize=(15.2, 11.2), constrained_layout=True)
    handles = None
    labels = None
    for ax, (topology, title) in zip(axes.flat, PANELS):
        for protocol in ["accio", "vh-cp", "vh-vp"]:
            protocol_xs = [x for x in xs if (topology, x, protocol) in values]
            if not protocol_xs:
                continue
            style = STYLE[protocol]
            ax.plot(
                protocol_xs,
                [values[(topology, x, protocol)] for x in protocol_xs],
                label=style["label"],
                color=style["color"],
                marker=style["marker"],
                linestyle=style["linestyle"],
                markeredgecolor="white",
                markeredgewidth=0.8,
            )
        ax.set_title(title, pad=8)
        ax.set_xlabel("Number of Payments")
        ax.set_ylabel("Total Completion Time (s)")
        ax.set_xticks(xs or [20])
        if len(xs) == 1:
            ax.set_xlim(xs[0] - 2, xs[0] + 2)
        ax.set_ylim(bottom=0)
        ax.yaxis.set_major_locator(MaxNLocator(nbins=5))
        ax.grid(True, color="#D7D7D7", linewidth=0.8)
        ax.set_axisbelow(True)
        for spine in ax.spines.values():
            spine.set_linewidth(1.1)
        if handles is None and ax.lines:
            handles, labels = ax.get_legend_handles_labels()

    if handles:
        legend = fig.legend(handles, labels, loc="upper center", ncol=3, frameon=True,
                            fancybox=False, framealpha=0.95, bbox_to_anchor=(0.5, 1.02))
        legend.get_frame().set_edgecolor("#777777")
        legend.get_frame().set_linewidth(0.9)

    pdf_out = OUT_DIR / "figure1_completion_time.pdf"
    png_out = OUT_DIR / "figure1_completion_time.png"
    fig.savefig(pdf_out, bbox_inches="tight")
    fig.savefig(png_out, dpi=240, bbox_inches="tight")
    plt.close(fig)
    print(pdf_out)
    print(png_out)


if __name__ == "__main__":
    main()
