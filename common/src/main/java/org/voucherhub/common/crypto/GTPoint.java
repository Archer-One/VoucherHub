package org.voucherhub.common.crypto;

import java.math.BigInteger;

public final class GTPoint {
    private final G1Point vector;

    public GTPoint(G1Point base, BigInteger g2Scalar) {
        this.vector = base.mul(Scalar.of(g2Scalar));
    }

    public GTPoint mul(GTPoint other) {
        return new GTPoint(vector.add(other.vector), BigInteger.ONE);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof GTPoint && vector.equals(((GTPoint) obj).vector);
    }

    @Override
    public int hashCode() {
        return vector.hashCode();
    }
}
