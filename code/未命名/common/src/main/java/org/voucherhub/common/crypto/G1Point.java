package org.voucherhub.common.crypto;

import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class G1Point implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    private final TreeMap<String, BigInteger> coeffs;
    private final org.aion.tetryon.G1Point nativePoint;

    private G1Point(TreeMap<String, BigInteger> coeffs) {
        this(coeffs, null);
    }

    private G1Point(TreeMap<String, BigInteger> coeffs, org.aion.tetryon.G1Point nativePoint) {
        this.nativePoint = nativePoint;
        this.coeffs = new TreeMap<>();
        for (Map.Entry<String, BigInteger> e : coeffs.entrySet()) {
            BigInteger v = e.getValue().mod(Scalar.Q);
            if (v.signum() != 0) {
                this.coeffs.put(e.getKey(), v);
            }
        }
    }

    public static G1Point zero() {
        if (CryptoBackend.isBn128()) {
            return new G1Point(new TreeMap<>(), Bn128Support.zero());
        }
        return new G1Point(new TreeMap<>());
    }

    public static G1Point basis(String name) {
        if (CryptoBackend.isBn128()) {
            return new G1Point(new TreeMap<>(), Bn128Support.basis(name));
        }
        TreeMap<String, BigInteger> m = new TreeMap<>();
        m.put(name, BigInteger.ONE);
        return new G1Point(m);
    }

    public G1Point add(G1Point other) {
        if (CryptoBackend.isBn128()) {
            return new G1Point(new TreeMap<>(), Bn128Support.add(nativePoint, other.nativePoint));
        }
        TreeMap<String, BigInteger> m = new TreeMap<>(coeffs);
        other.coeffs.forEach((k, v) -> m.merge(k, v, BigInteger::add));
        return new G1Point(m);
    }

    public G1Point sub(G1Point other) {
        if (CryptoBackend.isBn128()) {
            return new G1Point(new TreeMap<>(), Bn128Support.sub(nativePoint, other.nativePoint));
        }
        return add(other.mul(Scalar.of(-1)));
    }

    public G1Point mul(Scalar scalar) {
        if (CryptoBackend.isBn128()) {
            return new G1Point(new TreeMap<>(), Bn128Support.mul(nativePoint, scalar));
        }
        TreeMap<String, BigInteger> m = new TreeMap<>();
        coeffs.forEach((k, v) -> m.put(k, v.multiply(scalar.asBigInteger())));
        return new G1Point(m);
    }

    public GTPoint pair(G2Point other) {
        return new GTPoint(this, other.scalar());
    }

    public String canonical() {
        if (CryptoBackend.isBn128()) {
            return Bn128Support.canonical(nativePoint);
        }
        if (coeffs.isEmpty()) {
            return "0";
        }
        return coeffs.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue().mod(Scalar.Q).toString(16))
                .collect(Collectors.joining("|"));
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof G1Point)) {
            return false;
        }
        G1Point other = (G1Point) obj;
        if (CryptoBackend.isBn128()) {
            return Bn128Support.equals(nativePoint, other.nativePoint);
        }
        return coeffs.equals(other.coeffs);
    }

    @Override
    public int hashCode() {
        if (CryptoBackend.isBn128()) {
            return canonical().hashCode();
        }
        return Objects.hash(coeffs);
    }

    @Override
    public String toString() {
        return canonical();
    }

    boolean isZero() {
        return CryptoBackend.isBn128() ? Bn128Support.isZero(nativePoint) : coeffs.isEmpty();
    }

    org.aion.tetryon.G1Point nativePoint() {
        if (!CryptoBackend.isBn128()) {
            throw new IllegalStateException("native point requested while backend is " + CryptoBackend.name());
        }
        return nativePoint;
    }
}
