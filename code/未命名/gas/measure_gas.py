#!/usr/bin/env python3
"""Compile, execute and summarize the VoucherHub gas benchmark contracts."""

import argparse
import csv
import json
from pathlib import Path
import statistics


HERE = Path(__file__).resolve().parent
SOURCE = HERE / "contracts" / "VoucherHubGasBenchmarks.sol"


def summarize(values):
    values = [int(value) for value in values]
    ordered = sorted(values)
    trimmed = ordered[1:-1] if len(ordered) >= 3 else ordered
    return {
        "samples": len(values), "min": min(values), "max": max(values),
        "mean": statistics.mean(values), "median": statistics.median(values),
        "trimmed_mean": statistics.mean(trimmed),
    }


def signature_bytes(account, digest, key):
    signed = account._sign_hash(digest, private_key=key)
    return signed.r.to_bytes(32, "big") + signed.s.to_bytes(32, "big") + bytes([signed.v])


def compile_contracts(solcx, version):
    installed = {str(item) for item in solcx.get_installed_solc_versions()}
    if version not in installed:
        solcx.install_solc(version)
    source = SOURCE.read_text(encoding="utf-8")
    compiled = solcx.compile_standard({
        "language": "Solidity",
        "sources": {SOURCE.name: {"content": source}},
        "settings": {
            "optimizer": {"enabled": True, "runs": 200},
            "viaIR": True,
            "outputSelection": {"*": {"*": ["abi", "evm.bytecode.object"]}},
        },
    }, solc_version=version)
    return compiled["contracts"][SOURCE.name]


def deploy(w3, artifact, sender):
    contract = w3.eth.contract(abi=artifact["abi"],
                               bytecode=artifact["evm"]["bytecode"]["object"])
    receipt = w3.eth.wait_for_transaction_receipt(
        contract.constructor().transact({"from": sender, "gas": 30_000_000}))
    return w3.eth.contract(address=receipt.contractAddress, abi=artifact["abi"])


def transact(w3, function, sender):
    receipt = w3.eth.wait_for_transaction_receipt(
        function.transact({"from": sender, "gas": 30_000_000}))
    if receipt.status != 1:
        try:
            function.call({"from": sender, "gas": 30_000_000})
        except Exception as exc:
            raise RuntimeError(f"gas benchmark transaction reverted: {exc}") from exc
        raise RuntimeError("gas benchmark transaction reverted without a reason")
    return int(receipt.gasUsed)


def signed_lifecycle(w3, account, keys, contract, cid, user, hub, balance, blinding,
                     open_name="openBlindChannel", close_name="closeIdeal", pairing_rounds=None):

    commit = contract.functions.commitment(balance, blinding).call()
    digest = contract.functions.openDigest(cid, user, hub, commit).call()
    user_sig = signature_bytes(account, digest, keys[user])
    hub_sig = signature_bytes(account, digest, keys[hub])
    open_args = [cid, user, hub, balance, blinding, user_sig, hub_sig]
    if pairing_rounds is not None:
        open_args.append(pairing_rounds)
    open_gas = transact(w3, getattr(contract.functions, open_name)(*open_args), user)

    final_balance, final_blinding, version = balance - 10, blinding + 1, 1
    final_commit = contract.functions.commitment(final_balance, final_blinding).call()
    close_digest = contract.functions.closeDigest(cid, version, final_commit).call()
    close_user_sig = signature_bytes(account, close_digest, keys[user])
    close_hub_sig = signature_bytes(account, close_digest, keys[hub])
    close_args = [cid, version, final_balance, final_blinding, close_user_sig, close_hub_sig]
    if pairing_rounds is not None:
        close_args.append(pairing_rounds)
    close_gas = transact(w3, getattr(contract.functions, close_name)(*close_args), user)
    return open_gas, close_gas


def run(args):
    try:
        import solcx
        from eth_account import Account
        from eth_tester import EthereumTester, PyEVMBackend
        from web3 import Web3
        from web3.providers.eth_tester import EthereumTesterProvider
    except ImportError as exc:
        raise SystemExit(f"missing dependency: {exc}; install requirements-gas.txt")

    backend = PyEVMBackend()
    tester = EthereumTester(backend=backend)
    w3 = Web3(EthereumTesterProvider(tester))
    accounts = w3.eth.accounts
    keys = {address: backend.account_keys[index] for index, address in enumerate(accounts)}
    user, hub = accounts[1], accounts[2]
    artifacts = compile_contracts(solcx, args.solc_version)
    lifecycle = deploy(w3, artifacts["VoucherHubLifecycleGas"], accounts[0])
    batch = deploy(w3, artifacts["VoucherHubBatchRedeemGas"], accounts[0])
    normal = deploy(w3, artifacts["AccioNormalChannelGas"], accounts[0])
    hidden = deploy(w3, artifacts["AccioHiddenLifecycleGas"], accounts[0])

    raw = {"metadata": {
        "solc_version": args.solc_version, "optimizer": True, "optimizer_runs": 200,
        "backend": "eth-tester/PyEVM", "samples_per_operation": args.samples,
        "pairing_rounds": args.pairing_rounds,
        "scope": "reproducible benchmark scaffold; not a full production T-RSUC/DRSign verifier",
    }, "lifecycle": {}, "batch_redeem": {}}

    cases = [
        ("voucherhub", lifecycle, "openBlindChannel", "closeIdeal", None),
        ("accio_normal", normal, "openBlindChannel", "closeIdeal", None),
        ("accio_hidden", hidden, "openHidden", "closeHidden", args.pairing_rounds),
    ]
    for label, contract, open_name, close_name, rounds in cases:
        opens, closes = [], []
        for sample in range(args.samples):
            cid = w3.keccak(text=f"{label}-{sample}")
            open_gas, close_gas = signed_lifecycle(
                w3, Account, keys, contract, cid, user, hub, 1_000_000, sample + 100,
                open_name, close_name, rounds)
            opens.append(open_gas)
            closes.append(close_gas)
        raw["lifecycle"][label] = {"open": opens, "close": closes}

    for size in args.batch_sizes:
        samples = []
        for sample in range(args.samples):
            cid = w3.keccak(text=f"batch-{size}-{sample}")
            balance, blinding = 1_000_000, sample + 1000
            commit = batch.functions.commitment(balance, blinding).call()
            digest = batch.functions.openDigest(cid, user, hub, commit).call()
            open_user_sig = signature_bytes(Account, digest, keys[user])
            open_hub_sig = signature_bytes(Account, digest, keys[hub])
            transact(w3, batch.functions.openBlindChannel(
                cid, user, hub, balance, blinding, open_user_sig, open_hub_sig), user)
            vouchers = []
            total_amount = total_blinding = 0
            for index in range(size):
                nullifier = w3.keccak(text=f"nullifier-{size}-{sample}-{index}")
                amount = 10
                voucher_blinding = sample * 10000 + index + 1
                voucher_digest = batch.functions.voucherDigest(
                    cid, nullifier, amount, voucher_blinding, user).call()
                voucher_sig = signature_bytes(Account, voucher_digest, keys[hub])
                vouchers.append((nullifier, amount, voucher_blinding, user, voucher_sig))
                total_amount += amount
                total_blinding += voucher_blinding
            aggregate = batch.functions.batchCommitment(total_amount, total_blinding).call()
            close_digest = batch.functions.batchCloseDigest(cid, 1, aggregate, size).call()
            user_sig = signature_bytes(Account, close_digest, keys[user])
            hub_sig = signature_bytes(Account, close_digest, keys[hub])
            samples.append(transact(w3, batch.functions.closeWithBatchRedeem(
                cid, 1, aggregate, vouchers, user_sig, hub_sig), user))
        raw["batch_redeem"][str(size)] = samples
    return raw


def write_outputs(raw, output):
    output.mkdir(parents=True, exist_ok=True)
    (output / "gas_raw.json").write_text(json.dumps(raw, indent=2) + "\n", encoding="utf-8")
    rows = []
    for protocol, operations in raw["lifecycle"].items():
        for operation, values in operations.items():
            rows.append({"category": "lifecycle", "protocol": protocol,
                         "operation": operation, "batch_size": "", **summarize(values)})
    for size, values in raw["batch_redeem"].items():
        rows.append({"category": "batch_redeem", "protocol": "voucherhub",
                     "operation": "close", "batch_size": size, **summarize(values)})
    fields = ["category", "protocol", "operation", "batch_size", "samples",
              "min", "max", "mean", "median", "trimmed_mean"]
    with (output / "gas_summary.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
    lines = ["# Gas benchmark summary", "", "| Category | Protocol | Operation | Batch | Median gas | Trimmed mean |",
             "|---|---|---|---:|---:|---:|"]
    for row in rows:
        lines.append(f"| {row['category']} | {row['protocol']} | {row['operation']} | "
                     f"{row['batch_size'] or '-'} | {row['median']:.1f} | {row['trimmed_mean']:.1f} |")
    lines += ["", "Scope: benchmark contracts include authenticated lifecycle operations, nullifier storage,",
              "aggregate-opening checks and controlled bn128 pairing work. They are not a full production",
              "implementation of the paper's T-RSUC/DRSign verifier."]
    (output / "gas_summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--samples", type=int, default=20)
    parser.add_argument("--batch-sizes", default="20,40,60,80,100")
    parser.add_argument("--pairing-rounds", type=int, default=4)
    parser.add_argument("--solc-version", default="0.8.20")
    parser.add_argument("--output", type=Path, default=HERE / "results")
    args = parser.parse_args()
    args.batch_sizes = [int(value) for value in args.batch_sizes.split(",")]
    write_outputs(run(args), args.output)
    print(args.output)


if __name__ == "__main__":
    main()
