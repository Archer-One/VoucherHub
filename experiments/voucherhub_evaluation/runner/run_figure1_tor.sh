#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
raw=${1:-"$root/raw/e1_tor_measured"}
repetitions=${REPETITIONS:-7}

python3 "$root/runner/run_single_payer_tor.py" \
  --repetitions "$repetitions" --output-root "$raw"

python3 "$root/runner/run_n_to_1.py" \
  --topologies same_city_3to1,regional_3to1,global_3to1 \
  --payments 10,20,40,60,80,100 --repetitions "$repetitions" \
  --transport tor --output-root "$raw"

python3 "$root/runner/run_n_to_1.py" \
  --topologies same_city_5to1,regional_5to1,global_5to1 \
  --payments 20 --repetitions "$repetitions" \
  --transport tor --output-root "$raw"

python3 "$root/runner/summarize_e1_tor.py" \
  --raw "$raw" --repetitions "$repetitions"
python3 "$root/../../charts/plot_e1_tor_measured.py"
