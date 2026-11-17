package org.voucherhub.common.crypto;

public final class DRSign {
    private final PublicParameters pp;

    public DRSign(PublicParameters pp) {
        this.pp = pp;
    }

    public KeyPair keyGen() {
        Scalar sk = Scalar.random();
        return new KeyPair(sk, pp.Ghat.mul(sk));
    }

    public BlindSignature sign(Scalar sk, G1Point blindedMessage) {
        Scalar s = Scalar.random();
        return new BlindSignature(
                pp.G2.mul(sk).add(blindedMessage.mul(s)),
                pp.Ghat.mul(s),
                pp.P2.mul(s));
    }

    public boolean bVerify(G2Point pk, G1Point blindedMessage, BlindSignature sig) {
        boolean main = sig.sigma0.pair(pp.Ghat)
                .equals(pp.G2.pair(pk).mul(blindedMessage.pair(sig.sigma1)));
        boolean blind = pp.P2.pair(sig.sigma1).equals(sig.sigma2.pair(pp.Ghat));
        return main && blind;
    }

    public TargetCredential transform(G1Point blindedMessage, BlindSignature sig, Scalar rBlind) {
        G1Point message = blindedMessage.sub(pp.P2.mul(rBlind));
        Scalar sPrime = Scalar.random();
        Signature target = new Signature(
                sig.sigma0.sub(sig.sigma2.mul(rBlind)).add(message.mul(sPrime)),
                sig.sigma1.add(pp.Ghat.mul(sPrime)));
        return new TargetCredential(message, target);
    }

    public boolean verify(G2Point pk, G1Point message, Signature sig) {
        return sig.sigma0.pair(pp.Ghat)
                .equals(pp.G2.pair(pk).mul(message.pair(sig.sigma1)));
    }

    public static final class KeyPair {
        public final Scalar sk;
        public final G2Point pk;

        public KeyPair(Scalar sk, G2Point pk) {
            this.sk = sk;
            this.pk = pk;
        }
    }

    public static final class BlindSignature {
        public final G1Point sigma0;
        public final G2Point sigma1;
        public final G1Point sigma2;

        public BlindSignature(G1Point sigma0, G2Point sigma1, G1Point sigma2) {
            this.sigma0 = sigma0;
            this.sigma1 = sigma1;
            this.sigma2 = sigma2;
        }
    }

    public static final class Signature implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        public final G1Point sigma0;
        public final G2Point sigma1;

        public Signature(G1Point sigma0, G2Point sigma1) {
            this.sigma0 = sigma0;
            this.sigma1 = sigma1;
        }
    }

    public static final class TargetCredential {
        public final G1Point message;
        public final Signature signature;

        public TargetCredential(G1Point message, Signature signature) {
            this.message = message;
            this.signature = signature;
        }
    }
}
