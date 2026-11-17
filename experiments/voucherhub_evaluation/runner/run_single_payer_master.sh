#!/usr/bin/env bash
set -euo pipefail

marker=/home/ubuntu/voucherhub/results/evaluation/e1/ALL_SINGLE_PAYER_COMPLETE
rm -f "$marker"
/home/ubuntu/voucherhub/run_e1_20_queue.sh
/home/ubuntu/voucherhub/run_e1_1to5_queue.sh
touch "$marker"
