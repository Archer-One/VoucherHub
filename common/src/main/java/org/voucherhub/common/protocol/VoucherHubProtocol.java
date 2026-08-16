package org.voucherhub.common.protocol;

import org.voucherhub.common.crypto.DRSign;
import org.voucherhub.common.crypto.G1Point;
import org.voucherhub.common.crypto.PublicParameters;
import org.voucherhub.common.crypto.Scalar;
import org.voucherhub.common.crypto.SigmaProtocol;
import org.voucherhub.common.crypto.SigmaProtocol.OpeningProof;
import org.voucherhub.common.protocol.Model.BatchResult;
import org.voucherhub.common.protocol.Model.Channel;
import org.voucherhub.common.protocol.Model.Invoice;
import org.voucherhub.common.protocol.Model.MintRequest;
import org.voucherhub.common.protocol.Model.PreCredential;
import org.voucherhub.common.protocol.Model.ReleasedCredential;
import org.voucherhub.common.protocol.Model.RedeemResult;
import org.voucherhub.common.protocol.Model.WalletVoucher;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class VoucherHubProtocol {
    public final PublicParameters pp = new PublicParameters();
    public final DRSign drSign = new DRSign(pp);
    public final DRSign.KeyPair hubKey = drSign.keyGen();
    public final FairMint fairMint = new FairMint(pp, drSign);
    public final SigmaProtocol sigma = new SigmaProtocol(pp);
    private final Set<String> spentNullifiers = new HashSet<>();

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
