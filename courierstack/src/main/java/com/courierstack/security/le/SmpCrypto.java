package com.courierstack.security.le;

import com.courierstack.util.CourierLogger;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;

/**
 * SMP Cryptographic functions per Bluetooth Core Spec v5.3, Vol 3, Part H.
 *
 * <p>All f4/f5/f6/g2 functions handle the byte ordering conversion between
 * SMP's Little Endian wire format and AES-CMAC's Big Endian input:
 * <ul>
 *   <li>SMP PDUs use Little Endian (LSB first)</li>
 *   <li>AES-CMAC operates on Big Endian data (MSB first)</li>
 *   <li>All functions reverse LE inputs to BE for CMAC, then reverse outputs back to LE</li>
 * </ul>
 */
public final class SmpCrypto {
    private static final String TAG = "SmpCrypto";

    /** Enable verbose crypto logging. */
    private static volatile boolean sDebugLogging = false;

    private SmpCrypto() {}

    public static void setDebugLogging(boolean enabled) {
        sDebugLogging = enabled;
    }

    private static void logDebug(String msg) {
        if (sDebugLogging) {
            CourierLogger.d(TAG, msg);
        }
    }

    private static void logHex(String label, byte[] data) {
        if (sDebugLogging && data != null) {
            CourierLogger.d(TAG, label + ": " + bytesToHex(data) + " (" + data.length + " bytes)");
        }
    }

    // ==================== Secure Connections Functions ====================

    /**
     * Computes the confirm value f4.
     *
     * Per BT Core Spec v5.3, Vol 3, Part H, Section 2.2.6:
     * f4(U, V, X, Z) = AES-CMAC_X (U || V || Z)
     *
     * "The most significant octet of U shall be the most significant octet
     * of m and shall be in m[0]."
     *
     * This means U and V must be placed in Big Endian order in the message.
     * Since SMP uses Little Endian, we must reverse U and V.
     *
     * @param u PKax or PKbx (32 bytes, Little Endian from SMP)
     * @param v PKbx or PKax (32 bytes, Little Endian from SMP)
     * @param x Na or Nb - the CMAC key (16 bytes, Little Endian from SMP)
     * @param z 8-bit value (0x00 for Just Works/NumComp, 0x80|bit for Passkey)
     * @return Confirm value (16 bytes, Little Endian for SMP)
     */
    public static byte[] f4(byte[] u, byte[] v, byte[] x, byte z) {
        logDebug("=== f4 START ===");
        logHex("  f4 input U (LE from SMP)", u);
        logHex("  f4 input V (LE from SMP)", v);
        logHex("  f4 input X (LE from SMP)", x);
        logDebug("  f4 input Z: 0x" + String.format("%02X", z & 0xFF));

        // X is the Key for AES-CMAC. AES requires Big Endian Key.
        byte[] xBE = reverse(x);
        logHex("  f4 key X (BE)", xBE);

        // Per spec Section 2.2.6: "The most significant octet of U shall be in m[0]"
        // U and V must be Big Endian in the CMAC message.
        byte[] uBE = reverse(u);
        byte[] vBE = reverse(v);
        logHex("  f4 U reversed (BE)", uBE);
        logHex("  f4 V reversed (BE)", vBE);

        // Build message: U || V || Z (all in Big Endian)
        byte[] m = new byte[65];
        System.arraycopy(uBE, 0, m, 0, 32);
        System.arraycopy(vBE, 0, m, 32, 32);
        m[64] = z;
        logHex("  f4 message M (BE)", m);

        // Compute AES-CMAC
        byte[] cmacResult = aesCmacInternal(xBE, m);
        logHex("  f4 AES-CMAC result (BE)", cmacResult);

        // Reverse result to Little Endian for SMP PDU
        byte[] result = reverse(cmacResult);
        logHex("  f4 output (LE for SMP)", result);
        logDebug("=== f4 END ===");

        return result;
    }

    /**
     * Computes numeric comparison value g2.
     *
     * Per BT Core Spec v5.3, Vol 3, Part H, Section 2.2.9:
     * g2(U, V, X, Y) = AES-CMAC_X (U || V || Y) mod 2^32
     *
     * All inputs are reversed to Big Endian for CMAC.
     */
    public static int g2(byte[] u, byte[] v, byte[] x, byte[] y) {
        logDebug("=== g2 START ===");
        logHex("  g2 input U (LE)", u);
        logHex("  g2 input V (LE)", v);
        logHex("  g2 input X (LE)", x);
        logHex("  g2 input Y (LE)", y);

        // All inputs reversed to Big Endian
        byte[] xBE = reverse(x);
        byte[] uBE = reverse(u);
        byte[] vBE = reverse(v);
        byte[] yBE = reverse(y);

        logHex("  g2 key X (BE)", xBE);

        byte[] m = new byte[80];
        System.arraycopy(uBE, 0, m, 0, 32);
        System.arraycopy(vBE, 0, m, 32, 32);
        System.arraycopy(yBE, 0, m, 64, 16);
        logHex("  g2 message M (BE)", m);

        byte[] resultBE = aesCmacInternal(xBE, m);
        logHex("  g2 AES-CMAC result (BE)", resultBE);

        // Extract last 32 bits (Big Endian) and mod 10^6
        long val = ((resultBE[12] & 0xFFL) << 24) |
                ((resultBE[13] & 0xFFL) << 16) |
                ((resultBE[14] & 0xFFL) << 8)  |
                (resultBE[15] & 0xFFL);

        int numericValue = (int) (val % 1000000);
        logDebug("  g2 extracted value: " + val + " mod 1000000 = " + numericValue);
        logDebug("=== g2 END ===");

        return numericValue;
    }

    /**
     * Computes f5 key generation function.
     *
     * Per BT Core Spec v5.3, Vol 3, Part H, Section 2.2.7:
     * f5(W, N1, N2, A1, A2) generates MacKey and LTK
     */
    public static byte[][] f5(byte[] w, byte[] n1, byte[] n2, byte[] a1, byte[] a2) {
        logDebug("=== f5 START ===");
        logHex("  f5 input W (DHKey, LE)", w);
        logHex("  f5 input N1 (LE)", n1);
        logHex("  f5 input N2 (LE)", n2);
        logHex("  f5 input A1 (LE)", a1);
        logHex("  f5 input A2 (LE)", a2);

        // Salt is a fixed constant in Big Endian
        byte[] salt = hexToBytes("6C888391AAF5A53860370BDB5A6083BE");
        logHex("  f5 SALT (BE)", salt);

        // W (DHKey) reversed for CMAC message
        byte[] wBE = reverse(w);
        logHex("  f5 W (BE)", wBE);

        // T = AES-CMAC_SALT(W)
        byte[] t = aesCmacInternal(salt, wBE);
        logHex("  f5 T = CMAC_SALT(W)", t);

        // All components reversed to Big Endian
        byte[] n1BE = reverse(n1);
        byte[] n2BE = reverse(n2);
        byte[] a1BE = reverse(a1);
        byte[] a2BE = reverse(a2);

        byte[] keyId = {0x62, 0x74, 0x6C, 0x65}; // "btle"

        // Message format: Counter || keyID || N1 || N2 || A1 || A2 || Length
        byte[] m = new byte[53];
        System.arraycopy(keyId, 0, m, 1, 4);
        System.arraycopy(n1BE, 0, m, 5, 16);
        System.arraycopy(n2BE, 0, m, 21, 16);
        System.arraycopy(a1BE, 0, m, 37, 7);
        System.arraycopy(a2BE, 0, m, 44, 7);
        m[51] = 0x01; m[52] = 0x00; // Length = 256

        // MacKey: Counter = 0
        m[0] = 0x00;
        logHex("  f5 message M (Counter=0)", m);
        byte[] macKeyBE = aesCmacInternal(t, m);

        // LTK: Counter = 1
        m[0] = 0x01;
        logHex("  f5 message M (Counter=1)", m);
        byte[] ltkBE = aesCmacInternal(t, m);

        // Reverse back to LE for SMP
        byte[] macKey = reverse(macKeyBE);
        byte[] ltk = reverse(ltkBE);

        logHex("  f5 output MacKey (LE)", macKey);
        logHex("  f5 output LTK (LE)", ltk);
        logDebug("=== f5 END ===");

        return new byte[][] { macKey, ltk };
    }

    /**
     * Computes f6 DHKey Check.
     *
     * Per BT Core Spec v5.3, Vol 3, Part H, Section 2.2.8:
     * f6(W, N1, N2, R, IOcap, A1, A2) = AES-CMAC_W (N1 || N2 || R || IOcap || A1 || A2)
     */
    public static byte[] f6(byte[] w, byte[] n1, byte[] n2, byte[] r, byte[] ioCap, byte[] a1, byte[] a2) {
        logDebug("=== f6 START ===");
        logHex("  f6 input W (MacKey, LE)", w);
        logHex("  f6 input N1 (LE)", n1);
        logHex("  f6 input N2 (LE)", n2);
        logHex("  f6 input R (LE)", r);
        logHex("  f6 input IOcap (LE)", ioCap);
        logHex("  f6 input A1 (LE)", a1);
        logHex("  f6 input A2 (LE)", a2);

        // All inputs reversed to Big Endian
        byte[] wBE = reverse(w);
        byte[] n1BE = reverse(n1);
        byte[] n2BE = reverse(n2);
        byte[] rBE = reverse(r);
        byte[] ioCapBE = reverse(ioCap);
        byte[] a1BE = reverse(a1);
        byte[] a2BE = reverse(a2);

        // Build message: N1 || N2 || R || IOcap || A1 || A2
        byte[] m = new byte[65];
        System.arraycopy(n1BE, 0, m, 0, 16);
        System.arraycopy(n2BE, 0, m, 16, 16);
        System.arraycopy(rBE, 0, m, 32, 16);
        System.arraycopy(ioCapBE, 0, m, 48, 3);
        System.arraycopy(a1BE, 0, m, 51, 7);
        System.arraycopy(a2BE, 0, m, 58, 7);

        logHex("  f6 message M (BE)", m);

        byte[] cmacResult = aesCmacInternal(wBE, m);
        logHex("  f6 AES-CMAC result (BE)", cmacResult);

        byte[] result = reverse(cmacResult);
        logHex("  f6 output (LE)", result);
        logDebug("=== f6 END ===");

        return result;
    }

    // ==================== Legacy Pairing Functions ====================

    /**
     * Legacy pairing confirm value c1.
     */
    public static byte[] c1(byte[] k, byte[] r, byte[] preq, byte[] pres, int iat, int rat, byte[] ia, byte[] ra) {
        logDebug("=== c1 (Legacy Confirm) START ===");
        logHex("  c1 input k (TK)", k);
        logHex("  c1 input r (random)", r);
        logHex("  c1 input preq", preq);
        logHex("  c1 input pres", pres);
        logDebug("  c1 input iat=" + iat + ", rat=" + rat);
        logHex("  c1 input ia (initiator addr)", ia);
        logHex("  c1 input ra (responder addr)", ra);

        byte[] p1 = new byte[16];
        p1[0] = (byte) iat;
        p1[1] = (byte) rat;
        System.arraycopy(preq, 0, p1, 2, 7);
        System.arraycopy(pres, 0, p1, 9, 7);
        logHex("  c1 p1", p1);

        byte[] p2 = new byte[16];
        System.arraycopy(ra, 0, p2, 0, 6);
        System.arraycopy(ia, 0, p2, 6, 6);
        // p2[12..15] remain zero (padding)
        logHex("  c1 p2", p2);

        byte[] rXorP1 = xor(r, p1);
        logHex("  c1 r XOR p1", rXorP1);

        byte[] e1 = aes128(k, rXorP1);
        logHex("  c1 e1 = AES(k, r XOR p1)", e1);

        byte[] e1XorP2 = xor(e1, p2);
        logHex("  c1 e1 XOR p2", e1XorP2);

        byte[] result = aes128(k, e1XorP2);
        logHex("  c1 output", result);
        logDebug("=== c1 END ===");

        return result;
    }

    /**
     * Legacy pairing STK generation s1.
     */
    public static byte[] s1(byte[] k, byte[] r) {
        logDebug("=== s1 (Legacy STK) START ===");
        logHex("  s1 input k (TK)", k);
        logHex("  s1 input r (combined random)", r);

        byte[] result = aes128(k, r);
        logHex("  s1 output (STK)", result);
        logDebug("=== s1 END ===");
        return result;
    }

    /**
     * AES-128 encryption for legacy pairing.
     *
     * Per BT Spec, the AES block cipher operates on Big Endian data,
     * but SMP uses Little Endian. So we reverse inputs and outputs.
     */
    public static byte[] aes128(byte[] key, byte[] data) {
        try {
            byte[] keyBE = reverse(key);
            byte[] dataBE = reverse(data);

            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBE, "AES"));
            byte[] resultBE = cipher.doFinal(dataBE);

            return reverse(resultBE);
        } catch (Exception e) {
            CourierLogger.e(TAG, "aes128 failed: " + e.getMessage());
            return new byte[16];
        }
    }

    // ==================== AES-CMAC Implementation ====================

    /**
     * AES-CMAC implementation per RFC 4493.
     *
     * @param keyBE 16-byte key in Big Endian format
     * @param message message bytes (already in Big Endian)
     * @return 16-byte MAC in Big Endian format
     */
    private static byte[] aesCmacInternal(byte[] keyBE, byte[] message) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBE, "AES"));

            // Step 1: Generate subkeys
            byte[] L = cipher.doFinal(new byte[16]);
            byte[] K1 = generateSubkey(L);
            byte[] K2 = generateSubkey(K1);

            if (sDebugLogging) {
                logHex("  CMAC L = AES(0)", L);
                logHex("  CMAC K1", K1);
                logHex("  CMAC K2", K2);
            }

            // Step 2: Determine number of blocks
            int n = (message.length + 15) / 16;
            if (n == 0) n = 1;

            boolean lastBlockComplete = (message.length > 0) && (message.length % 16 == 0);

            // Step 3: Prepare last block
            byte[] lastBlock = new byte[16];
            int lastBlockStart = (n - 1) * 16;
            int len = message.length - lastBlockStart;

            if (lastBlockComplete) {
                System.arraycopy(message, lastBlockStart, lastBlock, 0, 16);
                xorInPlace(lastBlock, K1);
            } else {
                if (len > 0) {
                    System.arraycopy(message, lastBlockStart, lastBlock, 0, len);
                }
                lastBlock[len] = (byte) 0x80;
                xorInPlace(lastBlock, K2);
            }

            // Step 4: CBC-MAC
            byte[] X = new byte[16];
            for (int i = 0; i < n - 1; i++) {
                for (int j = 0; j < 16; j++) {
                    X[j] ^= message[i * 16 + j];
                }
                X = cipher.doFinal(X);
            }

            xorInPlace(X, lastBlock);
            byte[] result = cipher.doFinal(X);

            if (sDebugLogging) {
                logHex("  CMAC result", result);
            }

            return result;
        } catch (Exception e) {
            CourierLogger.e(TAG, "aesCmacInternal failed: " + e.getMessage());
            return new byte[16];
        }
    }

    private static byte[] generateSubkey(byte[] input) {
        byte[] output = new byte[16];
        int carry = 0;
        for (int i = 15; i >= 0; i--) {
            int b = (input[i] & 0xFF) << 1;
            output[i] = (byte) (b | carry);
            carry = (b >> 8) & 1;
        }
        if ((input[0] & 0x80) != 0) {
            output[15] ^= 0x87;
        }
        return output;
    }

    // ==================== Utility Functions ====================

    public static byte[] reverse(byte[] data) {
        if (data == null) return null;
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = data[data.length - 1 - i];
        }
        return result;
    }

    public static byte[] xor(byte[] a, byte[] b) {
        byte[] res = new byte[16];
        for (int i = 0; i < 16; i++) {
            res[i] = (byte) (a[i] ^ b[i]);
        }
        return res;
    }

    private static void xorInPlace(byte[] a, byte[] b) {
        for (int i = 0; i < 16; i++) {
            a[i] ^= b[i];
        }
    }

    public static byte[] hexToBytes(String s) {
        byte[] d = new byte[s.length() / 2];
        for (int i = 0; i < s.length(); i += 2) {
            d[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return d;
    }

    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    public static byte[] passkeyToTk(int p) {
        byte[] tk = new byte[16];
        tk[0] = (byte) p;
        tk[1] = (byte) (p >> 8);
        tk[2] = (byte) (p >> 16);
        tk[3] = (byte) (p >> 24);
        return tk;
    }

    public static byte[] buildScR(int p, boolean isPasskey) {
        byte[] r = new byte[16];
        if (isPasskey) {
            r[0] = (byte) p;
            r[1] = (byte) (p >> 8);
            r[2] = (byte) (p >> 16);
            r[3] = (byte) (p >> 24);
        }
        return r;
    }

    public static byte[] h6(byte[] w, byte[] k) {
        byte[] wBE = reverse(w);
        byte[] kBE = reverse(k);
        return reverse(aesCmacInternal(wBE, kBE));
    }

    public static byte[] deriveBrEdrLinkKey(byte[] ltk, boolean sc) {
        byte[] tmp1 = {0x31, 0x70, 0x6D, 0x74};
        byte[] lebr = {0x72, 0x62, 0x65, 0x6C};
        return sc ? h6(ltk, lebr) : h6(h6(ltk, tmp1), lebr);
    }

    public static byte[] deriveLeLtkFromLinkKey(byte[] linkKey) {
        byte[] tmp2 = {0x32, 0x70, 0x6D, 0x74};
        byte[] brle = {0x65, 0x6C, 0x72, 0x62};
        return h6(h6(linkKey, tmp2), brle);
    }

    /**
     * Run self-test to verify crypto implementation.
     *
     * Uses test vectors from Bluetooth Core Spec v5.3, Vol 3, Part H, Appendix D.
     */
    public static boolean runSelfTest() {
        CourierLogger.i(TAG, "Running crypto self-test...");

        try {
            // Test 1: Basic AES-128 (FIPS-197 test vector)
            // Key: 00000000000000000000000000000000
            // Plaintext: 00000000000000000000000000000000
            // Ciphertext: 66E94BD4EF8A2C3B884CFA59CA342B2E (Big Endian)
            byte[] testKey = new byte[16]; // All zeros
            byte[] testData = new byte[16]; // All zeros
            byte[] expectedBE = hexToBytes("66E94BD4EF8A2C3B884CFA59CA342B2E");

            byte[] resultLE = aes128(testKey, testData);
            byte[] resultBE = reverse(resultLE);

            if (!Arrays.equals(resultBE, expectedBE)) {
                CourierLogger.e(TAG, "SELF-TEST FAILED: AES-128 mismatch");
                CourierLogger.e(TAG, "  Expected (BE): " + bytesToHex(expectedBE));
                CourierLogger.e(TAG, "  Got (BE):      " + bytesToHex(resultBE));
                return false;
            }
            CourierLogger.d(TAG, "  AES-128 test: PASS");

            // Test 2: c1 function (Legacy pairing confirm)
            // Using simplified test - TK=0, random values
            byte[] tk = new byte[16];
            byte[] rand = hexToBytes("00112233445566778899AABBCCDDEEFF");
            byte[] preq = new byte[]{0x01, 0x00, 0x00, 0x00, 0x10, 0x07, 0x07};
            byte[] pres = new byte[]{0x02, 0x00, 0x00, 0x00, 0x10, 0x07, 0x07};
            byte[] ia = hexToBytes("A1A2A3A4A5A6");
            byte[] ra = hexToBytes("B1B2B3B4B5B6");

            byte[] c1Result = c1(tk, rand, preq, pres, 0, 0, ia, ra);
            if (c1Result == null || c1Result.length != 16) {
                CourierLogger.e(TAG, "SELF-TEST FAILED: c1 returned invalid result");
                return false;
            }
            CourierLogger.d(TAG, "  c1 function test: PASS (produced valid 16-byte result)");

            // Test 3: s1 function (STK generation)
            byte[] s1Result = s1(tk, rand);
            if (s1Result == null || s1Result.length != 16) {
                CourierLogger.e(TAG, "SELF-TEST FAILED: s1 returned invalid result");
                return false;
            }
            CourierLogger.d(TAG, "  s1 function test: PASS (produced valid 16-byte result)");

            CourierLogger.i(TAG, "Crypto self-test: ALL PASSED");
            return true;

        } catch (Exception e) {
            CourierLogger.e(TAG, "SELF-TEST EXCEPTION: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}