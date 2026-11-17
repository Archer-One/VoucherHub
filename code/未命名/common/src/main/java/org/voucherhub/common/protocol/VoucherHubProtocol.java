package org.voucherhub.common.protocol;

import encryption.bulletproof.BulletproofGens;
import encryption.bulletproof.PedersenCommitment;
import encryption.bulletproof.Point;
import encryption.bulletproof.RangeProof;
import encryption.bulletproof.Transcript;
import org.voucherhub.common.crypto.DRSign;
import org.voucherhub.common.crypto.G1Point;
import org.voucherhub.common.crypto.PublicParameters;
import org.voucherhub.common.crypto.Scalar;
import org.voucherhub.common.crypto.SigmaProtocol;
import org.voucherhub.common.crypto.SigmaProtocol.OpeningProof;
import org.voucherhub.common.protocol.Model.BatchResult;
import org.voucherhub.common.protocol.Model.Channel;
import org.voucherhub.common.protocol.Model.ChannelState;
import org.voucherhub.common.protocol.Model.CompletedPayment;
import org.voucherhub.common.protocol.Model.FreshnessProof;
import org.voucherhub.common.protocol.Model.Invoice;
import org.voucherhub.common.protocol.Model.MintRequest;
import org.voucherhub.common.protocol.Model.PreCredential;
import org.voucherhub.common.protocol.Model.ReleasedCredential;
import org.voucherhub.common.protocol.Model.RedeemResult;
import org.voucherhub.common.protocol.Model.TransferResult;
import org.voucherhub.common.protocol.Model.ProtocolMetrics;
import org.voucherhub.common.protocol.Model.WalletVoucher;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class VoucherHubProtocol {
    private static final int RANGE_BITS = 64;
    private static final boolean BULLETPROOF_ENABLED =
            "bulletproof".equals(System.getProperty("voucherhub.rangeproof.backend", "disabled"));
    public final PublicParameters pp = new PublicParameters();
    public final DRSign drSign = new DRSign(pp);
    public final DRSign.KeyPair hubKey = drSign.keyGen();
    public final FairMint fairMint = new FairMint(pp, drSign);
    public final SigmaProtocol sigma = new SigmaProtocol(pp);
    private final Set<String> spentNullifiers = new HashSet<>();
    private long hubSignatureNanos;
    private long proofGenerationNanos;
    private long proofVerificationNanos;

    public org.voucherhub.common.crypto.G2Point hubPublicKey() {
        return hubKey.pk;
    }

    public ChannelState signChannelState(Channel channel, int creditedVouchers, long creditedAmount) {
        G1Point statement = channelStateStatement(channel.cid, channel.version, channel.userBalance,
                channel.hubBalance, creditedVouchers, creditedAmount, channel.cUser, channel.cHub);
        DRSign.BlindSignature blind = drSign.sign(hubKey.sk, statement);
        DRSign.TargetCredential target = drSign.transform(statement, blind, Scalar.of(0));
        return new ChannelState(channel.cid, channel.version, channel.userBalance, channel.hubBalance,
                creditedVouchers, creditedAmount, channel.cUser, channel.cHub,
                target.message, target.signature);
    }

    public boolean verifyChannelState(ChannelState state,
                                      org.voucherhub.common.crypto.G2Point expectedHubKey) {
        G1Point expected = channelStateStatement(state.cid, state.version, state.userBalance,
                state.hubBalance, state.creditedVouchers, state.creditedAmount,
                state.cUser, state.cHub);
        return expected.equals(state.statement)
                && drSign.verify(expectedHubKey, state.statement, state.signature);
    }

    private G1Point channelStateStatement(String cid, long version, long userBalance,
                                          long hubBalance, int creditedVouchers, long creditedAmount,
                                          G1Point cUser, G1Point cHub) {
        return G1Point.basis("channel-state|" + cid + "|" + version + "|" + userBalance
                + "|" + hubBalance + "|" + creditedVouchers + "|" + creditedAmount
                + "|" + cUser.canonical() + "|" + cHub.canonical());
    }

    private static final class BulletproofContext {
        static final PedersenCommitment PC = new PedersenCommitment();
        static final BulletproofGens GENS = new BulletproofGens(RANGE_BITS, 1);
    }

    public static void warmBulletproof() {
        if (BULLETPROOF_ENABLED) {
            BulletproofContext.GENS.G.size();
        }
    }

    public void resetMetrics() {
        hubSignatureNanos = 0;
        proofGenerationNanos = 0;
        proofVerificationNanos = 0;
    }

    public ProtocolMetrics metrics() {
        return new ProtocolMetrics(hubSignatureNanos, proofGenerationNanos, proofVerificationNanos);
    }

    public Channel openChannel(String cid, long userBalance, long hubBalance) {
        Scalar rU = Scalar.random();
        Scalar rH = Scalar.random();
        return new Channel(cid, userBalance, hubBalance, pp.commit(Scalar.of(userBalance), rU),
                pp.commit(Scalar.of(hubBalance), rH));
    }

    public Invoice makeInvoice(String cidB, long amount) {
        return new Invoice(amount, Scalar.random(), Scalar.random(), Scalar.random(), cidB);
    }

    public MintRequest makeMintRequest(Channel alice, Invoice invoice, boolean malformedStructure) {
        Scalar x = Scalar.of(invoice.amount);
        Scalar rA = Scalar.random();
        G1Point cAmtA = pp.commit(x, rA);
        G1Point cX = pp.G1.mul(pp.hId(invoice.cidB))
                .add(pp.P1.mul(invoice.rId))
                .add(pp.G.mul(x))
                .add(pp.P.mul(invoice.rX))
                .add(pp.P2.mul(invoice.rBlind));
        if (malformedStructure) {
            cX = cX.add(pp.P2.mul(Scalar.of(17)));
        }
        return new MintRequest(alice.cid, alice.version, invoice.amount, cAmtA, cX,
                alice.cUser.sub(cAmtA), alice.cHub.add(cAmtA), !malformedStructure);
    }

    public PreCredential hubPrepare(Channel alice, MintRequest request) {
        verifyMintRequest(alice, request);
        return fairMint.hubPrepareMint(hubKey.sk, request);
    }

    public ReleasedCredential completeMint(Channel alice, MintRequest request, PreCredential pre) {
        if (!fairMint.aliceVerifyPreCredential(hubKey, request, pre)) {
            throw new IllegalStateException("pre-credential verification failed");
        }
        FairMint.AdaptorSignature adaptor = fairMint.aliceMakeAdaptorStateSig("state:" + alice.cid + ":" + (alice.version + 1), pre.T);
        Scalar t = fairMint.hubRelease(pre);
        ReleasedCredential credential = fairMint.aliceFinalizeCred(request, pre, t);
        Scalar fullSig = fairMint.hubAdaptAliceStateSig(adaptor, t);
        if (!fairMint.verifyAdapted(adaptor, fullSig, t)) {
            throw new IllegalStateException("adaptor signature adaptation failed");
        }
        alice.userBalance -= request.amount;
        alice.hubBalance += request.amount;
        alice.cUser = request.newAlice;
        alice.cHub = request.newHub;
        alice.version++;
        return credential;
    }

    public WalletVoucher bobAccept(Invoice invoice, ReleasedCredential credential) {
        DRSign.TargetCredential target = drSign.transform(credential.cX, credential.blindSignature, invoice.rBlind);
        if (!drSign.verify(hubKey.pk, target.message, target.signature)) {
            throw new IllegalStateException("target credential verification failed");
        }
        String nullifier = pp.hNull(credential.domain, invoice.cidB, target.message).toString();
        return new WalletVoucher(invoice, target.message, target.signature, nullifier);
    }

    public CompletedPayment completeChannelPayment(Channel payer, String recipientCid, long amount,
                                                   String operationId) {
        Invoice invoice = makeInvoice(recipientCid, amount);
        MintRequest request = makeMintRequest(payer, invoice, false);
        long signatureStart = System.nanoTime();
        PreCredential pre = hubPrepare(payer, request);
        hubSignatureNanos += System.nanoTime() - signatureStart;
        ReleasedCredential credential = completeMint(payer, request, pre);
        WalletVoucher voucher = bobAccept(invoice, credential);
        return new CompletedPayment(voucher, issueFreshness(voucher, operationId, "channel"));
    }

    public List<CompletedPayment> completeBatchedChannelPayments(Channel payer, List<String> recipientCids,
                                                                 List<Long> amounts, String operationId) {
        if (recipientCids.size() != amounts.size() || recipientCids.isEmpty()) {
            throw new IllegalArgumentException("recipient and amount lists must be non-empty and equal length");
        }
        long total = 0;
        for (long amount : amounts) {
            if (amount <= 0 || Long.MAX_VALUE - total < amount) {
                throw new IllegalArgumentException("invalid batched amount");
            }
            total += amount;
        }
        if (total > payer.userBalance) {
            throw new IllegalArgumentException("insufficient payer balance for batch");
        }
        proveAndVerifyAggregateDebit(total);
        List<WalletVoucher> vouchers = new ArrayList<>();
        for (int i = 0; i < amounts.size(); i++) {
            vouchers.add(issueTransferredVoucher(recipientCids.get(i), amounts.get(i),
                    "cp-batch|" + operationId + ":" + i));
        }

        Scalar aggregateRandomness = Scalar.random();
        G1Point aggregateDebit = pp.commit(Scalar.of(total), aggregateRandomness);
        payer.userBalance -= total;
        payer.hubBalance += total;
        payer.cUser = payer.cUser.sub(aggregateDebit);
        payer.cHub = payer.cHub.add(aggregateDebit);
        payer.version++;

        List<CompletedPayment> outputs = new ArrayList<>();
        for (int i = 0; i < vouchers.size(); i++) {
            WalletVoucher voucher = vouchers.get(i);
            outputs.add(new CompletedPayment(voucher,
                    issueFreshness(voucher, operationId + ":" + i, "channel-batch")));
        }
        return outputs;
    }

    private void proveAndVerifyAggregateDebit(long total) {
        if (!BULLETPROOF_ENABLED) {
            return;
        }
        encryption.bulletproof.Scalar blind = encryption.bulletproof.Scalar.random();
        Point commitment = BulletproofContext.PC.commit(
                encryption.bulletproof.Scalar.fromLong(total), blind);
        long generationStart = System.nanoTime();
        RangeProof proof = RangeProof.prove(total, blind, RANGE_BITS,
                BulletproofContext.PC, BulletproofContext.GENS,
                new Transcript("VoucherHub:channel-debit".getBytes()));
        proofGenerationNanos += System.nanoTime() - generationStart;
        long verificationStart = System.nanoTime();
        boolean valid = proof.verify(commitment, RANGE_BITS,
                BulletproofContext.PC, BulletproofContext.GENS,
                new Transcript("VoucherHub:channel-debit".getBytes()));
        proofVerificationNanos += System.nanoTime() - verificationStart;
        if (!valid) {
            throw new IllegalStateException("aggregate channel-debit Bulletproof verification failed");
        }
    }

    public TransferResult transferVoucher(WalletVoucher input, String recipientCid, String operationId) {
        return splitTransferVoucher(input, Collections.singletonList(recipientCid),
                Collections.singletonList(input.invoice.amount), operationId);
    }

    public TransferResult transferVoucher(CompletedPayment input, String recipientCid, String operationId) {
        return splitTransferVoucher(input, Collections.singletonList(recipientCid),
                Collections.singletonList(input.voucher.invoice.amount), operationId);
    }

    public synchronized TransferResult splitTransferVoucher(WalletVoucher input, List<String> recipientCids,
                                                             List<Long> amounts, String operationId) {
        if (!drSign.verify(hubKey.pk, input.cXPrime, input.signature)) {
            return new TransferResult(false, "invalid input voucher signature", Collections.emptyList());
        }
        return splitAuthorizedVoucher(input, recipientCids, amounts, operationId);
    }

    /**
     * Transfers a voucher whose complete validity was already certified by the hub's freshness
     * attestation. The attestation binds the full voucher representation and nullifier, so the
     * transfer path verifies that single attestation instead of repeating the voucher signature
     * verification performed when freshness was issued.
     */
    public synchronized TransferResult splitTransferVoucher(CompletedPayment input,
                                                             List<String> recipientCids,
                                                             List<Long> amounts,
                                                             String operationId) {
        if (!verifyFreshnessAttestation(input)) {
            return new TransferResult(false, "invalid input freshness attestation",
                    Collections.emptyList());
        }
        return splitAuthorizedVoucher(input.voucher, recipientCids, amounts, operationId);
    }

    private TransferResult splitAuthorizedVoucher(WalletVoucher input, List<String> recipientCids,
                                                   List<Long> amounts, String operationId) {
        if (recipientCids.size() != amounts.size() || recipientCids.isEmpty()) {
            return new TransferResult(false, "recipient and amount lists must be non-empty and equal length",
                    Collections.emptyList());
        }
        if (spentNullifiers.contains(input.nullifier)) {
            return new TransferResult(false, "input voucher already spent", Collections.emptyList());
        }
        long total = 0;
        for (long amount : amounts) {
            if (amount <= 0 || Long.MAX_VALUE - total < amount) {
                return new TransferResult(false, "invalid output amount", Collections.emptyList());
            }
            total += amount;
        }
        if (total != input.invoice.amount) {
            return new TransferResult(false, "output amounts do not conserve input value",
                    Collections.emptyList());
        }

        List<WalletVoucher> vouchers = new ArrayList<>();
        for (int i = 0; i < amounts.size(); i++) {
            vouchers.add(issueTransferredVoucher(recipientCids.get(i), amounts.get(i),
                    operationId + ":" + i));
        }
        spentNullifiers.add(input.nullifier);
        List<CompletedPayment> outputs = new ArrayList<>();
        for (int i = 0; i < vouchers.size(); i++) {
            WalletVoucher voucher = vouchers.get(i);
            outputs.add(new CompletedPayment(voucher,
                    issueFreshness(voucher, operationId + ":" + i, input.nullifier)));
        }
        return new TransferResult(true, "ok", outputs);
    }

    private boolean verifyFreshnessAttestation(CompletedPayment payment) {
        long verificationStart = System.nanoTime();
        WalletVoucher voucher = payment.voucher;
        FreshnessProof proof = payment.freshness;
        boolean valid = proof != null
                && voucher.nullifier.equals(proof.voucherNullifier)
                && freshnessStatement(voucher, proof.operationId, proof.predecessorNullifier)
                .equals(proof.statement)
                && drSign.verify(hubKey.pk, proof.statement, proof.signature);
        proofVerificationNanos += System.nanoTime() - verificationStart;
        return valid;
    }

    public boolean verifyCompletedPayment(CompletedPayment payment) {
        return verifyCompletedPayment(payment, hubKey.pk);
    }

    public boolean verifyCompletedPayment(CompletedPayment payment,
                                          org.voucherhub.common.crypto.G2Point expectedHubKey) {
        long verificationStart = System.nanoTime();
        WalletVoucher voucher = payment.voucher;
        FreshnessProof proof = payment.freshness;
        if (!voucher.nullifier.equals(proof.voucherNullifier)) {
            proofVerificationNanos += System.nanoTime() - verificationStart;
            return false;
        }
        if (!drSign.verify(expectedHubKey, voucher.cXPrime, voucher.signature)) {
            proofVerificationNanos += System.nanoTime() - verificationStart;
            return false;
        }
        G1Point expected = freshnessStatement(voucher, proof.operationId, proof.predecessorNullifier);
        boolean valid = expected.equals(proof.statement)
                && drSign.verify(expectedHubKey, proof.statement, proof.signature);
        proofVerificationNanos += System.nanoTime() - verificationStart;
        return valid;
    }

    private WalletVoucher issueTransferredVoucher(String recipientCid, long amount, String domain) {
        Invoice invoice = makeInvoice(recipientCid, amount);
        G1Point message = pp.G1.mul(pp.hId(recipientCid))
                .add(pp.P1.mul(invoice.rId))
                .add(pp.G.mul(Scalar.of(amount)))
                .add(pp.P.mul(invoice.rX));
        long signatureStart = System.nanoTime();
        DRSign.BlindSignature blind = drSign.sign(hubKey.sk, message);
        DRSign.TargetCredential target = drSign.transform(message, blind, Scalar.of(0));
        hubSignatureNanos += System.nanoTime() - signatureStart;
        String nullifier = pp.hNull("vp-transfer|" + domain, recipientCid, target.message).toString();
        return new WalletVoucher(invoice, target.message, target.signature, nullifier);
    }

    private FreshnessProof issueFreshness(WalletVoucher voucher, String operationId,
                                           String predecessorNullifier) {
        long proofStart = System.nanoTime();
        G1Point statement = freshnessStatement(voucher, operationId, predecessorNullifier);
        DRSign.BlindSignature blind = drSign.sign(hubKey.sk, statement);
        DRSign.TargetCredential target = drSign.transform(statement, blind, Scalar.of(0));
        proofGenerationNanos += System.nanoTime() - proofStart;
        return new FreshnessProof(operationId, predecessorNullifier, voucher.nullifier,
                target.message, target.signature);
    }

    private G1Point freshnessStatement(WalletVoucher voucher, String operationId,
                                       String predecessorNullifier) {
        String binding = "freshness|" + operationId + "|" + predecessorNullifier + "|"
                + voucher.nullifier + "|" + voucher.invoice.cidB + "|" + voucher.invoice.amount + "|"
                + voucher.cXPrime.canonical();
        return G1Point.basis(binding);
    }

    public RedeemResult redeem(Channel bob, WalletVoucher voucher, String cidB, Scalar rIdOverride) {
        if (!voucher.invoice.cidB.equals(cidB)) {
            return new RedeemResult(false, "wrong cid_B");
        }
        if (spentNullifiers.contains(voucher.nullifier)) {
            return new RedeemResult(false, "duplicate nullifier");
        }
        if (!drSign.verify(hubKey.pk, voucher.cXPrime, voucher.signature)) {
            return new RedeemResult(false, "invalid DRSign credential");
        }
        Scalar rId = rIdOverride == null ? voucher.invoice.rId : rIdOverride;
        G1Point amountCommitment = voucher.cXPrime
                .sub(pp.G1.mul(pp.hId(cidB)))
                .sub(pp.P1.mul(rId));
        G1Point expected = pp.commit(Scalar.of(voucher.invoice.amount), voucher.invoice.rX);
        if (!amountCommitment.equals(expected)) {
            return new RedeemResult(false, "opening mismatch");
        }
        if (bob.hubBalance < voucher.invoice.amount) {
            return new RedeemResult(false, "insufficient hub-side channel balance");
        }
        spentNullifiers.add(voucher.nullifier);
        bob.userBalance += voucher.invoice.amount;
        bob.hubBalance -= voucher.invoice.amount;
        bob.cUser = bob.cUser.add(amountCommitment);
        bob.cHub = bob.cHub.sub(amountCommitment);
        bob.version++;
        return new RedeemResult(true, "ok");
    }

    public OpeningProof makeRedeemProof(WalletVoucher voucher, String cidB) {
        G1Point amountCommitment = amountCommitment(voucher, cidB, voucher.invoice.rId);
        return sigma.proveOpening(amountCommitment, Scalar.of(voucher.invoice.amount), voucher.invoice.rX,
                redeemContext(cidB, voucher.nullifier, voucher.cXPrime, amountCommitment));
    }

    public RedeemResult redeemWithProof(Channel bob, WalletVoucher voucher, String cidB,
                                        Scalar rIdOverride, OpeningProof proof) {
        if (!voucher.invoice.cidB.equals(cidB)) {
            return new RedeemResult(false, "wrong cid_B");
        }
        if (spentNullifiers.contains(voucher.nullifier)) {
            return new RedeemResult(false, "duplicate nullifier");
        }
        if (!drSign.verify(hubKey.pk, voucher.cXPrime, voucher.signature)) {
            return new RedeemResult(false, "invalid DRSign credential");
        }
        Scalar rId = rIdOverride == null ? voucher.invoice.rId : rIdOverride;
        G1Point amountCommitment = amountCommitment(voucher, cidB, rId);
        if (!sigma.verifyOpening(amountCommitment, proof,
                redeemContext(cidB, voucher.nullifier, voucher.cXPrime, amountCommitment))) {
            return new RedeemResult(false, "invalid Sigma opening proof");
        }
        if (bob.hubBalance < voucher.invoice.amount) {
            return new RedeemResult(false, "insufficient hub-side channel balance");
        }
        spentNullifiers.add(voucher.nullifier);
        bob.userBalance += voucher.invoice.amount;
        bob.hubBalance -= voucher.invoice.amount;
        bob.cUser = bob.cUser.add(amountCommitment);
        bob.cHub = bob.cHub.sub(amountCommitment);
        bob.version++;
        return new RedeemResult(true, "ok");
    }

    public BatchResult batchRedeem(Channel bob, List<WalletVoucher> vouchers) {
        long total = 0;
        for (WalletVoucher v : vouchers) {
            RedeemResult check = redeemDryRun(bob, v);
            if (!check.success) {
                return new BatchResult(false, 0, total);
            }
            total += v.invoice.amount;
        }
        G1Point sum = G1Point.zero();
        for (WalletVoucher v : vouchers) {
            spentNullifiers.add(v.nullifier);
            sum = sum.add(v.cXPrime.sub(pp.G1.mul(pp.hId(v.invoice.cidB))).sub(pp.P1.mul(v.invoice.rId)));
        }
        bob.userBalance += total;
        bob.hubBalance -= total;
        bob.cUser = bob.cUser.add(sum);
        bob.cHub = bob.cHub.sub(sum);
        bob.version++;
        return new BatchResult(true, vouchers.size(), total);
    }

    public OpeningProof makeBatchRedeemProof(List<WalletVoucher> vouchers) {
        G1Point sum = G1Point.zero();
        Scalar totalValue = Scalar.of(0);
        Scalar totalRandomness = Scalar.of(0);
        for (WalletVoucher v : vouchers) {
            sum = sum.add(amountCommitment(v, v.invoice.cidB, v.invoice.rId));
            totalValue = totalValue.add(Scalar.of(v.invoice.amount));
            totalRandomness = totalRandomness.add(v.invoice.rX);
        }
        return sigma.proveOpening(sum, totalValue, totalRandomness, batchRedeemContext(vouchers, sum));
    }

    public BatchResult batchRedeemWithProof(Channel bob, List<WalletVoucher> vouchers, OpeningProof proof) {
        long total = 0;
        G1Point sum = G1Point.zero();
        Set<String> seen = new HashSet<>();
        for (WalletVoucher v : vouchers) {
            RedeemResult check = validateRedeemVoucher(bob, v);
            if (!check.success) {
                return new BatchResult(false, 0, total);
            }
            if (!seen.add(v.nullifier)) {
                return new BatchResult(false, 0, total);
            }
            total += v.invoice.amount;
            sum = sum.add(amountCommitment(v, v.invoice.cidB, v.invoice.rId));
        }
        if (bob.hubBalance < total) {
            return new BatchResult(false, 0, total);
        }
        if (!sigma.verifyOpening(sum, proof, batchRedeemContext(vouchers, sum))) {
            return new BatchResult(false, 0, total);
        }
        for (WalletVoucher v : vouchers) {
            spentNullifiers.add(v.nullifier);
        }
        bob.userBalance += total;
        bob.hubBalance -= total;
        bob.cUser = bob.cUser.add(sum);
        bob.cHub = bob.cHub.sub(sum);
        bob.version++;
        return new BatchResult(true, vouchers.size(), total);
    }

    private RedeemResult redeemDryRun(Channel bob, WalletVoucher voucher) {
        if (spentNullifiers.contains(voucher.nullifier)) {
            return new RedeemResult(false, "duplicate nullifier");
        }
        if (!drSign.verify(hubKey.pk, voucher.cXPrime, voucher.signature)) {
            return new RedeemResult(false, "invalid DRSign credential");
        }
        G1Point expected = pp.commit(Scalar.of(voucher.invoice.amount), voucher.invoice.rX);
        G1Point amountCommitment = voucher.cXPrime
                .sub(pp.G1.mul(pp.hId(voucher.invoice.cidB)))
                .sub(pp.P1.mul(voucher.invoice.rId));
        if (!amountCommitment.equals(expected)) {
            return new RedeemResult(false, "opening mismatch");
        }
        if (bob.hubBalance < voucher.invoice.amount) {
            return new RedeemResult(false, "insufficient hub-side channel balance");
        }
        return new RedeemResult(true, "ok");
    }

    private RedeemResult validateRedeemVoucher(Channel bob, WalletVoucher voucher) {
        if (spentNullifiers.contains(voucher.nullifier)) {
            return new RedeemResult(false, "duplicate nullifier");
        }
        if (!drSign.verify(hubKey.pk, voucher.cXPrime, voucher.signature)) {
            return new RedeemResult(false, "invalid DRSign credential");
        }
        if (bob.hubBalance < voucher.invoice.amount) {
            return new RedeemResult(false, "insufficient hub-side channel balance");
        }
        return new RedeemResult(true, "ok");
    }

    private G1Point amountCommitment(WalletVoucher voucher, String cidB, Scalar rId) {
        return voucher.cXPrime
                .sub(pp.G1.mul(pp.hId(cidB)))
                .sub(pp.P1.mul(rId));
    }

    private String redeemContext(String cidB, String nullifier, G1Point cXPrime, G1Point amountCommitment) {
        return "redeem|" + cidB + "|" + nullifier + "|" + cXPrime.canonical()
                + "|" + amountCommitment.canonical();
    }

    private String batchRedeemContext(List<WalletVoucher> vouchers, G1Point sum) {
        StringBuilder context = new StringBuilder("batch-redeem|").append(sum.canonical());
        for (WalletVoucher v : vouchers) {
            G1Point amountCommitment = amountCommitment(v, v.invoice.cidB, v.invoice.rId);
            context.append('|')
                    .append(v.invoice.cidB)
                    .append(':')
                    .append(v.nullifier)
                    .append(':')
                    .append(v.cXPrime.canonical())
                    .append(':')
                    .append(amountCommitment.canonical());
        }
        return context.toString();
    }

    private void verifyMintRequest(Channel alice, MintRequest request) {
        if (!alice.cid.equals(request.cidA) || alice.version != request.version) {
            throw new IllegalArgumentException("stale or wrong Alice channel state");
        }
        if (!request.structureProofValid) {
            throw new IllegalArgumentException("invalid C_x structure proof");
        }
        if (request.amount <= 0 || request.amount > alice.userBalance) {
            throw new IllegalArgumentException("invalid or insufficient Alice amount");
        }
        if (!request.newAlice.add(request.newHub).equals(alice.cUser.add(alice.cHub))) {
            throw new IllegalArgumentException("channel commitment conservation failed");
        }
    }
}
