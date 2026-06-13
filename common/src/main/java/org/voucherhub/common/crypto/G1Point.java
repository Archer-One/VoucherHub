package org.voucherhub.common.crypto;

import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class G1Point {
    private final TreeMap<String, BigInteger> coeffs;

    private G1Point(TreeMap<String, BigInteger> coeffs) {
        this.coeffs = new TreeMap<>();
        for (Map.Entry<String, BigInteger> e : coeffs.entrySet()) {
            BigInteger v = e.getValue().mod(Scalar.Q);
            if (v.signum() != 0) {
                this.coeffs.put(e.getKey(), v);
            }
        }
    }

    public static G1Point zero() {
        return new G1Point(new TreeMap<>());
    }

    public static G1Point basis(String name) {
        TreeMap<String, BigInteger> m = new TreeMap<>();
        m.put(name, BigInteger.ONE);
        return new G1Point(m);
    }

    public G1Point add(G1Point other) {
        TreeMap<String, BigInteger> m = new TreeMap<>(coeffs);
        other.coeffs.forEach((k, v) -> m.merge(k, v, BigInteger::add));
        return new G1Point(m);
    }

    public G1Point sub(G1Point other) {
        return add(other.mul(Scalar.of(-1)));
    }

    public G1Point mul(Scalar scalar) {
        TreeMap<String, BigInteger> m = new TreeMap<>();
        coeffs.forEach((k, v) -> m.put(k, v.multiply(scalar.asBigInteger())));
        return new G1Point(m);
    }

    public GTPoint pair(G2Point other) {
        return new GTPoint(this, other.scalar());
    }

    public String canonical() {
        if (coeffs.isEmpty()) {
            return "0";
        }
        return coeffs.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue().mod(Scalar.Q).toString(16))
                .collect(Collectors.joining("|"));
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof G1Point && coeffs.equals(((G1Point) obj).coeffs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(coeffs);
    }

    @Override
    public String toString() {
        return canonical();
    }
}
