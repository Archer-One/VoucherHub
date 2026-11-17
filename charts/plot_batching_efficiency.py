import csv
from pathlib import Path

import matplotlib

matplotlib.use("Agg")

import matplotlib.pyplot as plt
from matplotlib.ticker import MaxNLocator


ROOT = Path(__file__).resolve().parents[1]
RESULTS = ROOT / "experiments" / "voucherhub_evaluation" / "summary" / "batching_summary.csv"
OUT_DIR = ROOT / "experiments" / "voucherhub_evaluation" / "figures"
OUT_DIR.mkdir(parents=True, exist_ok=True)

plt.rcParams.update(
    {
        "font.family": "DejaVu Sans",
        "pdf.fonttype": 42,
        "ps.fonttype": 42,
        "axes.titlesize": 18,
        "axes.labelsize": 17,
        "xtick.labelsize": 13,
        "ytick.labelsize": 13,
        "legend.fontsize": 13,
        "lines.linewidth": 2.8,
        "lines.markersize": 8.5,
    }
)

STYLE = {
    "sequential": {"color": "#968BBE", "marker": "D", "linestyle": "--", "label": "Sequential"},
    "batched": {"color": "#456990", "marker": "o", "linestyle": "-", "label": "Batched"},
}


def load_data():
    values = {}
    with RESULTS.open(newline="", encoding="utf-8") as stream:
        for row in csv.DictReader(stream):
            values[(row["experiment"], row["mode"], int(row["k"]))] = float(row["total_ms_trimmed_mean"])
    return values


def main():
    values = load_data()
    xs = [1, 3, 5]
    panels = [
        ("vchmint", "(a) Batched VchMint"),
        ("vchsplittransfer", "(b) Batched VchSplitTransfer"),
    ]
    fig, axes = plt.subplots(1, 2, figsize=(13.8, 4.9), constrained_layout=True)

    for ax, (experiment, title) in zip(axes, panels):
        for mode in ["sequential", "batched"]:
            style = STYLE[mode]
            ys = [values[(experiment, mode, k)] for k in xs]
            ax.plot(
                xs,
                ys,
                label=style["label"],
                color=style["color"],
                marker=style["marker"],
                linestyle=style["linestyle"],
                markeredgecolor="white",
                markeredgewidth=0.9,
            )
        ax.set_title(title, pad=12)
        ax.set_xlabel("Number of Outputs (k)", labelpad=8)
        ax.set_ylabel("Completion Time (ms)", labelpad=8)
        ax.set_xticks(xs)
        ax.set_ylim(bottom=0)
        ax.yaxis.set_major_locator(MaxNLocator(nbins=5))
        ax.grid(True, which="major", color="#D7D7D7", linewidth=0.9)
        ax.set_axisbelow(True)
        for spine in ax.spines.values():
            spine.set_linewidth(1.2)

    legend = axes[0].legend(
        loc="upper left",
        frameon=True,
        fancybox=False,
        framealpha=0.95,
        borderpad=0.7,
        handlelength=2.4,
        labelspacing=0.55,
    )
    legend.get_frame().set_edgecolor("#777777")
    legend.get_frame().set_linewidth(0.9)

    pdf_out = OUT_DIR / "figure2_batching_efficiency.pdf"
    png_out = OUT_DIR / "figure2_batching_efficiency.png"
    fig.savefig(pdf_out)
    fig.savefig(png_out, dpi=240)
    plt.close(fig)
    print(pdf_out)
    print(png_out)


if __name__ == "__main__":
    main()
