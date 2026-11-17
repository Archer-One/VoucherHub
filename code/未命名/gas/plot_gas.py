#!/usr/bin/env python3
import argparse
import csv
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--summary", type=Path, default=Path(__file__).parent / "results/gas_summary.csv")
    parser.add_argument("--output", type=Path, default=Path(__file__).parent / "results")
    args = parser.parse_args()
    with args.summary.open(newline="", encoding="utf-8") as stream:
        rows = list(csv.DictReader(stream))
    args.output.mkdir(parents=True, exist_ok=True)

    lifecycle = [row for row in rows if row["category"] == "lifecycle"]
    labels = [f"{row['protocol']}\n{row['operation']}" for row in lifecycle]
    values = [float(row["median"]) for row in lifecycle]
    fig, ax = plt.subplots(figsize=(9.2, 4.8))
    ax.bar(labels, values, color=["#0072B2", "#56B4E9", "#009E73", "#66C2A5", "#D55E00", "#E69F00"])
    ax.set_ylabel("Median gas used")
    ax.grid(axis="y", color="#DDDDDD")
    ax.set_axisbelow(True)
    fig.tight_layout()
    for suffix in ["pdf", "png"]:
        fig.savefig(args.output / f"gas_lifecycle.{suffix}", dpi=220 if suffix == "png" else None)
    plt.close(fig)

    batch = sorted((row for row in rows if row["category"] == "batch_redeem"),
                   key=lambda row: int(row["batch_size"]))
    fig, ax = plt.subplots(figsize=(7.4, 4.8))
    ax.plot([int(row["batch_size"]) for row in batch],
            [float(row["median"]) for row in batch], marker="o", color="#0072B2", linewidth=2.4)
    ax.set_xlabel("Vouchers in one redeem batch")
    ax.set_ylabel("Median close gas used")
    ax.grid(color="#DDDDDD")
    ax.set_axisbelow(True)
    fig.tight_layout()
    for suffix in ["pdf", "png"]:
        fig.savefig(args.output / f"gas_batch_redeem.{suffix}", dpi=220 if suffix == "png" else None)
    plt.close(fig)


if __name__ == "__main__":
    main()
