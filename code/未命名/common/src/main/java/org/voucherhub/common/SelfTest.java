package org.voucherhub.common;

import org.voucherhub.common.crypto.DRSign;
import org.voucherhub.common.crypto.G1Point;
import org.voucherhub.common.crypto.PublicParameters;
import org.voucherhub.common.crypto.Scalar;
import org.voucherhub.common.crypto.SigmaProtocol.OpeningProof;
import org.voucherhub.common.protocol.FairMint;
import org.voucherhub.common.protocol.Model.Channel;
import org.voucherhub.common.protocol.Model.Invoice;
import org.voucherhub.common.protocol.Model.MintRequest;
import org.voucherhub.common.protocol.Model.PreCredential;
import org.voucherhub.common.protocol.Model.ReleasedCredential;
import org.voucherhub.common.protocol.Model.WalletVoucher;
import org.voucherhub.common.protocol.Model.CompletedPayment;
import org.voucherhub.common.protocol.Model.TransferResult;
import org.voucherhub.common.protocol.VoucherHubProtocol;

import java.util.Arrays;
import java.util.Collections;

public final class SelfTest {
    private SelfTest() {
    }

    public static void main(String[] args) {
        pedersenHomomorphism();
        drSignCorrectnessAndFailures();
        redeemAndBatch();
        adaptorSignature();
        paymentCompletionAndVoucherTransfer();
        System.out.println("SelfTest passed");
    }

    private static void paymentCompletionAndVoucherTransfer() {
        VoucherHubProtocol p = new VoucherHubProtocol();
        Channel payer = p.openChannel("payer", 10_000, 10_000);
        CompletedPayment funded = p.completeChannelPayment(payer, "recipient-a", 30, "cp-1");
        assertTrue(p.verifyCompletedPayment(funded), "channel payment completion proof");
        assertTrue(serializedSize(funded) > 0, "completed payment has a concrete wire encoding");

        long batchVersion = payer.version;
        java.util.List<CompletedPayment> batch = p.completeBatchedChannelPayments(payer,
                Arrays.asList("batch-r1", "batch-r2", "batch-r3"), Arrays.asList(2L, 3L, 4L), "cp-batch");
        assertTrue(batch.size() == 3 && payer.version == batchVersion + 1,
                "batched channel payment performs one aggregate channel update");
        for (CompletedPayment output : batch) {
            assertTrue(p.verifyCompletedPayment(output), "batched channel output completion proof");
        }

        TransferResult transferred = p.transferVoucher(funded.voucher, "recipient-b", "vp-1");
        assertTrue(transferred.success && transferred.outputs.size() == 1,
                "voucher transfer succeeds atomically");
        assertTrue(p.verifyCompletedPayment(transferred.outputs.get(0)),
                "voucher transfer completion proof");
        assertTrue(!p.transferVoucher(funded.voucher, "recipient-c", "vp-replay").success,
                "voucher transfer rejects replay");

        CompletedPayment splitInput = p.completeChannelPayment(payer, "holder", 12, "cp-split");
        TransferResult split = p.splitTransferVoucher(splitInput.voucher,
                Arrays.asList("r1", "r2", "r3"), Arrays.asList(3L, 4L, 5L), "vp-split");
        assertTrue(split.success && split.outputs.size() == 3,
                "voucher split transfer preserves value");
        for (CompletedPayment output : split.outputs) {
            assertTrue(p.verifyCompletedPayment(output), "split output completion proof");
        }
        CompletedPayment freshnessInput = p.completeChannelPayment(payer, "fresh-holder", 15,
                "cp-fresh-split");
        TransferResult freshnessSplit = p.splitTransferVoucher(freshnessInput,
                Arrays.asList("fresh-r1", "fresh-r2"), Arrays.asList(6L, 9L), "vp-fresh-split");
        assertTrue(freshnessSplit.success && freshnessSplit.outputs.size() == 2,
                "freshness-certified split verifies one input attestation");
        assertTrue(!p.splitTransferVoucher(freshnessInput, Collections.singletonList("fresh-r3"),
                Collections.singletonList(15L), "vp-fresh-replay").success,
                "freshness-certified split rejects replay");
        CompletedPayment badInput = p.completeChannelPayment(payer, "holder-2", 10, "cp-bad-split");
        TransferResult bad = p.splitTransferVoucher(badInput.voucher,
                Arrays.asList("r1", "r2"), Arrays.asList(4L, 5L), "vp-bad-split");
        assertTrue(!bad.success, "split transfer rejects value creation or loss");
        assertTrue(p.splitTransferVoucher(badInput.voucher, Collections.singletonList("r3"),
                Collections.singletonList(10L), "vp-after-bad").success,
                "failed split does not consume the input voucher");
    }

    private static int serializedSize(Object value) {
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes)) {
                out.writeObject(value);
            }
            return bytes.size();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("wire serialization failed", e);
        }
    }

    private static void pedersenHomomorphism() {
        PublicParameters pp = new PublicParameters();
        G1Point a = pp.commit(Scalar.of(7), Scalar.of(11));
        G1Point b = pp.commit(Scalar.of(5), Scalar.of(13));
        assertTrue(a.add(b).equals(pp.commit(Scalar.of(12), Scalar.of(24))), "Pedersen homomorphism");
    }

    private static void drSignCorrectnessAndFailures() {
        PublicParameters pp = new PublicParameters();
        DRSign dr = new DRSign(pp);
        DRSign.KeyPair key = dr.keyGen();
        G1Point m = pp.G.mul(Scalar.of(42)).add(pp.P.mul(Scalar.of(99)));
        Scalar rb = Scalar.random();
        G1Point mb = m.add(pp.P2.mul(rb));
        DRSign.BlindSignature blind = dr.sign(key.sk, mb);
        assertTrue(dr.bVerify(key.pk, mb, blind), "BVerify valid source");
        assertTrue(!dr.bVerify(key.pk, mb.add(pp.G.mul(Scalar.of(1))), blind), "BVerify invalid source");
        DRSign.TargetCredential target = dr.transform(mb, blind, rb);
        assertTrue(dr.verify(key.pk, target.message, target.signature), "Verify transformed target");
        DRSign.TargetCredential bad = dr.transform(mb, blind, rb.add(Scalar.of(1)));
        assertTrue(!bad.message.equals(m), "wrong rb changes target message");
    }

    private static void redeemAndBatch() {
        VoucherHubProtocol p = new VoucherHubProtocol();
        Channel alice = p.openChannel("alice", 10_000, 10_000);
        Channel bob = p.openChannel("bob", 100, 10_000);
        Invoice invoice = p.makeInvoice(bob.cid, 10);
        MintRequest request = p.makeMintRequest(alice, invoice, false);
        PreCredential pre = p.hubPrepare(alice, request);
        ReleasedCredential credential = p.completeMint(alice, request, pre);
        WalletVoucher voucher = p.bobAccept(invoice, credential);
        OpeningProof proof = p.makeRedeemProof(voucher, bob.cid);
        OpeningProof badProof = new OpeningProof(proof.announcement, proof.zValue.add(Scalar.of(1)), proof.zRandomness);
        assertTrue(!p.redeemWithProof(bob, voucher, bob.cid, null, badProof).success,
                "single redeem rejects malformed Sigma proof");
        assertTrue(p.redeemWithProof(bob, voucher, bob.cid, null, proof).success,
                "single redeem with Sigma proof");
        assertTrue(!p.redeemWithProof(bob, voucher, bob.cid, null, proof).success, "duplicate nullifier");

        VoucherHubProtocol p2 = new VoucherHubProtocol();
        Channel a2 = p2.openChannel("alice", 10_000, 10_000);
        Channel b2 = p2.openChannel("bob", 100, 10_000);
        WalletVoucher v1 = issue(p2, a2, b2, 3);
        WalletVoucher v2 = issue(p2, a2, b2, 4);
        java.util.List<WalletVoucher> batch = Arrays.asList(v1, v2);
        assertTrue(p2.batchRedeemWithProof(b2, batch, p2.makeBatchRedeemProof(batch)).success
                        && b2.userBalance == 107,
                "batch redeem aggregate Sigma proof");
        WalletVoucher wrongCid = issue(p2, a2, b2, 5);
        assertTrue(!p2.redeemWithProof(b2, wrongCid, "wrong", null, p2.makeRedeemProof(wrongCid, b2.cid)).success,
                "wrong cid");
    }

    private static WalletVoucher issue(VoucherHubProtocol p, Channel alice, Channel bob, long amount) {
        Invoice invoice = p.makeInvoice(bob.cid, amount);
        MintRequest request = p.makeMintRequest(alice, invoice, false);
        return p.bobAccept(invoice, p.completeMint(alice, request, p.hubPrepare(alice, request)));
    }

    private static void adaptorSignature() {
        PublicParameters pp = new PublicParameters();
        FairMint fairMint = new FairMint(pp, new DRSign(pp));
        Scalar t = Scalar.random();
        FairMint.AdaptorSignature adaptor = fairMint.aliceMakeAdaptorStateSig("state", pp.G.mul(t));
        Scalar full = fairMint.hubAdaptAliceStateSig(adaptor, t);
        assertTrue(fairMint.verifyAdapted(adaptor, full, t), "adaptor release/adapt");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
