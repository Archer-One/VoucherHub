package org.aion.tetryon;

import java.math.BigInteger;
import java.util.Arrays;

public class Util {

    public static final int FP_SIZE=32;

    public static byte[] serializeG1(G1Point p) {
        byte[] data = new byte[FP_SIZE*2];

        byte[] px = toWord(p.x.c0);
        System.arraycopy(px, 0, data, 0, FP_SIZE);

        byte[] py = toWord(p.y.c0);
        System.arraycopy(py, 0, data, FP_SIZE, FP_SIZE);

        return data;
    }

    public static G1Point deserializeG1(byte[] data) {
        byte[] pxData = Arrays.copyOfRange(data, 0, FP_SIZE);
        byte[] pyData = Arrays.copyOfRange(data, FP_SIZE, data.length);

        Fp p1x = new Fp(new BigInteger(1, pxData));
        Fp p1y = new Fp(new BigInteger(1, pyData));

        G1Point p1 = new G1Point(p1x, p1y);
        return p1;
    }

    public static byte[] serializeG2(G2Point p) {
        byte[] data = new byte[FP_SIZE*4]; // zero byte array

        byte[] px1 = toWord(p.x.a);
        System.arraycopy(px1, 0, data, 0, FP_SIZE);

        byte[] px2 = toWord(p.x.b);
        System.arraycopy(px2, 0, data, FP_SIZE, FP_SIZE);

        byte[] py1 = toWord(p.y.a);
        System.arraycopy(py1, 0, data, FP_SIZE*2, FP_SIZE);

        byte[] py2 = toWord(p.y.b);
        System.arraycopy(py2, 0, data, FP_SIZE*3, FP_SIZE);
        return data;
    }

    public static byte[] serializeScalar(BigInteger scalar) {
        assert (scalar.signum() != -1); // scalar can't be negative (it can be zero or positive)

        return toWord(scalar);

    }

    private static byte[] toWord(BigInteger value) {
        byte[] raw = value.mod(Fp.FIELD_MODULUS).toByteArray();
        byte[] out = new byte[FP_SIZE];
        int copy = Math.min(raw.length, FP_SIZE);
        System.arraycopy(raw, raw.length - copy, out, FP_SIZE - copy, copy);
        return out;
    }

    private static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];

        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }

        return new String(hexChars);
    }



}
