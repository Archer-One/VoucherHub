#!/usr/bin/env bash
set -euo pipefail

project=/Users/archer_oneee/Documents/ariticles/VoucherHub
evaluation="$project/experiments/voucherhub_evaluation"
key="$project/cloud server/tokyo.pem"
remote=ubuntu@13.115.221.201
remote_marker=/home/ubuntu/voucherhub/results/evaluation/e1/ALL_SINGLE_PAYER_COMPLETE
log="$evaluation/logs/remaining_20_queue.log"

mkdir -p "$evaluation/logs" "$evaluation/raw/e1"
printf '%s waiting for 1-to-1/1-to-n queue\n' "$(date -u +%FT%TZ)" >> "$log"
while ! ssh -o BatchMode=yes -o ConnectTimeout=15 -o StrictHostKeyChecking=no \
  -i "$key" "$remote" "test -f '$remote_marker'"; do
  sleep 30
done

printf '%s downloading completed single-payer results\n' "$(date -u +%FT%TZ)" >> "$log"
rsync -az -e "ssh -o StrictHostKeyChecking=no -i '$key'" \
  "$remote:/home/ubuntu/voucherhub/results/evaluation/e1/" "$evaluation/raw/e1/"

result_count=$(find "$evaluation/raw/e1" -path '*/n20/*/rep*/result.json' -type f | wc -l | tr -d ' ')
if [[ "$result_count" != 360 ]]; then
  printf '%s expected 360 main-figure single-payer results, found %s\n' "$(date -u +%FT%TZ)" "$result_count" >> "$log"
  exit 1
fi

printf '%s running distributed 3-to-1 and 5-to-1 formal samples\n' "$(date -u +%FT%TZ)" >> "$log"
python3 "$evaluation/runner/run_n_to_1.py" \
  --topologies same_city_3to1,regional_3to1,global_3to1 --repetitions 7 \
  --output-root "$evaluation/raw/e1" >> "$log" 2>&1

printf '%s summarizing and plotting Figure 1\n' "$(date -u +%FT%TZ)" >> "$log"
python3 "$evaluation/runner/summarize_e1.py" >> "$log" 2>&1
python3 "$project/charts/plot_e1_completion_time.py" >> "$log" 2>&1
printf '%s FIGURE1_COMPLETE\n' "$(date -u +%FT%TZ)" >> "$log"

printf '%s running distributed 5-to-1 supplementary samples\n' "$(date -u +%FT%TZ)" >> "$log"
python3 "$evaluation/runner/run_n_to_1.py" \
  --topologies same_city_5to1,regional_5to1,global_5to1 --repetitions 7 \
  --output-root "$evaluation/raw/e1" >> "$log" 2>&1
python3 "$evaluation/runner/summarize_e1.py" >> "$log" 2>&1
printf '%s COMPLETE\n' "$(date -u +%FT%TZ)" >> "$log"
