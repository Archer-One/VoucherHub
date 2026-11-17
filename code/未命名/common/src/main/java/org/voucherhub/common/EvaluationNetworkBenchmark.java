package org.voucherhub.common;

import org.voucherhub.common.EvaluationWire.Request;
import org.voucherhub.common.EvaluationWire.Response;
import org.voucherhub.common.protocol.Model.Channel;
import org.voucherhub.common.protocol.Model.ChannelState;
import org.voucherhub.common.protocol.Model.BatchResult;
import org.voucherhub.common.protocol.Model.CompletedPayment;
import org.voucherhub.common.protocol.Model.TransferResult;
import org.voucherhub.common.protocol.Model.WalletVoucher;
import org.voucherhub.common.protocol.VoucherHubProtocol;
import org.voucherhub.common.crypto.SigmaProtocol.OpeningProof;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class EvaluationNetworkBenchmark {
    private EvaluationNetworkBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        String role = arg(args, "--role", "payer");
        if ("hub".equals(role)) {
            runHub(Integer.parseInt(arg(args, "--port", "9100")));
        } else if ("payee".equals(role)) {
            runPayee(Integer.parseInt(arg(args, "--port", "9101")), arg(args, "--cid", "payee"),
                    arg(args, "--hub-host", "127.0.0.1"),
                    Integer.parseInt(arg(args, "--hub-port", "19100")));
        } else if ("payer".equals(role)) {
            runPayer(args);
        } else {
            throw new IllegalArgumentException("unknown role: " + role);
        }
    }

    private static void runHub(int port) throws IOException {
        VoucherHubProtocol.warmBulletproof();
        VoucherHubProtocol protocol = new VoucherHubProtocol();
        Map<String, Channel> payeeChannels = new ConcurrentHashMap<>();
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("evaluation hub listening on " + port);
            while (true) {
                Socket socket = server.accept();
                Thread worker = new Thread(() -> handleHub(protocol, payeeChannels, socket));
                worker.setDaemon(true);
                worker.start();
            }
        }
    }

    private static void handleHub(VoucherHubProtocol protocol, Map<String, Channel> payeeChannels,
                                  Socket socket) {
        Channel payer = protocol.openChannel("payer-" + socket.getPort(), 100_000_000L, 100_000_000L);
        try {
            socket.setTcpNoDelay(true);
        } catch (IOException e) {
            throw new IllegalStateException("failed to enable TCP_NODELAY for hub connection", e);
        }
        try (Socket ignored = socket;
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
            out.flush();
            while (true) {
                Request request;
                try {
                    request = (Request) in.readObject();
                } catch (EOFException e) {
                    return;
                }
                long start = System.nanoTime();
                Response response;
                try {
                    synchronized (protocol) {
                        response = handleHubRequest(protocol, payer, payeeChannels, request, start);
                    }
                } catch (RuntimeException e) {
                    response = new Response(false, e.getClass().getSimpleName() + ":" + e.getMessage(),
                            Collections.emptyList(), protocol.hubPublicKey(), System.nanoTime() - start);
                }
                out.writeObject(response);
                out.flush();
                out.reset();
            }
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("hub connection failed: " + e.getMessage());
        }
    }

    private static Response handleHubRequest(VoucherHubProtocol protocol, Channel payer,
                                             Map<String, Channel> payeeChannels,
                                             Request request, long start) {
        List<CompletedPayment> outputs = new ArrayList<>();
        ChannelState channelState = null;
        if ("CP_SINGLE".equals(request.command) || "ACCIO".equals(request.command)) {
            outputs.add(protocol.completeChannelPayment(payer, request.recipients.get(0),
                    request.amounts.get(0), request.operationId));
        } else if ("CP_BATCH".equals(request.command) || "VP_PREPARE".equals(request.command)) {
            outputs.addAll(protocol.completeBatchedChannelPayments(payer, request.recipients, request.amounts,
                    request.operationId));
        } else if ("VP_TRANSFER".equals(request.command)) {
            if (request.payments.size() != 1) {
                throw new IllegalArgumentException("VP_TRANSFER requires one freshness-certified input voucher");
            }
            TransferResult result = protocol.splitTransferVoucher(request.payments.get(0), request.recipients,
                    request.amounts, request.operationId);
            if (!result.success) {
                return new Response(false, result.reason, result.outputs, protocol.hubPublicKey(),
                        System.nanoTime() - start);
            }
            outputs.addAll(result.outputs);
        } else if ("BATCH_REDEEM".equals(request.command)) {
            if (request.recipients.size() != 1 || request.payments.isEmpty()
                    || request.openingProof == null) {
                throw new IllegalArgumentException("BATCH_REDEEM requires cid, vouchers and proof");
            }
            String cid = request.recipients.get(0);
            Channel payee = payeeChannels.computeIfAbsent(cid,
                    key -> protocol.openChannel(key, 0L, 100_000_000L));
            List<WalletVoucher> vouchers = new ArrayList<>();
            for (CompletedPayment payment : request.payments) {
                if (!cid.equals(payment.voucher.invoice.cidB)) {
                    throw new IllegalArgumentException("mixed recipient batch");
                }
                vouchers.add(payment.voucher);
            }
            BatchResult result = protocol.batchRedeemWithProof(payee, vouchers, request.openingProof);
            if (!result.success) {
                throw new IllegalStateException("batch redeem failed");
            }
            channelState = protocol.signChannelState(payee, result.accepted, result.totalAmount);
        } else if ("ACCIO_FULL".equals(request.command)) {
            String cid = request.recipients.get(0);
            CompletedPayment payment = protocol.completeChannelPayment(payer, cid,
                    request.amounts.get(0), request.operationId);
            Channel payee = payeeChannels.computeIfAbsent(cid,
                    key -> protocol.openChannel(key, 0L, 100_000_000L));
            OpeningProof proof = protocol.makeRedeemProof(payment.voucher, cid);
            if (!protocol.redeemWithProof(payee, payment.voucher, cid, null, proof).success) {
                throw new IllegalStateException("Accio recipient-channel update failed");
            }
            channelState = protocol.signChannelState(payee, 1, payment.voucher.invoice.amount);
        } else {
            throw new IllegalArgumentException("unknown command " + request.command);
        }
        return new Response(true, "ok", outputs, protocol.hubPublicKey(), System.nanoTime() - start,
                Collections.emptyList(), Collections.emptyList(), channelState);
    }

    private static void runPayee(int port, String cid, String hubHost, int hubPort) throws IOException {
        Map<String, PendingRedeemGroup> redeemGroups = new ConcurrentHashMap<>();
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress("127.0.0.1", port));
            System.out.println("evaluation payee " + cid + " listening on " + port);
            while (true) {
                Socket socket = server.accept();
                Thread worker = new Thread(() -> handlePayee(socket, cid, hubHost, hubPort, redeemGroups));
                worker.setDaemon(true);
                worker.start();
            }
        }
    }

    private static void handlePayee(Socket socket, String cid, String hubHost, int hubPort,
                                    Map<String, PendingRedeemGroup> redeemGroups) {
        VoucherHubProtocol verifier = new VoucherHubProtocol();
        try {
            socket.setTcpNoDelay(true);
        } catch (IOException e) {
            throw new IllegalStateException("failed to enable TCP_NODELAY for payee connection", e);
        }
        try (Socket ignored = socket;
             Peer hub = Peer.direct(hubHost, hubPort);
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
            out.flush();
            while (true) {
                Request request;
                try {
                    request = (Request) in.readObject();
                } catch (EOFException e) {
                    return;
                }
                long start = System.nanoTime();
                boolean groupedRedeem = "DELIVER_REDEEM_GROUP".equals(request.command);
                boolean valid = ("DELIVER".equals(request.command)
                        || "DELIVER_REDEEM".equals(request.command) || groupedRedeem)
                        && request.hubPublicKey != null;
                if ("REQUEST_BATCH".equals(request.command)) {
                    int count = Math.toIntExact(request.amounts.get(0));
                    long amount = request.amounts.get(1);
                    List<String> recipients = new ArrayList<>();
                    List<Long> amounts = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        recipients.add(cid);
                        amounts.add(amount);
                    }
                    Response response = new Response(true, "requested", Collections.emptyList(),
                            null, System.nanoTime() - start, recipients, amounts, null);
                    out.writeObject(response);
                    out.flush();
                    out.reset();
                    continue;
                }
                if ("VERIFY_CHANNEL_STATE".equals(request.command)) {
                    valid = request.channelState != null && request.hubPublicKey != null
                            && verifier.verifyChannelState(request.channelState, request.hubPublicKey)
                            && cid.equals(request.channelState.cid);
                } else if (valid) {
                    for (CompletedPayment payment : request.payments) {
                        valid &= cid.equals(payment.voucher.invoice.cidB)
                                && verifier.verifyCompletedPayment(payment, request.hubPublicKey);
                    }
                    if (valid && ("DELIVER_REDEEM".equals(request.command) || groupedRedeem)) {
                        if (groupedRedeem) {
                            int expectedPayers = Math.toIntExact(request.amounts.get(0));
                            PendingRedeemGroup group = redeemGroups.computeIfAbsent(request.operationId,
                                    key -> new PendingRedeemGroup(expectedPayers));
                            Response credited = group.submit(request.payments, cid, request.hubPublicKey,
                                    verifier, hub);
                            valid = credited.success && credited.channelState != null
                                    && verifier.verifyChannelState(credited.channelState, request.hubPublicKey);
                            Response response = new Response(valid,
                                    valid ? "group credited" : "invalid grouped channel successor",
                                    Collections.emptyList(), request.hubPublicKey,
                                    System.nanoTime() - start, Collections.emptyList(),
                                    Collections.emptyList(), credited.channelState);
                            out.writeObject(response);
                            out.flush();
                            out.reset();
                            if (group.finished()) {
                                redeemGroups.remove(request.operationId, group);
                            }
                            continue;
                        }
                        List<WalletVoucher> vouchers = new ArrayList<>();
                        long total = 0;
                        for (CompletedPayment payment : request.payments) {
                            vouchers.add(payment.voucher);
                            total += payment.voucher.invoice.amount;
                        }
                        OpeningProof proof = verifier.makeBatchRedeemProof(vouchers);
                        Request redeem = new Request("BATCH_REDEEM", request.operationId,
                                Collections.singletonList(cid), Collections.emptyList(), null,
                                request.payments, request.hubPublicKey, proof);
                        Response credited = hub.exchange(redeem);
                        valid = credited.success && credited.channelState != null
                                && verifier.verifyChannelState(credited.channelState, request.hubPublicKey)
                                && cid.equals(credited.channelState.cid)
                                && credited.channelState.creditedVouchers == vouchers.size()
                                && credited.channelState.creditedAmount == total;
                        Response response = new Response(valid,
                                valid ? "credited" : "invalid channel successor",
                                Collections.emptyList(), request.hubPublicKey,
                                System.nanoTime() - start, Collections.emptyList(),
                                Collections.emptyList(), credited.channelState);
                        out.writeObject(response);
                        out.flush();
                        out.reset();
                        continue;
                    }
                }
                Response response = new Response(valid, valid ? "accepted" : "invalid payment evidence",
                        Collections.emptyList(), request.hubPublicKey, System.nanoTime() - start);
                out.writeObject(response);
                out.flush();
                out.reset();
            }
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("payee connection failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("payee grouped redemption interrupted");
        }
    }

    private static final class PendingRedeemGroup {
        private final int expectedPayers;
        private final List<CompletedPayment> payments = new ArrayList<>();
        private int arrivals;
        private Response credited;

        private PendingRedeemGroup(int expectedPayers) {
            if (expectedPayers < 2) {
                throw new IllegalArgumentException("grouped redemption requires at least two payers");
            }
            this.expectedPayers = expectedPayers;
        }

        synchronized Response submit(List<CompletedPayment> batch, String cid,
                                     org.voucherhub.common.crypto.G2Point hubPublicKey,
                                     VoucherHubProtocol verifier, Peer hub)
                throws IOException, ClassNotFoundException, InterruptedException {
            payments.addAll(batch);
            arrivals++;
            if (arrivals > expectedPayers) {
                throw new IllegalStateException("too many grouped redemption arrivals");
            }
            if (arrivals == expectedPayers) {
                List<WalletVoucher> vouchers = new ArrayList<>();
                for (CompletedPayment payment : payments) {
                    vouchers.add(payment.voucher);
                }
                OpeningProof proof = verifier.makeBatchRedeemProof(vouchers);
                Request redeem = new Request("BATCH_REDEEM", "group-redeem|" + cid,
                        Collections.singletonList(cid), Collections.emptyList(), null,
                        payments, hubPublicKey, proof);
                credited = hub.exchange(redeem);
                notifyAll();
            }
            while (credited == null) {
                wait();
            }
            return credited;
        }

        synchronized boolean finished() {
            return credited != null;
        }
    }

    private static void runPayer(String[] args) throws Exception {
        String protocolName = arg(args, "--protocol", "vh-cp");
        int payments = Integer.parseInt(arg(args, "--payments", "10"));
        String hubHost = arg(args, "--hub-host", "127.0.0.1");
        int hubPort = Integer.parseInt(arg(args, "--hub-port", "9100"));
        String[] onions = arg(args, "--payee-onions", "").split(",");
        String transport = arg(args, "--transport", "tor");
        boolean allowDirectBaseline = Boolean.parseBoolean(arg(args, "--allow-direct-baseline", "false"));
        String[] directHosts = arg(args, "--payee-hosts", "").split(",");
        String[] directPorts = arg(args, "--payee-ports", "9101").split(",");
        String[] cids = arg(args, "--payee-cids", "payee").split(",");
        String socksHost = arg(args, "--socks-host", "127.0.0.1");
        int socksPort = Integer.parseInt(arg(args, "--socks-port", "9050"));
        Path results = Paths.get(arg(args, "--results", "results/evaluation_network.json"));
        String readyPath = arg(args, "--ready-file", "");
        String gatePath = arg(args, "--gate-file", "");
        long gateTimeoutSeconds = Long.parseLong(arg(args, "--gate-timeout-seconds", "600"));
        int expectedPayers = Integer.parseInt(arg(args, "--expected-payers", "1"));
        String groupId = arg(args, "--group-id", "single-payer");
        boolean direct = "direct".equals(transport);
        if (!direct && !"tor".equals(transport)) {
            throw new IllegalArgumentException("transport must be tor or direct");
        }
        if (direct && !allowDirectBaseline) {
            throw new IllegalArgumentException(
                    "payer-payee direct TCP is disabled; pass --allow-direct-baseline true only for a labelled baseline");
        }
        if (!direct) {
            for (String onion : onions) {
                if (!onion.matches("[a-z2-7]{56}\\.onion")) {
                    throw new IllegalArgumentException("Tor requires a v3 .onion hostname for every payee");
                }
            }
        }
        String[] endpoints = direct ? directHosts : onions;
        if (endpoints.length != cids.length || endpoints.length == 0 || endpoints[0].isEmpty()) {
            throw new IllegalArgumentException("payee endpoint and cid lists must be non-empty and equal length");
        }
        if (direct && directPorts.length != 1 && directPorts.length != directHosts.length) {
            throw new IllegalArgumentException("direct payee ports must contain one port or one per host");
        }
        if (results.getParent() != null && !Files.isDirectory(results.getParent())) {
            Files.createDirectories(results.getParent());
        }

        try (Peer hub = Peer.direct(hubHost, hubPort)) {
            List<Peer> payees = new ArrayList<>();
            try {
                for (int index = 0; index < endpoints.length; index++) {
                    if (direct) {
                        int portIndex = directPorts.length == 1 ? 0 : index;
                        payees.add(Peer.direct(directHosts[index], Integer.parseInt(directPorts[portIndex])));
                    } else {
                        payees.add(Peer.socks(onions[index], 9101, socksHost, socksPort));
                    }
                }
                RunResult result = execute(protocolName, payments, hub, payees, Arrays.asList(cids),
                        readyPath, gatePath, gateTimeoutSeconds, expectedPayers, groupId);
                String json = result.json(protocolName, payments, endpoints.length,
                        direct ? "Direct TCP" : "Tor v3 onion via SOCKS5");
                Files.write(results, json.getBytes(StandardCharsets.UTF_8));
                System.out.println(json);
                if (!result.success) {
                    throw new IllegalStateException("network benchmark failed");
                }
            } finally {
                for (Peer payee : payees) {
                    payee.close();
                }
            }
        }
    }

    private static void waitForStartGate(String readyPath, String gatePath, long timeoutSeconds)
            throws IOException, InterruptedException {
        if (gatePath.isEmpty()) {
            return;
        }
        Path gate = Paths.get(gatePath);
        if (!readyPath.isEmpty()) {
            Path ready = Paths.get(readyPath);
            if (ready.getParent() != null) {
                Files.createDirectories(ready.getParent());
            }
            Files.write(ready, Instant.now().toString().getBytes(StandardCharsets.UTF_8));
        }
        long deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L;
        while (!Files.exists(gate)) {
            if (System.nanoTime() >= deadline) {
                throw new IOException("timed out waiting for start gate " + gate);
            }
            Thread.sleep(5L);
        }
    }

    private static RunResult execute(String protocolName, int count, Peer hub, List<Peer> payees,
                                     List<String> cids, String readyPath, String gatePath,
                                     long gateTimeoutSeconds, int expectedPayers,
                                     String groupId) throws Exception {
        if ("accio-credit".equals(protocolName)) {
            waitForStartGate(readyPath, gatePath, gateTimeoutSeconds);
            return executeSequentialAccioCredit(count, hub, payees, cids);
        }
        if ("vh-cp-credit".equals(protocolName)) {
            waitForStartGate(readyPath, gatePath, gateTimeoutSeconds);
            return executeBatchedChannelCredit(count, hub, payees, cids, expectedPayers, groupId);
        }
        if ("accio".equals(protocolName)) {
            waitForStartGate(readyPath, gatePath, gateTimeoutSeconds);
            return executeSequentialAccio(count, hub, payees, cids);
        }
        if ("vh-cp".equals(protocolName)) {
            waitForStartGate(readyPath, gatePath, gateTimeoutSeconds);
            return executeBatchedChannelPayments(count, hub, payees, cids);
        }
        boolean voucherCredit = "vh-vp-credit".equals(protocolName);
        if (!"vh-vp".equals(protocolName) && !voucherCredit) {
            throw new IllegalArgumentException("unknown protocol " + protocolName);
        }

        long bytes = 0;
        Request prepare = new Request("VP_PREPARE", "prepare",
                Collections.singletonList("prepared-holder"), Collections.singletonList(10L * count),
                null, null, null);
        Response response = hub.exchange(prepare);
        if (!response.success || response.payments.size() != 1) {
            return new RunResult(false, 0, 0, 0, "preparation failed: " + response.reason);
        }
        CompletedPayment prepared = response.payments.get(0);

        // Input vouchers are intentionally prepared before the shared online
        // start gate, matching the VH-VP measurement boundary in the protocol.
        waitForStartGate(readyPath, gatePath, gateTimeoutSeconds);
        if (voucherCredit) {
            return executeBatchedVoucherCredit(count, hub, payees, cids, expectedPayers,
                    groupId, prepared);
        }
        long start = System.nanoTime();
        int completed = 0;
        long serviceNanos = 0;
        List<List<CompletedPayment>> deliveries = emptyDeliveries(payees.size());
        List<String> outputCids = new ArrayList<>();
        List<Long> outputAmounts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            outputCids.add(cids.get(i % cids.size()));
            outputAmounts.add(10L);
        }
        Request transfer = new Request("VP_TRANSFER", "vh-vp-batch",
                outputCids, outputAmounts, null, Collections.singletonList(prepared), null);
        bytes += serializedSize(transfer);
        Response hubResponse = hub.exchange(transfer);
        bytes += serializedSize(hubResponse);
        serviceNanos += hubResponse.serviceNanos;
        if (!hubResponse.success || hubResponse.payments.size() != count) {
            return new RunResult(false, completed, System.nanoTime() - start, bytes,
                    "hub failed: " + hubResponse.reason);
        }
        for (int i = 0; i < count; i++) {
            deliveries.get(i % payees.size()).add(hubResponse.payments.get(i));
        }
        for (int i = 0; i < deliveries.size(); i++) {
            if (deliveries.get(i).isEmpty()) {
                continue;
            }
            Response accepted = deliverBatch(payees.get(i), deliveries.get(i), hubResponse.hubPublicKey,
                    "vh-vp-deliver-" + i);
            bytes += acceptedBytes;
            serviceNanos += accepted.serviceNanos;
            if (!accepted.success) {
                return new RunResult(false, completed, System.nanoTime() - start, bytes,
                        "payee rejected: " + accepted.reason);
            }
            completed += deliveries.get(i).size();
        }
        return new RunResult(completed == count, completed, System.nanoTime() - start, bytes,
                "service_nanos=" + serviceNanos);
    }

    private static long acceptedBytes;

    private static Response deliver(Peer payee, CompletedPayment payment,
                                    org.voucherhub.common.crypto.G2Point hubPublicKey,
                                    String operationId) throws Exception {
        Request delivery = new Request("DELIVER", operationId, null, null, null,
                Collections.singletonList(payment), hubPublicKey);
        Response accepted = payee.exchange(delivery);
        acceptedBytes = serializedSize(delivery) + serializedSize(accepted);
        return accepted;
    }

    private static Response deliverBatch(Peer payee, List<CompletedPayment> payments,
                                         org.voucherhub.common.crypto.G2Point hubPublicKey,
                                         String operationId) throws Exception {
        Request delivery = new Request("DELIVER", operationId, null, null, null, payments, hubPublicKey);
        Response accepted = payee.exchange(delivery);
        acceptedBytes = serializedSize(delivery) + serializedSize(accepted);
        return accepted;
    }

    private static List<List<CompletedPayment>> emptyDeliveries(int count) {
        List<List<CompletedPayment>> deliveries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            deliveries.add(new ArrayList<>());
        }
        return deliveries;
    }

    private static RunResult executeSequentialAccio(int count, Peer hub, List<Peer> payees,
                                                    List<String> cids) throws Exception {
        long start = System.nanoTime();
        int completed = 0;
        long bytes = 0;
        long serviceNanos = 0;
        for (int i = 0; i < count; i++) {
            int payeeIndex = i % payees.size();
            String cid = cids.get(payeeIndex);
            Request request = new Request("ACCIO", "accio-" + i,
                    Collections.singletonList(cid), Collections.singletonList(10L), null, null, null);
            bytes += serializedSize(request);
            Response hubResponse = hub.exchange(request);
            bytes += serializedSize(hubResponse);
            serviceNanos += hubResponse.serviceNanos;
            if (!hubResponse.success || hubResponse.payments.size() != 1) {
                return new RunResult(false, completed, System.nanoTime() - start, bytes,
                        "hub failed: " + hubResponse.reason);
            }
            Response accepted = deliver(payees.get(payeeIndex), hubResponse.payments.get(0),
                    hubResponse.hubPublicKey, request.operationId);
            bytes += acceptedBytes;
            serviceNanos += accepted.serviceNanos;
            if (!accepted.success) {
                return new RunResult(false, completed, System.nanoTime() - start, bytes,
                        "payee rejected: " + accepted.reason);
            }
            completed++;
        }
        return new RunResult(completed == count, completed, System.nanoTime() - start, bytes,
                "service_nanos=" + serviceNanos);
    }

    private static RunResult executeSequentialAccioCredit(int count, Peer hub, List<Peer> payees,
                                                          List<String> cids) throws Exception {
        long start = System.nanoTime();
        int completed = 0;
        long bytes = 0;
        long serviceNanos = 0;
        for (int i = 0; i < count; i++) {
            int payeeIndex = i % payees.size();
            Peer payee = payees.get(payeeIndex);
            Response requestBatch = payee.exchange(new Request("REQUEST_BATCH", "accio-request-" + i,
                    null, Arrays.asList(1L, 10L), null, null, null));
            if (!requestBatch.success || requestBatch.recipients.size() != 1) {
                return new RunResult(false, completed, System.nanoTime() - start, bytes,
                        "payee request failed");
            }
            Request update = new Request("ACCIO_FULL", "accio-credit-" + i,
                    requestBatch.recipients, requestBatch.amounts, null, null, null);
            bytes += serializedSize(update);
            Response credited = hub.exchange(update);
            bytes += serializedSize(credited);
            serviceNanos += credited.serviceNanos;
            if (!credited.success || credited.channelState == null) {
                return new RunResult(false, completed, System.nanoTime() - start, bytes,
                        "Accio hub update failed: " + credited.reason);
            }
            Request verify = new Request("VERIFY_CHANNEL_STATE", "accio-verify-" + i,
                    null, null, null, null, credited.hubPublicKey, null, credited.channelState);
            Response accepted = payee.exchange(verify);
            bytes += serializedSize(verify) + serializedSize(accepted);
            serviceNanos += accepted.serviceNanos;
            if (!accepted.success) {
                return new RunResult(false, completed, System.nanoTime() - start, bytes,
                        "Accio payee rejected state: " + accepted.reason);
            }
            completed++;
        }
        return new RunResult(completed == count, completed, System.nanoTime() - start, bytes,
                "boundary=verified_channel_successor;service_nanos=" + serviceNanos);
    }

    private static RunResult executeBatchedChannelCredit(int count, Peer hub, List<Peer> payees,
                                                         List<String> cids, int expectedPayers,
                                                         String groupId) throws Exception {
        if (payees.size() != cids.size() || payees.isEmpty()) {
            throw new IllegalArgumentException("payee and cid lists must be non-empty and equal");
        }
        if (expectedPayers > 1 && payees.size() != 1) {
            throw new IllegalArgumentException("grouped multi-payer redemption requires one payee");
        }
        long start = System.nanoTime();
        long bytes = 0;
        long serviceNanos = 0;
        List<String> recipients = new ArrayList<>();
        List<Long> amounts = new ArrayList<>();
        int base = count / payees.size();
        int extra = count % payees.size();
        for (int index = 0; index < payees.size(); index++) {
            int requested = base + (index < extra ? 1 : 0);
            if (requested == 0) {
                continue;
            }
            Response paymentRequest = payees.get(index).exchange(new Request("REQUEST_BATCH",
                    "vh-request|" + groupId + "|" + index, null,
                    Arrays.asList((long) requested, 10L), null, null, null));
            if (!paymentRequest.success || paymentRequest.recipients.size() != requested
                    || paymentRequest.amounts.size() != requested) {
                return new RunResult(false, 0, System.nanoTime() - start, bytes,
                        "payee batch request failed");
            }
            recipients.addAll(paymentRequest.recipients);
            amounts.addAll(paymentRequest.amounts);
        }
        Request mint = new Request("CP_BATCH", "vh-cp-credit-batch|" + groupId,
                recipients, amounts, null, null, null);
        bytes += serializedSize(mint);
        Response minted = hub.exchange(mint);
        bytes += serializedSize(minted);
        serviceNanos += minted.serviceNanos;
        if (!minted.success || minted.payments.size() != count) {
            return new RunResult(false, 0, System.nanoTime() - start, bytes,
                    "batch mint failed: " + minted.reason);
        }
        List<List<CompletedPayment>> deliveries = emptyDeliveries(payees.size());
        for (CompletedPayment payment : minted.payments) {
            int index = cids.indexOf(payment.voucher.invoice.cidB);
            if (index < 0) {
                return new RunResult(false, 0, System.nanoTime() - start, bytes,
                        "unknown output recipient");
            }
            deliveries.get(index).add(payment);
        }
        boolean success = true;
        int completed = 0;
        List<Long> versions = new ArrayList<>();
        for (int index = 0; index < payees.size(); index++) {
            if (deliveries.get(index).isEmpty()) {
                continue;
            }
            String command = expectedPayers > 1 ? "DELIVER_REDEEM_GROUP" : "DELIVER_REDEEM";
            List<Long> groupMetadata = expectedPayers > 1
                    ? Collections.singletonList((long) expectedPayers) : Collections.emptyList();
            Request deliver = new Request(command, "vh-cp-credit-group|" + groupId,
                    null, groupMetadata, null, deliveries.get(index), minted.hubPublicKey);
            bytes += serializedSize(deliver);
            Response credited = payees.get(index).exchange(deliver);
            bytes += serializedSize(credited);
            serviceNanos += credited.serviceNanos;
            boolean valid = credited.success && credited.channelState != null;
            if (expectedPayers == 1) {
                valid &= credited.channelState.creditedVouchers == deliveries.get(index).size();
            }
            success &= valid;
            if (valid) {
                completed += deliveries.get(index).size();
                versions.add(credited.channelState.version);
            }
        }
        success &= completed == count;
        return new RunResult(success, success ? count : 0, System.nanoTime() - start, bytes,
                "boundary=verified_channel_successor;channel_versions=" + versions
                        + ";service_nanos=" + serviceNanos);
    }

    private static RunResult executeBatchedVoucherCredit(int count, Peer hub, List<Peer> payees,
                                                         List<String> cids, int expectedPayers,
                                                         String groupId, CompletedPayment prepared)
            throws Exception {
        if (payees.size() != cids.size() || payees.isEmpty()) {
            throw new IllegalArgumentException("payee and cid lists must be non-empty and equal");
        }
        if (expectedPayers > 1 && payees.size() != 1) {
            throw new IllegalArgumentException("grouped multi-payer redemption requires one payee");
        }
        long start = System.nanoTime();
        long bytes = 0;
        long serviceNanos = 0;
        List<String> recipients = new ArrayList<>();
        List<Long> amounts = new ArrayList<>();
        int base = count / payees.size();
        int extra = count % payees.size();
        for (int index = 0; index < payees.size(); index++) {
            int requested = base + (index < extra ? 1 : 0);
            if (requested == 0) {
                continue;
            }
            Response paymentRequest = payees.get(index).exchange(new Request("REQUEST_BATCH",
                    "vh-vp-request|" + groupId + "|" + index, null,
                    Arrays.asList((long) requested, 10L), null, null, null));
            if (!paymentRequest.success || paymentRequest.recipients.size() != requested
                    || paymentRequest.amounts.size() != requested) {
                return new RunResult(false, 0, System.nanoTime() - start, bytes,
                        "payee batch request failed");
            }
            recipients.addAll(paymentRequest.recipients);
            amounts.addAll(paymentRequest.amounts);
        }
        Request transfer = new Request("VP_TRANSFER", "vh-vp-credit-batch|" + groupId,
                recipients, amounts, null, Collections.singletonList(prepared), null);
        bytes += serializedSize(transfer);
        Response transferred = hub.exchange(transfer);
        bytes += serializedSize(transferred);
        serviceNanos += transferred.serviceNanos;
        if (!transferred.success || transferred.payments.size() != count) {
            return new RunResult(false, 0, System.nanoTime() - start, bytes,
                    "batch transfer failed: " + transferred.reason);
        }
        List<List<CompletedPayment>> deliveries = emptyDeliveries(payees.size());
        for (CompletedPayment payment : transferred.payments) {
            int index = cids.indexOf(payment.voucher.invoice.cidB);
            if (index < 0) {
                return new RunResult(false, 0, System.nanoTime() - start, bytes,
                        "unknown output recipient");
            }
            deliveries.get(index).add(payment);
        }
        boolean success = true;
        int completed = 0;
        List<Long> versions = new ArrayList<>();
        for (int index = 0; index < payees.size(); index++) {
            if (deliveries.get(index).isEmpty()) {
                continue;
            }
            String command = expectedPayers > 1 ? "DELIVER_REDEEM_GROUP" : "DELIVER_REDEEM";
            List<Long> groupMetadata = expectedPayers > 1
                    ? Collections.singletonList((long) expectedPayers) : Collections.emptyList();
            Request deliver = new Request(command, "vh-vp-credit-group|" + groupId,
                    null, groupMetadata, null, deliveries.get(index), transferred.hubPublicKey);
            bytes += serializedSize(deliver);
            Response credited = payees.get(index).exchange(deliver);
            bytes += serializedSize(credited);
            serviceNanos += credited.serviceNanos;
            boolean valid = credited.success && credited.channelState != null;
            if (expectedPayers == 1) {
                valid &= credited.channelState.creditedVouchers == deliveries.get(index).size();
            }
            success &= valid;
            if (valid) {
                completed += deliveries.get(index).size();
                versions.add(credited.channelState.version);
            }
        }
        success &= completed == count;
        return new RunResult(success, success ? count : 0, System.nanoTime() - start, bytes,
                "boundary=verified_channel_successor;channel_versions=" + versions
                        + ";service_nanos=" + serviceNanos);
    }

    private static RunResult executeBatchedChannelPayments(int count, Peer hub, List<Peer> payees,
                                                           List<String> cids) throws Exception {
        List<String> recipients = new ArrayList<>();
        List<Long> amounts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            recipients.add(cids.get(i % cids.size()));
            amounts.add(10L);
        }
        Request request = new Request("CP_BATCH", "vh-cp-batch", recipients, amounts,
                null, null, null);
        long start = System.nanoTime();
        long bytes = serializedSize(request);
        Response hubResponse = hub.exchange(request);
        bytes += serializedSize(hubResponse);
        long serviceNanos = hubResponse.serviceNanos;
        if (!hubResponse.success || hubResponse.payments.size() != count) {
            return new RunResult(false, 0, System.nanoTime() - start, bytes,
                    "hub failed: " + hubResponse.reason);
        }
        int completed = 0;
        List<List<CompletedPayment>> deliveries = emptyDeliveries(payees.size());
        for (int i = 0; i < count; i++) {
            int payeeIndex = i % payees.size();
            deliveries.get(payeeIndex).add(hubResponse.payments.get(i));
        }
        for (int i = 0; i < deliveries.size(); i++) {
            if (deliveries.get(i).isEmpty()) {
                continue;
            }
            Response accepted = deliverBatch(payees.get(i), deliveries.get(i),
                    hubResponse.hubPublicKey, "vh-cp-deliver-" + i);
            bytes += acceptedBytes;
            serviceNanos += accepted.serviceNanos;
            if (!accepted.success) {
                return new RunResult(false, completed, System.nanoTime() - start, bytes,
                        "payee rejected: " + accepted.reason);
            }
            completed += deliveries.get(i).size();
        }
        return new RunResult(completed == count, completed, System.nanoTime() - start, bytes,
                "service_nanos=" + serviceNanos);
    }

    private static long serializedSize(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        return bytes.size();
    }

    private static String arg(String[] args, String name, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return defaultValue;
    }

    private static final class Peer implements AutoCloseable {
        final Socket socket;
        final ObjectOutputStream out;
        final ObjectInputStream in;

        private Peer(Socket socket) throws IOException {
            this.socket = socket;
            this.socket.setTcpNoDelay(true);
            this.socket.setSoTimeout(120_000);
            this.out = new ObjectOutputStream(socket.getOutputStream());
            this.out.flush();
            this.in = new ObjectInputStream(socket.getInputStream());
        }

        static Peer direct(String host, int port) throws IOException {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 30_000);
            return new Peer(socket);
        }

        static Peer socks(String host, int port, String socksHost, int socksPort) throws IOException {
            Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(socksHost, socksPort));
            Socket socket = new Socket(proxy);
            socket.connect(InetSocketAddress.createUnresolved(host, port), 60_000);
            return new Peer(socket);
        }

        Response exchange(Request request) throws IOException, ClassNotFoundException {
            out.writeObject(request);
            out.flush();
            out.reset();
            return (Response) in.readObject();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static final class RunResult {
        final boolean success;
        final int completed;
        final long totalNanos;
        final long bytes;
        final String detail;

        RunResult(boolean success, int completed, long totalNanos, long bytes, String detail) {
            this.success = success;
            this.completed = completed;
            this.totalNanos = totalNanos;
            this.bytes = bytes;
            this.detail = detail;
        }

        String json(String protocol, int payments, int payees, String transport) {
            return String.format(Locale.ROOT,
                    "{\n  \"timestamp\": \"%s\",\n  \"protocol\": \"%s\",\n  \"payments\": %d,\n"
                            + "  \"payees\": %d,\n  \"completed\": %d,\n  \"success\": %s,\n"
                            + "  \"total_ms\": %.6f,\n  \"communication_bytes\": %d,\n  \"transport\": \"%s\",\n"
                            + "  \"detail\": \"%s\"\n}\n",
                    Instant.now(), protocol, payments, payees, completed, Boolean.toString(success),
                    totalNanos / 1_000_000.0, bytes, transport, detail.replace("\"", "'"));
        }
    }
}
