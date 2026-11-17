#!/usr/bin/env bash
set -euo pipefail

project=/Users/archer_oneee/Documents/ariticles/VoucherHub
evaluation="$project/experiments/voucherhub_evaluation"
log="$evaluation/logs/figure1_7_finish.log"

printf '%s START 3-to-1 seven-repetition run\n' "$(date -u +%FT%TZ)" >> "$log"
python3 "$evaluation/runner/run_n_to_1.py" \
  --topologies same_city_3to1,regional_3to1,global_3to1 --repetitions 7 \
  --output-root "$evaluation/raw/e1" >> "$log" 2>&1
printf '%s SUMMARIZE\n' "$(date -u +%FT%TZ)" >> "$log"
python3 "$evaluation/runner/summarize_e1.py" >> "$log" 2>&1
python3 "$project/charts/plot_e1_completion_time.py" >> "$log" 2>&1
printf '%s FIGURE1_COMPLETE\n' "$(date -u +%FT%TZ)" >> "$log"
