# VoucherHub 完整实验代码整理（2026-11-17）

本包包含协议实现、自动测试、AWS/Tor 实验运行器、结果汇总、Figure 1 画图代码，以及 gas 测量合约和云端运行器。补充了 native/ 下的 Rust/JNI 源码。包内不包含 SSH 私钥、预编译动态库、真实实验原始数据或生成图。


## 目录

- `code/未命名/`：Java 协议原型、自测、Solidity gas benchmark。
- `experiments/voucherhub_evaluation/`：AWS 部署、Tor 实验、汇总与测试。
- `charts/`： Tor 数据画图和 synthetic demo 数据生成器。

## 1. 本地无 native 库的基础检查

```bash
./run_all_checks.sh
```

该命令会运行 Java `SelfTest`、Python runner/gas 单元测试以及脚本语法检查。它不要求本机安装 Rust bn128 动态库。

## 2. 配置 AWS 路径

```bash
export VOUCHERHUB_PROJECT="$PWD"
export VOUCHERHUB_KEY_ROOT="/绝对路径/cloud server_1"
export VOUCHERHUB_NATIVE_LIB="$PWD/native/target/release/libbn_jni.so"
```

`VOUCHERHUB_KEY_ROOT` 中应保存 `tokyo1.pem`、`seoul1.pem`、`singapore1.pem`、`frankfurt1.pem` 和 `virginia1.pem`。私钥不会被复制进代码包。

在目标 Linux 架构环境中先编译 native：`cd native && cargo build --release`，再返回包根目录。本机 macOS 生成的 dylib 不能部署到 Linux。

## 3. 编译并部署 Java/Tor 服务

```bash
cd code/未命名
scripts/run_self_tests.sh
scripts/package_classes.sh
cd ../..

python3 experiments/voucherhub_evaluation/deploy/deploy_new_environment.py
```

部署器会在 payer 节点启动 Tor SOCKS，在每个 payee 上创建 v3 hidden service，把新 onion 地址写回 `config/servers_new.json`，并启动 hub/payee 服务。后续只重启服务可使用：

```bash
python3 experiments/voucherhub_evaluation/deploy/deploy_new_environment.py --services-only
```

## 4. 最小真实 Tor smoke test

```bash
python3 experiments/voucherhub_evaluation/runner/run_single_payer_tor.py \
  --topologies same_city_1to1 \
  --payments 1 \
  --repetitions 1 \
  --output-root work/tor-smoke
```

结果 JSON 必须同时满足：`success=true`、`completed=payments`、`transport="Tor v3 onion via SOCKS5"`。Java 默认禁止 payer-payee 直接 TCP；只有显式加入 `--allow-direct-baseline true` 才能跑带标签的 TCP 对照。

## 5. 完整 Figure 1 Tor 测试

```bash
REPETITIONS=7 bash experiments/voucherhub_evaluation/runner/run_figure1_tor.sh \
  experiments/voucherhub_evaluation/raw/e1_tor_measured
```

正式路径直接测量 onion 通信，不使用“TCP 时间 + Tor 校准差值”。多 payer 场景在共同 gate 后计时，取最慢 worker 的完成时间；100 笔、5-to-1 会分配为每个 payer 20 笔。

只汇总数据：

```bash
python3 experiments/voucherhub_evaluation/runner/summarize_e1_tor.py \
  --raw experiments/voucherhub_evaluation/raw/e1_tor_measured \
  --repetitions 7
```

汇总器会拒绝 TCP 或未标记为真实 Tor 的输入。7 次实验按“去掉一个最小值和一个最大值，再平均剩余 5 次”处理。

## 6. 画图代码（不需要真实数据即可检查）

```bash
python3 -m venv .plot-venv
.plot-venv/bin/pip install -r requirements-plot.txt

mkdir -p work/demo-figures
.plot-venv/bin/python charts/generate_demo_tor_summary.py \
  --output work/demo_tor_summary.csv

E1_TOR_MEASURED_CSV="$PWD/work/demo_tor_summary.csv" \
E1_TOR_FIGURE_DIR="$PWD/work/demo-figures" \
  .plot-venv/bin/python charts/plot_e1_tor_measured.py
```

demo CSV 每行都标记 `SYNTHETIC_DEMO_NOT_EXPERIMENT`，只能用于检查图形布局。正式画图时把 `E1_TOR_MEASURED_CSV` 指向真实汇总 CSV。

## 7. AWS gas 测量

小规模验证：

```bash
python3 experiments/voucherhub_evaluation/deploy/run_gas_on_aws.py \
  --node tokyo5 --samples 2 --batch-sizes 2,3 --pairing-rounds 1 \
  --output work/gas-smoke
```

正式测量：

```bash
python3 experiments/voucherhub_evaluation/deploy/run_gas_on_aws.py \
  --node tokyo5 --samples 20 --batch-sizes 20,40,60,80,100 \
  --pairing-rounds 4 --output work/gas-formal
```

runner 会在 AWS 上创建 Python 环境、编译 Solidity 0.8.20、执行交易并下载 receipt `gasUsed` 的 raw JSON、CSV 和 Markdown。gas benchmark 是可复现的测量 scaffold，包含认证、commitment、nullifier、aggregate opening 和 bn128 precompile 工作量；它不宣称是论文中完整生产级 T-RSUC/DRSign 链上验证器。

## 原包记录的历史 smoke 验证（不是本次重跑）

- Java `SelfTest`：通过。
- Python runner/gas tests：通过。
- Solidity 0.8.20 + optimizer 200 + via-IR：5 个合约/库编译通过。
- AWS tokyo5 gas：open、close、hidden pairing、batch redeem 交易全部通过。
- AWS 真实 Tor：same-city 1-to-1、1 payment 下 VH-CP、VH-VP、Accio 全部通过。
