package org.voucherhub.common.crypto;

public final class PublicParameters {
    public final G1Point G = G1Point.basis("G");
    public final G1Point P = G1Point.basis("P");
    public final G1Point G1 = G1Point.basis("G1");
    public final G1Point P1 = G1Point.basis("P1");
    public final G1Point P2 = G1Point.basis("P2");
    public final G1Point G2 = G1Point.basis("G2");
    public final G2Point Ghat = G2Point.generator();

    public G1Point commit(Scalar v, Scalar r) {
        return G.mul(v).add(P.mul(r));
    }

    public Scalar hId(String cid) {
        return Hashing.hashToScalar("VoucherHub:H_id", cid);
    }

    public Scalar hNull(String domain, String cid, G1Point cPrime) {
        return Hashing.hashToScalar("VoucherHub:H_null", domain + "|" + cid + "|" + cPrime.canonical());
    }
}
