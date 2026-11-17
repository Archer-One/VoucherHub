#!/usr/bin/env bash
set -euo pipefail

runner=/home/ubuntu/voucherhub/run_e1_direct_series.sh
output=/home/ubuntu/voucherhub/results/evaluation/e1_direct

"$runner" same_city_1to1 127.0.0.1 19201 tokyo3 "$output"
"$runner" same_city_1to3 127.0.0.1,127.0.0.1,127.0.0.1 19201,19202,19203 tokyo3,tokyo6,tokyo7 "$output"
"$runner" same_city_1to5 127.0.0.1,127.0.0.1,127.0.0.1,127.0.0.1,127.0.0.1 19201,19202,19203,19204,19205 tokyo3,tokyo6,tokyo7,tokyo1,tokyo5 "$output"

"$runner" regional_1to1 127.0.0.1 19304 singapore1 "$output"
"$runner" regional_1to3 127.0.0.1,127.0.0.1,127.0.0.1 19301,19302,19304 seoul1,seoul2,singapore1 "$output"
"$runner" regional_1to5 127.0.0.1,127.0.0.1,127.0.0.1,127.0.0.1,127.0.0.1 19301,19302,19303,19304,19305 seoul1,seoul2,seoul3,singapore1,singapore2 "$output"

"$runner" global_1to1 127.0.0.1 19403 virginia1 "$output"
"$runner" global_1to3 127.0.0.1,127.0.0.1,127.0.0.1 19401,19402,19403 frankfurt1,frankfurt2,virginia1 "$output"
"$runner" global_1to5 127.0.0.1,127.0.0.1,127.0.0.1,127.0.0.1,127.0.0.1 19201,19401,19402,19403,19404 tokyo3,frankfurt1,frankfurt2,virginia1,virginia2 "$output"

touch "$output/COMPLETE"
