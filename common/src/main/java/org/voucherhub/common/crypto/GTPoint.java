package org.voucherhub.common.crypto;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class GTPoint {
    private final G1Point vector;
    private final List<Term> terms;

    public GTPoint(G1Point base, BigInteger g2Scalar) {
        if (CryptoBackend.isBn128()) {
            this.vector = null;
            this.terms = Collections.singletonList(new Term(base, g2Scalar.mod(Scalar.Q)));
        } else {
            this.vector = base.mul(Scalar.of(g2Scalar));
            this.terms = Collections.emptyList();
        }
    }

    private GTPoint(List<Term> terms) {
        this.vector = null;
        this.terms = terms;
    }

    public GTPoint mul(GTPoint other) {
        if (CryptoBackend.isBn128()) {
            List<Term> combined = new ArrayList<>(terms.size() + other.terms.size());
            combined.addAll(terms);
            combined.addAll(other.terms);
            return new GTPoint(combined);
        }
        return new GTPoint(vector.add(other.vector), BigInteger.ONE);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof GTPoint)) {
            return false;
        }
        GTPoint other = (GTPoint) obj;
        if (CryptoBackend.isBn128()) {
            return Bn128Support.pairingProductEquals(terms, other.terms);
        }
        return vector.equals(other.vector);
    }

    @Override
    public int hashCode() {
        if (CryptoBackend.isBn128()) {
            return terms.hashCode();
        }
        return vector.hashCode();
    }

    static final class Term {
        final G1Point g1;
        final BigInteger g2Scalar;

        Term(G1Point g1, BigInteger g2Scalar) {
            this.g1 = g1;
            this.g2Scalar = g2Scalar;
        }
    }
}
