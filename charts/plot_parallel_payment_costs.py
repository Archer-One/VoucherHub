import json
from pathlib import Path

import matplotlib

matplotlib.use("Agg")

import matplotlib.pyplot as plt
from matplotlib.ticker import MaxNLocator


ROOT = Path(__file__).resolve().parents[1]
RESULTS = ROOT / "code" / "未命名" / "work" / "aws_10_100_bn128_results" / "combined_results.json"
OUT_DIR = ROOT / "charts" / "figures"
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
    "VoucherHub-1 payer": {"color": "#456990", "marker": "o", "linestyle": "-"},
    "VoucherHub-3 payers": {"color": "#48C0AA", "marker": "s", "linestyle": "-"},
    "VoucherHub-5 payers": {"color": "#EF767A", "marker": "^", "linestyle": "-"},
    "Accio serialized": {"color": "#968BBE", "marker": "D", "linestyle": "--"},
}

OUTPUT_NAMES = {
    "same_region_tokyo": "same_region_tokyo.pdf",
    "regional_tokyo_frankfurt_seoul": "regional_tokyo_frankfurt_seoul.pdf",
    "intercontinental_tokyo_frankfurt_virginia": "intercontinental_tokyo_frankfurt_virginia.pdf",
}

ALIASES = {
    "regional_tokyo_frankfurt_seoul": ["regional_tokyo_singapore_seoul.pdf"],
}


def plot_experiment(name, cfg):
    fig, ax = plt.subplots(figsize=(7.2, 4.8), constrained_layout=True)

    xs = cfg["x"]
    for label, values in cfg["series"].items():
        style = STYLE[label]
        ax.plot(
            xs,
            values,
            label=label,
            color=style["color"],
            marker=style["marker"],
            linestyle=style["linestyle"],
            markeredgecolor="white",
            markeredgewidth=0.9,
        )

    ax.set_xlabel("Payment Number", labelpad=8)
    ax.set_ylabel("Cumulative Completion Time (s)", labelpad=8)
    ax.set_xlim(min(xs) - 3, max(xs) + 4)
    ax.set_ylim(bottom=0)
    ax.set_xticks(xs)
    ax.yaxis.set_major_locator(MaxNLocator(nbins=5))
    ax.grid(True, which="major", color="#D7D7D7", linewidth=0.9)
    ax.set_axisbelow(True)

    for spine in ax.spines.values():
        spine.set_linewidth(1.2)

    legend = ax.legend(
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

    output_paths = [OUT_DIR / OUTPUT_NAMES.get(name, f"{name}.pdf")]
    output_paths.extend(OUT_DIR / alias for alias in ALIASES.get(name, []))
    saved = []
    for out in output_paths:
        png_out = out.with_suffix(".png")
        fig.savefig(out)
        fig.savefig(png_out, dpi=240)
        saved.extend([out, png_out])
    plt.close(fig)
    return saved


def main():
    with RESULTS.open(encoding="utf-8") as f:
        experiments = json.load(f)

    generated = [plot_experiment(name, cfg) for name, cfg in experiments.items()]
    for paths in generated:
        for path in paths:
            print(path)


if __name__ == "__main__":
    main()
