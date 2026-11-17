#!/usr/bin/env python3
"""Plot Figure 1 from real Tor measurements; adjusted TCP data is rejected."""

import csv
import os
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from mpl_toolkits.axes_grid1.inset_locator import inset_axes


ROOT = Path(__file__).resolve().parents[1]
RESULTS = Path(os.environ.get(
    "E1_TOR_MEASURED_CSV",
    ROOT / "experiments/voucherhub_evaluation/summary/e1_tor_measured_summary.csv"))
OUT = Path(os.environ.get(
    "E1_TOR_FIGURE_DIR",
    ROOT / "experiments/voucherhub_evaluation/figures"))
OUT.mkdir(parents=True, exist_ok=True)

STYLE = {
    "accio": {"label": "Accio", "color": "#D55E00", "marker": "D", "linestyle": "--"},
    "vh-cp": {"label": "VH-CP", "color": "#0072B2", "marker": "o", "linestyle": "-"},
    "vh-vp": {"label": "VH-VP", "color": "#009E73", "marker": "s", "linestyle": "-."},
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
            if row["transport"] != "Tor v3 onion via SOCKS5":
                raise ValueError("plot input contains non-Tor data")
            if row["status"] == "complete":
                values[(row["topology"], int(row["payments"]), row["protocol"])] = (
                    float(row["mean_ms"]) / 1000.0)
    return values


def format_axes(ax, title, xlabel):
    ax.set_title(title, pad=8)
    ax.set_xlabel(xlabel)
    ax.set_ylabel("Measured Completion Time over Tor (s)")
    ax.set_ylim(bottom=0)
    ax.grid(True, color="#D7D7D7", linewidth=0.8)
    ax.set_axisbelow(True)


def completion_figure(values):
    xs = [10, 20, 40, 60, 80, 100]
    fig, axes = plt.subplots(3, 3, figsize=(15.2, 11.2))
    fig.subplots_adjust(top=0.92, bottom=0.07, left=0.07, right=0.985,
                        hspace=0.34, wspace=0.20)
    handles = labels = None
    for ax, (topology, title) in zip(axes.flat, PANELS):
        for protocol in STYLE:
            style = STYLE[protocol]
            ys = [values[(topology, x, protocol)] for x in xs]
            ax.plot(xs, ys, color=style["color"], marker=style["marker"],
                    linestyle=style["linestyle"], label=style["label"],
                    markeredgecolor="white", markeredgewidth=0.8, linewidth=2.4)
        ax.set_xticks(xs)
        format_axes(ax, title, "Number of Payments")
        zoom_values = []
        zoom = inset_axes(ax, width="45%", height="31.5%", loc="lower right", borderpad=0.72,
                          bbox_to_anchor=(0, 0.10, 1, 1), bbox_transform=ax.transAxes)
        for protocol in ["vh-cp", "vh-vp"]:
            style = STYLE[protocol]
            ys = [values[(topology, x, protocol)] for x in xs]
            zoom_values.extend(ys)
            zoom.plot(xs, ys, color=style["color"], marker=style["marker"],
                      linestyle=style["linestyle"], linewidth=1.5, markersize=4)
        low, high = min(zoom_values), max(zoom_values)
        pad = max((high - low) * 0.14, high * 0.035, 0.02)
        zoom.set_ylim(max(0, low - pad), high + pad)
        zoom.set_xticks([10, 100])
        zoom.tick_params(labelsize=6.5)
        zoom.grid(True, color="#DDDDDD", linewidth=0.5)
        zoom.set_title("VH-CP vs VH-VP", fontsize=7)
        if handles is None:
            handles, labels = ax.get_legend_handles_labels()
    fig.legend(handles, labels, loc="upper center", ncol=3, frameon=True,
               bbox_to_anchor=(0.5, 0.99))
    for suffix in ["pdf", "png"]:
        path = OUT / f"figure1_tor_measured_completion_time.{suffix}"
        fig.savefig(path, dpi=240 if suffix == "png" else None, bbox_inches="tight")
        print(path)
    plt.close(fig)


def scalability_figure(values):
    regions = [("same_city", "Same-city"), ("regional", "Regional"), ("global", "Global")]
    fig, axes = plt.subplots(2, 3, figsize=(14.8, 7.8))
    fig.subplots_adjust(top=0.86, bottom=0.10, left=0.07, right=0.985,
                        hspace=0.34, wspace=0.20)
    handles = labels = None
    for column, (prefix, label) in enumerate(regions):
        for row, direction in enumerate(["1to", "to1"]):
            ax = axes[row, column]
            topologies = ([f"{prefix}_1to1", f"{prefix}_1to3", f"{prefix}_1to5"]
                          if direction == "1to" else
                          [f"{prefix}_1to1", f"{prefix}_3to1", f"{prefix}_5to1"])
            for protocol in STYLE:
                style = STYLE[protocol]
                ax.plot([1, 3, 5], [values[(topology, 20, protocol)] for topology in topologies],
                        color=style["color"], marker=style["marker"],
                        linestyle=style["linestyle"], label=style["label"], linewidth=2.4)
            ax.set_xticks([1, 3, 5])
            relation = "One payer to payees" if row == 0 else "Payers to one payee"
            format_axes(ax, f"{label}: {relation}",
                        "Number of Payees" if row == 0 else "Number of Payers")
            if handles is None:
                handles, labels = ax.get_legend_handles_labels()
    fig.legend(handles, labels, loc="upper center", ncol=3, frameon=True,
               bbox_to_anchor=(0.5, 0.985))
    for suffix in ["pdf", "png"]:
        path = OUT / f"figure1_tor_measured_scalability.{suffix}"
        fig.savefig(path, dpi=240 if suffix == "png" else None, bbox_inches="tight")
        print(path)
    plt.close(fig)


def main():
    values = load_values()
    completion_figure(values)
    scalability_figure(values)


if __name__ == "__main__":
    main()
