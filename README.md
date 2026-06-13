# VoucherHub Prototype

This repository contains a Java prototype for the VoucherHub payment-channel experiments.

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

## Requirements

- Java 11 or newer
- Maven, if you want to build through the Maven workflow

If Maven is not available, some helper scripts fall back to `javac` for the `common` module.

## Build

From the repository root:

```bash
mvn -q compile
```

Or compile the shared module through the helper script:

```bash
scripts/compile_common.sh
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

Run a single-payment VoucherHub experiment:

```bash
scripts/run_voucherhub_single.sh 30
```

Run the Accio baseline scaffold:

```bash
scripts/run_accio_baseline.sh 30
```

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
