package org.voucherhub.common;

import org.voucherhub.common.protocol.Model.Channel;
import org.voucherhub.common.protocol.Model.Invoice;
import org.voucherhub.common.protocol.Model.MintRequest;
import org.voucherhub.common.protocol.Model.PreCredential;
import org.voucherhub.common.protocol.Model.ReleasedCredential;
import org.voucherhub.common.protocol.Model.WalletVoucher;
import org.voucherhub.common.protocol.VoucherHubProtocol;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public final class DistributedBenchmark {
    private DistributedBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        String role = arg(args, "--role", "payer");
        if ("hub".equals(role)) {
            runHub(Integer.parseInt(arg(args, "--port", "9100")));
        } else if ("payee".equals(role)) {
            runPayee(Integer.parseInt(arg(args, "--port", "9101")));
        } else if ("payer".equals(role)) {
            runPayer(args);
        } else {
            throw new IllegalArgumentException("unknown role: " + role);
        }
    }

    private static void runHub(int port) throws IOException {
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("hub listening on " + port);
            while (true) {
                Socket socket = server.accept();
                Thread worker = new Thread(() -> {
                    VoucherHubProtocol protocol = new VoucherHubProtocol();
                    Channel alice = protocol.openChannel("alice-hub", 100_000_000, 100_000_000);
                    Channel bob = protocol.openChannel("bob-hub", 100_000, 100_000_000);
                    try (Socket s = socket) {
                        handleHubConnection(protocol, alice, bob, s);
                    } catch (IOException e) {
                        System.err.println("hub connection failed: " + e.getMessage());
                    }
                });
                worker.setDaemon(true);
                worker.start();
            }
        }
    }

    private static void handleHubConnection(VoucherHubProtocol protocol, Channel alice, Channel bob, Socket socket)
            throws IOException {
        BufferedReader in = reader(socket);
        BufferedWriter out = writer(socket);
        String line;
        while ((line = in.readLine()) != null) {
            String[] parts = line.split(" ");
            long amount = parts.length > 2 ? Long.parseLong(parts[2]) : 10L;
            long start = System.nanoTime();
            boolean ok = true;
            String status = "ok";
            try {
                if ("VOUCHER_MINT".equals(parts[0])) {
                    voucherMintOnly(protocol, alice, bob.cid, amount);
                } else if ("ACCIO_TX".equals(parts[0])) {
                    accioCompleteTransaction(protocol, alice, bob, amount);
                } else if ("ACCIO_CONFIRM".equals(parts[0])) {
                    // Models the final hub-side confirmation round required before Accio is complete.
                    // Channel open/close is still outside the measured loop.
                } else if ("STOP".equals(parts[0])) {
                    writeLine(out, "OK bye");
                    return;
                } else {
                    ok = false;
                    status = "unknown-command";
                }
            } catch (RuntimeException e) {
                ok = false;
                status = e.getClass().getSimpleName() + ":" + e.getMessage();
            }
            double ms = (System.nanoTime() - start) / 1_000_000.0;
            writeLine(out, (ok ? "OK " : "ERR ") + status + " " + format(ms));
        }
    }

    private static void runPayee(int port) throws IOException {
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("payee listening on " + port);
            while (true) {
                Socket socket = server.accept();
                Thread worker = new Thread(() -> {
                    try (Socket s = socket) {
                        handlePayeeConnection(s);
                    } catch (IOException e) {
                        System.err.println("payee connection failed: " + e.getMessage());
                    }
                });
                worker.setDaemon(true);
                worker.start();
            }
        }
    }

    private static void handlePayeeConnection(Socket socket) throws IOException {
        BufferedReader in = reader(socket);
        BufferedWriter out = writer(socket);
        String line;
        while ((line = in.readLine()) != null) {
            String[] parts = line.split(" ");
            long start = System.nanoTime();
            if ("STOP".equals(parts[0])) {
                writeLine(out, "OK bye");
                return;
            }
            if ("INVOICE".equals(parts[0])) {
                writeLine(out, "OK invoice " + (parts.length > 2 ? parts[2] : "10") + " " + formatSince(start));
            } else if ("VOUCHER_RECEIVE".equals(parts[0])) {
                writeLine(out, "OK accepted " + formatSince(start));
            } else if ("ACCIO_RECEIVE".equals(parts[0])) {
                writeLine(out, "OK settled " + formatSince(start));
            } else if ("ACCIO_FINAL".equals(parts[0])) {
                writeLine(out, "OK finalized " + formatSince(start));
            } else {
                writeLine(out, "ERR unknown-command " + formatSince(start));
            }
        }
    }

    private static void runPayer(String[] args) throws IOException {
        String hubHost = arg(args, "--hub-host", "127.0.0.1");
        int hubPort = Integer.parseInt(arg(args, "--hub-port", "9100"));
        String payeeHost = arg(args, "--payee-host", "127.0.0.1");
        int payeePort = Integer.parseInt(arg(args, "--payee-port", "9101"));
        int runs = Integer.parseInt(arg(args, "--runs", "500"));
        int payerCount = Integer.parseInt(arg(args, "--payer-count", "1"));
        String protocol = arg(args, "--protocol", "both");
        long amount = Long.parseLong(arg(args, "--amount", "10"));
        Path results = Paths.get(arg(args, "--results", "results/distributed_results.csv"));

        Files.createDirectories(results.getParent());
        List<String> rows = Collections.synchronizedList(new ArrayList<>());
        rows.add("protocol,payer_count,run_id,latency_ms,completion_ms,success,hub_response_ms,payee_rounds,blocking_network_messages");

        RunResult voucher = new RunResult(0, 0);
        RunResult accio = new RunResult(0, 0);
        if ("both".equals(protocol) || "voucher".equals(protocol)) {
            voucher = runVoucher(hubHost, hubPort, payeeHost, payeePort, runs, payerCount, amount, rows);
        }
        if ("both".equals(protocol) || "accio".equals(protocol)) {
            accio = runAccio(hubHost, hubPort, payeeHost, payeePort, runs, payerCount, amount, rows);
        }
        Files.write(results, rows, StandardCharsets.UTF_8);

        Path summary = results.getParent().resolve("distributed_summary.json");
        String json = "{\n"
                + "  \"timestamp\": \"" + Instant.now() + "\",\n"
                + "  \"runs\": " + runs + ",\n"
                + "  \"payer_count\": " + payerCount + ",\n"
                + "  \"protocol\": \"" + protocol + "\",\n"
                + "  \"amount\": " + amount + ",\n"
                + "  \"voucher_total_ms\": " + format(voucher.totalMs) + ",\n"
                + "  \"accio_total_ms\": " + format(accio.totalMs) + ",\n"
                + "  \"voucher_failures\": " + voucher.failures + ",\n"
                + "  \"accio_failures\": " + accio.failures + ",\n"
                + "  \"voucher_payee_rounds_per_tx\": 1,\n"
                + "  \"voucher_parallelism\": \"different payers execute concurrently\",\n"
                + "  \"accio_completion\": \"transactions to the same payee are serialized even with multiple payers\",\n"
                + "  \"excludes_channel_open_close\": true\n"
                + "}\n";
        Files.write(summary, json.getBytes(StandardCharsets.UTF_8));
        System.out.println(json);
    }

    private static RunResult runVoucher(String hubHost, int hubPort, String payeeHost, int payeePort,
                                        int runs, int payerCount, long amount, List<String> rows) throws IOException {
        AtomicInteger nextRun = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        long startWall = System.nanoTime();
        for (int payer = 0; payer < payerCount; payer++) {
            int payerId = payer;
            Thread worker = new Thread(() -> {
                try (Socket hub = new Socket(hubHost, hubPort);
                     Socket payee = new Socket(payeeHost, payeePort)) {
                    BufferedReader hubIn = reader(hub);
                    BufferedWriter hubOut = writer(hub);
                    BufferedReader payeeIn = reader(payee);
                    BufferedWriter payeeOut = writer(payee);
                    while (true) {
                        int run = nextRun.getAndIncrement();
                        if (run >= runs) {
                            break;
                        }
                        long start = System.nanoTime();
                        writeLine(hubOut, "VOUCHER_MINT " + run + " " + amount + " " + payerId);
                        String hubResp = hubIn.readLine();
                        writeLine(payeeOut, "VOUCHER_RECEIVE " + run + " " + amount + " " + payerId);
                        String payeeResp = payeeIn.readLine();
                        long end = System.nanoTime();
                        double ms = (end - start) / 1_000_000.0;
                        double completionMs = (end - startWall) / 1_000_000.0;
                        boolean ok = isOk(hubResp) && isOk(payeeResp);
                        if (!ok) {
                            failures.incrementAndGet();
                        }
                        rows.add("VoucherHub," + payerCount + "," + run + "," + format(ms) + ","
                                + format(completionMs) + "," + ok + ","
                                + responseMs(hubResp) + ",1,2");
                    }
                } catch (IOException e) {
                    failures.incrementAndGet();
                    System.err.println("voucher payer worker failed: " + e.getMessage());
                    rows.add("VoucherHub," + payerCount + ",-1,0,0,false,0,1,2");
                }
            });
            workers.add(worker);
            worker.start();
        }
        joinAll(workers);
        return new RunResult((System.nanoTime() - startWall) / 1_000_000.0, failures.get());
    }

    private static RunResult runAccio(String hubHost, int hubPort, String payeeHost, int payeePort,
                                      int runs, int payerCount, long amount, List<String> rows) throws IOException {
        try (Socket hub = new Socket(hubHost, hubPort);
             Socket payee = new Socket(payeeHost, payeePort)) {
            BufferedReader hubIn = reader(hub);
            BufferedWriter hubOut = writer(hub);
            BufferedReader payeeIn = reader(payee);
            BufferedWriter payeeOut = writer(payee);
            int failures = 0;
            long startWall = System.nanoTime();
            for (int i = 0; i < runs; i++) {
                int payerId = i % Math.max(1, payerCount);
                long start = System.nanoTime();
                writeLine(payeeOut, "INVOICE " + i + " " + amount + " " + payerId);
                String invoiceResp = payeeIn.readLine();
                writeLine(hubOut, "ACCIO_TX " + i + " " + amount + " " + payerId);
                String hubResp = hubIn.readLine();
                writeLine(payeeOut, "ACCIO_RECEIVE " + i + " " + amount + " " + payerId);
                String payeeResp = payeeIn.readLine();
                writeLine(hubOut, "ACCIO_CONFIRM " + i + " " + amount + " " + payerId);
                String confirmResp = hubIn.readLine();
                writeLine(payeeOut, "ACCIO_FINAL " + i + " " + amount + " " + payerId);
                String finalResp = payeeIn.readLine();
                long end = System.nanoTime();
                double ms = (end - start) / 1_000_000.0;
                double completionMs = (end - startWall) / 1_000_000.0;
                boolean ok = isOk(invoiceResp) && isOk(hubResp) && isOk(payeeResp)
                        && isOk(confirmResp) && isOk(finalResp);
                if (!ok) {
                    failures++;
                }
                rows.add("Accio," + payerCount + "," + i + "," + format(ms) + ","
                        + format(completionMs) + "," + ok + ","
                        + responseMs(hubResp) + ",3,5");
            }
            return new RunResult((System.nanoTime() - startWall) / 1_000_000.0, failures);
        }
    }

    private static void joinAll(List<Thread> workers) throws IOException {
        for (Thread worker : workers) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for worker threads", e);
            }
        }
    }

    private static void voucherMintOnly(VoucherHubProtocol protocol, Channel alice, String bobCid, long amount) {
        Invoice invoice = protocol.makeInvoice(bobCid, amount);
        MintRequest request = protocol.makeMintRequest(alice, invoice, false);
        PreCredential pre = protocol.hubPrepare(alice, request);
        protocol.completeMint(alice, request, pre);
    }

    private static void accioCompleteTransaction(VoucherHubProtocol protocol, Channel alice, Channel bob, long amount) {
        Invoice invoice = protocol.makeInvoice(bob.cid, amount);
        MintRequest request = protocol.makeMintRequest(alice, invoice, false);
        PreCredential pre = protocol.hubPrepare(alice, request);
        ReleasedCredential credential = protocol.completeMint(alice, request, pre);
        WalletVoucher voucher = protocol.bobAccept(invoice, credential);
        if (!protocol.redeem(bob, voucher, bob.cid, null).success) {
            throw new IllegalStateException("accio redeem failed");
        }
    }

    private static BufferedReader reader(Socket socket) throws IOException {
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    }

    private static BufferedWriter writer(Socket socket) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    private static void writeLine(BufferedWriter out, String line) throws IOException {
        out.write(line);
        out.newLine();
        out.flush();
    }

    private static boolean isOk(String response) {
        return response != null && response.startsWith("OK ");
    }

    private static String responseMs(String response) {
        if (response == null) {
            return "0";
        }
        String[] parts = response.split(" ");
        return parts.length == 0 ? "0" : parts[parts.length - 1];
    }

    private static String formatSince(long start) {
        return format((System.nanoTime() - start) / 1_000_000.0);
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static String arg(String[] args, String name, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return defaultValue;
    }

    private static final class RunResult {
        private final double totalMs;
        private final int failures;

        private RunResult(double totalMs, int failures) {
            this.totalMs = totalMs;
            this.failures = failures;
        }
    }
}
