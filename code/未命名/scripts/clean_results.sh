#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p results logs
rm -f results/*.csv results/*.json logs/*.log
