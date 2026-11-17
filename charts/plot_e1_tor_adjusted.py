import csv
import os
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from mpl_toolkits.axes_grid1.inset_locator import inset_axes


ROOT = Path(__file__).resolve().parents[1]
RESULTS = Path(os.environ.get(
    "E1_TOR_ADJUSTED_CSV",
    ROOT / "experiments" / "voucherhub_evaluation" / "summary" / "e1_tor_adjusted_summary.csv",
))
OUT = ROOT / "experiments" / "voucherhub_evaluation" / "figures"
OUT.mkdir(parents=True, exist_ok=True)

plt.rcParams.update({
    "font.family": "DejaVu Sans", "pdf.fonttype": 42, "ps.fonttype": 42,
    "axes.titlesize": 13, "axes.labelsize": 12, "xtick.labelsize": 10,
    "ytick.labelsize": 10, "legend.fontsize": 11, "lines.linewidth": 2.4,
    "lines.markersize": 7,
})

STYLE = {
    # High-contrast, color-vision-friendly palette (vermillion/blue/green).
    "accio": {"label": r"$\mathsf{Accio}$", "color": "#D55E00", "marker": "D", "linestyle": "--"},
    "vh-cp": {"label": r"$\mathsf{VH\!-\!CP}$", "color": "#0072B2", "marker": "o", "linestyle": "-"},
    "vh-vp": {"label": r"$\mathsf{VH\!-\!VP}$", "color": "#009E73", "marker": "s", "linestyle": "-."},
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


def load_values():
    values = {}
    with RESULTS.open(newline="", encoding="utf-8") as stream:
        for row in csv.DictReader(stream):
            values[(row["topology"], int(row["payments"]), row["protocol"])] = (
                float(row["tor_adjusted_mean_ms"]) / 1000.0
            )
    return values


def main():
    values = load_values()
    xs = [10, 20, 40, 60, 80, 100]
    protocols = ["accio", "vh-cp", "vh-vp"]
    fig, axes = plt.subplots(3, 3, figsize=(15.2, 11.2))
    fig.subplots_adjust(top=0.92, bottom=0.07, left=0.07, right=0.985,
                        hspace=0.34, wspace=0.18)
    handles = None
    labels = None
    for ax, (topology, title) in zip(axes.flat, PANELS):
        for protocol in protocols:
            style = STYLE[protocol]
            ax.plot(xs, [values[(topology, x, protocol)] for x in xs],
                    color=style["color"], marker=style["marker"],
                    linestyle=style["linestyle"], label=style["label"],
                    markeredgecolor="white", markeredgewidth=0.8)
        ax.set_title(title, pad=8)
        ax.set_xlabel("Number of Payments")
        ax.set_ylabel("Tor-adjusted Completion Time (s)")
        ax.set_xticks(xs)
        ax.set_ylim(bottom=0)
        ax.grid(True, color="#D7D7D7", linewidth=0.8)
        ax.set_axisbelow(True)
        for spine in ax.spines.values():
            spine.set_linewidth(1.1)

        # Accio's per-payment Tor round trips dominate the shared y-axis. Add
        # a local zoom so the two VoucherHub variants remain distinguishable
        # without changing the honest scale of the main panel.
        zoom = inset_axes(
            ax, width="45%", height="31.5%", loc="lower right", borderpad=0.72,
            bbox_to_anchor=(0.0, 0.10, 1.0, 1.0), bbox_transform=ax.transAxes,
        )
        zoom_values = []
        for protocol in ["vh-cp", "vh-vp"]:
            style = STYLE[protocol]
            ys = [values[(topology, x, protocol)] for x in xs]
            zoom_values.extend(ys)
            zoom.plot(xs, ys, color=style["color"], marker=style["marker"],
                      linestyle=style["linestyle"], linewidth=1.6, markersize=4.2,
                      markeredgecolor="white", markeredgewidth=0.55)
        low, high = min(zoom_values), max(zoom_values)
        padding = max((high - low) * 0.14, high * 0.035, 0.08)
        zoom.set_ylim(max(0.0, low - padding), high + padding)
        zoom.set_xlim(7, 103)
        zoom.set_xticks([10, 100])
        zoom.tick_params(axis="both", labelsize=6.6, length=2.5, pad=1.5)
        zoom.grid(True, color="#DDDDDD", linewidth=0.55)
        zoom.set_axisbelow(True)
        zoom.set_facecolor("#FCFCFC")
        zoom.set_title(
            r"$\mathsf{VH\!-\!CP}$ vs $\mathsf{VH\!-\!VP}$",
            fontsize=7.2, pad=2.0,
        )
        for spine in zoom.spines.values():
            spine.set_color("#555555")
            spine.set_linewidth(0.75)
        if handles is None:
            handles, labels = ax.get_legend_handles_labels()

    legend = fig.legend(handles, labels, loc="upper center", ncol=3,
                        frameon=True, fancybox=False, framealpha=0.95,
                        bbox_to_anchor=(0.5, 0.99))
    legend.get_frame().set_edgecolor("#777777")
    legend.get_frame().set_linewidth(0.9)

    basename = os.environ.get("E1_FIGURE_BASENAME", "figure1_tor_adjusted_completion_time")
    pdf = OUT / f"{basename}.pdf"
    png = OUT / f"{basename}.png"
    fig.savefig(pdf, bbox_inches="tight")
    fig.savefig(png, dpi=240, bbox_inches="tight")
    plt.close(fig)
    print(pdf)
    print(png)


if __name__ == "__main__":
    main()
