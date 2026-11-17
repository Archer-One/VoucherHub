#!/usr/bin/env python3
"""Upload the gas benchmark to one AWS node, run it, and download results."""

import argparse
import json
import os
from pathlib import Path
import shlex
import subprocess
import tarfile
import tempfile


PROJECT = Path(os.environ.get("VOUCHERHUB_PROJECT", Path(__file__).resolve().parents[3]))
CONFIG = PROJECT / "experiments/voucherhub_evaluation/config/servers_new.json"
KEY_ROOT = PROJECT / "cloud server_1"
GAS_ROOT = PROJECT / "code/未命名/gas"


def inventory():
    payload = json.loads(CONFIG.read_text(encoding="utf-8"))
    records = {payload["hub"]["name"]: payload["hub"]}
    records.update({item["name"]: item for item in payload["nodes"]})
    return records


def options(records, name):
    node = records[name]
    result = ["-o", "BatchMode=yes", "-o", "ConnectTimeout=20",
              "-o", "ServerAliveInterval=10", "-o", "ServerAliveCountMax=6",
              "-o", "StrictHostKeyChecking=no", "-i", str(KEY_ROOT / node["key"])]
    if node.get("jump"):
        jump = records[node["jump"]]
        proxy = ("ssh -o BatchMode=yes -o ConnectTimeout=20 -o StrictHostKeyChecking=no "
                 f"-i {shlex.quote(str(KEY_ROOT / jump['key']))} -W %h:%p "
                 f"ubuntu@{jump['public_ip']}")
        result += ["-o", "ProxyCommand=" + proxy]
    return result


def ssh(records, name, command, timeout=3600):
    node = records[name]
    return subprocess.run(["ssh"] + options(records, name)
                          + [f"ubuntu@{node['public_ip']}", command],
                          text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=timeout)


def scp_to(records, name, source, destination):
    node = records[name]
    return subprocess.run(["scp"] + options(records, name)
                          + [str(source), f"ubuntu@{node['public_ip']}:{destination}"],
                          text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=600)


def scp_from(records, name, source, destination):
    node = records[name]
    return subprocess.run(["scp"] + options(records, name)
                          + [f"ubuntu@{node['public_ip']}:{source}", str(destination)],
                          text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=600)


def require(result, action):
    if result.returncode != 0:
        raise RuntimeError(f"{action} failed: {result.stderr.strip() or result.stdout.strip()}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--node", default="tokyo5")
    parser.add_argument("--samples", type=int, default=20)
    parser.add_argument("--batch-sizes", default="20,40,60,80,100")
    parser.add_argument("--pairing-rounds", type=int, default=4)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    records = inventory()
    if args.node not in records:
        raise ValueError(f"unknown node {args.node}")
    args.output.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="voucherhub-gas-") as temporary:
        archive = Path(temporary) / "gas-code.tar.gz"
        with tarfile.open(archive, "w:gz") as stream:
            for path in GAS_ROOT.rglob("*"):
                relative = path.relative_to(GAS_ROOT)
                if any(part in {"results", ".venv", "__pycache__"} for part in relative.parts):
                    continue
                stream.add(path, arcname=Path("gas") / relative)
        require(scp_to(records, args.node, archive, "/home/ubuntu/gas-code.tar.gz"), "upload")
        command = (
            "set -e; "
            "rm -rf /home/ubuntu/voucherhub-venv-check; "
            "if ! python3 -m venv /home/ubuntu/voucherhub-venv-check >/dev/null 2>&1; then "
            "for i in $(seq 1 60); do "
            "sudo fuser /var/lib/dpkg/lock-frontend >/dev/null 2>&1 || break; sleep 5; done; "
            "sudo DEBIAN_FRONTEND=noninteractive apt-get update -qq; "
            "sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq python3-venv; fi; "
            "rm -rf /home/ubuntu/voucherhub-venv-check; "
            "rm -rf /home/ubuntu/voucherhub-gas && mkdir -p /home/ubuntu/voucherhub-gas; "
            "tar -xzf /home/ubuntu/gas-code.tar.gz -C /home/ubuntu/voucherhub-gas; "
            "cd /home/ubuntu/voucherhub-gas/gas; python3 -m venv .venv; "
            ".venv/bin/pip install -q --upgrade pip; "
            ".venv/bin/pip install -q -r requirements-gas.txt; "
            f".venv/bin/python measure_gas.py --samples {args.samples} "
            f"--batch-sizes {shlex.quote(args.batch_sizes)} --pairing-rounds {args.pairing_rounds} "
            "--output results; tar -czf /home/ubuntu/voucherhub-gas-results.tar.gz results"
        )
        completed = ssh(records, args.node, command)
        (args.output / "aws_run.log").write_text(
            completed.stdout + "\nSTDERR\n" + completed.stderr, encoding="utf-8")
        require(completed, "remote gas benchmark")
        downloaded = args.output / "voucherhub-gas-results.tar.gz"
        require(scp_from(records, args.node, "/home/ubuntu/voucherhub-gas-results.tar.gz", downloaded),
                "result download")
        with tarfile.open(downloaded, "r:gz") as stream:
            for member in stream.getmembers():
                if member.name.startswith("/") or ".." in Path(member.name).parts:
                    raise RuntimeError(f"unsafe result archive member: {member.name}")
            stream.extractall(args.output)
    print(args.output)


if __name__ == "__main__":
    main()
