#!/usr/bin/env python3
import csv
from collections import defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "raw" / "batching_raw.csv"
SUMMARY = ROOT / "summary" / "batching_summary.csv"
MARKED = ROOT / "summary" / "batching_trimmed_samples.csv"
METRICS = [
    "total_ms",
    "proof_generation_ms",
    "proof_verification_ms",
    "hub_signature_ms",
    "communication_bytes",
    "communication_rounds",
]
SAMPLE_COUNT = 7


def trimmed(values):
    ordered = sorted(values, key=lambda item: (item[1], item[0]))
    if len(ordered) != SAMPLE_COUNT:
        raise ValueError(f"expected {SAMPLE_COUNT} valid samples, got {len(ordered)}")
    kept = ordered[1:-1]
    return sum(value for _, value in kept) / len(kept), ordered[0][0], ordered[-1][0]


def main():
    groups = defaultdict(list)
    with RAW.open(newline="", encoding="utf-8") as stream:
        for row in csv.DictReader(stream):
            if row["success"].lower() != "true":
                raise ValueError(f"failed sample cannot be aggregated: {row}")
            if int(row["sample"]) < SAMPLE_COUNT:
                groups[(row["experiment"], row["mode"], int(row["k"]))].append(row)

    SUMMARY.parent.mkdir(parents=True, exist_ok=True)
    summary_fields = ["experiment", "mode", "k", "valid_samples"] + [f"{m}_trimmed_mean" for m in METRICS]
    marked_fields = ["experiment", "mode", "k", "metric", "sample", "value", "status"]
    marked_rows = []
    with SUMMARY.open("w", newline="", encoding="utf-8") as summary_stream:
        writer = csv.DictWriter(summary_stream, fieldnames=summary_fields)
        writer.writeheader()
        for key in sorted(groups):
            experiment, mode, k = key
            rows = groups[key]
            output = {"experiment": experiment, "mode": mode, "k": k, "valid_samples": len(rows)}
            for metric in METRICS:
                values = [(int(row["sample"]), float(row[metric])) for row in rows]
                mean, minimum_sample, maximum_sample = trimmed(values)
                output[f"{metric}_trimmed_mean"] = f"{mean:.6f}"
                for sample, value in values:
                    status = "kept"
                    if sample == minimum_sample:
                        status = "trimmed_min"
                    elif sample == maximum_sample:
                        status = "trimmed_max"
                    marked_rows.append({
                        "experiment": experiment,
                        "mode": mode,
                        "k": k,
                        "metric": metric,
                        "sample": sample,
                        "value": f"{value:.6f}",
                        "status": status,
                    })
            writer.writerow(output)

    with MARKED.open("w", newline="", encoding="utf-8") as marked_stream:
        writer = csv.DictWriter(marked_stream, fieldnames=marked_fields)
        writer.writeheader()
        writer.writerows(marked_rows)

    print(SUMMARY)
    print(MARKED)


if __name__ == "__main__":
    main()
