package com.courierstack.security.le;

/**
 * Security Manager Protocol constants per Bluetooth Core Spec v5.3, Vol 3, Part H.
 *
 * <p>This class contains all constants needed for SMP protocol implementation
 * including command codes, error codes, IO capabilities, and key distribution flags.
 *
 * <p>Thread Safety: This class is immutable and thread-safe.
 */
public final class SmpConstants {

    private SmpConstants() {
        // Utility class - prevent instantiation
    }

    // ==================== Fixed L2CAP Channel ====================

    /** SMP fixed L2CAP channel ID for LE. */
    public static final int SMP_CID = 0x0006;

    /** SMP fixed L2CAP channel ID for BR/EDR. */
    public static final int SMP_BR_EDR_CID = 0x0007;

    // ==================== Command Codes (Section 3.3) ====================

    public static final int PAIRING_REQUEST = 0x01;
    public static final int PAIRING_RESPONSE = 0x02;
    public static final int PAIRING_CONFIRM = 0x03;
    public static final int PAIRING_RANDOM = 0x04;
    public static final int PAIRING_FAILED = 0x05;
    public static final int ENCRYPTION_INFORMATION = 0x06;
    public static final int MASTER_IDENTIFICATION = 0x07;
    public static final int IDENTITY_INFORMATION = 0x08;
    public static final int IDENTITY_ADDRESS_INFORMATION = 0x09;
    public static final int SIGNING_INFORMATION = 0x0A;
    public static final int SECURITY_REQUEST = 0x0B;
    public static final int PAIRING_PUBLIC_KEY = 0x0C;
    public static final int PAIRING_DHKEY_CHECK = 0x0D;
    public static final int PAIRING_KEYPRESS_NOTIFICATION = 0x0E;

    // Aliases for compatibility
    public static final int ENCRYPTION_INFO = ENCRYPTION_INFORMATION;
    public static final int IDENTITY_INFO = IDENTITY_INFORMATION;
    public static final int IDENTITY_ADDRESS_INFO = IDENTITY_ADDRESS_INFORMATION;

    // ==================== Error Codes (Section 3.5.5) ====================

    public static final int ERR_PASSKEY_ENTRY_FAILED = 0x01;
    public static final int ERR_OOB_NOT_AVAILABLE = 0x02;
    public static final int ERR_AUTHENTICATION_REQUIREMENTS = 0x03;
    public static final int ERR_CONFIRM_VALUE_FAILED = 0x04;
    public static final int ERR_PAIRING_NOT_SUPPORTED = 0x05;
    public static final int ERR_ENCRYPTION_KEY_SIZE = 0x06;
    public static final int ERR_COMMAND_NOT_SUPPORTED = 0x07;
    public static final int ERR_UNSPECIFIED_REASON = 0x08;
    public static final int ERR_REPEATED_ATTEMPTS = 0x09;
    public static final int ERR_INVALID_PARAMETERS = 0x0A;
    public static final int ERR_DHKEY_CHECK_FAILED = 0x0B;
    public static final int ERR_NUMERIC_COMPARISON_FAILED = 0x0C;
    public static final int ERR_BR_EDR_IN_PROGRESS = 0x0D;
    public static final int ERR_CROSS_TRANSPORT_NOT_ALLOWED = 0x0E;
    public static final int ERR_KEY_REJECTED = 0x0F;

    /** Alias for ERR_UNSPECIFIED_REASON. */
    public static final int ERR_UNSPECIFIED = ERR_UNSPECIFIED_REASON;

    // ==================== IO Capability (Section 3.5.1) ====================

    public static final int IO_CAP_DISPLAY_ONLY = 0x00;
    public static final int IO_CAP_DISPLAY_YES_NO = 0x01;
    public static final int IO_CAP_KEYBOARD_ONLY = 0x02;
    public static final int IO_CAP_NO_INPUT_NO_OUTPUT = 0x03;
    public static final int IO_CAP_KEYBOARD_DISPLAY = 0x04;

    // ==================== OOB Data Flag (Section 3.5.2) ====================

    public static final int OOB_AUTH_DATA_NOT_PRESENT = 0x00;
    public static final int OOB_AUTH_DATA_PRESENT = 0x01;
    public static final int OOB_DATA_NOT_PRESENT = OOB_AUTH_DATA_NOT_PRESENT;
    public static final int OOB_DATA_PRESENT = OOB_AUTH_DATA_PRESENT;

    // ==================== AuthReq Flags (Section 3.5.1) ====================

    public static final int AUTH_REQ_NO_BONDING = 0x00;
    public static final int AUTH_REQ_BONDING = 0x01;
    public static final int AUTH_REQ_MITM = 0x04;
    public static final int AUTH_REQ_SC = 0x08;
    public static final int AUTH_REQ_KEYPRESS = 0x10;
    public static final int AUTH_REQ_CT2 = 0x20;

    // ==================== Key Distribution Flags (Section 3.6.1) ====================

    public static final int KEY_DIST_ENC_KEY = 0x01;
    public static final int KEY_DIST_ID_KEY = 0x02;
    public static final int KEY_DIST_SIGN = 0x04;
    public static final int KEY_DIST_LINK_KEY = 0x08;

    // ==================== Key Sizes ====================

    public static final int MIN_ENC_KEY_SIZE = 7;
    public static final int MAX_ENC_KEY_SIZE = 16;

    // ==================== Keypress Notification Types (Section 3.5.8) ====================

    public static final int KEYPRESS_STARTED = 0x00;
    public static final int KEYPRESS_DIGIT_ENTERED = 0x01;
    public static final int KEYPRESS_DIGIT_ERASED = 0x02;
    public static final int KEYPRESS_CLEARED = 0x03;
    public static final int KEYPRESS_COMPLETED = 0x04;

    // ==================== HCI Event Codes ====================

    public static final int HCI_LE_META_EVENT = 0x3E;
    public static final int HCI_ENCRYPTION_CHANGE_EVENT = 0x08;
    public static final int HCI_ENCRYPTION_KEY_REFRESH_COMPLETE = 0x30;

    // LE Meta Subevents
    public static final int HCI_LE_CONNECTION_COMPLETE = 0x01;
    public static final int HCI_LE_LONG_TERM_KEY_REQUEST = 0x05;
    public static final int HCI_LE_READ_LOCAL_P256_PUBLIC_KEY_COMPLETE = 0x08;
    public static final int HCI_LE_GENERATE_DHKEY_COMPLETE = 0x09;
    public static final int HCI_LE_ENHANCED_CONNECTION_COMPLETE = 0x0A;
    public static final int HCI_LE_GENERATE_DHKEY_COMPLETE_V2 = 0x1E;

    // ==================== Utility Methods ====================

    /**
     * Returns human-readable error description.
     *
     * @param errorCode SMP error code
     * @return error description string
     */
    public static String getErrorString(int errorCode) {
        switch (errorCode) {
            case ERR_PASSKEY_ENTRY_FAILED: return "Passkey Entry Failed";
            case ERR_OOB_NOT_AVAILABLE: return "OOB Not Available";
            case ERR_AUTHENTICATION_REQUIREMENTS: return "Authentication Requirements";
            case ERR_CONFIRM_VALUE_FAILED: return "Confirm Value Failed";
            case ERR_PAIRING_NOT_SUPPORTED: return "Pairing Not Supported";
            case ERR_ENCRYPTION_KEY_SIZE: return "Encryption Key Size";
            case ERR_COMMAND_NOT_SUPPORTED: return "Command Not Supported";
            case ERR_UNSPECIFIED_REASON: return "Unspecified Reason";
            case ERR_REPEATED_ATTEMPTS: return "Repeated Attempts";
            case ERR_INVALID_PARAMETERS: return "Invalid Parameters";
            case ERR_DHKEY_CHECK_FAILED: return "DHKey Check Failed";
            case ERR_NUMERIC_COMPARISON_FAILED: return "Numeric Comparison Failed";
            case ERR_BR_EDR_IN_PROGRESS: return "BR/EDR Pairing In Progress";
            case ERR_CROSS_TRANSPORT_NOT_ALLOWED: return "Cross-Transport Key Not Allowed";
            case ERR_KEY_REJECTED: return "Key Rejected";
            default: return String.format("Unknown Error (0x%02X)", errorCode);
        }
    }

    /**
     * Returns human-readable IO capability description.
     *
     * @param ioCap IO capability value
     * @return IO capability description
     */
    public static String getIoCapabilityString(int ioCap) {
        switch (ioCap) {
            case IO_CAP_DISPLAY_ONLY: return "DisplayOnly";
            case IO_CAP_DISPLAY_YES_NO: return "DisplayYesNo";
            case IO_CAP_KEYBOARD_ONLY: return "KeyboardOnly";
            case IO_CAP_NO_INPUT_NO_OUTPUT: return "NoInputNoOutput";
            case IO_CAP_KEYBOARD_DISPLAY: return "KeyboardDisplay";
            default: return String.format("Unknown (0x%02X)", ioCap);
        }
    }

    /**
     * Returns human-readable command name.
     *
     * @param code SMP command code
     * @return command name
     */
    public static String getCommandName(int code) {
        switch (code) {
            case PAIRING_REQUEST: return "Pairing Request";
            case PAIRING_RESPONSE: return "Pairing Response";
            case PAIRING_CONFIRM: return "Pairing Confirm";
            case PAIRING_RANDOM: return "Pairing Random";
            case PAIRING_FAILED: return "Pairing Failed";
            case ENCRYPTION_INFORMATION: return "Encryption Information";
            case MASTER_IDENTIFICATION: return "Master Identification";
            case IDENTITY_INFORMATION: return "Identity Information";
            case IDENTITY_ADDRESS_INFORMATION: return "Identity Address Information";
            case SIGNING_INFORMATION: return "Signing Information";
            case SECURITY_REQUEST: return "Security Request";
            case PAIRING_PUBLIC_KEY: return "Pairing Public Key";
            case PAIRING_DHKEY_CHECK: return "Pairing DHKey Check";
            case PAIRING_KEYPRESS_NOTIFICATION: return "Keypress Notification";
            default: return String.format("Unknown (0x%02X)", code);
        }
    }

    /**
     * Formats authentication requirements as a readable string.
     *
     * @param authReq authentication requirements flags
     * @return formatted string
     */
    public static String formatAuthReq(int authReq) {
        StringBuilder sb = new StringBuilder();
        if ((authReq & AUTH_REQ_BONDING) != 0) sb.append("Bonding ");
        if ((authReq & AUTH_REQ_MITM) != 0) sb.append("MITM ");
        if ((authReq & AUTH_REQ_SC) != 0) sb.append("SC ");
        if ((authReq & AUTH_REQ_KEYPRESS) != 0) sb.append("Keypress ");
        if ((authReq & AUTH_REQ_CT2) != 0) sb.append("CT2 ");
        return sb.length() > 0 ? sb.toString().trim() : "None";
    }

    /**
     * Formats key distribution flags as a readable string.
     *
     * @param keyDist key distribution flags
     * @return formatted string
     */
    public static String formatKeyDist(int keyDist) {
        StringBuilder sb = new StringBuilder();
        if ((keyDist & KEY_DIST_ENC_KEY) != 0) sb.append("EncKey ");
        if ((keyDist & KEY_DIST_ID_KEY) != 0) sb.append("IdKey ");
        if ((keyDist & KEY_DIST_SIGN) != 0) sb.append("Sign ");
        if ((keyDist & KEY_DIST_LINK_KEY) != 0) sb.append("LinkKey ");
        return sb.length() > 0 ? sb.toString().trim() : "None";
    }

    /**
     * Formats a Bluetooth address as a string (XX:XX:XX:XX:XX:XX).
     *
     * @param address 6-byte address in little-endian
     * @return formatted address string
     */
    public static String formatAddress(byte[] address) {
        if (address == null || address.length != 6) return "??:??:??:??:??:??";
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