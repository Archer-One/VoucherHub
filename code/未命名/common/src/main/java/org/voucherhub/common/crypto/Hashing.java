package org.voucherhub.common.crypto;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Hashing {
    private Hashing() {
    }

    public static Scalar hashToScalar(String domain, String message) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(domain.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(message.getBytes(StandardCharsets.UTF_8));
            return Scalar.of(new BigInteger(1, digest.digest()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
