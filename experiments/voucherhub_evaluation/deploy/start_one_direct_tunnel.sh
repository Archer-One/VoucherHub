#!/usr/bin/env bash
set -euo pipefail

port=${1:?local port required}
host=${2:?remote host required}
name=${3:?name required}
mkdir -p /home/ubuntu/voucherhub/logs/direct_tunnels
if ! ss -ltn | awk '{print $4}' | grep -q ":${port}$"; then
  nohup ssh -N -o BatchMode=yes -o ExitOnForwardFailure=yes \
    -o ConnectTimeout=15 -o ServerAliveInterval=20 -o ServerAliveCountMax=3 \
    -o StrictHostKeyChecking=no -i /home/ubuntu/.ssh/voucherhub_eval_tunnel \
    -L "127.0.0.1:${port}:127.0.0.1:9101" "ubuntu@${host}" \
    > "/home/ubuntu/voucherhub/logs/direct_tunnels/${name}.log" 2>&1 < /dev/null &
  sleep 3
fi
ss -ltn | awk '{print $4}' | grep -q ":${port}$"
