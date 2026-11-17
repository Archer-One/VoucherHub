#!/usr/bin/env python3
import csv
import json
from collections import defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "raw" / "e1"
SUMMARY = ROOT / "summary" / "e1_summary.csv"
MARKED = ROOT / "summary" / "e1_trimmed_samples.csv"


def main():
    groups = defaultdict(list)
    for result in RAW.glob("*/n*/*/rep*/result.json"):
        relative = result.relative_to(RAW)
        topology = relative.parts[0]
        payments = int(relative.parts[1][1:])
        protocol = relative.parts[2]
        repetition = int(relative.parts[3][3:])
        if repetition >= 7:
            continue
        payload = json.loads(result.read_text(encoding="utf-8"))
        if payload.get("success") is not True or payload.get("completed") != payments:
            continue
        groups[(topology, payments, protocol)].append((repetition, float(payload["total_ms"])))

    SUMMARY.parent.mkdir(parents=True, exist_ok=True)
    marked_rows = []
    with SUMMARY.open("w", newline="", encoding="utf-8") as stream:
        fields = ["topology", "payments", "protocol", "valid_samples", "trimmed_mean_ms", "status"]
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        for key in sorted(groups):
            values = sorted(groups[key], key=lambda item: (item[1], item[0]))
            status = "complete" if len(values) == 7 else "incomplete"
            mean = ""
            if status == "complete":
                mean = f"{sum(value for _, value in values[1:-1]) / 5:.6f}"
            writer.writerow({
                "topology": key[0],
                "payments": key[1],
                "protocol": key[2],
                "valid_samples": len(values),
                "trimmed_mean_ms": mean,
                "status": status,
            })
            for index, (repetition, value) in enumerate(values):
                sample_status = "kept"
                if status == "complete" and index == 0:
                    sample_status = "trimmed_min"
                elif status == "complete" and index == len(values) - 1:
                    sample_status = "trimmed_max"
                marked_rows.append({
                    "topology": key[0],
                    "payments": key[1],
                    "protocol": key[2],
                    "repetition": repetition,
                    "total_ms": f"{value:.6f}",
                    "status": sample_status,
                })

    with MARKED.open("w", newline="", encoding="utf-8") as stream:
        fields = ["topology", "payments", "protocol", "repetition", "total_ms", "status"]
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(marked_rows)

    print(SUMMARY)
    print(MARKED)


if __name__ == "__main__":
    main()
