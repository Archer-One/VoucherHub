package org.voucherhub.common.crypto;

public final class SigmaProtocol {
    private final PublicParameters pp;

    public SigmaProtocol(PublicParameters pp) {
        this.pp = pp;
    }

    public OpeningProof proveOpening(G1Point commitment, Scalar value, Scalar randomness, String context) {
        Scalar blindValue = Scalar.random();
        Scalar blindRandomness = Scalar.random();
        G1Point announcement = pp.commit(blindValue, blindRandomness);
        Scalar challenge = challenge(commitment, announcement, context);
        return new OpeningProof(announcement, blindValue.add(challenge.mul(value)),
                blindRandomness.add(challenge.mul(randomness)));
    }

    public boolean verifyOpening(G1Point commitment, OpeningProof proof, String context) {
        Scalar challenge = challenge(commitment, proof.announcement, context);
        G1Point left = pp.commit(proof.zValue, proof.zRandomness);
        G1Point right = proof.announcement.add(commitment.mul(challenge));
        return left.equals(right);
    }

    private Scalar challenge(G1Point commitment, G1Point announcement, String context) {
        return Hashing.hashToScalar("VoucherHub:Sigma:Opening",
                context + "|" + commitment.canonical() + "|" + announcement.canonical());
    }

    public static final class OpeningProof implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final G1Point announcement;
        public final Scalar zValue;
        public final Scalar zRandomness;

        public OpeningProof(G1Point announcement, Scalar zValue, Scalar zRandomness) {
            this.announcement = announcement;
            this.zValue = zValue;
            this.zRandomness = zRandomness;
        }
    }
}
