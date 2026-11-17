#!/usr/bin/env bash
set -euo pipefail

topology=${1:?topology name required}
onions=${2:?comma-separated onion list required}
cids=${3:?comma-separated payee cid list required}
output_root=${4:?output root required}
repetitions=${REPETITIONS:-20}
hub_port=${HUB_PORT:-19100}
classes=${CLASSES:-/home/ubuntu/voucherhub/classes-eval}
native=${NATIVE_LIBS:-/home/ubuntu/voucherhub/native}

mkdir -p "$output_root/$topology"
progress="$output_root/$topology/progress.log"
# The current evaluation request fixes every topology at exactly 20 completed
# payments. Keep this as an array so the same resumable loop remains usable.
payments=(20)
protocols=(accio vh-cp vh-vp)

run_one() {
  local protocol=$1 count=$2 repetition=$3
  local dir="$output_root/$topology/n${count}/${protocol}/rep${repetition}"
  local result="$dir/result.json"
  if [[ -f "$result" ]] && grep -q '"success": true' "$result"; then
    printf '%s SKIP n=%s protocol=%s rep=%s\n' "$(date -u +%FT%TZ)" "$count" "$protocol" "$repetition" >> "$progress"
    return
  fi
  mkdir -p "$dir"
  if [[ -f "$dir/runner.log" ]]; then
    mv "$dir/runner.log" "$dir/runner.failed.$(date -u +%Y%m%dT%H%M%SZ).log"
  fi
  printf '%s START n=%s protocol=%s rep=%s\n' "$(date -u +%FT%TZ)" "$count" "$protocol" "$repetition" | tee -a "$progress"
  uptime > "$dir/system_before.txt"
  free -m >> "$dir/system_before.txt"
  if timeout 1800 java -Xms128m -Xmx512m \
    -Djava.library.path="$native" -Dvoucherhub.crypto.backend=bn128 \
    -cp "$classes" org.voucherhub.common.EvaluationNetworkBenchmark \
    --role payer --protocol "$protocol" --payments "$count" \
    --hub-host 127.0.0.1 --hub-port "$hub_port" \
    --payee-onions "$onions" --payee-cids "$cids" \
    --socks-host 127.0.0.1 --socks-port 9050 \
    --results "$result" > "$dir/runner.log" 2>&1 \
    && grep -q '"success": true' "$result"; then
    printf '%s PASS n=%s protocol=%s rep=%s\n' "$(date -u +%FT%TZ)" "$count" "$protocol" "$repetition" | tee -a "$progress"
  else
    printf '%s FAIL n=%s protocol=%s rep=%s\n' "$(date -u +%FT%TZ)" "$count" "$protocol" "$repetition" | tee -a "$progress"
    return 1
  fi
  uptime > "$dir/system_after.txt"
  free -m >> "$dir/system_after.txt"
}

for count in "${payments[@]}"; do
  for ((rep=0; rep<repetitions; rep++)); do
    offset=$(((count / 10 + rep) % 3))
    for ((index=0; index<3; index++)); do
      protocol=${protocols[$(((index + offset) % 3))]}
      completed=false
      for attempt in 1 2 3 4 5; do
        if run_one "$protocol" "$count" "$rep"; then
          completed=true
          break
        fi
        printf '%s RETRY n=%s protocol=%s rep=%s attempt=%s\n' \
          "$(date -u +%FT%TZ)" "$count" "$protocol" "$rep" "$attempt" | tee -a "$progress"
        sleep 10
      done
      if [[ "$completed" != true ]]; then
        printf '%s ABORT n=%s protocol=%s rep=%s after retries\n' \
          "$(date -u +%FT%TZ)" "$count" "$protocol" "$rep" | tee -a "$progress"
        exit 1
      fi
    done
  done
done

printf '%s COMPLETE\n' "$(date -u +%FT%TZ)" | tee -a "$progress"
