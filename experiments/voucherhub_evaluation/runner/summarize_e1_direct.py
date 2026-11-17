#!/usr/bin/env python3
import csv
import json
import os
from collections import defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RAW = Path(os.environ.get("E1_RAW", ROOT / "raw" / "e1_direct_public"))
SUMMARY = Path(os.environ.get("E1_SUMMARY", ROOT / "summary" / "e1_direct_public_summary.csv"))
MARKED = Path(os.environ.get("E1_MARKED", ROOT / "summary" / "e1_direct_public_trimmed_samples.csv"))


def main():
    groups = defaultdict(list)
    for result in RAW.glob("*/n*/*/rep*/result.json"):
        relative = result.relative_to(RAW)
        topology = relative.parts[0]
        payments = int(relative.parts[1][1:])
        protocol = relative.parts[2]
        repetition = int(relative.parts[3][3:])
        if repetition >= 3:
            continue
        payload = json.loads(result.read_text(encoding="utf-8"))
        if payload.get("success") is True and payload.get("completed") == payments:
            groups[(topology, payments, protocol)].append((repetition, float(payload["total_ms"])))

    SUMMARY.parent.mkdir(parents=True, exist_ok=True)
    marked_rows = []
    with SUMMARY.open("w", newline="", encoding="utf-8") as stream:
        fields = ["topology", "payments", "protocol", "valid_samples", "mean_ms", "status"]
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        for key in sorted(groups):
            values = sorted(groups[key], key=lambda item: (item[1], item[0]))
            status = "complete" if len(values) == 3 else "incomplete"
            mean = f"{sum(value for _, value in values) / 3:.6f}" if status == "complete" else ""
            writer.writerow({"topology": key[0], "payments": key[1], "protocol": key[2],
                             "valid_samples": len(values), "mean_ms": mean, "status": status})
            for repetition, value in values:
                sample_status = "kept"
                marked_rows.append({"topology": key[0], "payments": key[1], "protocol": key[2],
                                    "repetition": repetition, "total_ms": f"{value:.6f}",
                                    "status": sample_status})

    with MARKED.open("w", newline="", encoding="utf-8") as stream:
        fields = ["topology", "payments", "protocol", "repetition", "total_ms", "status"]
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(marked_rows)
    print(SUMMARY)
    print(MARKED)


if __name__ == "__main__":
    main()
