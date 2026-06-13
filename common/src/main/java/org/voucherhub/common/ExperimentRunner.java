package org.voucherhub.common;

import org.voucherhub.common.metrics.Metrics;
import org.voucherhub.common.protocol.Model.BatchResult;
import org.voucherhub.common.protocol.Model.Channel;
import org.voucherhub.common.protocol.Model.Invoice;
import org.voucherhub.common.protocol.Model.MintRequest;
import org.voucherhub.common.protocol.Model.PreCredential;
import org.voucherhub.common.protocol.Model.ReleasedCredential;
import org.voucherhub.common.protocol.Model.WalletVoucher;
import org.voucherhub.common.protocol.VoucherHubProtocol;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ExperimentRunner {
    private ExperimentRunner() {
    }

    public static void main(String[] args) throws Exception {
        String mode = arg(args, "--mode", "voucherhub-single");
        int runs = Integer.parseInt(arg(args, "--runs", "30"));
        Path results = Paths.get(arg(args, "--results", "results"));
        switch (mode) {
            case "voucherhub-single":
                runSingle(runs, results.resolve("voucherhub_single.csv"), results.resolve("summary.json"), false);
                break;
            case "voucherhub-delayed":
                runSingle(runs, results.resolve("voucherhub_delayed.csv"), results.resolve("summary.json"), true);
                break;
            case "voucherhub-batch":
                runBatch(runs, results.resolve("voucherhub_batch.csv"), results.resolve("summary.json"));
                break;
            case "voucherhub-griefing":
                runGriefing(results.resolve("voucherhub_griefing.csv"));
                break;
            case "accio-baseline":
                runAccioBaseline(runs, results.resolve("accio_baseline.csv"), results.resolve("summary.json"));
                break;
            case "gas":
                writeGas(results.resolve("gas.json"));
                break;
            default:
                throw new IllegalArgumentException("unknown mode: " + mode);
        }
    }

    public static void runSingle(int runs, Path csv, Path summary, boolean delayedRedeem) throws IOException {
        Metrics metrics = new Metrics();
        for (int i = 0; i < runs; i++) {
            VoucherHubProtocol p = new VoucherHubProtocol();
            Channel alice = p.openChannel("alice-hub", 1_000_000, 1_000_000);
            Channel bob = p.openChannel("bob-hub", 100_000, 1_000_000);
            int runId = i;
            Invoice invoice = metrics.time("VoucherHub", delayedRedeem ? "delayed" : "single", "payee",
                    "invoice_generate", 1, runId, 192, 0, () -> p.makeInvoice(bob.cid, 10 + runId));
            MintRequest request = metrics.time("VoucherHub", delayedRedeem ? "delayed" : "single", "payer",
                    "voucher_message_and_debit_commitment", 1, runId, 896, 0, () -> p.makeMintRequest(alice, invoice, false));
            metrics.record("VoucherHub", delayedRedeem ? "delayed" : "single", "payer",
                    "amount_validity_proof_generate_stub", 1, runId, 0.02, 512, 0, true);
            PreCredential pre = metrics.time("VoucherHub", delayedRedeem ? "delayed" : "single", "hub",
                    "DRSign.Sign_and_proof_verify", 1, runId, 512, 0, () -> p.hubPrepare(alice, request));
            ReleasedCredential credential = metrics.time("VoucherHub", delayedRedeem ? "delayed" : "single", "payer",
                    "fair_mint_verify_adapt_finalize", 1, runId, 448, 0, () -> p.completeMint(alice, request, pre));
            WalletVoucher voucher = metrics.time("VoucherHub", delayedRedeem ? "delayed" : "single", "payee",
                    "DRSign.Transform_Verify_receive", 1, runId, 704, 0, () -> p.bobAccept(invoice, credential));
            if (!delayedRedeem) {
                metrics.time("VoucherHub", "single", "hub", "redeem_verify_update", 1, runId, 768, 0,
                        () -> p.redeem(bob, voucher, bob.cid, null));
            } else {
                metrics.time("VoucherHub", "delayed", "hub", "redeem_only_delayed", 1, runId, 768, 0,
                        () -> p.redeem(bob, voucher, bob.cid, null));
            }
        }
        metrics.writeCsv(csv);
        metrics.writeSummary(summary, "VoucherHub");
    }

    public static void runBatch(int runs, Path csv, Path summary) throws IOException {
        Metrics metrics = new Metrics();
        List<Integer> sizes = Arrays.asList(1, 2, 4, 8, 16, 32);
        for (int size : sizes) {
            for (int run = 0; run < runs; run++) {
                VoucherHubProtocol p = new VoucherHubProtocol();
                Channel alice = p.openChannel("alice-hub", 10_000_000, 10_000_000);
                Channel bob = p.openChannel("bob-hub", 100_000, 10_000_000);
                List<WalletVoucher> vouchers = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    Invoice invoice = p.makeInvoice(bob.cid, 10 + i);
                    MintRequest request = p.makeMintRequest(alice, invoice, false);
                    PreCredential pre = p.hubPrepare(alice, request);
                    ReleasedCredential credential = p.completeMint(alice, request, pre);
                    vouchers.add(p.bobAccept(invoice, credential));
                }
                int runId = run;
                metrics.time("VoucherHub", "batch", "hub", "batch_redeem_verify_aggregate_update",
                        size, runId, 768L * size, 0, () -> p.batchRedeem(bob, vouchers));
            }
        }
        metrics.writeCsv(csv);
        metrics.writeSummary(summary, "VoucherHub batch");
    }

    public static void runGriefing(Path csv) throws IOException {
        Metrics metrics = new Metrics();
        String[] scenarios = {
                "payer_abort_after_invoice",
                "hub_refuses_release_t",
                "malformed_C_x",
                "invalid_credential",
                "duplicate_redeem",
                "wrong_cid_B",
                "wrong_r_id",
                "delayed_batch"
        };
        int run = 0;
        for (String scenario : scenarios) {
            boolean success = executeGriefingScenario(scenario);
            metrics.record("VoucherHub", scenario, "integration", "griefing_assertion",
                    1, run++, 0.1, 0, 0, success);
        }
        metrics.writeCsv(csv);
    }

    private static boolean executeGriefingScenario(String scenario) {
        VoucherHubProtocol p = new VoucherHubProtocol();
        Channel alice = p.openChannel("alice-hub", 1_000_000, 1_000_000);
        Channel bob = p.openChannel("bob-hub", 100_000, 1_000_000);
        try {
            if ("payer_abort_after_invoice".equals(scenario)) {
                p.makeInvoice(bob.cid, 10);
                return alice.userBalance == 1_000_000 && bob.userBalance == 100_000;
            }
            Invoice invoice = p.makeInvoice(bob.cid, 10);
            MintRequest request = p.makeMintRequest(alice, invoice, "malformed_C_x".equals(scenario));
            if ("malformed_C_x".equals(scenario)) {
                try {
                    p.hubPrepare(alice, request);
                    return false;
                } catch (IllegalArgumentException expected) {
                    return true;
                }
            }
            PreCredential pre = p.hubPrepare(alice, request);
            if ("hub_refuses_release_t".equals(scenario)) {
                return alice.userBalance == 1_000_000;
            }
            ReleasedCredential credential = p.completeMint(alice, request, pre);
            WalletVoucher voucher = p.bobAccept(invoice, credential);
            if ("invalid_credential".equals(scenario)) {
                return !p.redeem(bob, voucher, bob.cid, invoice.rId.add(org.voucherhub.common.crypto.Scalar.of(1))).success;
            }
            if ("duplicate_redeem".equals(scenario)) {
                return p.redeem(bob, voucher, bob.cid, null).success && !p.redeem(bob, voucher, bob.cid, null).success;
            }
            if ("wrong_cid_B".equals(scenario)) {
                return !p.redeem(bob, voucher, "other-channel", null).success;
            }
            if ("wrong_r_id".equals(scenario)) {
                return !p.redeem(bob, voucher, bob.cid, invoice.rId.add(org.voucherhub.common.crypto.Scalar.of(2))).success;
            }
            if ("delayed_batch".equals(scenario)) {
                List<WalletVoucher> vouchers = new ArrayList<>();
                vouchers.add(voucher);
                for (int i = 0; i < 3; i++) {
                    Invoice next = p.makeInvoice(bob.cid, 11 + i);
                    MintRequest r = p.makeMintRequest(alice, next, false);
                    vouchers.add(p.bobAccept(next, p.completeMint(alice, r, p.hubPrepare(alice, r))));
                }
                BatchResult result = p.batchRedeem(bob, vouchers);
                return result.success && result.accepted == 4;
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static void runAccioBaseline(int runs, Path csv, Path summary) throws IOException {
        Metrics metrics = new Metrics();
        for (int run = 0; run < runs; run++) {
            int runId = run;
            metrics.record("Accio", "baseline", "payer", "hidden_state_update_simulated", 1, runId, 0.08, 640, 0, true);
            metrics.record("Accio", "baseline", "hub", "randomizable_signature_verify_simulated", 1, runId, 0.06, 512, 0, true);
            metrics.record("Accio", "baseline", "payee", "payee_state_receive_simulated", 1, runId, 0.03, 256, 0, true);
        }
        metrics.writeCsv(csv);
        metrics.writeSummary(summary, "Accio baseline prototype");
    }

    private static void writeGas(Path path) throws IOException {
        java.nio.file.Files.createDirectories(path.getParent());
        String json = "{\n"
                + "  \"prototype_limitation\": \"Solidity bn128 verifier not deployed in this iteration.\",\n"
                + "  \"open_channel\": 0,\n"
                + "  \"normal_close\": 0,\n"
                + "  \"dispute_close\": 0,\n"
                + "  \"single_claim\": 0,\n"
                + "  \"batch_claim\": 0,\n"
                + "  \"pairing_verify\": 0,\n"
                + "  \"nullifier_write\": 0\n"
                + "}\n";
        java.nio.file.Files.write(path, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String arg(String[] args, String name, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return defaultValue;
    }
}
