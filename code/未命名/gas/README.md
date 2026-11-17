# Reproducible gas benchmark

This directory contains independent Solidity benchmark contracts and the Python runner used to measure receipt `gasUsed` on an in-memory PyEVM chain.

## Run

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements-gas.txt
.venv/bin/python measure_gas.py --samples 20 --batch-sizes 20,40,60,80,100
.venv/bin/python plot_gas.py
python3 -m unittest discover -s tests -v
```

Outputs are written to `results/`: raw JSON, summary CSV/Markdown, and PDF/PNG figures.

The runner records actual transaction receipt gas rather than estimates. It compiles with Solidity 0.8.20, optimizer enabled with 200 runs, and drops exactly one minimum and one maximum only when reporting the trimmed mean.

## Measurement boundary

- `VoucherHubLifecycleGas`: authenticated commitment-based channel open and ideal close.
- `VoucherHubBatchRedeemGas`: dual-authenticated close, per-voucher hub authorization, nullifier storage, and aggregate-opening verification.
- `AccioNormalChannelGas`: ordinary authenticated channel lifecycle scaffold.
- `AccioHiddenLifecycleGas`: the same lifecycle plus a controlled number of two-pair bn128 precompile calls using valid point-at-infinity identities.

These are reproducible gas benchmark contracts. They are not production contracts and do not claim to implement the paper's complete T-RSUC or DRSign verifier on chain. The pairing-round count and compiler metadata are always written into the raw result so comparisons remain auditable.
