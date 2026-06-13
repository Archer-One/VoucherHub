#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
classes="$(scripts/compile_common.sh)"
java -cp "$classes" org.voucherhub.common.ExperimentRunner --mode accio-baseline --runs "${1:-30}" --results results
