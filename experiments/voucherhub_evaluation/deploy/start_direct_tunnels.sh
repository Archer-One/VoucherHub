#!/usr/bin/env bash
set -euo pipefail

mkdir -p /home/ubuntu/voucherhub/logs/direct_tunnels

start_tunnel() {
  local port=$1 host=$2 name=$3
  if ! ss -ltn | awk '{print $4}' | grep -q ":${port}$"; then
    nohup ssh -N -o BatchMode=yes -o ExitOnForwardFailure=yes \
      -o ConnectTimeout=15 -o ServerAliveInterval=20 -o ServerAliveCountMax=3 \
      -o StrictHostKeyChecking=no -i /home/ubuntu/.ssh/voucherhub_eval_tunnel \
      -L "127.0.0.1:${port}:127.0.0.1:9101" "ubuntu@${host}" \
      > "/home/ubuntu/voucherhub/logs/direct_tunnels/${name}.log" 2>&1 < /dev/null &
    sleep 1
  fi
}

start_tunnel 19201 43.207.41.139 tokyo3
start_tunnel 19202 35.77.212.228 tokyo6
start_tunnel 19203 43.207.56.175 tokyo7
start_tunnel 19204 43.206.123.103 tokyo1
start_tunnel 19205 13.114.202.133 tokyo5
start_tunnel 19301 13.124.175.101 seoul1
start_tunnel 19302 13.209.72.157 seoul2
start_tunnel 19303 13.125.224.108 seoul3
start_tunnel 19304 13.251.45.161 singapore1
start_tunnel 19305 13.214.197.52 singapore2
start_tunnel 19401 3.126.251.25 frankfurt1
start_tunnel 19402 3.121.228.250 frankfurt2
start_tunnel 19403 44.220.130.48 virginia1
start_tunnel 19404 52.90.163.231 virginia2

sleep 3
for port in 19201 19202 19203 19204 19205 19301 19302 19303 19304 19305 19401 19402 19403 19404; do
  if ss -ltn | awk '{print $4}' | grep -q ":${port}$"; then
    printf '%s up\n' "$port"
  else
    printf '%s down\n' "$port"
  fi
done
