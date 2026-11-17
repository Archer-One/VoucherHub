#!/usr/bin/env python3
"""Run the 1-to-N Figure 1 workloads with real Tor v3 payee links."""

import argparse
import json
import os
from pathlib import Path
import shlex
import subprocess


ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "config" / "servers_new.json"
PROJECT = Path(os.environ.get("VOUCHERHUB_PROJECT", Path(__file__).resolve().parents[3]))
KEY_ROOT = Path(os.environ.get("VOUCHERHUB_KEY_ROOT", PROJECT / "cloud server_1"))
TOPOLOGIES = {
    "same_city_1to1": ["tokyo3"],
    "same_city_1to3": ["tokyo3", "tokyo6", "tokyo7"],
    "same_city_1to5": ["tokyo1", "tokyo3", "tokyo5", "tokyo6", "tokyo7"],
    "regional_1to1": ["singapore1"],
    "regional_1to3": ["seoul1", "seoul2", "singapore1"],
    "regional_1to5": ["seoul1", "seoul2", "seoul3", "singapore1", "singapore2"],
    "global_1to1": ["virginia1"],
    "global_1to3": ["frankfurt1", "frankfurt2", "virginia1"],
    "global_1to5": ["tokyo3", "frankfurt1", "frankfurt2", "virginia1", "virginia2"],
}


def inventory():
    payload = json.loads(CONFIG.read_text(encoding="utf-8"))
    records = {payload["hub"]["name"]: payload["hub"]}
    records.update({item["name"]: item for item in payload["nodes"]})
    return records


def ssh_argv(records, node_name, command):
    node = records[node_name]
    argv = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=20",
            "-o", "ServerAliveInterval=10", "-o", "ServerAliveCountMax=6",
            "-o", "StrictHostKeyChecking=no", "-i", str(KEY_ROOT / node["key"])]
    if node.get("jump"):
        jump = records[node["jump"]]
        proxy = ("ssh -o BatchMode=yes -o ConnectTimeout=20 -o StrictHostKeyChecking=no "
                 f"-i {shlex.quote(str(KEY_ROOT / jump['key']))} -W %h:%p "
                 f"ubuntu@{jump['public_ip']}")
        argv += ["-o", "ProxyCommand=" + proxy]
    return argv + [f"ubuntu@{node['public_ip']}", command]


def remote(records, node, command, timeout=1900):
    return subprocess.run(ssh_argv(records, node, command), text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)


def run_one(records, payer, topology, protocol, payments, repetition, output_root, classes, hub_port):
    local_dir = output_root / topology / f"n{payments}" / protocol / f"rep{repetition}"
    result_path = local_dir / "result.json"
    if result_path.exists():
        old = json.loads(result_path.read_text(encoding="utf-8"))
        if old.get("success") and old.get("transport") == "Tor v3 onion via SOCKS5":
            print("SKIP", topology, payments, protocol, repetition, flush=True)
            return
    local_dir.mkdir(parents=True, exist_ok=True)
    payees = TOPOLOGIES[topology]
    onions = []
    for name in payees:
        onion = records[name].get("onion", "")
        if not onion.endswith(".onion"):
            raise RuntimeError(f"missing onion for {name}; deploy Tor services first")
        onions.append(onion)
    remote_result = (f"/home/ubuntu/voucherhub/results/evaluation/e1_tor/{topology}/"
                     f"n{payments}/{protocol}/rep{repetition}/result.json")
    command = (
        f"mkdir -p {shlex.quote(str(Path(remote_result).parent))} && "
        "(systemctl is-active --quiet tor@default || systemctl is-active --quiet tor) && "
        "timeout 1800 java -Xms128m -Xmx512m "
        "-Djava.library.path=/home/ubuntu/voucherhub/native "
        "-Dvoucherhub.crypto.backend=bn128 -Dvoucherhub.rangeproof.backend=bulletproof "
        f"-cp {shlex.quote(classes)} org.voucherhub.common.EvaluationNetworkBenchmark "
        f"--role payer --protocol {shlex.quote(protocol)} --payments {payments} "
        f"--hub-host 127.0.0.1 --hub-port {hub_port} --transport tor "
        f"--payee-onions {shlex.quote(','.join(onions))} "
        f"--payee-cids {shlex.quote(','.join(payees))} "
        "--socks-host 127.0.0.1 --socks-port 9050 "
        f"--results {shlex.quote(remote_result)}"
    )
    completed = remote(records, payer, command)
    (local_dir / "runner.log").write_text(
        completed.stdout + "\nSTDERR\n" + completed.stderr, encoding="utf-8")
    fetched = remote(records, payer, f"cat {shlex.quote(remote_result)}", timeout=60)
    if fetched.returncode != 0:
        raise RuntimeError(f"missing result for {topology}: {fetched.stderr.strip()}")
    payload = json.loads(fetched.stdout)
    payload.update({"topology": topology, "repetition": repetition,
                    "payer_payee_transport_verified": True})
    result_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    if not payload.get("success") or payload.get("completed") != payments:
        raise RuntimeError(f"invalid result: {result_path}")
    print("PASS", topology, payments, protocol, repetition,
          f"{payload['total_ms']:.3f} ms", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--topologies", default=",".join(TOPOLOGIES))
    parser.add_argument("--repetitions", type=int, default=7)
    parser.add_argument("--payments", default="10,20,40,60,80,100")
    parser.add_argument("--output-root", type=Path, required=True)
    parser.add_argument("--payer", default="tokyo4")
    parser.add_argument("--classes", default="/home/ubuntu/voucherhub/classes-eval-vp-batch")
    parser.add_argument("--hub-port", type=int, default=19100)
    args = parser.parse_args()
    records = inventory()
    requested_payments = [int(value) for value in args.payments.split(",")]
    for topology in args.topologies.split(","):
        if topology not in TOPOLOGIES:
            raise ValueError(f"unknown topology {topology}")
        counts = [20] if topology.endswith("1to5") else requested_payments
        for payments in counts:
            for repetition in range(args.repetitions):
                protocols = ["accio", "vh-cp", "vh-vp"]
                offset = (payments + repetition + len(TOPOLOGIES[topology])) % len(protocols)
                for index in range(len(protocols)):
                    protocol = protocols[(index + offset) % len(protocols)]
                    for attempt in range(1, 6):
                        try:
                            run_one(records, args.payer, topology, protocol, payments,
                                    repetition, args.output_root, args.classes, args.hub_port)
                            break
                        except (RuntimeError, subprocess.TimeoutExpired) as exc:
                            print("RETRY", topology, payments, protocol, repetition,
                                  attempt, exc, flush=True)
                            if attempt == 5:
                                raise


if __name__ == "__main__":
    main()
