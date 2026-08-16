# VoucherHub Prototype

This repository contains the Java prototype used for VoucherHub payment-channel experiments.

The codebase is organized as a multi-module Maven project:

- `common`: shared protocol logic, benchmark entry points, metrics, and cryptographic abstractions
- `payer`: payer-side launcher
- `hub`: hub-side launcher
- `payee`: payee-side launcher
- `contracts`: prototype Solidity contract
- `scripts`: helper scripts for compilation and benchmark execution

## Scope

This public release is intended for experiment reproduction and code inspection.

It includes:

- protocol and benchmark source code
- Maven build files
- helper scripts
- prototype contract code

It does not include:

- experiment result CSV/JSON files
- private cloud server configuration
- SSH keys or deployment credentials
- local scratch directories, dependency caches, or generated archives

## Requirements

- Java 11 or newer is recommended. The AWS experiment nodes used Java 17.
- Maven, if you want to build through the Maven workflow.

If Maven is not available, `scripts/compile_common.sh` falls back to `javac` for the `common` module.

## Build

From the repository root:

```bash
mvn -q compile
```

Or compile the shared module through the helper script:

```bash
scripts/compile_common.sh
```

Create the class archive used for remote nodes:

```bash
scripts/package_classes.sh
```

## Main Entrypoints

Local protocol and benchmark entry points live in `common`:

- `org.voucherhub.common.ExperimentRunner`
- `org.voucherhub.common.DistributedBenchmark`
- `org.voucherhub.common.SelfTest`

The role-specific modules provide thin launchers:

- `payer/src/main/java/org/example/Main.java`
- `hub/src/main/java/org/example/Main.java`
- `payee/src/main/java/org/example/Main.java`

## Example Commands

Run self-tests:

```bash
scripts/run_self_tests.sh
```

Run all local experiment modes:

```bash
scripts/run_all_experiments.sh 30
```

Run a single-payment VoucherHub experiment:

```bash
scripts/run_voucherhub_single.sh 30
```

Run the Accio baseline scaffold:

```bash
scripts/run_accio_baseline.sh 30
```

The scripts write CSV/JSON files to `results/`.

## Distributed Benchmark

The distributed benchmark uses three roles:

- `hub`: listens for VoucherHub and Accio hub-side requests
- `payee`: listens for invoice, accept, and finalization requests
- `payer`: connects to the hub and payee, runs the measured workload, and writes results

Start the hub:

```bash
java -cp common/target/classes org.voucherhub.common.DistributedBenchmark --role hub --port 9100
```

Start the payee:

```bash
java -cp common/target/classes org.voucherhub.common.DistributedBenchmark --role payee --port 9101
```

Run the payer:

```bash
java -cp common/target/classes org.voucherhub.common.DistributedBenchmark \
  --role payer \
  --hub-host 127.0.0.1 --hub-port 9100 \
  --payee-host 127.0.0.1 --payee-port 9101 \
  --runs 500 \
  --payer-count 1 \
  --protocol both \
  --voucher-boundary batch-redeem \
  --results results/distributed_results.csv
```

If a remote server does not have Maven, build locally and copy the generated class archive:

```bash
scripts/package_classes.sh
scp -i KEY_PATH voucherhub-classes.tar.gz ubuntu@HUB_PUBLIC_DNS:/home/ubuntu/voucherhub/
ssh -i KEY_PATH ubuntu@HUB_PUBLIC_DNS 'cd /home/ubuntu/voucherhub && mkdir -p classes && tar -xzf voucherhub-classes.tar.gz -C classes'
```

Then run the role commands with `-cp /home/ubuntu/voucherhub/classes`.

The original AWS evaluation used separate regional nodes and private keys. Those details are intentionally not part of the public repository.

## Direct Local Example

Run the distributed benchmark directly:

```bash
java -cp common/target/classes org.voucherhub.common.DistributedBenchmark --role hub --port 9100
java -cp common/target/classes org.voucherhub.common.DistributedBenchmark --role payee --port 9101
java -cp common/target/classes org.voucherhub.common.DistributedBenchmark --role payer --hub-host 127.0.0.1 --hub-port 9100 --payee-host 127.0.0.1 --payee-port 9101 --runs 500
```

## Notes

- This is a prototype codebase, not a production payment system.
- The cryptographic layer is simplified for experiment and benchmarking purposes.
- The Solidity contract is a prototype artifact and not a production deployment target.
- The off-chain benchmark excludes channel open/close unless a script explicitly measures that path.
