#!/usr/bin/env python3
"""Install and launch the corrected VoucherHub evaluation environment."""

import concurrent.futures
import argparse
import json
import os
from pathlib import Path
import shlex
import subprocess


PROJECT = Path(os.environ.get("VOUCHERHUB_PROJECT", Path(__file__).resolve().parents[3]))
CONFIG = PROJECT / "experiments/voucherhub_evaluation/config/servers_new.json"
KEY_ROOT = Path(os.environ.get("VOUCHERHUB_KEY_ROOT", PROJECT / "cloud server_1"))
CODE = PROJECT / "code/未命名"
TAR = CODE / "voucherhub-classes.tar.gz"
NATIVE = Path(os.environ.get("VOUCHERHUB_NATIVE_LIB",
                             PROJECT / "native/target/release/libbn_jni.so"))
RUNNER_ROOT = PROJECT / "experiments/voucherhub_evaluation/runner"


config = json.loads(CONFIG.read_text(encoding="utf-8"))
nodes = {config["hub"]["name"]: config["hub"]}
nodes.update({node["name"]: node for node in config["nodes"]})


def common_options(node):
    options = [
        "-o", "BatchMode=yes", "-o", "ConnectTimeout=20",
        "-o", "ServerAliveInterval=10", "-o", "ServerAliveCountMax=6",
        "-o", "StrictHostKeyChecking=no", "-i", str(KEY_ROOT / node["key"]),
    ]
    jump_name = node.get("jump")
    if jump_name:
        jump = nodes[jump_name]
        options += [
            "-o", "ProxyCommand=ssh -o BatchMode=yes -o ConnectTimeout=20 "
            "-o StrictHostKeyChecking=no -i " + shlex.quote(str(KEY_ROOT / jump["key"]))
            + " -W %h:%p ubuntu@" + jump["public_ip"],
        ]
    return options


def ssh(name, command, timeout=1200):
    node = nodes[name]
    argv = ["ssh"] + common_options(node) + [f"ubuntu@{node['public_ip']}", command]
    return subprocess.run(argv, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=timeout)


def scp(name, source, destination, timeout=300):
    node = nodes[name]
    argv = ["scp"] + common_options(node) + [str(source), f"ubuntu@{node['public_ip']}:{destination}"]
    return subprocess.run(argv, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=timeout)


def require(result, action):
    if result.returncode != 0:
        raise RuntimeError(f"{action} failed: {result.stderr.strip()}")


def install(name):
    result = ssh(name,
        "sudo DEBIAN_FRONTEND=noninteractive apt-get update -qq && "
        "sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "
        "openjdk-17-jre-headless tor openssh-client && "
        "mkdir -p /home/ubuntu/voucherhub/{native,logs,results/evaluation} /home/ubuntu/.ssh")
    require(result, f"install on {name}")
    return name


def upload(name):
    require(scp(name, TAR, "/home/ubuntu/voucherhub/voucherhub-classes.tar.gz"),
            f"class upload to {name}")
    require(scp(name, NATIVE, "/home/ubuntu/voucherhub/native/libbn_jni.so"),
            f"native upload to {name}")
    require(scp(name, KEY_ROOT / "tokyo1.pem", "/home/ubuntu/.ssh/voucherhub_hub"),
            f"hub tunnel key upload to {name}")
    command = (
        "rm -rf /home/ubuntu/voucherhub/classes-eval-vp-batch.new && "
        "mkdir -p /home/ubuntu/voucherhub/classes-eval-vp-batch.new && "
        "tar -xzf /home/ubuntu/voucherhub/voucherhub-classes.tar.gz "
        "-C /home/ubuntu/voucherhub/classes-eval-vp-batch.new && "
        "rm -rf /home/ubuntu/voucherhub/classes-eval-vp-batch && "
        "mv /home/ubuntu/voucherhub/classes-eval-vp-batch.new "
        "/home/ubuntu/voucherhub/classes-eval-vp-batch && "
        "chmod 600 /home/ubuntu/.ssh/voucherhub_hub && "
        "chmod 755 /home/ubuntu/voucherhub/native/libbn_jni.so"
    )
    require(ssh(name, command), f"class extraction on {name}")
    return name


def start_payee(name):
    command = (
        "pkill -f '^java .*EvaluationNetworkBenchmark --role payee' 2>/dev/null || true; sleep 1; "
        "nohup java -Xms128m -Xmx512m "
        "-Djava.library.path=/home/ubuntu/voucherhub/native "
        "-Dvoucherhub.crypto.backend=bn128 "
        "-cp /home/ubuntu/voucherhub/classes-eval-vp-batch "
        "org.voucherhub.common.EvaluationNetworkBenchmark --role payee "
        f"--port 9101 --cid {shlex.quote(name)} "
        "> /home/ubuntu/voucherhub/logs/payee.log 2>&1 < /dev/null &"
    )
    require(ssh(name, command), f"payee start on {name}")
    return name


def configure_tor(name):
    """Create a Tor v3 hidden service for the payee and return its hostname."""
    command = (
        "sudo install -d -m 755 /etc/tor/torrc.d && "
        "printf '%s\\n' "
        "'HiddenServiceDir /var/lib/tor/voucherhub-payee/' "
        "'HiddenServiceVersion 3' "
        "'HiddenServicePort 9101 127.0.0.1:9101' "
        "| sudo tee /etc/tor/torrc.d/voucherhub-payee.conf >/dev/null && "
        "if ! sudo grep -q '^%include /etc/tor/torrc.d/\\*\\.conf$' /etc/tor/torrc; then "
        "echo '%include /etc/tor/torrc.d/*.conf' | sudo tee -a /etc/tor/torrc >/dev/null; fi && "
        "sudo systemctl restart tor@default 2>/dev/null || sudo systemctl restart tor; "
        "for i in $(seq 1 30); do "
        "sudo test -s /var/lib/tor/voucherhub-payee/hostname && "
        "sudo cat /var/lib/tor/voucherhub-payee/hostname && exit 0; sleep 2; done; exit 1"
    )
    result = ssh(name, command, timeout=180)
    require(result, f"Tor hidden service setup on {name}")
    onion = result.stdout.strip().splitlines()[-1]
    if not onion.endswith(".onion") or len(onion) != 62:
        raise RuntimeError(f"invalid onion hostname returned by {name}: {onion!r}")
    return name, onion


def start_hub():
    command = (
        "pkill -f '^java .*EvaluationNetworkBenchmark --role hub' 2>/dev/null || true; sleep 1; "
        "nohup java -Xms128m -Xmx512m "
        "-Djava.library.path=/home/ubuntu/voucherhub/native "
        "-Dvoucherhub.crypto.backend=bn128 "
        "-Dvoucherhub.rangeproof.backend=bulletproof "
        "-cp /home/ubuntu/voucherhub/classes-eval-vp-batch "
        "org.voucherhub.common.EvaluationNetworkBenchmark --role hub --port 9100 "
        "> /home/ubuntu/voucherhub/logs/hub.log 2>&1 < /dev/null &"
    )
    require(ssh("tokyo2", command), "hub start")


def start_tunnel(name):
    command = (
        "pkill -f '^ssh .*127.0.0.1:19100:127.0.0.1:9100' 2>/dev/null || true; "
        "nohup ssh -N -o BatchMode=yes -o ExitOnForwardFailure=yes "
        "-o ConnectTimeout=20 -o ServerAliveInterval=10 -o ServerAliveCountMax=6 "
        "-o StrictHostKeyChecking=no -i /home/ubuntu/.ssh/voucherhub_hub "
        "-o 'ProxyCommand=ssh -o BatchMode=yes -o ConnectTimeout=20 "
        "-o StrictHostKeyChecking=no -i /home/ubuntu/.ssh/voucherhub_hub "
        "-W %h:%p ubuntu@18.182.46.50' "
        "-L 127.0.0.1:19100:127.0.0.1:9100 "
        "ubuntu@35.79.227.192 "
        "> /home/ubuntu/voucherhub/logs/hub_tunnel.log 2>&1 < /dev/null &"
    )
    require(ssh(name, command), f"hub tunnel start on {name}")
    return name


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--services-only", action="store_true")
    args = parser.parse_args()
    phases = [] if args.services_only else [("install", install), ("upload", upload)]
    for phase, worker in phases:
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            futures = {pool.submit(worker, name): name for name in nodes}
            for future in concurrent.futures.as_completed(futures):
                print(phase, future.result(), flush=True)

    start_hub()
    print("hub tokyo2", flush=True)

    users = [name for name in nodes if name != "tokyo2"]
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        for name in pool.map(start_payee, users):
            print("payee", name, flush=True)

    onion_by_name = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        futures = {pool.submit(configure_tor, name): name for name in users}
        for future in concurrent.futures.as_completed(futures):
            name, onion = future.result()
            onion_by_name[name] = onion
            print("tor", name, onion, flush=True)
    for node in config["nodes"]:
        if node["name"] in onion_by_name:
            node["onion"] = onion_by_name[node["name"]]
    CONFIG.write_text(json.dumps(config, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        for name in pool.map(start_tunnel, users):
            print("tunnel", name, flush=True)

    for filename in ["run_e1_direct_series.sh", "run_e1_direct_corrected_queue.sh"]:
        require(scp("tokyo4", RUNNER_ROOT / filename, f"/home/ubuntu/voucherhub/{filename}"),
                f"runner upload {filename}")
    require(ssh("tokyo4", "chmod +x /home/ubuntu/voucherhub/run_e1_direct_*.sh"),
            "runner permission")
    print("deployment complete", flush=True)


if __name__ == "__main__":
    main()
