import json
from pathlib import Path

import matplotlib

matplotlib.use("Agg")

import matplotlib.pyplot as plt
from matplotlib.ticker import FuncFormatter, MaxNLocator


ROOT = Path(__file__).resolve().parents[1]
RESULTS = ROOT / "code" / "未命名" / "work" / "channel_gas" / "channel_open_close_gas.json"
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


def millions(value, _):
    return f"{value / 1_000_000:.1f}M"


def main():
    with RESULTS.open(encoding="utf-8") as f:
        results = json.load(f)

    measured = results["voucherhub_batch_redeem_close"]["measured"]
    xs = measured["batch_sizes"]
    ys = [
        measured["close_with_batch_redeem"][str(count)]["gas_used"]["median"]
        for count in xs
    ]

    fig, ax = plt.subplots(figsize=(7.2, 4.8), constrained_layout=True)

    ax.plot(
        xs,
        ys,
        label="VoucherHub close with batch redeem",
        color="#456990",
        marker="o",
        linestyle="-",
        markeredgecolor="white",
        markeredgewidth=0.9,
    )

    ax.set_xlabel("Outstanding Vouchers", labelpad=8)
    ax.set_ylabel("Close Gas", labelpad=8)
    ax.set_xlim(min(xs) - 3, max(xs) + 4)
    ax.set_ylim(bottom=0)
    ax.set_xticks(xs)
    ax.yaxis.set_major_locator(MaxNLocator(nbins=5))
    ax.yaxis.set_major_formatter(FuncFormatter(millions))
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

    pdf_out = OUT_DIR / "voucherhub_batch_redeem_close_gas.pdf"
    png_out = OUT_DIR / "voucherhub_batch_redeem_close_gas.png"
    fig.savefig(pdf_out)
    fig.savefig(png_out, dpi=240)
    plt.close(fig)
    print(pdf_out)
    print(png_out)


if __name__ == "__main__":
    main()
