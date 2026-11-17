package org.voucherhub.common.crypto;

import java.math.BigInteger;

public final class G2Point implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    private final BigInteger scalar;

    public G2Point(BigInteger scalar) {
        this.scalar = scalar.mod(Scalar.Q);
    }

    public static G2Point generator() {
        return new G2Point(BigInteger.ONE);
    }

    public G2Point add(G2Point other) {
        return new G2Point(scalar.add(other.scalar));
    }

    public G2Point mul(Scalar s) {
        return new G2Point(scalar.multiply(s.asBigInteger()));
    }

    BigInteger scalar() {
        return scalar;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof G2Point && scalar.equals(((G2Point) obj).scalar);
    }

    @Override
    public int hashCode() {
        return scalar.hashCode();
    }

    @Override
    public String toString() {
        return scalar.toString(16);
    }
}
