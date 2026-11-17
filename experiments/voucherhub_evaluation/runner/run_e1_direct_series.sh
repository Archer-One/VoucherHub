#!/usr/bin/env bash
set -euo pipefail

topology=${1:?topology required}
hosts=${2:?comma-separated tunnel hosts required}
ports=${3:?comma-separated tunnel ports required}
cids=${4:?comma-separated payee cids required}
output_root=${5:?output root required}
repetitions=${REPETITIONS:-3}
classes=${CLASSES:-/home/ubuntu/voucherhub/classes-eval}
hub_port=${HUB_PORT:-19100}
payments_csv=${PAYMENTS_CSV:-10,20,40,60,80,100}
protocols_csv=${PROTOCOLS_CSV:-accio,vh-cp,vh-vp}
IFS=',' read -r -a payments <<< "$payments_csv"

mkdir -p "$output_root/$topology"
progress="$output_root/$topology/progress.log"
IFS=',' read -r -a protocols <<< "$protocols_csv"

run_one() {
  local protocol=$1 count=$2 repetition=$3
  local dir="$output_root/$topology/n${count}/$protocol/rep${repetition}"
  local result="$dir/result.json"
  if [[ -f "$result" ]] && grep -q '"success": true' "$result"; then
    printf '%s SKIP n=%s protocol=%s rep=%s\n' "$(date -u +%FT%TZ)" "$count" "$protocol" "$repetition" >> "$progress"
    return 0
  fi
  mkdir -p "$dir"
  if [[ -f "$dir/runner.log" ]]; then
    mv "$dir/runner.log" "$dir/runner.failed.$(date -u +%Y%m%dT%H%M%SZ).log"
  fi
  printf '%s START n=%s protocol=%s rep=%s\n' "$(date -u +%FT%TZ)" "$count" "$protocol" "$repetition" | tee -a "$progress"
  if timeout 600 java -Xms128m -Xmx512m \
    -Djava.library.path=/home/ubuntu/voucherhub/native -Dvoucherhub.crypto.backend=bn128 \
    -Dvoucherhub.rangeproof.backend=bulletproof \
    -cp "$classes" org.voucherhub.common.EvaluationNetworkBenchmark \
    --role payer --protocol "$protocol" --payments "$count" \
    --transport direct --allow-direct-baseline true \
    --hub-host 127.0.0.1 --hub-port "$hub_port" \
    --payee-hosts "$hosts" --payee-ports "$ports" --payee-cids "$cids" \
    --results "$result" > "$dir/runner.log" 2>&1 \
    && grep -q '"success": true' "$result"; then
    printf '%s PASS n=%s protocol=%s rep=%s\n' "$(date -u +%FT%TZ)" "$count" "$protocol" "$repetition" | tee -a "$progress"
    return 0
  fi
  printf '%s FAIL n=%s protocol=%s rep=%s\n' "$(date -u +%FT%TZ)" "$count" "$protocol" "$repetition" | tee -a "$progress"
  return 1
}

for count in "${payments[@]}"; do
  for ((rep=0; rep<repetitions; rep++)); do
    protocol_count=${#protocols[@]}
    offset=$(((rep + count) % protocol_count))
    for ((index=0; index<protocol_count; index++)); do
      protocol=${protocols[$(((index + offset) % protocol_count))]}
      for attempt in 1 2 3 4 5; do
        if run_one "$protocol" "$count" "$rep"; then break; fi
        if [[ "$attempt" == 5 ]]; then exit 1; fi
        sleep 5
      done
    done
  done
done
printf '%s COMPLETE\n' "$(date -u +%FT%TZ)" | tee -a "$progress"
