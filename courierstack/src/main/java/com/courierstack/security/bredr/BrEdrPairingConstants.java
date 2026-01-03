package com.courierstack.security.bredr;

/**
 * BR/EDR Pairing constants per Bluetooth Core Spec v5.3, Vol 3, Part C.
 *
 * <p>This class contains all constants needed for BR/EDR SSP implementation
 * including IO capabilities, authentication requirements, link key types,
 * and HCI event codes.
 *
 * <p>Thread Safety: This class is immutable and thread-safe.
 */
public final class BrEdrPairingConstants {

    private BrEdrPairingConstants() {
        // Utility class - prevent instantiation
    }

    // ==================== IO Capability Values (Vol 3, Part C, Section 5.2.2.4) ====================

    /** DisplayOnly - can display but not receive input. */
    public static final int IO_CAP_DISPLAY_ONLY = 0x00;

    /** DisplayYesNo - can display and receive yes/no confirmation. */
    public static final int IO_CAP_DISPLAY_YES_NO = 0x01;

    /** KeyboardOnly - can receive keyboard input but no display. */
    public static final int IO_CAP_KEYBOARD_ONLY = 0x02;

    /** NoInputNoOutput - no input or output capability. */
    public static final int IO_CAP_NO_INPUT_NO_OUTPUT = 0x03;

    /** KeyboardDisplay - has both keyboard and display. */
    public static final int IO_CAP_KEYBOARD_DISPLAY = 0x04;

    // ==================== Authentication Requirements (Vol 3, Part C, Section 5.2.2.5) ====================

    /** No MITM protection, no bonding. */
    public static final int AUTH_REQ_NO_BONDING = 0x00;

    /** MITM protection required, no bonding. */
    public static final int AUTH_REQ_MITM_NO_BONDING = 0x01;

    /** No MITM protection, dedicated bonding. */
    public static final int AUTH_REQ_DEDICATED_BONDING = 0x02;

    /** MITM protection required, dedicated bonding. */
    public static final int AUTH_REQ_MITM_DEDICATED_BONDING = 0x03;

    /** No MITM protection, general bonding. */
    public static final int AUTH_REQ_GENERAL_BONDING = 0x04;

    /** MITM protection required, general bonding. */
    public static final int AUTH_REQ_MITM_GENERAL_BONDING = 0x05;

    // ==================== Link Key Types ====================

    /** Combination key (legacy pairing). */
    public static final int KEY_TYPE_COMBINATION = 0x00;

    /** Debug combination key. */
    public static final int KEY_TYPE_DEBUG_COMBINATION = 0x03;

    /** Unauthenticated P-192 key (Just Works). */
    public static final int KEY_TYPE_UNAUTHENTICATED_P192 = 0x04;

    /** Authenticated P-192 key (MITM protected). */
    public static final int KEY_TYPE_AUTHENTICATED_P192 = 0x05;

    /** Changed combination key. */
    public static final int KEY_TYPE_CHANGED_COMBINATION = 0x06;

    /** Unauthenticated P-256 key. */
    public static final int KEY_TYPE_UNAUTHENTICATED_P256 = 0x07;

    /** Authenticated P-256 key (MITM protected). */
    public static final int KEY_TYPE_AUTHENTICATED_P256 = 0x08;

    // ==================== HCI Event Codes ====================

    /** IO Capability Request event. */
    public static final int EVT_IO_CAPABILITY_REQUEST = 0x31;

    /** IO Capability Response event. */
    public static final int EVT_IO_CAPABILITY_RESPONSE = 0x32;

    /** User Confirmation Request event. */
    public static final int EVT_USER_CONFIRMATION_REQUEST = 0x33;

    /** User Passkey Request event. */
    public static final int EVT_USER_PASSKEY_REQUEST = 0x34;

    /** User Passkey Notification event. */
    public static final int EVT_USER_PASSKEY_NOTIFICATION = 0x3B;

    /** Authentication Complete event. */
    public static final int EVT_AUTHENTICATION_COMPLETE = 0x06;

    /** Encryption Change event. */
    public static final int EVT_ENCRYPTION_CHANGE = 0x08;

    /** Link Key Request event. */
    public static final int EVT_LINK_KEY_REQUEST = 0x17;

    /** Link Key Notification event. */
    public static final int EVT_LINK_KEY_NOTIFICATION = 0x18;

    /** Simple Pairing Complete event. */
    public static final int EVT_SIMPLE_PAIRING_COMPLETE = 0x36;

    // ==================== Error Codes ====================

    /** Pairing not allowed. */
    public static final int ERR_PAIRING_NOT_ALLOWED = 0x18;

    /** Authentication failure. */
    public static final int ERR_AUTHENTICATION_FAILURE = 0x05;

    /** PIN or key missing. */
    public static final int ERR_PIN_OR_KEY_MISSING = 0x06;

    /** Connection rejected due to security. */
    public static final int ERR_CONNECTION_REJECTED_SECURITY = 0x0E;

    // ==================== Utility Methods ====================

    /**
     * Returns human-readable IO capability description.
     *
     * @param ioCap IO capability value
     * @return IO capability description
     */
    public static String getIoCapabilityString(int ioCap) {
        switch (ioCap) {
            case IO_CAP_DISPLAY_ONLY:
                return "DisplayOnly";
            case IO_CAP_DISPLAY_YES_NO:
                return "DisplayYesNo";
            case IO_CAP_KEYBOARD_ONLY:
                return "KeyboardOnly";
            case IO_CAP_NO_INPUT_NO_OUTPUT:
                return "NoInputNoOutput";
            case IO_CAP_KEYBOARD_DISPLAY:
                return "KeyboardDisplay";
            default:
                return String.format("Unknown (0x%02X)", ioCap);
        }
    }

    /**
     * Returns human-readable authentication requirement description.
     *
     * @param authReq authentication requirement value
     * @return authentication requirement description
     */
    public static String getAuthReqString(int authReq) {
        switch (authReq) {
            case AUTH_REQ_NO_BONDING:
                return "NoBonding";
            case AUTH_REQ_MITM_NO_BONDING:
                return "MITM+NoBonding";
            case AUTH_REQ_DEDICATED_BONDING:
                return "DedicatedBonding";
            case AUTH_REQ_MITM_DEDICATED_BONDING:
                return "MITM+DedicatedBonding";
            case AUTH_REQ_GENERAL_BONDING:
                return "GeneralBonding";
            case AUTH_REQ_MITM_GENERAL_BONDING:
                return "MITM+GeneralBonding";
            default:
                return String.format("Unknown (0x%02X)", authReq);
        }
    }

    /**
     * Returns human-readable link key type description.
     *
     * @param keyType link key type value
     * @return link key type description
     */
    public static String getLinkKeyTypeString(int keyType) {
        switch (keyType) {
            case KEY_TYPE_COMBINATION:
                return "Combination";
            case KEY_TYPE_DEBUG_COMBINATION:
                return "DebugCombination";
            case KEY_TYPE_UNAUTHENTICATED_P192:
                return "UnauthenticatedP192";
            case KEY_TYPE_AUTHENTICATED_P192:
                return "AuthenticatedP192";
            case KEY_TYPE_CHANGED_COMBINATION:
                return "ChangedCombination";
            case KEY_TYPE_UNAUTHENTICATED_P256:
                return "UnauthenticatedP256";
            case KEY_TYPE_AUTHENTICATED_P256:
                return "AuthenticatedP256";
            default:
                return String.format("Unknown (0x%02X)", keyType);
        }
    }

    /**
     * Returns whether the link key type provides MITM protection.
     *
     * @param keyType link key type
     * @return true if MITM protected
     */
    public static boolean isMitmProtected(int keyType) {
        return keyType == KEY_TYPE_AUTHENTICATED_P192 ||
                keyType == KEY_TYPE_AUTHENTICATED_P256;
    }

    /**
     * Formats a Bluetooth address as a string (XX:XX:XX:XX:XX:XX).
     *
     * @param address 6-byte address in little-endian
     * @return formatted address string
     */
    public static String formatAddress(byte[] address) {
        if (address == null || address.length != 6) {
            return "??:??:??:??:??:??";
        }
        return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                address[5] & 0xFF, address[4] & 0xFF, address[3] & 0xFF,
                address[2] & 0xFF, address[1] & 0xFF, address[0] & 0xFF);
    }

    /**
     * Formats bytes as a hexadecimal string.
     *
     * @param bytes byte array
     * @return hex string
     */
    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }
}