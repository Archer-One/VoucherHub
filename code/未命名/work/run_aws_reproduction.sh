#!/usr/bin/env bash
set -euo pipefail

root=${1:-/home/ubuntu/voucherhub/results/reproduction_20261106}
classes=/home/ubuntu/voucherhub/classes
native=/home/ubuntu/voucherhub/native
repetitions=${REPETITIONS:-1}
workload=${WORKLOAD:-20}

java_cmd=(java
  -Djava.library.path="$native"
  -Dvoucherhub.crypto.backend=bn128
  -cp "$classes"
  org.voucherhub.common.DistributedBenchmark)

mkdir -p "$root"
progress="$root/progress.log"
failures="$root/failures.log"
touch "$progress" "$failures"

timestamp() {
  date -u +%Y-%m-%dT%H:%M:%SZ
}

run_case() {
  local topology=$1 hub_port=$2 payee_port=$3 phase=$4 runs=$5 label=$6 rep=$7 protocol=$8 payer_count=$9
  local dir="$root/raw/$phase/$topology/n${runs}_${label}/rep${rep}"
  local summary="$dir/distributed_summary.json"
  local failure_field=accio_failures
  if [[ "$protocol" == voucher ]]; then
    failure_field=voucher_failures
  fi

  if [[ -f "$summary" ]] && grep -q "\"$failure_field\": 0" "$summary"; then
    printf '%s SKIP %s n=%s %s rep=%s\n' "$(timestamp)" "$topology" "$runs" "$label" "$rep" | tee -a "$progress"
    return 0
  fi

  mkdir -p "$dir"
  local attempt
  for attempt in 1 2 3; do
    printf '%s START %s n=%s %s rep=%s attempt=%s\n' "$(timestamp)" "$topology" "$runs" "$label" "$rep" "$attempt" | tee -a "$progress"
    if timeout 900 "${java_cmd[@]}" \
      --role payer \
      --hub-host 127.0.0.1 --hub-port "$hub_port" \
      --payee-host 127.0.0.1 --payee-port "$payee_port" \
      --runs "$runs" --payer-count "$payer_count" \
      --protocol "$protocol" --voucher-boundary batch-redeem \
      --results "$dir/distributed.csv" > "$dir/runner.log" 2>&1 \
      && grep -q "\"$failure_field\": 0" "$summary"; then
      printf '%s PASS %s n=%s %s rep=%s\n' "$(timestamp)" "$topology" "$runs" "$label" "$rep" | tee -a "$progress"
      return 0
    fi
    printf '%s RETRY %s n=%s %s rep=%s attempt=%s\n' "$(timestamp)" "$topology" "$runs" "$label" "$rep" "$attempt" | tee -a "$progress"
    sleep 2
  done

  printf '%s FAIL %s n=%s %s rep=%s\n' "$(timestamp)" "$topology" "$runs" "$label" "$rep" | tee -a "$progress" "$failures"
  return 1
}

run_topology() {
  local topology=$1 hub_port=$2 payee_port=$3
  local label protocol payers rep runs

  for runs in "$workload"; do
    # Rotate configuration order between workloads and repetitions to reduce order bias.
    for ((rep=0; rep<repetitions; rep++)); do
      case $(((runs / 10 + rep) % 4)) in
        0) order=(Accio VoucherHub-1 VoucherHub-3 VoucherHub-5) ;;
        1) order=(VoucherHub-1 VoucherHub-3 VoucherHub-5 Accio) ;;
        2) order=(VoucherHub-3 VoucherHub-5 Accio VoucherHub-1) ;;
        3) order=(VoucherHub-5 Accio VoucherHub-1 VoucherHub-3) ;;
      esac
      for label in "${order[@]}"; do
        protocol=voucher
        payers=${label#VoucherHub-}
        if [[ "$label" == Accio ]]; then
          protocol=accio
          payers=1
        fi
        run_case "$topology" "$hub_port" "$payee_port" measured "$runs" "$label" "$rep" "$protocol" "$payers"
      done
    done
  done
}

printf '%s\n' \
  'VoucherHub AWS reproduction' \
  'Backend: bn128 JNI' \
  "Workload: $workload payments" \
  'Configurations: Accio, VoucherHub-1, VoucherHub-3, VoucherHub-5' \
  "Measured repetitions: $repetitions" \
  'Timing boundary: VoucherHub batch-redeem' \
  'Topologies: Tokyo/Tokyo/Tokyo, Tokyo/Seoul/Singapore, Tokyo/Frankfurt/Virginia' \
  "Started: $(timestamp)" > "$root/README.txt"

run_topology tokyo_tokyo_tokyo 19100 19101
run_topology tokyo_seoul_singapore 19200 19201
run_topology tokyo_frankfurt_virginia 19300 19301

if [[ -s "$failures" ]]; then
  printf '%s COMPLETED_WITH_FAILURES\n' "$(timestamp)" | tee -a "$progress"
  exit 1
fi

printf '%s COMPLETE\n' "$(timestamp)" | tee -a "$progress"
