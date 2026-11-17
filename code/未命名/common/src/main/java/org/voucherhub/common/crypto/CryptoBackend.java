package org.voucherhub.common.crypto;

public final class CryptoBackend {
    private static final String BACKEND = System.getProperty("voucherhub.crypto.backend", "symbolic");

    private CryptoBackend() {
    }

    public static boolean isBn128() {
        return "bn128".equalsIgnoreCase(BACKEND);
    }

    public static String name() {
        return isBn128() ? "bn128" : "symbolic";
    }
}
