#!/usr/bin/env python3
"""Coordinate real n-to-1 payments across independent EC2 payer nodes."""

import argparse
import concurrent.futures
import json
import os
from pathlib import Path
import shlex
import subprocess
import time


PROJECT = Path(os.environ.get("VOUCHERHUB_PROJECT", Path(__file__).resolve().parents[3]))
KEY_ROOT = Path(os.environ.get("VOUCHERHUB_KEY_ROOT", PROJECT / "cloud server_1"))
SERVER_CONFIG = Path(__file__).resolve().parents[1] / "config" / "servers_new.json"
NODES = {
    "tokyo1": ("18.183.246.100", "tokyo1.pem"),
    "tokyo4": ("13.231.139.77", "tokyo1.pem"),
    "tokyo5": ("18.182.46.50", "tokyo1.pem"),
    "tokyo6": ("18.183.117.178", "tokyo1.pem"),
    "tokyo7": ("52.199.246.164", "tokyo1.pem"),
    "seoul2": ("15.165.159.44", "seoul1.pem"),
    "seoul3": ("13.125.210.92", "seoul1.pem"),
    "singapore2": ("13.214.149.253", "singapore1.pem"),
    "frankfurt1": ("18.196.227.238", "frankfurt1.pem"),
    "frankfurt2": ("3.121.41.160", "frankfurt1.pem"),
    "virginia2": ("50.19.139.73", "virginia1.pem"),
}

JUMPS = {
    "tokyo1": ("18.182.46.50", "tokyo1.pem"),
    "tokyo4": ("18.182.46.50", "tokyo1.pem"),
    "tokyo6": ("18.182.46.50", "tokyo1.pem"),
    "tokyo7": ("18.182.46.50", "tokyo1.pem"),
    "seoul2": ("3.34.198.147", "seoul1.pem"),
    "singapore2": ("3.34.198.147", "seoul1.pem"),
}

TOPOLOGIES = {
    "same_city_3to1": {
        "payers": ["tokyo1", "tokyo6", "tokyo7"],
        "onion": "76hxjcm2qfso55njk3fxqulads5tuwyxzjatw5p6ezuym6bsinyeuzqd.onion",
        "cid": "tokyo3",
        "direct_port": 19201,
        "direct_cid": "tokyo3",
        "direct_host": "54.92.2.10",
    },
    "same_city_5to1": {
        "payers": ["tokyo1", "tokyo4", "tokyo5", "tokyo6", "tokyo7"],
        "onion": "76hxjcm2qfso55njk3fxqulads5tuwyxzjatw5p6ezuym6bsinyeuzqd.onion",
        "cid": "tokyo3",
        "direct_port": 19201,
        "direct_cid": "tokyo3",
        "direct_host": "54.92.2.10",
    },
    "regional_3to1": {
        "payers": ["tokyo1", "seoul3", "singapore2"],
        "onion": "7o5vk5ttisyzjtgzlpri5jnjjtezothnj4ms5l2lwtusxa5522tuviqd.onion",
        "cid": "seoul2",
        "direct_port": 19302,
        "direct_cid": "seoul2",
        "direct_host": "15.165.159.44",
    },
    "regional_5to1": {
        "payers": ["tokyo1", "tokyo4", "tokyo5", "seoul3", "singapore2"],
        "onion": "p6iqu3ncdtkakqkxlcaylasbpskrnfv7mvq6ektbbczwhsf765zptfyd.onion",
        "cid": "singapore1",
        "direct_port": 19302,
        "direct_cid": "singapore1",
        "direct_host": "18.143.153.164",
    },
    "global_3to1": {
        "payers": ["tokyo1", "frankfurt1", "virginia2"],
        "onion": "hboi5zdwozb2dz4iov2s53hhcvp34z57e6jzk6upzfceteqvv3cqg5ad.onion",
        "cid": "virginia1",
        "direct_port": 19403,
        "direct_cid": "virginia1",
        "direct_host": "98.84.188.150",
    },
    "global_5to1": {
        "payers": ["tokyo1", "tokyo4", "frankfurt1", "frankfurt2", "virginia2"],
        "onion": "hboi5zdwozb2dz4iov2s53hhcvp34z57e6jzk6upzfceteqvv3cqg5ad.onion",
        "cid": "virginia1",
        "direct_port": 19403,
        "direct_cid": "virginia1",
        "direct_host": "98.84.188.150",
    },
}


def configure_inventory(transport):
    """Load current IP, jump-host and onion data instead of stale literals."""
    payload = json.loads(SERVER_CONFIG.read_text(encoding="utf-8"))
    records = {payload["hub"]["name"]: payload["hub"]}
    records.update({item["name"]: item for item in payload["nodes"]})
    NODES.clear()
    JUMPS.clear()
    for name, item in records.items():
        NODES[name] = (item["public_ip"], item["key"])
        if item.get("jump"):
            jump = records[item["jump"]]
            JUMPS[name] = (jump["public_ip"], jump["key"])
    for spec in TOPOLOGIES.values():
        payee = records[spec["cid"]]
        spec["direct_host"] = payee["public_ip"]
        spec["direct_cid"] = payee["name"]
        if transport == "tor":
            onion = payee.get("onion", "")
            if not onion.endswith(".onion"):
                raise ValueError(
                    f"missing Tor onion for {payee['name']} in {SERVER_CONFIG}; "
                    "run deploy_new_environment.py --services-only first")
            spec["onion"] = onion


def ssh_argv(node, command):
    host, key = NODES[node]
    argv = [
        "ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=15",
        "-o", "ConnectionAttempts=1", "-o", "ServerAliveInterval=5",
        "-o", "ServerAliveCountMax=3",
        "-o", "ControlMaster=auto", "-o", "ControlPersist=600",
        "-o", "ControlPath=/tmp/vh-ssh-%C",
        "-o", "StrictHostKeyChecking=no", "-i", str(KEY_ROOT / key),
    ]
    if node in JUMPS:
        jump_host, jump_key = JUMPS[node]
        argv += ["-o", "ProxyCommand=ssh -o BatchMode=yes -o ConnectTimeout=15 "
                 "-o StrictHostKeyChecking=no -i " + shlex.quote(str(KEY_ROOT / jump_key))
                 + " -W %h:%p ubuntu@" + jump_host]
    return argv + [f"ubuntu@{host}", command]


def ssh(node, command, check=True):
    try:
        return subprocess.run(ssh_argv(node, command), check=check, text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
    except subprocess.TimeoutExpired as exc:
        return subprocess.CompletedProcess(exc.cmd, 124, exc.stdout or "", exc.stderr or "")


def wait_ready(nodes, ready_files, timeout=240, initial_wait=15, retry_wait=15):
    deadline = time.monotonic() + timeout
    pending = set(nodes)
    time.sleep(initial_wait)
    while pending:
        for node in list(pending):
            index = nodes.index(node)
            if ssh(node, f"test -f {shlex.quote(ready_files[index])}", check=False).returncode == 0:
                pending.remove(node)
        if pending and time.monotonic() >= deadline:
            raise TimeoutError(f"workers not ready: {sorted(pending)}")
        if pending:
            time.sleep(retry_wait)


def distribute(total, workers):
    base, extra = divmod(total, workers)
    return [base + (1 if index < extra else 0) for index in range(workers)]


def run_once(topology_name, protocol, payment_count, repetition, output_root, transport,
             classes, hub_port):
    spec = TOPOLOGIES[topology_name]
    nodes = spec["payers"]
    counts = distribute(payment_count, len(nodes))
    local_dir = output_root / topology_name / f"n{payment_count}" / protocol / f"rep{repetition}"
    aggregate_path = local_dir / "result.json"
    if aggregate_path.exists() and json.loads(aggregate_path.read_text()).get("success"):
        print(f"SKIP {topology_name} n={payment_count} {protocol} rep={repetition}", flush=True)
        return
    local_dir.mkdir(parents=True, exist_ok=True)

    run_id = f"{topology_name}-n{payment_count}-{protocol}-rep{repetition}-{time.time_ns()}"
    remote_dirs = [f"/home/ubuntu/voucherhub/results/evaluation/workers/{run_id}/{node}" for node in nodes]
    ready_files = [f"{directory}/ready" for directory in remote_dirs]
    gate_files = [f"{directory}/gate" for directory in remote_dirs]
    result_files = [f"{directory}/result.json" for directory in remote_dirs]
    launch_codes = []

    for index, node in enumerate(nodes):
        directory = remote_dirs[index]
        prepared = ssh(node, f"mkdir -p {shlex.quote(directory)}", check=False)
        if prepared.returncode != 0:
            raise RuntimeError(f"failed to prepare remote worker directory on {node}")
        endpoint_args = (
            f"--transport direct --payee-hosts {spec['direct_host']} "
            f"--payee-ports 9101 --payee-cids {shlex.quote(spec['direct_cid'])} "
            "--allow-direct-baseline true"
            if transport == "direct" else
            f"--payee-onions {shlex.quote(spec['onion'])} --payee-cids {shlex.quote(spec['cid'])} "
            "--socks-host 127.0.0.1 --socks-port 9050"
        )
        command = (
            "timeout 1800 java -Xms128m -Xmx512m "
            "-Djava.library.path=/home/ubuntu/voucherhub/native "
            "-Dvoucherhub.crypto.backend=bn128 "
            "-Dvoucherhub.rangeproof.backend=bulletproof "
            f"-cp {shlex.quote(classes)} "
            "org.voucherhub.common.EvaluationNetworkBenchmark --role payer "
            f"--protocol {shlex.quote(protocol)} --payments {counts[index]} "
            f"--hub-host 127.0.0.1 --hub-port {hub_port} "
            f"{endpoint_args} "
            f"--ready-file {shlex.quote(ready_files[index])} "
            f"--gate-file {shlex.quote(gate_files[index])} --gate-timeout-seconds 600 "
            f"--results {shlex.quote(result_files[index])}"
        )
        detached = f"nohup sh -c {shlex.quote(command)} > {shlex.quote(directory + '/worker.log')} 2>&1 < /dev/null &"
        launched = ssh(node, detached, check=False)
        launch_codes.append(launched.returncode)

    wait_ready(nodes, ready_files, initial_wait=(0.5 if transport == "direct" else 15),
               retry_wait=(1 if transport == "direct" else 15))
    start_ns = time.monotonic_ns()
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(nodes)) as pool:
        gates = [pool.submit(ssh, node, f"touch {shlex.quote(gate_files[index])}", False)
                 for index, node in enumerate(nodes)]
        for future in gates:
            future.result()
    gate_deadline = time.monotonic() + 120
    pending_gates = set(range(len(nodes)))
    while pending_gates:
        for index in list(pending_gates):
            checked = ssh(nodes[index], f"test -f {shlex.quote(gate_files[index])}", check=False)
            if checked.returncode == 0:
                pending_gates.remove(index)
            else:
                ssh(nodes[index], f"touch {shlex.quote(gate_files[index])}", check=False)
        if pending_gates:
            if time.monotonic() >= gate_deadline:
                raise TimeoutError("failed to release every distributed start gate")
            time.sleep(0.5 if transport == "direct" else 10)
    # SSH sessions can be reset after launching a valid remote worker. The
    # protocol completion condition is therefore the presence of every
    # verified remote result, not the transport process's exit status.
    deadline = time.monotonic() + 240
    worker_results = [None] * len(nodes)
    time.sleep(0.2 if transport == "direct" else 10)
    while any(item is None for item in worker_results):
        pending = [index for index, item in enumerate(worker_results) if item is None]
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(pending)) as pool:
            fetches = {
                index: pool.submit(ssh, nodes[index],
                                   f"cat {shlex.quote(result_files[index])}", False)
                for index in pending
            }
            for index, future in fetches.items():
                fetched = future.result()
                if fetched.returncode == 0:
                    try:
                        worker_results[index] = json.loads(fetched.stdout)
                    except json.JSONDecodeError:
                        pass
        if any(item is None for item in worker_results):
            if time.monotonic() >= deadline:
                raise TimeoutError("distributed results did not complete within 240 seconds")
            time.sleep(0.5 if transport == "direct" else 10)

    elapsed_ns = time.monotonic_ns() - start_ns
    for index, worker in enumerate(worker_results):
        (local_dir / f"{nodes[index]}.json").write_text(
            json.dumps(worker, indent=2, sort_keys=True) + "\n")
    return_codes = launch_codes
    for index, node in enumerate(nodes):
        remote_log = ssh(node, f"cat {shlex.quote(remote_dirs[index] + '/worker.log')}", check=False)
        (local_dir / f"{node}.log").write_text(remote_log.stdout or "")

    completed = sum(item.get("completed", 0) for item in worker_results)
    success = (len(worker_results) == len(nodes)
               and all(item.get("success") for item in worker_results)
               and completed == payment_count)
    # Every worker starts after the shared gate and measures its own complete
    # payer-to-payee exchange.  The distributed completion time is therefore
    # the slowest worker.  Coordinator polling is deliberately excluded.
    reported_ms = max(item["total_ms"] for item in worker_results)
    aggregate = {
        "topology": topology_name,
        "protocol": protocol,
        "payments": payment_count,
        "payers": len(nodes),
        "payer_nodes": nodes,
        "per_payer_payments": counts,
        "completed": completed,
        "success": success,
        "total_ms": reported_ms,
        "clock": "maximum worker monotonic duration after shared start gate",
        "transport": ("Direct TCP" if transport == "direct"
                      else "Tor v3 onion via SOCKS5"),
        "payer_payee_transport_verified": transport == "tor",
        "coordinator_elapsed_ms": elapsed_ns / 1_000_000.0,
        "worker_return_codes": return_codes,
    }
    aggregate_path.write_text(json.dumps(aggregate, indent=2, sort_keys=True) + "\n")
    print(("PASS" if success else "FAIL"), topology_name, f"n={payment_count}", protocol,
          f"rep={repetition}", f"total_ms={aggregate['total_ms']:.3f}", flush=True)
    if not success:
        raise RuntimeError(f"invalid distributed run: {aggregate}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--topologies", default=",".join(TOPOLOGIES))
    parser.add_argument("--repetitions", type=int, default=7)
    parser.add_argument("--payments", default="20",
                        help="comma-separated payment counts")
    parser.add_argument("--output-root", type=Path, required=True)
    parser.add_argument("--transport", choices=["tor", "direct"], default="tor")
    parser.add_argument("--classes", default="/home/ubuntu/voucherhub/classes-eval")
    parser.add_argument("--hub-port", type=int, default=19100)
    args = parser.parse_args()
    configure_inventory(args.transport)
    protocols = ["accio", "vh-cp", "vh-vp"]
    payment_counts = [int(value) for value in args.payments.split(",")]
    for topology_name in args.topologies.split(","):
        if topology_name not in TOPOLOGIES:
            raise ValueError(f"unknown topology: {topology_name}")
        for payment_count in payment_counts:
            for repetition in range(args.repetitions):
                offset = (repetition + payment_count + len(TOPOLOGIES[topology_name]["payers"])) % 3
                for index in range(3):
                    protocol = protocols[(index + offset) % 3]
                    for attempt in range(1, 6):
                        try:
                            run_once(topology_name, protocol, payment_count, repetition,
                                     args.output_root, args.transport, args.classes, args.hub_port)
                            break
                        except (RuntimeError, TimeoutError) as exc:
                            print(f"RETRY {topology_name} n={payment_count} {protocol} "
                                  f"rep={repetition} attempt={attempt}: {exc}", flush=True)
                            if attempt == 5:
                                raise
                            time.sleep(20)


if __name__ == "__main__":
    main()
