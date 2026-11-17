#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
lock=/tmp/voucherhub-build.lock
while ! mkdir "$lock" 2>/dev/null; do
  sleep 0.2
done
trap 'rmdir "$lock"' EXIT
if command -v mvn >/dev/null 2>&1; then
  mvn -q -pl common -am compile
  echo "$(pwd)/common/target/classes"
else
  mkdir -p "$(pwd)/common/target/classes"
  bundled_bulletproof="$(pwd)/third_party/bulletproof-src"
  external_bulletproof="$(pwd)/../../../PBHub/LightHub/exp/off-chain/LightHubNew/src/main/java"
  if [[ -n "${BULLETPROOF_SRC:-}" ]]; then
    bulletproof_src=$BULLETPROOF_SRC
  elif [[ -d "$bundled_bulletproof/encryption/bulletproof" ]]; then
    bulletproof_src=$bundled_bulletproof
  else
    bulletproof_src=$external_bulletproof
  fi
  if [[ ! -d "$bulletproof_src/encryption/bulletproof" ]]; then
    echo "Bulletproof source directory not found: $bulletproof_src/encryption/bulletproof" >&2
    exit 1
  fi
  javac -d "$(pwd)/common/target/classes" \
    $(find common/src/main/java -name '*.java') \
    $(find "$bulletproof_src/encryption/bulletproof" -name '*.java')
  echo "$(pwd)/common/target/classes"
fi
