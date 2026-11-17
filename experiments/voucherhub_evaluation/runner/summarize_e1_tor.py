#!/usr/bin/env python3
"""Summarize only measurements whose payer-payee path was real Tor."""

import argparse
import csv
import json
from collections import defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--raw", type=Path, default=ROOT / "raw" / "e1_tor_measured")
    parser.add_argument("--summary", type=Path,
                        default=ROOT / "summary" / "e1_tor_measured_summary.csv")
    parser.add_argument("--samples", type=Path,
                        default=ROOT / "summary" / "e1_tor_measured_samples.csv")
    parser.add_argument("--repetitions", type=int, default=7)
    args = parser.parse_args()

    groups = defaultdict(list)
    rejected = []
    for result in args.raw.glob("*/n*/*/rep*/result.json"):
        relative = result.relative_to(args.raw)
        topology, payment_dir, protocol, repetition_dir = relative.parts[:4]
        payments = int(payment_dir[1:])
        repetition = int(repetition_dir[3:])
        if repetition >= args.repetitions:
            continue
        payload = json.loads(result.read_text(encoding="utf-8"))
        tor_label = payload.get("transport") == "Tor v3 onion via SOCKS5"
        verified = payload.get("payer_payee_transport_verified", tor_label)
        if not tor_label or not verified:
            rejected.append(str(result))
            continue
        if payload.get("success") is True and payload.get("completed") == payments:
            groups[(topology, payments, protocol)].append(
                (repetition, float(payload["total_ms"])))

    if rejected:
        raise SystemExit("refusing non-Tor input:\n" + "\n".join(rejected))

    args.summary.parent.mkdir(parents=True, exist_ok=True)
    sample_rows = []
    with args.summary.open("w", newline="", encoding="utf-8") as stream:
        fields = ["topology", "payments", "protocol", "valid_samples", "mean_ms",
                  "aggregation", "transport", "status"]
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        for key in sorted(groups):
            values = sorted(groups[key], key=lambda item: (item[1], item[0]))
            complete = len(values) == args.repetitions
            kept = values[1:-1] if complete and args.repetitions >= 5 else values
            mean = sum(value for _, value in kept) / len(kept) if complete else None
            writer.writerow({
                "topology": key[0], "payments": key[1], "protocol": key[2],
                "valid_samples": len(values),
                "mean_ms": f"{mean:.6f}" if mean is not None else "",
                "aggregation": "drop_one_min_and_max_then_mean" if args.repetitions >= 5 else "mean",
                "transport": "Tor v3 onion via SOCKS5",
                "status": "complete" if complete else "incomplete",
            })
            for index, (repetition, value) in enumerate(values):
                status = "kept"
                if complete and args.repetitions >= 5 and index == 0:
                    status = "trimmed_min"
                elif complete and args.repetitions >= 5 and index == len(values) - 1:
                    status = "trimmed_max"
                sample_rows.append({
                    "topology": key[0], "payments": key[1], "protocol": key[2],
                    "repetition": repetition, "total_ms": f"{value:.6f}",
                    "transport": "Tor v3 onion via SOCKS5", "status": status,
                })

    with args.samples.open("w", newline="", encoding="utf-8") as stream:
        fields = ["topology", "payments", "protocol", "repetition", "total_ms",
                  "transport", "status"]
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(sample_rows)
    print(args.summary)
    print(args.samples)


if __name__ == "__main__":
    main()
