package org.voucherhub.common;

import org.voucherhub.common.protocol.Model.Channel;
import org.voucherhub.common.protocol.Model.CompletedPayment;
import org.voucherhub.common.protocol.Model.ProtocolMetrics;
import org.voucherhub.common.protocol.Model.TransferResult;
import org.voucherhub.common.protocol.VoucherHubProtocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class BatchingBenchmark {
    private static final long AMOUNT = 10L;

    private BatchingBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        int samples = Integer.parseInt(arg(args, "--samples", "20"));
        int warmups = Integer.parseInt(arg(args, "--warmups", "3"));
        Path results = Paths.get(arg(args, "--results", "results/batching_raw.csv"));
        if (results.getParent() != null && !Files.isDirectory(results.getParent())) {
            Files.createDirectories(results.getParent());
        }

        List<String> rows = new ArrayList<>();
        rows.add("experiment,mode,k,sample,total_ms,proof_generation_ms,proof_verification_ms,hub_signature_ms,communication_bytes,communication_rounds,success");
        for (int k : Arrays.asList(1, 3, 5)) {
            for (int i = 0; i < warmups; i++) {
                runChannelMint("sequential", k, -1);
                runChannelMint("batched", k, -1);
                runVoucherTransfer("sequential", k, -1);
                runVoucherTransfer("batched", k, -1);
            }
            for (int sample = 0; sample < samples; sample++) {
                rows.add(runChannelMint("sequential", k, sample).csv());
                rows.add(runChannelMint("batched", k, sample).csv());
                rows.add(runVoucherTransfer("sequential", k, sample).csv());
                rows.add(runVoucherTransfer("batched", k, sample).csv());
            }
        }
        Files.write(results, rows, StandardCharsets.UTF_8);
        System.out.println("wrote " + (rows.size() - 1) + " samples to " + results);
    }

    private static Sample runChannelMint(String mode, int k, int sample) throws IOException {
        VoucherHubProtocol protocol = new VoucherHubProtocol();
        Channel payer = protocol.openChannel("cp-payer", 1_000_000, 1_000_000);
        List<String> recipients = recipients(k, "cp-recipient-");
        List<Long> amounts = amounts(k);
        protocol.resetMetrics();
        long start = System.nanoTime();
        List<CompletedPayment> outputs = new ArrayList<>();
        if ("sequential".equals(mode)) {
            for (int i = 0; i < k; i++) {
                outputs.add(protocol.completeChannelPayment(payer, recipients.get(i), amounts.get(i),
                        "cp-seq-" + sample + "-" + i));
            }
        } else {
            outputs.addAll(protocol.completeBatchedChannelPayments(payer, recipients, amounts,
                    "cp-batch-" + sample));
        }
        boolean success = verifyAll(protocol, outputs);
        double totalMs = millisSince(start);
        ProtocolMetrics metrics = protocol.metrics();
        long bytes = wireBytes(recipients, amounts) + wireBytes(outputs);
        int rounds = "sequential".equals(mode) ? 2 * k : 1 + k;
        return new Sample("vchmint", mode, k, sample, totalMs, metrics, bytes, rounds, success);
    }

    private static Sample runVoucherTransfer(String mode, int k, int sample) throws IOException {
        VoucherHubProtocol protocol = new VoucherHubProtocol();
        Channel source = protocol.openChannel("vp-source", 1_000_000, 1_000_000);
        CompletedPayment input = protocol.completeChannelPayment(source, "vp-holder", AMOUNT * k,
                "vp-prepare-" + sample);
        List<String> recipients = recipients(k, "vp-recipient-");
        List<Long> amounts = amounts(k);
        protocol.resetMetrics();
        long start = System.nanoTime();
        List<CompletedPayment> outputs = new ArrayList<>();
        boolean success;
        long bytes;
        int rounds;
        if ("sequential".equals(mode)) {
            List<String> holders = recipients(k, "vp-intermediate-");
            TransferResult split = protocol.splitTransferVoucher(input.voucher, holders, amounts,
                    "vp-pre-split-" + sample);
            success = split.success;
            bytes = wireBytes(input.voucher, holders, amounts, split.outputs);
            if (success) {
                for (int i = 0; i < k; i++) {
                    TransferResult transfer = protocol.transferVoucher(split.outputs.get(i).voucher,
                            recipients.get(i), "vp-seq-transfer-" + sample + "-" + i);
                    success &= transfer.success;
                    if (transfer.success) {
                        outputs.addAll(transfer.outputs);
                    }
                    bytes += wireBytes(split.outputs.get(i).voucher, recipients.get(i), transfer.outputs);
                }
            }
            rounds = 1 + 2 * k;
        } else {
            TransferResult transfer = protocol.splitTransferVoucher(input.voucher, recipients, amounts,
                    "vp-batch-transfer-" + sample);
            success = transfer.success;
            outputs.addAll(transfer.outputs);
            bytes = wireBytes(input.voucher, recipients, amounts, transfer.outputs);
            rounds = 1 + k;
        }
        success &= outputs.size() == k && verifyAll(protocol, outputs);
        double totalMs = millisSince(start);
        ProtocolMetrics metrics = protocol.metrics();
        return new Sample("vchsplittransfer", mode, k, sample, totalMs, metrics, bytes, rounds, success);
    }

    private static boolean verifyAll(VoucherHubProtocol protocol, List<CompletedPayment> payments) {
        boolean valid = true;
        for (CompletedPayment payment : payments) {
            valid &= protocol.verifyCompletedPayment(payment);
        }
        return valid;
    }

    private static List<String> recipients(int k, String prefix) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            values.add(prefix + i);
        }
        return values;
    }

    private static List<Long> amounts(int k) {
        List<Long> values = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            values.add(AMOUNT);
        }
        return values;
    }

    private static long wireBytes(Object... values) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            for (Object value : values) {
                out.writeObject(value);
            }
        }
        return bytes.size();
    }

    private static double millisSince(long start) {
        return (System.nanoTime() - start) / 1_000_000.0;
    }

    private static String arg(String[] args, String name, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return defaultValue;
    }

    private static final class Sample {
        final String experiment;
        final String mode;
        final int k;
        final int sample;
        final double totalMs;
        final ProtocolMetrics metrics;
        final long bytes;
        final int rounds;
        final boolean success;

        Sample(String experiment, String mode, int k, int sample, double totalMs,
               ProtocolMetrics metrics, long bytes, int rounds, boolean success) {
            this.experiment = experiment;
            this.mode = mode;
            this.k = k;
            this.sample = sample;
            this.totalMs = totalMs;
            this.metrics = metrics;
            this.bytes = bytes;
            this.rounds = rounds;
            this.success = success;
        }

        String csv() {
            return String.format(Locale.ROOT, "%s,%s,%d,%d,%.6f,%.6f,%.6f,%.6f,%d,%d,%s",
                    experiment, mode, k, sample, totalMs,
                    metrics.proofGenerationNanos / 1_000_000.0,
                    metrics.proofVerificationNanos / 1_000_000.0,
                    metrics.hubSignatureNanos / 1_000_000.0,
                    bytes, rounds, Boolean.toString(success));
        }
    }
}
