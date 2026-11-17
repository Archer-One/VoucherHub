#!/usr/bin/env bash
set -euo pipefail

runner=/home/ubuntu/voucherhub/run_e1_direct_series.sh
output=/home/ubuntu/voucherhub/results/evaluation/e1_direct_public_corrected
classes=/home/ubuntu/voucherhub/classes-eval-vp-batch

run_series() {
  CLASSES="$classes" REPETITIONS=3 "$runner" "$@" "$output"
}

run_series same_city_1to1 54.92.2.10 9101 tokyo3
run_series same_city_1to3 54.92.2.10,18.183.117.178,52.199.246.164 9101 tokyo3,tokyo6,tokyo7

run_series regional_1to1 18.143.153.164 9101 singapore1
run_series regional_1to3 3.34.198.147,15.165.159.44,18.143.153.164 9101 seoul1,seoul2,singapore1

run_series global_1to1 98.84.188.150 9101 virginia1
run_series global_1to3 18.196.227.238,3.121.41.160,98.84.188.150 9101 frankfurt1,frankfurt2,virginia1

touch "$output/FIGURE1_3_COMPLETE"
