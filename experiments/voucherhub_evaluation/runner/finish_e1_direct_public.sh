#!/usr/bin/env bash
set -euo pipefail

project=/Users/archer_oneee/Documents/ariticles/VoucherHub
evaluation="$project/experiments/voucherhub_evaluation"
key="$project/cloud server/tokyo.pem"
remote=ubuntu@13.115.221.201
log="$evaluation/logs/e1_direct_public.log"

printf '%s waiting for direct single-payer queue\n' "$(date -u +%FT%TZ)" >> "$log"
while ! ssh -q -o ConnectTimeout=15 -i "$key" "$remote" \
  'test -f /home/ubuntu/voucherhub/results/evaluation/e1_direct_public/COMPLETE'; do
  sleep 30
done
mkdir -p "$evaluation/raw/e1_direct_public"
rsync -az --partial -e "ssh -o ServerAliveInterval=20 -i '$key'" \
  "$remote:/home/ubuntu/voucherhub/results/evaluation/e1_direct_public/" \
  "$evaluation/raw/e1_direct_public/"
printf '%s running direct multi-payer queue\n' "$(date -u +%FT%TZ)" >> "$log"
python3 "$evaluation/runner/run_n_to_1.py" --transport direct \
  --topologies same_city_3to1,same_city_5to1,regional_3to1,regional_5to1,global_3to1,global_5to1 \
  --repetitions 7 --output-root "$evaluation/raw/e1_direct_public" >> "$log" 2>&1
python3 "$evaluation/runner/summarize_e1_direct.py" >> "$log" 2>&1
printf '%s COMPLETE\n' "$(date -u +%FT%TZ)" >> "$log"
