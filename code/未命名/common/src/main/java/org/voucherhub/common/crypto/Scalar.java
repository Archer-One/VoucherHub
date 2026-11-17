package org.voucherhub.common.crypto;

import java.math.BigInteger;
import java.security.SecureRandom;

public final class Scalar implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    public static final BigInteger Q = new BigInteger(
            "21888242871839275222246405745257275088548364400416034343698204186575808495617");
    private static final SecureRandom RNG = new SecureRandom();
    private final BigInteger value;

    public Scalar(BigInteger value) {
        this.value = value.mod(Q);
    }

    public static Scalar of(long value) {
        return new Scalar(BigInteger.valueOf(value));
    }

    public static Scalar of(BigInteger value) {
        return new Scalar(value);
    }

    public static Scalar random() {
        BigInteger v;
        do {
            v = new BigInteger(Q.bitLength(), RNG).mod(Q);
        } while (v.signum() == 0);
        return new Scalar(v);
    }

    public Scalar add(Scalar other) {
        return new Scalar(value.add(other.value));
    }

    public Scalar sub(Scalar other) {
        return new Scalar(value.subtract(other.value));
    }

    public Scalar mul(Scalar other) {
        return new Scalar(value.multiply(other.value));
    }

    public BigInteger asBigInteger() {
        return value;
    }

    public long asLongExact() {
        return value.longValueExact();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Scalar && value.equals(((Scalar) obj).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value.toString(16);
    }
}
