#!/usr/bin/env bash
set -euo pipefail

runner=/home/ubuntu/voucherhub/run_e1_direct_series.sh
output=/home/ubuntu/voucherhub/results/evaluation/e1_direct_public

"$runner" same_city_1to1 43.207.41.139 9101 tokyo3 "$output"
"$runner" same_city_1to3 43.207.41.139,35.77.212.228,43.207.56.175 9101 tokyo3,tokyo6,tokyo7 "$output"

"$runner" regional_1to1 13.251.45.161 9101 singapore1 "$output"
"$runner" regional_1to3 13.124.175.101,13.209.72.157,13.251.45.161 9101 seoul1,seoul2,singapore1 "$output"

"$runner" global_1to1 44.220.130.48 9101 virginia1 "$output"
"$runner" global_1to3 3.126.251.25,3.121.228.250,44.220.130.48 9101 frankfurt1,frankfurt2,virginia1 "$output"

touch "$output/FIGURE1_3_COMPLETE"
