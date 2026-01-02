package com.courierstack.gatt;

import java.util.UUID;

/**
 * GATT and ATT protocol constants per Bluetooth Core Spec v5.3.
 *
 * <p>This class serves as the single source of truth for all ATT/GATT protocol
 * constants including opcodes, error codes, UUIDs, and configuration values.
 *
 * <p>Reference: Bluetooth Core Spec v5.3, Vol 3, Part F (ATT) and Part G (GATT)
 */
public final class GattConstants {

    private GattConstants() {
        // Utility class - prevent instantiation
    }

    // ==================== Channel Configuration ====================

    /** ATT fixed channel ID (CID) for LE connections. */
    public static final int ATT_CID = 0x0004;

    // ==================== ATT Opcodes (Vol 3, Part F, Section 3.4) ====================

    /** Error Response. */
    public static final int ATT_ERROR_RSP = 0x01;
    /** Exchange MTU Request. */
    public static final int ATT_EXCHANGE_MTU_REQ = 0x02;
    /** Exchange MTU Response. */
    public static final int ATT_EXCHANGE_MTU_RSP = 0x03;
    /** Find Information Request. */
    public static final int ATT_FIND_INFORMATION_REQ = 0x04;
    /** Find Information Response. */
    public static final int ATT_FIND_INFORMATION_RSP = 0x05;
    /** Find By Type Value Request. */
    public static final int ATT_FIND_BY_TYPE_VALUE_REQ = 0x06;
    /** Find By Type Value Response. */
    public static final int ATT_FIND_BY_TYPE_VALUE_RSP = 0x07;
    /** Read By Type Request. */
    public static final int ATT_READ_BY_TYPE_REQ = 0x08;
    /** Read By Type Response. */
    public static final int ATT_READ_BY_TYPE_RSP = 0x09;
    /** Read Request. */
    public static final int ATT_READ_REQ = 0x0A;
    /** Read Response. */
    public static final int ATT_READ_RSP = 0x0B;
    /** Read Blob Request. */
    public static final int ATT_READ_BLOB_REQ = 0x0C;
    /** Read Blob Response. */
    public static final int ATT_READ_BLOB_RSP = 0x0D;
    /** Read Multiple Request. */
    public static final int ATT_READ_MULTIPLE_REQ = 0x0E;
    /** Read Multiple Response. */
    public static final int ATT_READ_MULTIPLE_RSP = 0x0F;
    /** Read By Group Type Request. */
    public static final int ATT_READ_BY_GROUP_TYPE_REQ = 0x10;
    /** Read By Group Type Response. */
    public static final int ATT_READ_BY_GROUP_TYPE_RSP = 0x11;
    /** Write Request. */
    public static final int ATT_WRITE_REQ = 0x12;
    /** Write Response. */
    public static final int ATT_WRITE_RSP = 0x13;
    /** Prepare Write Request. */
    public static final int ATT_PREPARE_WRITE_REQ = 0x16;
    /** Prepare Write Response. */
    public static final int ATT_PREPARE_WRITE_RSP = 0x17;
    /** Execute Write Request. */
    public static final int ATT_EXECUTE_WRITE_REQ = 0x18;
    /** Execute Write Response. */
    public static final int ATT_EXECUTE_WRITE_RSP = 0x19;
    /** Handle Value Notification. */
    public static final int ATT_HANDLE_VALUE_NTF = 0x1B;
    /** Handle Value Indication. */
    public static final int ATT_HANDLE_VALUE_IND = 0x1D;
    /** Handle Value Confirmation. */
    public static final int ATT_HANDLE_VALUE_CFM = 0x1E;
    /** Write Command (no response). */
    public static final int ATT_WRITE_CMD = 0x52;
    /** Signed Write Command. */
    public static final int ATT_SIGNED_WRITE_CMD = 0xD2;

    // ==================== ATT Error Codes (Vol 3, Part F, Section 3.4.1.1) ====================

    /** Invalid Handle. */
    public static final int ATT_ERR_INVALID_HANDLE = 0x01;
    /** Read Not Permitted. */
    public static final int ATT_ERR_READ_NOT_PERMITTED = 0x02;
    /** Write Not Permitted. */
    public static final int ATT_ERR_WRITE_NOT_PERMITTED = 0x03;
    /** Invalid PDU. */
    public static final int ATT_ERR_INVALID_PDU = 0x04;
    /** Insufficient Authentication. */
    public static final int ATT_ERR_INSUFFICIENT_AUTHENTICATION = 0x05;
    /** Request Not Supported. */
    public static final int ATT_ERR_REQUEST_NOT_SUPPORTED = 0x06;
    /** Invalid Offset. */
    public static final int ATT_ERR_INVALID_OFFSET = 0x07;
    /** Insufficient Authorization. */
    public static final int ATT_ERR_INSUFFICIENT_AUTHORIZATION = 0x08;
    /** Prepare Queue Full. */
    public static final int ATT_ERR_PREPARE_QUEUE_FULL = 0x09;
    /** Attribute Not Found. */
    public static final int ATT_ERR_ATTRIBUTE_NOT_FOUND = 0x0A;
    /** Attribute Not Long. */
    public static final int ATT_ERR_ATTRIBUTE_NOT_LONG = 0x0B;
    /** Insufficient Encryption Key Size. */
    public static final int ATT_ERR_INSUFFICIENT_ENCRYPTION_KEY_SIZE = 0x0C;
    /** Invalid Attribute Length. */
    public static final int ATT_ERR_INVALID_ATTRIBUTE_LENGTH = 0x0D;
    /** Unlikely Error. */
    public static final int ATT_ERR_UNLIKELY_ERROR = 0x0E;
    /** Insufficient Encryption. */
    public static final int ATT_ERR_INSUFFICIENT_ENCRYPTION = 0x0F;
    /** Unsupported Group Type. */
    public static final int ATT_ERR_UNSUPPORTED_GROUP_TYPE = 0x10;
    /** Insufficient Resources. */
    public static final int ATT_ERR_INSUFFICIENT_RESOURCES = 0x11;
    /** Application Error range start. */
    public static final int ATT_ERR_APP_ERROR_START = 0x80;
    /** Application Error range end. */
    public static final int ATT_ERR_APP_ERROR_END = 0x9F;

    // ==================== MTU Configuration ====================

    /** Default LE MTU (minimum). */
    public static final int ATT_DEFAULT_LE_MTU = 23;
    /** Maximum supported MTU. */
    public static final int ATT_MAX_MTU = 517;

    // ==================== GATT Attribute Type UUIDs (Assigned Numbers) ====================

    /** Primary Service Declaration. */
    public static final int GATT_PRIMARY_SERVICE_UUID = 0x2800;
    /** Secondary Service Declaration. */
    public static final int GATT_SECONDARY_SERVICE_UUID = 0x2801;
    /** Include Declaration. */
    public static final int GATT_INCLUDE_UUID = 0x2802;
    /** Characteristic Declaration. */
    public static final int GATT_CHARACTERISTIC_UUID = 0x2803;
    /** Characteristic Extended Properties Descriptor. */
    public static final int GATT_CHAR_EXT_PROPS_UUID = 0x2900;
    /** Characteristic User Description Descriptor. */
    public static final int GATT_CHAR_USER_DESC_UUID = 0x2901;
    /** Client Characteristic Configuration Descriptor. */
    public static final int GATT_CLIENT_CHAR_CONFIG_UUID = 0x2902;
    /** Server Characteristic Configuration Descriptor. */
    public static final int GATT_SERVER_CHAR_CONFIG_UUID = 0x2903;
    /** Characteristic Presentation Format Descriptor. */
    public static final int GATT_CHAR_FORMAT_UUID = 0x2904;
    /** Characteristic Aggregate Format Descriptor. */
    public static final int GATT_CHAR_AGGREGATE_FORMAT_UUID = 0x2905;

    // ==================== Standard Service UUIDs ====================

    /** Generic Access Profile Service. */
    public static final UUID UUID_GAP_SERVICE = uuidFrom16Bit(0x1800);
    /** Generic Attribute Profile Service. */
    public static final UUID UUID_GATT_SERVICE = uuidFrom16Bit(0x1801);
    /** Device Information Service. */
    public static final UUID UUID_DEVICE_INFO_SERVICE = uuidFrom16Bit(0x180A);
    /** Battery Service. */
    public static final UUID UUID_BATTERY_SERVICE = uuidFrom16Bit(0x180F);
    /** Heart Rate Service. */
    public static final UUID UUID_HEART_RATE_SERVICE = uuidFrom16Bit(0x180D);

    // ==================== Standard Characteristic UUIDs ====================

    /** Device Name Characteristic. */
    public static final UUID UUID_DEVICE_NAME = uuidFrom16Bit(0x2A00);
    /** Appearance Characteristic. */
    public static final UUID UUID_APPEARANCE = uuidFrom16Bit(0x2A01);
    /** Peripheral Privacy Flag Characteristic. */
    public static final UUID UUID_PERIPHERAL_PRIVACY_FLAG = uuidFrom16Bit(0x2A02);
    /** Reconnection Address Characteristic. */
    public static final UUID UUID_RECONNECTION_ADDRESS = uuidFrom16Bit(0x2A03);
    /** Peripheral Preferred Connection Parameters Characteristic. */
    public static final UUID UUID_PPCP = uuidFrom16Bit(0x2A04);
    /** Service Changed Characteristic. */
    public static final UUID UUID_SERVICE_CHANGED = uuidFrom16Bit(0x2A05);
    /** Battery Level Characteristic. */
    public static final UUID UUID_BATTERY_LEVEL = uuidFrom16Bit(0x2A19);

    // ==================== CCCD Values ====================

    /** CCCD: Notifications and Indications disabled. */
    public static final int CCCD_NONE = 0x0000;
    /** CCCD: Notifications enabled. */
    public static final int CCCD_NOTIFICATION = 0x0001;
    /** CCCD: Indications enabled. */
    public static final int CCCD_INDICATION = 0x0002;

    // ==================== Characteristic Properties (Vol 3, Part G, Section 3.3.1.1) ====================

    /** Broadcast property. */
    public static final int CHAR_PROP_BROADCAST = 0x01;
    /** Read property. */
    public static final int CHAR_PROP_READ = 0x02;
    /** Write Without Response property. */
    public static final int CHAR_PROP_WRITE_NO_RSP = 0x04;
    /** Write property. */
    public static final int CHAR_PROP_WRITE = 0x08;
    /** Notify property. */
    public static final int CHAR_PROP_NOTIFY = 0x10;
    /** Indicate property. */
    public static final int CHAR_PROP_INDICATE = 0x20;
    /** Authenticated Signed Writes property. */
    public static final int CHAR_PROP_AUTH_SIGNED_WRITE = 0x40;
    /** Extended Properties property. */
    public static final int CHAR_PROP_EXTENDED = 0x80;

    // ==================== Characteristic Permissions ====================

    /** Read permission. */
    public static final int CHAR_PERM_READ = 0x01;
    /** Read with encryption permission. */
    public static final int CHAR_PERM_READ_ENCRYPTED = 0x02;
    /** Read with encryption and MITM protection permission. */
    public static final int CHAR_PERM_READ_ENCRYPTED_MITM = 0x04;
    /** Write permission. */
    public static final int CHAR_PERM_WRITE = 0x10;
    /** Write with encryption permission. */
    public static final int CHAR_PERM_WRITE_ENCRYPTED = 0x20;
    /** Write with encryption and MITM protection permission. */
    public static final int CHAR_PERM_WRITE_ENCRYPTED_MITM = 0x40;

    // ==================== Bluetooth Base UUID ====================

    /** Bluetooth Base UUID suffix (lower 96 bits). */
    private static final long BT_BASE_UUID_LSB = 0x800000805F9B34FBL;
    /** Bluetooth Base UUID prefix mask. */
    private static final long BT_BASE_UUID_MSB_MASK = 0x0000FFFFFFFFFFFFL;
    /** Expected MSB pattern for 16-bit UUIDs. */
    private static final long BT_BASE_UUID_MSB_PATTERN = 0x0000000010000000L;

    // ==================== UUID Utilities ====================

    /**
     * Converts a 16-bit UUID to full 128-bit Bluetooth Base UUID format.
     *
     * @param shortUuid 16-bit UUID value (0x0000-0xFFFF)
     * @return 128-bit UUID in Bluetooth Base UUID format
     */
    public static UUID uuidFrom16Bit(int shortUuid) {
        long msb = ((long) (shortUuid & 0xFFFF) << 32) | BT_BASE_UUID_MSB_PATTERN;
        return new UUID(msb, BT_BASE_UUID_LSB);
    }

    /**
     * Converts a 32-bit UUID to full 128-bit Bluetooth Base UUID format.
     *
     * @param uuid32 32-bit UUID value
     * @return 128-bit UUID in Bluetooth Base UUID format
     */
    public static UUID uuidFrom32Bit(long uuid32) {
        long msb = ((uuid32 & 0xFFFFFFFFL) << 32) | BT_BASE_UUID_MSB_PATTERN;
        return new UUID(msb, BT_BASE_UUID_LSB);
    }

    /**
     * Checks if a UUID is a standard Bluetooth 16-bit UUID.
     *
     * @param uuid UUID to check
     * @return true if it's a 16-bit Bluetooth UUID
     */
    public static boolean is16BitUuid(UUID uuid) {
        if (uuid == null) return false;
        if (uuid.getLeastSignificantBits() != BT_BASE_UUID_LSB) return false;
        long msb = uuid.getMostSignificantBits();
        return (msb & BT_BASE_UUID_MSB_MASK) == BT_BASE_UUID_MSB_PATTERN;
    }

    /**
     * Extracts the 16-bit UUID value from a Bluetooth Base UUID.
     *
     * @param uuid 128-bit UUID
     * @return 16-bit UUID value, or -1 if not a standard Bluetooth UUID
     */
    public static int uuidTo16Bit(UUID uuid) {
        if (!is16BitUuid(uuid)) {
            return -1;
        }
        return (int) ((uuid.getMostSignificantBits() >> 32) & 0xFFFF);
    }

    /**
     * Returns a short string representation of a UUID.
     *
     * <p>For 16-bit Bluetooth UUIDs, returns "0xXXXX" format.
     * For other UUIDs, returns the full UUID string.
     *
     * @param uuid UUID to format
     * @return formatted UUID string
     */
    public static String getShortUuid(UUID uuid) {
        if (uuid == null) return "null";
        int short16 = uuidTo16Bit(uuid);
        if (short16 >= 0) {
            return String.format("0x%04X", short16);
        }
        return uuid.toString();
    }

    // ==================== Error Code Utilities ====================

    /**
     * Returns a human-readable description of an ATT error code.
     *
     * @param errorCode ATT error code
     * @return error description
     */
    public static String getAttErrorString(int errorCode) {
        switch (errorCode) {
            case 0x00: return "Success";
            case ATT_ERR_INVALID_HANDLE: return "Invalid Handle";
            case ATT_ERR_READ_NOT_PERMITTED: return "Read Not Permitted";
            case ATT_ERR_WRITE_NOT_PERMITTED: return "Write Not Permitted";
            case ATT_ERR_INVALID_PDU: return "Invalid PDU";
            case ATT_ERR_INSUFFICIENT_AUTHENTICATION: return "Insufficient Authentication";
            case ATT_ERR_REQUEST_NOT_SUPPORTED: return "Request Not Supported";
            case ATT_ERR_INVALID_OFFSET: return "Invalid Offset";
            case ATT_ERR_INSUFFICIENT_AUTHORIZATION: return "Insufficient Authorization";
            case ATT_ERR_PREPARE_QUEUE_FULL: return "Prepare Queue Full";
            case ATT_ERR_ATTRIBUTE_NOT_FOUND: return "Attribute Not Found";
            case ATT_ERR_ATTRIBUTE_NOT_LONG: return "Attribute Not Long";
            case ATT_ERR_INSUFFICIENT_ENCRYPTION_KEY_SIZE: return "Insufficient Encryption Key Size";
            case ATT_ERR_INVALID_ATTRIBUTE_LENGTH: return "Invalid Attribute Length";
            case ATT_ERR_UNLIKELY_ERROR: return "Unlikely Error";
            case ATT_ERR_INSUFFICIENT_ENCRYPTION: return "Insufficient Encryption";
            case ATT_ERR_UNSUPPORTED_GROUP_TYPE: return "Unsupported Group Type";
            case ATT_ERR_INSUFFICIENT_RESOURCES: return "Insufficient Resources";
            default:
                if (errorCode >= ATT_ERR_APP_ERROR_START && errorCode <= ATT_ERR_APP_ERROR_END) {
                    return String.format("Application Error (0x%02X)", errorCode);
                }
                return String.format("Unknown Error (0x%02X)", errorCode);
        }
    }

    /**
     * Returns a human-readable name for an ATT opcode.
     *
     * @param opcode ATT opcode
     * @return opcode name
     */
    public static String getOpcodeName(int opcode) {
        switch (opcode) {
            case ATT_ERROR_RSP: return "Error Response";
            case ATT_EXCHANGE_MTU_REQ: return "Exchange MTU Request";
            case ATT_EXCHANGE_MTU_RSP: return "Exchange MTU Response";
            case ATT_FIND_INFORMATION_REQ: return "Find Information Request";
            case ATT_FIND_INFORMATION_RSP: return "Find Information Response";
            case ATT_FIND_BY_TYPE_VALUE_REQ: return "Find By Type Value Request";
            case ATT_FIND_BY_TYPE_VALUE_RSP: return "Find By Type Value Response";
            case ATT_READ_BY_TYPE_REQ: return "Read By Type Request";
            case ATT_READ_BY_TYPE_RSP: return "Read By Type Response";
            case ATT_READ_REQ: return "Read Request";
            case ATT_READ_RSP: return "Read Response";
            case ATT_READ_BLOB_REQ: return "Read Blob Request";
            case ATT_READ_BLOB_RSP: return "Read Blob Response";
            case ATT_READ_MULTIPLE_REQ: return "Read Multiple Request";
            case ATT_READ_MULTIPLE_RSP: return "Read Multiple Response";
            case ATT_READ_BY_GROUP_TYPE_REQ: return "Read By Group Type Request";
            case ATT_READ_BY_GROUP_TYPE_RSP: return "Read By Group Type Response";
            case ATT_WRITE_REQ: return "Write Request";
            case ATT_WRITE_RSP: return "Write Response";
            case ATT_WRITE_CMD: return "Write Command";
            case ATT_PREPARE_WRITE_REQ: return "Prepare Write Request";
            case ATT_PREPARE_WRITE_RSP: return "Prepare Write Response";
            case ATT_EXECUTE_WRITE_REQ: return "Execute Write Request";
            case ATT_EXECUTE_WRITE_RSP: return "Execute Write Response";
            case ATT_HANDLE_VALUE_NTF: return "Handle Value Notification";
            case ATT_HANDLE_VALUE_IND: return "Handle Value Indication";
            case ATT_HANDLE_VALUE_CFM: return "Handle Value Confirmation";
            case ATT_SIGNED_WRITE_CMD: return "Signed Write Command";
            default: return String.format("Unknown (0x%02X)", opcode);
        }
    }

    // ==================== Opcode Classification ====================

    /**
     * Checks if an opcode is a request that expects a response.
     *
     * @param opcode ATT opcode
     * @return true if request opcode
     */
    public static boolean isRequest(int opcode) {
        switch (opcode) {
            case ATT_EXCHANGE_MTU_REQ:
            case ATT_FIND_INFORMATION_REQ:
            case ATT_FIND_BY_TYPE_VALUE_REQ:
            case ATT_READ_BY_TYPE_REQ:
            case ATT_READ_REQ:
            case ATT_READ_BLOB_REQ:
            case ATT_READ_MULTIPLE_REQ:
            case ATT_READ_BY_GROUP_TYPE_REQ:
            case ATT_WRITE_REQ:
            case ATT_PREPARE_WRITE_REQ:
            case ATT_EXECUTE_WRITE_REQ:
                return true;
            default:
                return false;
        }
    }

    /**
     * Checks if an opcode is a command (no response expected).
     *
     * @param opcode ATT opcode
     * @return true if command opcode
     */
    public static boolean isCommand(int opcode) {
        return opcode == ATT_WRITE_CMD || opcode == ATT_SIGNED_WRITE_CMD;
    }

    /**
     * Checks if an opcode is a response.
     *
     * @param opcode ATT opcode
     * @return true if response opcode
     */
    public static boolean isResponse(int opcode) {
        switch (opcode) {
            case ATT_ERROR_RSP:
            case ATT_EXCHANGE_MTU_RSP:
            case ATT_FIND_INFORMATION_RSP:
            case ATT_FIND_BY_TYPE_VALUE_RSP:
            case ATT_READ_BY_TYPE_RSP:
            case ATT_READ_RSP:
            case ATT_READ_BLOB_RSP:
            case ATT_READ_MULTIPLE_RSP:
            case ATT_READ_BY_GROUP_TYPE_RSP:
            case ATT_WRITE_RSP:
            case ATT_PREPARE_WRITE_RSP:
            case ATT_EXECUTE_WRITE_RSP:
                return true;
            default:
                return false;
        }
    }

    /**
     * Checks if an opcode is a notification or indication.
     *
     * @param opcode ATT opcode
     * @return true if notification/indication opcode
     */
    public static boolean isNotificationOrIndication(int opcode) {
        return opcode == ATT_HANDLE_VALUE_NTF || opcode == ATT_HANDLE_VALUE_IND;
    }

    // ==================== Property Utilities ====================

    /**
     * Checks if characteristic properties include read capability.
     *
     * @param properties characteristic properties bitmask
     * @return true if readable
     */
    public static boolean isReadable(int properties) {
        return (properties & CHAR_PROP_READ) != 0;
    }

    /**
     * Checks if characteristic properties include write capability.
     *
     * @param properties characteristic properties bitmask
     * @return true if writable (with or without response)
     */
    public static boolean isWritable(int properties) {
        return (properties & (CHAR_PROP_WRITE | CHAR_PROP_WRITE_NO_RSP)) != 0;
    }

    /**
     * Checks if characteristic properties include notification capability.
     *
     * @param properties characteristic properties bitmask
     * @return true if supports notifications
     */
    public static boolean supportsNotify(int properties) {
        return (properties & CHAR_PROP_NOTIFY) != 0;
    }

    /**
     * Checks if characteristic properties include indication capability.
     *
     * @param properties characteristic properties bitmask
     * @return true if supports indications
     */
    public static boolean supportsIndicate(int properties) {
        return (properties & CHAR_PROP_INDICATE) != 0;
    }

    /**
     * Returns a human-readable string of characteristic properties.
     *
     * @param properties characteristic properties bitmask
     * @return comma-separated list of property names
     */
    public static String getPropertiesString(int properties) {
        StringBuilder sb = new StringBuilder();
        if ((properties & CHAR_PROP_BROADCAST) != 0) sb.append("Broadcast,");
        if ((properties & CHAR_PROP_READ) != 0) sb.append("Read,");
        if ((properties & CHAR_PROP_WRITE_NO_RSP) != 0) sb.append("WriteNoRsp,");
        if ((properties & CHAR_PROP_WRITE) != 0) sb.append("Write,");
        if ((properties & CHAR_PROP_NOTIFY) != 0) sb.append("Notify,");
        if ((properties & CHAR_PROP_INDICATE) != 0) sb.append("Indicate,");
        if ((properties & CHAR_PROP_AUTH_SIGNED_WRITE) != 0) sb.append("SignedWrite,");
        if ((properties & CHAR_PROP_EXTENDED) != 0) sb.append("Extended,");
        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1); // Remove trailing comma
        }
        return sb.toString();
    }
}