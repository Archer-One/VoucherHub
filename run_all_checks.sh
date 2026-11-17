#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "$0")" && pwd)

"$root/code/未命名/scripts/run_self_tests.sh"
python3 -m unittest discover -s "$root/code/未命名/gas/tests" -v
python3 -m unittest discover -s "$root/experiments/voucherhub_evaluation/tests" -v

python3 -m py_compile \
  "$root/experiments/voucherhub_evaluation/deploy/deploy_new_environment.py" \
  "$root/experiments/voucherhub_evaluation/deploy/run_gas_on_aws.py" \
  "$root/experiments/voucherhub_evaluation/runner/run_n_to_1.py" \
  "$root/experiments/voucherhub_evaluation/runner/run_single_payer_tor.py" \
  "$root/experiments/voucherhub_evaluation/runner/summarize_e1_tor.py" \
  "$root/charts/generate_demo_tor_summary.py" \
  "$root/charts/plot_e1_tor_measured.py" \
  "$root/code/未命名/gas/measure_gas.py"

bash -n \
  "$root/experiments/voucherhub_evaluation/runner/run_figure1_tor.sh" \
  "$root/experiments/voucherhub_evaluation/runner/run_e1_series.sh" \
  "$root/code/未命名/scripts/package_classes.sh"

echo "All offline checks passed."
