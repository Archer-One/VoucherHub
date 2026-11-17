#!/usr/bin/env bash
set -euo pipefail

output=${1:-/home/ubuntu/voucherhub/results/evaluation/transport_calibration}
repetitions=${REPETITIONS:-10}
classes=${CLASSES:-/home/ubuntu/voucherhub/classes-eval}
native=${NATIVE_LIBS:-/home/ubuntu/voucherhub/native}

regions=(same_city regional global)
direct_hosts=(43.207.41.139 13.251.45.161 44.220.130.48)
onions=(
  76hxjcm2qfso55njk3fxqulads5tuwyxzjatw5p6ezuym6bsinyeuzqd.onion
  p6iqu3ncdtkakqkxlcaylasbpskrnfv7mvq6ektbbczwhsf765zptfyd.onion
  hboi5zdwozb2dz4iov2s53hhcvp34z57e6jzk6upzfceteqvv3cqg5ad.onion
)
cids=(tokyo3 singapore1 virginia1)

run_one() {
  local region=$1 transport=$2 endpoint=$3 cid=$4 sample=$5
  local dir="$output/$region/$transport/rep${sample}"
  local result="$dir/result.json"
  if [[ -f "$result" ]] && grep -q '"success": true' "$result"; then
    echo "SKIP region=$region transport=$transport rep=$sample"
    return 0
  fi
  mkdir -p "$dir"
  local endpoint_args
  if [[ "$transport" == direct ]]; then
    endpoint_args=(--transport direct --payee-hosts "$endpoint" --payee-ports 9101)
  else
    endpoint_args=(--transport tor --payee-onions "$endpoint" --socks-host 127.0.0.1 --socks-port 9050)
  fi
  timeout 300 java -Xms128m -Xmx512m \
    -Djava.library.path="$native" -Dvoucherhub.crypto.backend=bn128 \
    -cp "$classes" org.voucherhub.common.EvaluationNetworkBenchmark \
    --role payer --protocol vh-cp --payments 1 \
    --hub-host 127.0.0.1 --hub-port 19100 \
    "${endpoint_args[@]}" --payee-cids "$cid" \
    --results "$result" > "$dir/runner.log" 2>&1 \
    && grep -q '"success": true' "$result"
}

for index in 0 1 2; do
  region=${regions[$index]}
  # One unreported warm-up for each transport and region.
  for transport in direct tor; do
    endpoint=${direct_hosts[$index]}
    [[ "$transport" == tor ]] && endpoint=${onions[$index]}
    for attempt in 1 2 3 4 5; do
      if run_one "$region" "$transport" "$endpoint" "${cids[$index]}" warmup; then break; fi
      [[ "$attempt" == 5 ]] && exit 1
      sleep 5
    done
  done
  for ((rep=0; rep<repetitions; rep++)); do
    for transport in direct tor; do
      endpoint=${direct_hosts[$index]}
      [[ "$transport" == tor ]] && endpoint=${onions[$index]}
      for attempt in 1 2 3 4 5; do
        if run_one "$region" "$transport" "$endpoint" "${cids[$index]}" "$rep"; then
          echo "PASS region=$region transport=$transport rep=$rep"
          break
        fi
        echo "RETRY region=$region transport=$transport rep=$rep attempt=$attempt"
        [[ "$attempt" == 5 ]] && exit 1
        sleep 5
      done
    done
  done
done

touch "$output/COMPLETE"
echo COMPLETE
