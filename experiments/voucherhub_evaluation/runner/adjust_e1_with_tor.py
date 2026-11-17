#!/usr/bin/env python3
"""Add the independently measured single-exchange Tor-minus-TCP delta."""

import csv
import json
import os
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DIRECT = Path(os.environ.get("E1_DIRECT_SUMMARY", ROOT / "summary" / "e1_direct_public_summary.csv"))
CALIBRATION_RAW = ROOT / "raw" / "transport_calibration"
ADJUSTED = Path(os.environ.get("E1_TOR_ADJUSTED", ROOT / "summary" / "e1_tor_adjusted_summary.csv"))
CALIBRATION = Path(os.environ.get("E1_TOR_CALIBRATION", ROOT / "summary" / "e1_tor_latency_calibration.csv"))

PAYMENTS = [10, 20, 40, 60, 80, 100]
PROTOCOLS = ["accio", "vh-cp", "vh-vp"]
PANELS = [
    "same_city_1to1", "same_city_1to3", "same_city_3to1",
    "regional_1to1", "regional_1to3", "regional_3to1",
    "global_1to1", "global_1to3", "global_3to1",
]


def region(topology):
    if topology.startswith("same_city_"):
        return "same_city"
    if topology.startswith("regional_"):
        return "regional"
    if topology.startswith("global_"):
        return "global"
    raise ValueError(f"unknown topology: {topology}")


def anonymous_exchanges(topology, protocol, payments):
    if protocol == "accio":
        return payments
    relation = topology.rsplit("_", 1)[1]
    payer_count, payee_count = (int(value) for value in relation.split("to", 1))
    # Each VoucherHub payer sends one batched anonymous delivery to each
    # destination payee.  Thus both 1-to-3 and 3-to-1 require three Tor
    # exchanges, even though all payments inside one payer/payee pair are
    # delivered in a single batch.
    return payer_count * payee_count


def main():
    direct = {}
    with DIRECT.open(newline="", encoding="utf-8") as stream:
        for row in csv.DictReader(stream):
            if row["status"] == "complete":
                direct[(row["topology"], int(row["payments"]), row["protocol"])] = float(row["mean_ms"])

    calibration = {}
    calibration_rows = []
    for region_name in ["same_city", "regional", "global"]:
        transport_values = {}
        transport_service = {}
        for transport in ["direct", "tor"]:
            totals = []
            services = []
            for repetition in range(10):
                result = CALIBRATION_RAW / region_name / transport / f"rep{repetition}" / "result.json"
                payload = json.loads(result.read_text(encoding="utf-8"))
                if payload.get("success") is not True or payload.get("completed") != 1:
                    raise ValueError(f"invalid standard transport sample: {result}")
                totals.append(float(payload["total_ms"]))
                services.append(int(payload["detail"].split("=", 1)[1]) / 1_000_000.0)
            transport_values[transport] = sum(totals) / len(totals)
            transport_service[transport] = sum(services) / len(services)
        delta = transport_values["tor"] - transport_values["direct"]
        if delta <= 0:
            raise ValueError(f"non-positive standard Tor increment for {region_name}: {delta}")
        calibration[region_name] = delta
        calibration_rows.append({
            "region": region_name,
            "standard_message": "one_valid_vh_cp_delivery",
            "direct_valid_samples": 10,
            "tor_valid_samples": 10,
            "direct_mean_ms": f"{transport_values['direct']:.6f}",
            "tor_mean_ms": f"{transport_values['tor']:.6f}",
            "direct_mean_service_ms": f"{transport_service['direct']:.6f}",
            "tor_mean_service_ms": f"{transport_service['tor']:.6f}",
            "tor_minus_tcp_per_exchange_ms": f"{delta:.6f}",
        })

    CALIBRATION.parent.mkdir(parents=True, exist_ok=True)
    with CALIBRATION.open("w", newline="", encoding="utf-8") as stream:
        fields = list(calibration_rows[0])
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(calibration_rows)

    adjusted_rows = []
    for topology in PANELS:
        region_name = region(topology)
        for payments in PAYMENTS:
            for protocol in PROTOCOLS:
                direct_mean = direct[(topology, payments, protocol)]
                exchanges = anonymous_exchanges(topology, protocol, payments)
                increment = calibration[region_name] * exchanges
                adjusted_rows.append({
                    "topology": topology,
                    "payments": payments,
                    "protocol": protocol,
                    "direct_mean_ms": f"{direct_mean:.6f}",
                    "tor_calibration_region": region_name,
                    "anonymous_exchanges": exchanges,
                    "net_tor_increment_ms": f"{increment:.6f}",
                    "tor_adjusted_mean_ms": f"{direct_mean + increment:.6f}",
                    "method": "direct_total_plus_standard_tor_minus_tcp_delta_times_anonymous_exchanges",
                })

    with ADJUSTED.open("w", newline="", encoding="utf-8") as stream:
        fields = list(adjusted_rows[0])
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(adjusted_rows)

    print(CALIBRATION)
    print(ADJUSTED)


if __name__ == "__main__":
    main()
