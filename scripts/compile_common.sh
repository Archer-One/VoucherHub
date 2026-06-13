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
  mkdir -p /tmp/voucherhub-common-classes
  javac -d /tmp/voucherhub-common-classes $(find common/src/main/java -name '*.java')
  echo "/tmp/voucherhub-common-classes"
fi
