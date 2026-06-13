#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
scripts/run_self_tests.sh
scripts/run_voucherhub_single.sh "${1:-30}"
scripts/run_voucherhub_delayed.sh "${1:-30}"
scripts/run_voucherhub_batch.sh "${1:-30}"
scripts/run_voucherhub_griefing_tests.sh
scripts/run_accio_baseline.sh "${1:-30}"
scripts/collect_results.sh
