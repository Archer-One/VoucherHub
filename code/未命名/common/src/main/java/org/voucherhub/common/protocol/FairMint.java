package org.voucherhub.common.protocol;

import org.voucherhub.common.crypto.DRSign;
import org.voucherhub.common.crypto.G1Point;
import org.voucherhub.common.crypto.PublicParameters;
import org.voucherhub.common.crypto.Scalar;
import org.voucherhub.common.protocol.Model.MintRequest;
import org.voucherhub.common.protocol.Model.PreCredential;
import org.voucherhub.common.protocol.Model.ReleasedCredential;

public final class FairMint {
    private final PublicParameters pp;
    private final DRSign drSign;

    public FairMint(PublicParameters pp, DRSign drSign) {
        this.pp = pp;
        this.drSign = drSign;
    }

    public PreCredential hubPrepareMint(Scalar hubSk, MintRequest request) {
        Scalar t = Scalar.random();
        return new PreCredential(t, pp.G.mul(t), drSign.sign(hubSk, request.cX));
    }

    public boolean aliceVerifyPreCredential(DRSign.KeyPair hubKey, MintRequest request, PreCredential pre) {
        return drSign.bVerify(hubKey.pk, request.cX, pre.blindSignature);
    }

    public AdaptorSignature aliceMakeAdaptorStateSig(String state, G1Point T) {
        Scalar nonce = Scalar.random();
        Scalar challenge = org.voucherhub.common.crypto.Hashing.hashToScalar("VoucherHub:adaptor", state + "|" + T);
        Scalar preSig = nonce.add(challenge);
        return new AdaptorSignature(state, preSig, T);
    }

    public Scalar hubRelease(PreCredential pre) {
        return pre.t;
    }

    public ReleasedCredential aliceFinalizeCred(MintRequest request, PreCredential pre, Scalar t) {
        if (!pp.G.mul(t).equals(pre.T)) {
            throw new IllegalArgumentException("invalid fair-mint release secret");
        }
        return new ReleasedCredential(request.cX, pre.blindSignature, "VoucherHub:v1");
    }

    public Scalar hubAdaptAliceStateSig(AdaptorSignature adaptor, Scalar t) {
        return adaptor.preSignature.add(t);
    }

    public boolean verifyAdapted(AdaptorSignature adaptor, Scalar fullSignature, Scalar t) {
        return fullSignature.sub(adaptor.preSignature).equals(t);
    }

    public static final class AdaptorSignature {
        public final String state;
        public final Scalar preSignature;
        public final G1Point T;

        public AdaptorSignature(String state, Scalar preSignature, G1Point tPoint) {
            this.state = state;
            this.preSignature = preSignature;
            this.T = tPoint;
        }
    }
}
