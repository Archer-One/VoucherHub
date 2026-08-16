#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
classes="$(scripts/compile_common.sh)"
tar -C "$classes" -czf voucherhub-classes.tar.gz org
echo "$(pwd)/voucherhub-classes.tar.gz"
