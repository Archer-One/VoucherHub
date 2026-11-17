#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
classes="$(scripts/compile_common.sh)"
java -cp "$classes" org.voucherhub.common.ExperimentRunner --mode gas --results results
ls -1 results
