package com.courierstack.smp;

import com.courierstack.core.CourierLogger;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cryptographic functions for SMP per Bluetooth Core Spec v5.3, Vol 3, Part H.
 *
 * <p>Implements the cryptographic toolbox functions:
 * <ul>
 *   <li>c1 - Confirm value generation (Legacy pairing)</li>
 *   <li>s1 - STK generation (Legacy pairing)</li>
 *   <li>f4 - Confirm value generation (Secure Connections)</li>
 *   <li>f5 - Key derivation (Secure Connections)</li>
 *   <li>f6 - DHKey check (Secure Connections)</li>
 *   <li>g2 - Numeric comparison value (Secure Connections)</li>
 * </ul>
 *
 * <p>Thread Safety: All methods are stateless and thread-safe.
 */
public final class SmpCrypto {

    private static final String TAG = "SmpCrypto";

    private SmpCrypto() {
        // Utility class
    }

    // ==================== Legacy Pairing Functions ====================

    /**
     * Computes the confirm value c1 for Legacy pairing.
     *
     * <p>c1(k, r, preq, pres, iat, rat, ia, ra) = e(k, e(k, r XOR p1) XOR p2)
     *
     * @param k Temporary Key (16 bytes)
     * @param r Random value (16 bytes)
     * @param preq Pairing Request PDU (7 bytes)
     * @param pres Pairing Response PDU (7 bytes)
     * @param iat Initiator address type
     * @param rat Responder address type
     * @param ia Initiator address (6 bytes)
     * @param ra Responder address (6 bytes)
     * @return confirm value (16 bytes)
     */
    public static byte[] c1(byte[] k, byte[] r, byte[] preq, byte[] pres,
                            int iat, int rat, byte[] ia, byte[] ra) {
        // Per Bluetooth Core Spec v5.3, Vol 3, Part H, Section 2.2.3
        // p1 = pres || preq || rat || iat
        // In byte array (LSB at index 0):
        //   p1[0] = iat
        //   p1[1] = rat
        //   p1[2-8] = preq (7 bytes)
        //   p1[9-15] = pres (7 bytes)
        byte[] p1 = new byte[16];
        p1[0] = (byte) iat;
        p1[1] = (byte) rat;
        System.arraycopy(preq, 0, p1, 2, 7);
        System.arraycopy(pres, 0, p1, 9, 7);

        // p2 = padding || ia || ra
        // In byte array (LSB at index 0):
        //   p2[0-5] = ra (Responder Address)
        //   p2[6-11] = ia (Initiator Address)
        //   p2[12-15] = padding (zeros)
        byte[] p2 = new byte[16];
        System.arraycopy(ra, 0, p2, 0, 6);
        System.arraycopy(ia, 0, p2, 6, 6);
        // bytes 12-15 are initialized to zero by new byte[16]

        // c1 = e(k, e(k, r XOR p1) XOR p2)
        byte[] rXorP1 = xor(r, p1);
        byte[] e1 = aes128(k, rXorP1);
        byte[] e1XorP2 = xor(e1, p2);
        return aes128(k, e1XorP2);
    }

    /**
     * Computes the Short Term Key s1 for Legacy pairing.
     *
     * <p>s1(k, r1, r2) = e(k, r')
     * where r' = r1[0..7] || r2[0..7]
     *
     * @param k Temporary Key (16 bytes)
     * @param r Combined random values (16 bytes: Srand[0:7] || Mrand[0:7])
     * @return STK (16 bytes)
     */
    public static byte[] s1(byte[] k, byte[] r) {
        return aes128(k, r);
    }

    // ==================== Secure Connections Functions ====================

    /**
     * Computes the confirm value f4 for Secure Connections.
     *
     * <p>f4(U, V, X, Z) = AES-CMAC_X(U || V || Z)
     *
     * @param u Public key X coordinate (32 bytes) - PKax or PKbx
     * @param v Public key X coordinate (32 bytes) - PKbx or PKax
     * @param x Random value (16 bytes) - Na or Nb
     * @param z Single byte - 0x00 for numeric comparison/just works, 0x8X for passkey
     * @return confirm value (16 bytes)
     */
    public static byte[] f4(byte[] u, byte[] v, byte[] x, byte z) {
        byte[] m = new byte[65];
        System.arraycopy(u, 0, m, 0, 32);
        System.arraycopy(v, 0, m, 32, 32);
        m[64] = z;
        return aesCmac(x, m);
    }

    /**
     * Computes the f5 key generation function for Secure Connections.
     *
     * <p>f5(W, N1, N2, A1, A2) derives MacKey and LTK from DHKey.
     *
     * @param w DHKey (32 bytes)
     * @param n1 Initiator nonce Na (16 bytes)
     * @param n2 Responder nonce Nb (16 bytes)
     * @param a1 Initiator address with type (7 bytes: type || address)
     * @param a2 Responder address with type (7 bytes: type || address)
     * @return array of [MacKey (16 bytes), LTK (16 bytes)]
     */
    public static byte[][] f5(byte[] w, byte[] n1, byte[] n2, byte[] a1, byte[] a2) {
        // Salt for f5
        byte[] salt = {
                (byte) 0x6C, (byte) 0x88, (byte) 0x83, (byte) 0x91,
                (byte) 0xAA, (byte) 0xF5, (byte) 0xA5, (byte) 0x38,
                (byte) 0x60, (byte) 0x37, (byte) 0x0B, (byte) 0xDB,
                (byte) 0x5A, (byte) 0x60, (byte) 0x83, (byte) 0xBE
        };

        // T = AES-CMAC_SALT(W)
        byte[] t = aesCmac(salt, w);

        // keyID = "btle"
        byte[] keyId = {(byte) 0x62, (byte) 0x74, (byte) 0x6C, (byte) 0x65};

        // m = Counter || keyID || N1 || N2 || A1 || A2 || Length
        byte[] m = new byte[53];
        m[0] = 0x00; // Counter (will be 0 for MacKey, 1 for LTK)
        System.arraycopy(keyId, 0, m, 1, 4);
        System.arraycopy(n1, 0, m, 5, 16);
        System.arraycopy(n2, 0, m, 21, 16);
        System.arraycopy(a1, 0, m, 37, 7);
        System.arraycopy(a2, 0, m, 44, 7);
        m[51] = 0x00; // Length MSB
        m[52] = 0x01; // Length LSB (256 bits)

        // MacKey = AES-CMAC_T(0 || keyID || N1 || N2 || A1 || A2 || 256)
        byte[] macKey = aesCmac(t, m);

        // LTK = AES-CMAC_T(1 || keyID || N1 || N2 || A1 || A2 || 256)
        m[0] = 0x01; // Counter = 1
        byte[] ltk = aesCmac(t, m);

        return new byte[][] { macKey, ltk };
    }

    /**
     * Computes the DHKey check value f6 for Secure Connections.
     *
     * <p>f6(W, N1, N2, R, IOcap, A1, A2) = AES-CMAC_W(N1 || N2 || R || IOcap || A1 || A2)
     *
     * @param w MacKey (16 bytes)
     * @param n1 Initiator nonce (16 bytes)
     * @param n2 Responder nonce (16 bytes)
     * @param r 128-bit value (16 bytes) - from passkey or zeros
     * @param ioCap IO capabilities (3 bytes: AuthReq || OOB || IO)
     * @param a1 Initiator address (6 bytes)
     * @param a2 Responder address (6 bytes)
     * @return DHKey check value Ea or Eb (16 bytes)
     */
    public static byte[] f6(byte[] w, byte[] n1, byte[] n2, byte[] r,
                            byte[] ioCap, byte[] a1, byte[] a2) {
        byte[] m = new byte[65];
        System.arraycopy(n1, 0, m, 0, 16);
        System.arraycopy(n2, 0, m, 16, 16);
        System.arraycopy(r, 0, m, 32, 16);
        System.arraycopy(ioCap, 0, m, 48, 3);
        System.arraycopy(a1, 0, m, 51, 6);
        System.arraycopy(a2, 0, m, 57, 6);
        // Last 2 bytes remain 0

        return aesCmac(w, m);
    }

    /**
     * Computes the numeric comparison value g2 for Secure Connections.
     *
     * <p>g2(U, V, X, Y) = AES-CMAC_X(U || V || Y) mod 10^6
     *
     * @param u Initiator public key X (32 bytes)
     * @param v Responder public key X (32 bytes)
     * @param x Initiator nonce Na (16 bytes)
     * @param y Responder nonce Nb (16 bytes)
     * @return 6-digit numeric comparison value (0-999999)
     */
    public static int g2(byte[] u, byte[] v, byte[] x, byte[] y) {
        byte[] m = new byte[80];
        System.arraycopy(u, 0, m, 0, 32);
        System.arraycopy(v, 0, m, 32, 32);
        System.arraycopy(y, 0, m, 64, 16);

        byte[] result = aesCmac(x, m);

        // Take last 4 bytes as big-endian integer
        long value = ((result[12] & 0xFFL) << 24) |
                ((result[13] & 0xFFL) << 16) |
                ((result[14] & 0xFFL) << 8) |
                (result[15] & 0xFFL);

        return (int) (value % 1000000);
    }

    // ==================== Cross-Transport Key Derivation (CTKD) ====================

    /**
     * Computes the h6 key derivation function for CTKD.
     *
     * <p>h6(W, keyID) = AES-CMAC_W(keyID)
     *
     * <p>Per Bluetooth Core Spec v5.3, Vol 3, Part H, Section 2.2.10.
     *
     * @param w Key (16 bytes) - LTK or intermediate key
     * @param keyId 4-byte key identifier (e.g., "tmp1", "lebr")
     * @return derived key (16 bytes)
     */
    public static byte[] h6(byte[] w, byte[] keyId) {
        if (w == null || w.length != 16) {
            throw new IllegalArgumentException("W must be 16 bytes");
        }
        if (keyId == null || keyId.length != 4) {
            throw new IllegalArgumentException("keyId must be 4 bytes");
        }
        return aesCmac(w, keyId);
    }

    /**
     * Derives a BR/EDR link key from an LE LTK using Cross-Transport Key Derivation.
     *
     * <p>For Legacy LE pairing:
     * <ul>
     *   <li>ILK = h6(LTK, "tmp1")</li>
     *   <li>Link Key = h6(ILK, "lebr")</li>
     * </ul>
     *
     * <p>For LE Secure Connections:
     * <ul>
     *   <li>Link Key = h6(LTK, "lebr")</li>
     * </ul>
     *
     * <p>Per Bluetooth Core Spec v5.3, Vol 3, Part H, Section 2.4.2.4.
     *
     * @param ltk Long Term Key from LE pairing (16 bytes)
     * @param isSecureConnections true if LTK was derived using LE Secure Connections
     * @return BR/EDR link key (16 bytes)
     */
    public static byte[] deriveBrEdrLinkKey(byte[] ltk, boolean isSecureConnections) {
        if (ltk == null || ltk.length != 16) {
            throw new IllegalArgumentException("LTK must be 16 bytes");
        }

        // Key IDs as byte arrays
        byte[] keyIdTmp1 = { 0x74, 0x6D, 0x70, 0x31 }; // "tmp1"
        byte[] keyIdLebr = { 0x6C, 0x65, 0x62, 0x72 }; // "lebr"

        if (isSecureConnections) {
            // For SC: Link Key = h6(LTK, "lebr")
            return h6(ltk, keyIdLebr);
        } else {
            // For Legacy: ILK = h6(LTK, "tmp1"), Link Key = h6(ILK, "lebr")
            byte[] ilk = h6(ltk, keyIdTmp1);
            return h6(ilk, keyIdLebr);
        }
    }

    /**
     * Derives an LE LTK from a BR/EDR link key using Cross-Transport Key Derivation.
     *
     * <p>For BR/EDR to LE key derivation (CT2):
     * <ul>
     *   <li>ILK = h6(LinkKey, "tmp2")</li>
     *   <li>LTK = h6(ILK, "brle")</li>
     * </ul>
     *
     * <p>Per Bluetooth Core Spec v5.3, Vol 3, Part H, Section 2.4.2.5.
     *
     * @param linkKey BR/EDR link key (16 bytes)
     * @return LE LTK (16 bytes)
     */
    public static byte[] deriveLeLtkFromLinkKey(byte[] linkKey) {
        if (linkKey == null || linkKey.length != 16) {
            throw new IllegalArgumentException("Link key must be 16 bytes");
        }

        byte[] keyIdTmp2 = { 0x74, 0x6D, 0x70, 0x32 }; // "tmp2"
        byte[] keyIdBrle = { 0x62, 0x72, 0x6C, 0x65 }; // "brle"

        byte[] ilk = h6(linkKey, keyIdTmp2);
        return h6(ilk, keyIdBrle);
    }

    // ==================== Core Crypto Operations ====================

    /**
     * AES-128 encryption in ECB mode (SMP uses little-endian).
     *
     * <p>Reverses input/output for little-endian compatibility.
     *
     * @param key encryption key (16 bytes)
     * @param data data to encrypt (16 bytes)
     * @return encrypted data (16 bytes)
     */
    public static byte[] aes128(byte[] key, byte[] data) {
        try {
            // SMP uses little-endian, AES uses big-endian
            byte[] keyBE = reverse(key);
            byte[] dataBE = reverse(data);

            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(keyBE, "AES");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            byte[] resultBE = cipher.doFinal(dataBE);

            return reverse(resultBE);
        } catch (Exception e) {
            CourierLogger.e(TAG, "AES-128 failed", e);
            return new byte[16];
        }
    }

    /**
     * AES-128 encryption in ECB mode (standard big-endian).
     *
     * @param key encryption key (16 bytes)
     * @param data data to encrypt (16 bytes)
     * @return encrypted data (16 bytes)
     */
    public static byte[] aes128Standard(byte[] key, byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            return cipher.doFinal(data);
        } catch (Exception e) {
            CourierLogger.e(TAG, "AES-128 (standard) failed", e);
            return new byte[16];
        }
    }

    /**
     * AES-CMAC message authentication code.
     *
     * <p>First tries the JCE provider, falls back to manual implementation.
     *
     * @param key CMAC key (16 bytes)
     * @param message message to authenticate
     * @return MAC (16 bytes)
     */
    public static byte[] aesCmac(byte[] key, byte[] message) {
        try {
            // Try JCE provider first (may not be available on all platforms)
            Mac mac = Mac.getInstance("AESCMAC");
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            mac.init(keySpec);
            return mac.doFinal(message);
        } catch (Exception e) {
            // Fall back to manual implementation
            return aesCmacManual(key, message);
        }
    }

    /**
     * Manual AES-CMAC implementation per RFC 4493.
     *
     * @param key CMAC key (16 bytes)
     * @param message message to authenticate
     * @return MAC (16 bytes)
     */
    private static byte[] aesCmacManual(byte[] key, byte[] message) {
        // Step 1: Generate subkeys K1 and K2
        byte[] zero = new byte[16];
        byte[] l = aes128Standard(key, zero);
        byte[] k1 = generateSubkey(l);
        byte[] k2 = generateSubkey(k1);

        // Step 2: Determine number of blocks
        int n = (message.length + 15) / 16;
        boolean lastBlockComplete;
        if (n == 0) {
            n = 1;
            lastBlockComplete = false;
        } else {
            lastBlockComplete = (message.length % 16 == 0);
        }

        // Step 3: Select key for last block
        byte[] lastBlock;
        if (lastBlockComplete) {
            lastBlock = xor(getBlock(message, n - 1), k1);
        } else {
            lastBlock = xor(padBlock(getLastBlock(message, n - 1)), k2);
        }

        // Step 4: CBC-MAC with last block modified
        byte[] x = new byte[16];
        for (int i = 0; i < n - 1; i++) {
            byte[] y = xor(x, getBlock(message, i));
            x = aes128Standard(key, y);
        }

        byte[] y = xor(x, lastBlock);
        return aes128Standard(key, y);
    }

    /**
     * Generates AES-CMAC subkey.
     *
     * @param l input block
     * @return subkey
     */
    private static byte[] generateSubkey(byte[] l) {
        byte[] result = new byte[16];
        int carry = 0;
        for (int i = 15; i >= 0; i--) {
            int temp = ((l[i] & 0xFF) << 1) | carry;
            result[i] = (byte) temp;
            carry = (l[i] & 0x80) != 0 ? 1 : 0;
        }
        if ((l[0] & 0x80) != 0) {
            result[15] ^= 0x87; // Rb = 0x87 for AES-128
        }
        return result;
    }

    // ==================== Helper Functions ====================

    /**
     * Reverses a byte array.
     *
     * @param data input array
     * @return reversed array
     */
    public static byte[] reverse(byte[] data) {
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = data[data.length - 1 - i];
        }
        return result;
    }

    /**
     * XORs two 16-byte arrays.
     *
     * @param a first array
     * @param b second array
     * @return XOR result
     */
    public static byte[] xor(byte[] a, byte[] b) {
        byte[] result = new byte[16];
        for (int i = 0; i < 16; i++) {
            result[i] = (byte) (a[i] ^ b[i]);
        }
        return result;
    }

    /**
     * Gets a 16-byte block from a message.
     *
     * @param data message
     * @param blockIndex block index
     * @return 16-byte block
     */
    private static byte[] getBlock(byte[] data, int blockIndex) {
        byte[] block = new byte[16];
        int offset = blockIndex * 16;
        int len = Math.min(16, data.length - offset);
        System.arraycopy(data, offset, block, 0, len);
        return block;
    }

    /**
     * Gets the last partial block from a message.
     *
     * @param data message
     * @param blockIndex block index
     * @return partial block
     */
    private static byte[] getLastBlock(byte[] data, int blockIndex) {
        int offset = blockIndex * 16;
        int len = data.length - offset;
        byte[] block = new byte[len];
        if (len > 0) {
            System.arraycopy(data, offset, block, 0, len);
        }
        return block;
    }

    /**
     * Pads a partial block per RFC 4493.
     *
     * @param block partial block
     * @return padded 16-byte block
     */
    private static byte[] padBlock(byte[] block) {
        byte[] padded = new byte[16];
        System.arraycopy(block, 0, padded, 0, block.length);
        padded[block.length] = (byte) 0x80;
        // Rest already zeros
        return padded;
    }

    /**
     * Converts a passkey to TK format for Legacy pairing.
     *
     * @param passkey 6-digit passkey (0-999999)
     * @return TK (16 bytes, little-endian)
     */
    public static byte[] passkeyToTk(int passkey) {
        byte[] tk = new byte[16];
        tk[0] = (byte) (passkey & 0xFF);
        tk[1] = (byte) ((passkey >> 8) & 0xFF);
        tk[2] = (byte) ((passkey >> 16) & 0xFF);
        tk[3] = (byte) ((passkey >> 24) & 0xFF);
        // tk[4..15] = 0
        return tk;
    }

    /**
     * Builds the 'r' value for SC DHKey check.
     *
     * @param passkey passkey value (or 0 for Just Works/Numeric Comparison)
     * @param isPasskeyMethod true if passkey entry method
     * @return r value (16 bytes)
     */
    public static byte[] buildScR(int passkey, boolean isPasskeyMethod) {
        byte[] r = new byte[16];
        if (isPasskeyMethod) {
            r[0] = (byte) (passkey & 0xFF);
            r[1] = (byte) ((passkey >> 8) & 0xFF);
            r[2] = (byte) ((passkey >> 16) & 0xFF);
            r[3] = (byte) ((passkey >> 24) & 0xFF);
        }
        return r;
    }

    // ==================== IRK Address Resolution ====================

    /**
     * Computes the 'ah' cryptographic function used for RPA resolution.
     *
     * <p>ah(k, r) = e(k, r') mod 2^24
     * where r' is r (24-bit prand) zero-padded to 128 bits.
     *
     * <p>Per Bluetooth Core Spec v5.3, Vol 3, Part H, Section 2.2.2.
     *
     * @param irk Identity Resolving Key (16 bytes)
     * @param prand 24-bit random part from RPA (3 bytes)
     * @return 24-bit hash (3 bytes)
     */
    public static byte[] ah(byte[] irk, byte[] prand) {
        if (irk == null || irk.length != 16) {
            throw new IllegalArgumentException("IRK must be 16 bytes");
        }
        if (prand == null || prand.length != 3) {
            throw new IllegalArgumentException("prand must be 3 bytes");
        }

        // r' = prand (24 bits) zero-padded to 128 bits
        // In little-endian: prand in bytes [13-15], rest zeros
        byte[] rPrime = new byte[16];
        rPrime[13] = prand[0];
        rPrime[14] = prand[1];
        rPrime[15] = prand[2];

        // Encrypt using AES-128 (SMP uses little-endian)
        byte[] encrypted = aes128(irk, rPrime);

        // Return first 3 bytes (hash = e(k, r') mod 2^24)
        byte[] hash = new byte[3];
        hash[0] = encrypted[0];
        hash[1] = encrypted[1];
        hash[2] = encrypted[2];

        return hash;
    }

    /**
     * Checks if a Resolvable Private Address (RPA) was generated using the given IRK.
     *
     * <p>An RPA has the format: hash[23:0] || prand[23:0]
     * where hash = ah(IRK, prand).
     *
     * <p>The two MSBs of prand must be '01' for a valid RPA.
     *
     * @param rpa Resolvable Private Address (6 bytes, little-endian)
     * @param irk Identity Resolving Key (16 bytes)
     * @return true if the RPA resolves to this IRK
     */
    public static boolean resolveRpa(byte[] rpa, byte[] irk) {
        if (rpa == null || rpa.length != 6) {
            return false;
        }
        if (irk == null || irk.length != 16) {
            return false;
        }

        // Check if this is actually an RPA (two MSBs of prand = '01')
        // prand is in bytes [3-5] of the address (big-endian BD_ADDR)
        // In little-endian storage: prand is at bytes [3-5], hash at [0-2]
        int prandMsb = (rpa[5] & 0xC0) >> 6;
        if (prandMsb != 0x01) {
            // Not a resolvable private address
            return false;
        }

        // Extract prand (bytes 3-5) and hash (bytes 0-2) from RPA
        byte[] prand = new byte[3];
        prand[0] = rpa[3];
        prand[1] = rpa[4];
        prand[2] = rpa[5];

        byte[] expectedHash = new byte[3];
        expectedHash[0] = rpa[0];
        expectedHash[1] = rpa[1];
        expectedHash[2] = rpa[2];

        // Calculate hash using ah function
        byte[] calculatedHash = ah(irk, prand);

        // Compare hashes
        return (calculatedHash[0] == expectedHash[0]) &&
                (calculatedHash[1] == expectedHash[1]) &&
                (calculatedHash[2] == expectedHash[2]);
    }

    /**
     * Checks if a Bluetooth address is a Resolvable Private Address (RPA).
     *
     * <p>An RPA has the format where the two MSBs of the address are '01'.
     *
     * @param address 6-byte Bluetooth address (little-endian)
     * @return true if the address is an RPA
     */
    public static boolean isResolvablePrivateAddress(byte[] address) {
        if (address == null || address.length != 6) {
            return false;
        }
        // Check two MSBs of the most significant byte (byte[5] in little-endian)
        int msb = (address[5] & 0xC0) >> 6;
        return msb == 0x01;
    }

    /**
     * Checks if a Bluetooth address is a Non-Resolvable Private Address.
     *
     * <p>A non-resolvable private address has the two MSBs set to '00'.
     *
     * @param address 6-byte Bluetooth address (little-endian)
     * @return true if the address is a non-resolvable private address
     */
    public static boolean isNonResolvablePrivateAddress(byte[] address) {
        if (address == null || address.length != 6) {
            return false;
        }
        int msb = (address[5] & 0xC0) >> 6;
        return msb == 0x00;
    }

    /**
     * Checks if a Bluetooth address is a Static Random Address.
     *
     * <p>A static random address has the two MSBs set to '11'.
     *
     * @param address 6-byte Bluetooth address (little-endian)
     * @return true if the address is a static random address
     */
    public static boolean isStaticRandomAddress(byte[] address) {
        if (address == null || address.length != 6) {
            return false;
        }
        int msb = (address[5] & 0xC0) >> 6;
        return msb == 0x03;
    }
}