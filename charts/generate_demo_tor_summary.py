#!/usr/bin/env python3
"""Generate clearly synthetic input for checking Figure 1 plotting code."""

import argparse
import csv
from pathlib import Path


REGIONS = {"same_city": 1.0, "regional": 1.35, "global": 1.8}
RELATIONS = {"1to1": 1.0, "1to3": 1.18, "3to1": 1.24, "1to5": 1.34, "5to1": 1.42}
PROTOCOLS = {"accio": 0.15, "vh-cp": 0.055, "vh-vp": 0.064}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    fields = ["topology", "payments", "protocol", "valid_samples", "mean_ms",
              "aggregation", "transport", "status", "data_source"]
    with args.output.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        for region, region_factor in REGIONS.items():
            for relation, relation_factor in RELATIONS.items():
                counts = [20] if "5" in relation else [10, 20, 40, 60, 80, 100]
                for payments in counts:
                    for protocol, slope in PROTOCOLS.items():
                        mean = 650 + 1000 * slope * payments * region_factor * relation_factor
                        writer.writerow({
                            "topology": f"{region}_{relation}", "payments": payments,
                            "protocol": protocol, "valid_samples": 7,
                            "mean_ms": f"{mean:.6f}",
                            "aggregation": "demo_only", "transport": "Tor v3 onion via SOCKS5",
                            "status": "complete", "data_source": "SYNTHETIC_DEMO_NOT_EXPERIMENT",
                        })
    print(args.output)


if __name__ == "__main__":
    main()
