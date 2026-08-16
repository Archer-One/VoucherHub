package org.voucherhub.common.crypto;

import org.aion.tetryon.Fp;
import org.aion.tetryon.Fp2;
import org.aion.tetryon.G1;
import org.aion.tetryon.Pairing;
import org.aion.tetryon.Util;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

final class Bn128Support {
    private static final org.aion.tetryon.G1Point G1_GENERATOR = Pairing.P1();
    private static final org.aion.tetryon.G2Point G2_GENERATOR = new org.aion.tetryon.G2Point(
            new Fp2(new BigInteger("27d2525616cd883a2e952616138e052125201826d45e179a9ae28655338ca2be", 16),
                    new BigInteger("2167ff55d36a2ed92eb480b1b9365382ea2facea90c860d63211827f122fdc29", 16)),
            new Fp2(new BigInteger("2c6e8b5d5da9a03f2d6b57bf2338168eca1e43409693b43659fe834149e506a9", 16),
                    new BigInteger("020401d78e6fe746fe3d9512f9b4eedcfdd7eb5d08e307f1d6ee5d38f9a253ec", 16)));

    private Bn128Support() {
    }

    static org.aion.tetryon.G1Point zero() {
        return null;
    }

    static boolean isZero(org.aion.tetryon.G1Point point) {
        return point == null || point.isZero();
    }

    static org.aion.tetryon.G1Point basis(String name) {
        return mul(G1_GENERATOR, nonZeroHash("VoucherHub:bn128:basis", name));
    }

    static org.aion.tetryon.G1Point add(org.aion.tetryon.G1Point left, org.aion.tetryon.G1Point right) {
        if (isZero(left)) {
            return right;
        }
        if (isZero(right)) {
            return left;
        }
        try {
            org.aion.tetryon.G1Point out = G1.add(left, right);
            return out.isZero() ? null : out;
        } catch (Exception e) {
            throw new IllegalStateException("bn128 G1 add failed", e);
        }
    }

    static org.aion.tetryon.G1Point negate(org.aion.tetryon.G1Point point) {
        if (isZero(point)) {
            return null;
        }
        return G1.negate(point);
    }

    static org.aion.tetryon.G1Point sub(org.aion.tetryon.G1Point left, org.aion.tetryon.G1Point right) {
        return add(left, negate(right));
    }

    static org.aion.tetryon.G1Point mul(org.aion.tetryon.G1Point point, Scalar scalar) {
        return mul(point, scalar.asBigInteger());
    }

    static org.aion.tetryon.G1Point mul(org.aion.tetryon.G1Point point, BigInteger scalar) {
        BigInteger s = scalar.mod(Scalar.Q);
        if (isZero(point) || s.signum() == 0) {
            return null;
        }
        try {
            org.aion.tetryon.G1Point out = G1.mul(point, s);
            return out.isZero() ? null : out;
        } catch (Exception e) {
            throw new IllegalStateException("bn128 G1 mul failed", e);
        }
    }

    static boolean equals(org.aion.tetryon.G1Point left, org.aion.tetryon.G1Point right) {
        if (isZero(left) && isZero(right)) {
            return true;
        }
        if (isZero(left) || isZero(right)) {
            return false;
        }
        return left.equals(right);
    }

    static String canonical(org.aion.tetryon.G1Point point) {
        if (isZero(point)) {
            return "0";
        }
        return Util.bytesToHex(Util.serializeG1(point));
    }

    static boolean pairingProductEquals(List<GTPoint.Term> left, List<GTPoint.Term> right) {
        List<org.aion.tetryon.G1Point> g1Terms = new ArrayList<>();
        List<org.aion.tetryon.G2Point> g2Terms = new ArrayList<>();
        appendTerms(g1Terms, g2Terms, left, BigInteger.ONE);
        appendTerms(g1Terms, g2Terms, right, Scalar.Q.subtract(BigInteger.ONE));
        if (g1Terms.isEmpty()) {
            return true;
        }
        try {
            return Pairing.pairing(
                    g1Terms.toArray(new org.aion.tetryon.G1Point[0]),
                    g2Terms.toArray(new org.aion.tetryon.G2Point[0]));
        } catch (Exception e) {
            throw new IllegalStateException("bn128 pairing product check failed", e);
        }
    }

    private static void appendTerms(List<org.aion.tetryon.G1Point> g1Terms,
                                    List<org.aion.tetryon.G2Point> g2Terms,
                                    List<GTPoint.Term> terms,
                                    BigInteger sign) {
        for (GTPoint.Term term : terms) {
            if (term.g1.isZero()) {
                continue;
            }
            BigInteger scalar = term.g2Scalar.multiply(sign).mod(Scalar.Q);
            if (scalar.signum() == 0) {
                continue;
            }
            org.aion.tetryon.G1Point scaled = mul(term.g1.nativePoint(), scalar);
            if (isZero(scaled)) {
                continue;
            }
            g1Terms.add(scaled);
            g2Terms.add(G2_GENERATOR);
        }
    }

    private static BigInteger nonZeroHash(String domain, String message) {
        BigInteger value = Hashing.hashToScalar(domain, message).asBigInteger();
        return value.signum() == 0 ? BigInteger.ONE : value;
    }
}
