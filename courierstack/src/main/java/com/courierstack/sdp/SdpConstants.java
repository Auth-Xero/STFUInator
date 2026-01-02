package com.courierstack.sdp;

import java.util.UUID;

/**
 * SDP protocol constants per Bluetooth Core Spec v5.3, Vol 3, Part B.
 *
 * <p>This class contains all constants needed for SDP protocol implementation:
 * <ul>
 *   <li>PDU IDs (Section 4.2)</li>
 *   <li>Data Element Types (Section 3.2)</li>
 *   <li>Universal Attribute IDs (Assigned Numbers)</li>
 *   <li>Protocol and Service Class UUIDs</li>
 *   <li>Error Codes (Section 4.4.1)</li>
 * </ul>
 *
 * <p>Thread safety: This class is immutable and thread-safe.
 */
public final class SdpConstants {

    private SdpConstants() {
        // Utility class - prevent instantiation
    }

    // ==================== L2CAP PSM ====================

    /** L2CAP PSM for SDP (0x0001). */
    public static final int PSM_SDP = 0x0001;

    // ==================== PDU IDs (Section 4.2) ====================

    /** Error Response PDU. */
    public static final int SDP_ERROR_RESPONSE = 0x01;

    /** Service Search Request PDU. */
    public static final int SDP_SERVICE_SEARCH_REQUEST = 0x02;

    /** Service Search Response PDU. */
    public static final int SDP_SERVICE_SEARCH_RESPONSE = 0x03;

    /** Service Attribute Request PDU. */
    public static final int SDP_SERVICE_ATTR_REQUEST = 0x04;

    /** Service Attribute Response PDU. */
    public static final int SDP_SERVICE_ATTR_RESPONSE = 0x05;

    /** Service Search Attribute Request PDU. */
    public static final int SDP_SERVICE_SEARCH_ATTR_REQUEST = 0x06;

    /** Service Search Attribute Response PDU. */
    public static final int SDP_SERVICE_SEARCH_ATTR_RESPONSE = 0x07;

    // ==================== Data Element Types (Section 3.2) ====================

    /** Nil type (no data). */
    public static final int DE_NIL = 0;

    /** Unsigned integer. */
    public static final int DE_UINT = 1;

    /** Signed integer (two's complement). */
    public static final int DE_INT = 2;

    /** UUID. */
    public static final int DE_UUID = 3;

    /** Text string. */
    public static final int DE_STRING = 4;

    /** Boolean. */
    public static final int DE_BOOL = 5;

    /** Data element sequence. */
    public static final int DE_SEQ = 6;

    /** Data element alternative. */
    public static final int DE_ALT = 7;

    /** URL. */
    public static final int DE_URL = 8;

    // ==================== Universal Attribute IDs ====================

    /** ServiceRecordHandle (0x0000) - Unique identifier for service record. */
    public static final int ATTR_SERVICE_RECORD_HANDLE = 0x0000;

    /** ServiceClassIDList (0x0001) - UUIDs of service classes. */
    public static final int ATTR_SERVICE_CLASS_ID_LIST = 0x0001;

    /** ServiceRecordState (0x0002) - Change counter for service record. */
    public static final int ATTR_SERVICE_RECORD_STATE = 0x0002;

    /** ServiceID (0x0003) - UUID that uniquely identifies the service instance. */
    public static final int ATTR_SERVICE_ID = 0x0003;

    /** ProtocolDescriptorList (0x0004) - Protocols used to access the service. */
    public static final int ATTR_PROTOCOL_DESCRIPTOR_LIST = 0x0004;

    /** BrowseGroupList (0x0005) - Browse groups this service belongs to. */
    public static final int ATTR_BROWSE_GROUP_LIST = 0x0005;

    /** LanguageBaseAttributeIDList (0x0006) - Language information. */
    public static final int ATTR_LANGUAGE_BASE_ATTR_LIST = 0x0006;

    /** ServiceInfoTimeToLive (0x0007) - Seconds until info expires. */
    public static final int ATTR_SERVICE_INFO_TIME_TO_LIVE = 0x0007;

    /** ServiceAvailability (0x0008) - Relative availability. */
    public static final int ATTR_SERVICE_AVAILABILITY = 0x0008;

    /** BluetoothProfileDescriptorList (0x0009) - Profiles supported. */
    public static final int ATTR_BT_PROFILE_DESCRIPTOR_LIST = 0x0009;

    /** DocumentationURL (0x000A) - URL to documentation. */
    public static final int ATTR_DOCUMENTATION_URL = 0x000A;

    /** ClientExecutableURL (0x000B) - URL to client executable. */
    public static final int ATTR_CLIENT_EXECUTABLE_URL = 0x000B;

    /** IconURL (0x000C) - URL to icon. */
    public static final int ATTR_ICON_URL = 0x000C;

    /** AdditionalProtocolDescriptorLists (0x000D) - Additional protocols. */
    public static final int ATTR_ADDITIONAL_PROTOCOL_DESC_LISTS = 0x000D;

    // Language base offsets (added to language base attribute ID)
    /** ServiceName offset (base + 0x0000). */
    public static final int ATTR_SERVICE_NAME = 0x0100;

    /** ServiceDescription offset (base + 0x0001). */
    public static final int ATTR_SERVICE_DESCRIPTION = 0x0101;

    /** ProviderName offset (base + 0x0002). */
    public static final int ATTR_PROVIDER_NAME = 0x0102;

    // Profile-specific attributes
    /** GoepL2capPsm - L2CAP PSM for GOEP (OBEX profiles). */
    public static final int ATTR_GOEP_L2CAP_PSM = 0x0200;

    /** SupportedFormatsList - for OBEX Object Push. */
    public static final int ATTR_SUPPORTED_FORMATS_LIST = 0x0303;

    /** SupportedFeatures - for various profiles (HFP, MAP, PBAP, etc.). */
    public static final int ATTR_SUPPORTED_FEATURES = 0x0311;

    // ==================== Protocol UUIDs ====================

    /** SDP protocol UUID. */
    public static final UUID UUID_SDP = UUID.fromString("00000001-0000-1000-8000-00805F9B34FB");

    /** UDP protocol UUID. */
    public static final UUID UUID_UDP = UUID.fromString("00000002-0000-1000-8000-00805F9B34FB");

    /** RFCOMM protocol UUID. */
    public static final UUID UUID_RFCOMM = UUID.fromString("00000003-0000-1000-8000-00805F9B34FB");

    /** TCP protocol UUID. */
    public static final UUID UUID_TCP = UUID.fromString("00000004-0000-1000-8000-00805F9B34FB");

    /** OBEX protocol UUID. */
    public static final UUID UUID_OBEX = UUID.fromString("00000008-0000-1000-8000-00805F9B34FB");

    /** IP protocol UUID. */
    public static final UUID UUID_IP = UUID.fromString("00000009-0000-1000-8000-00805F9B34FB");

    /** FTP protocol UUID. */
    public static final UUID UUID_FTP = UUID.fromString("0000000A-0000-1000-8000-00805F9B34FB");

    /** HTTP protocol UUID. */
    public static final UUID UUID_HTTP = UUID.fromString("0000000C-0000-1000-8000-00805F9B34FB");

    /** BNEP protocol UUID. */
    public static final UUID UUID_BNEP = UUID.fromString("0000000F-0000-1000-8000-00805F9B34FB");

    /** HIDP protocol UUID. */
    public static final UUID UUID_HIDP = UUID.fromString("00000011-0000-1000-8000-00805F9B34FB");

    /** AVCTP protocol UUID. */
    public static final UUID UUID_AVCTP = UUID.fromString("00000017-0000-1000-8000-00805F9B34FB");

    /** AVDTP protocol UUID. */
    public static final UUID UUID_AVDTP = UUID.fromString("00000019-0000-1000-8000-00805F9B34FB");

    /** CMTP protocol UUID. */
    public static final UUID UUID_CMTP = UUID.fromString("0000001B-0000-1000-8000-00805F9B34FB");

    /** L2CAP protocol UUID. */
    public static final UUID UUID_L2CAP = UUID.fromString("00000100-0000-1000-8000-00805F9B34FB");

    // ==================== Service Class UUIDs ====================

    /** Serial Port Profile (SPP) UUID. */
    public static final UUID UUID_SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    /** LAN Access Using PPP UUID. */
    public static final UUID UUID_LAN_ACCESS = UUID.fromString("00001102-0000-1000-8000-00805F9B34FB");

    /** Dialup Networking (DUN) UUID. */
    public static final UUID UUID_DUN = UUID.fromString("00001103-0000-1000-8000-00805F9B34FB");

    /** IrMC Sync UUID. */
    public static final UUID UUID_IRMC_SYNC = UUID.fromString("00001104-0000-1000-8000-00805F9B34FB");

    /** OBEX Object Push UUID. */
    public static final UUID UUID_OBEX_PUSH = UUID.fromString("00001105-0000-1000-8000-00805F9B34FB");

    /** OBEX File Transfer UUID. */
    public static final UUID UUID_OBEX_FTP = UUID.fromString("00001106-0000-1000-8000-00805F9B34FB");

    /** IrMC Sync Command UUID. */
    public static final UUID UUID_IRMC_SYNC_CMD = UUID.fromString("00001107-0000-1000-8000-00805F9B34FB");

    /** Headset (HSP-HS) UUID. */
    public static final UUID UUID_HSP_HS = UUID.fromString("00001108-0000-1000-8000-00805F9B34FB");

    /** Cordless Telephony UUID. */
    public static final UUID UUID_CORDLESS_TELEPHONY = UUID.fromString("00001109-0000-1000-8000-00805F9B34FB");

    /** Audio Source (A2DP) UUID. */
    public static final UUID UUID_A2DP_SOURCE = UUID.fromString("0000110A-0000-1000-8000-00805F9B34FB");

    /** Audio Sink (A2DP) UUID. */
    public static final UUID UUID_A2DP_SINK = UUID.fromString("0000110B-0000-1000-8000-00805F9B34FB");

    /** A/V Remote Control Target (AVRCP) UUID. */
    public static final UUID UUID_AVRCP_TARGET = UUID.fromString("0000110C-0000-1000-8000-00805F9B34FB");

    /** Advanced Audio Distribution Profile UUID. */
    public static final UUID UUID_ADVANCED_AUDIO = UUID.fromString("0000110D-0000-1000-8000-00805F9B34FB");

    /** A/V Remote Control Controller (AVRCP) UUID. */
    public static final UUID UUID_AVRCP_CONTROLLER = UUID.fromString("0000110E-0000-1000-8000-00805F9B34FB");

    /** A/V Remote Control UUID. */
    public static final UUID UUID_AVRCP = UUID.fromString("0000110F-0000-1000-8000-00805F9B34FB");

    /** Intercom UUID. */
    public static final UUID UUID_INTERCOM = UUID.fromString("00001110-0000-1000-8000-00805F9B34FB");

    /** Fax UUID. */
    public static final UUID UUID_FAX = UUID.fromString("00001111-0000-1000-8000-00805F9B34FB");

    /** Headset Audio Gateway (HSP-AG) UUID. */
    public static final UUID UUID_HSP_AG = UUID.fromString("00001112-0000-1000-8000-00805F9B34FB");

    /** WAP UUID. */
    public static final UUID UUID_WAP = UUID.fromString("00001113-0000-1000-8000-00805F9B34FB");

    /** WAP Client UUID. */
    public static final UUID UUID_WAP_CLIENT = UUID.fromString("00001114-0000-1000-8000-00805F9B34FB");

    /** PAN User (PANU) UUID. */
    public static final UUID UUID_PANU = UUID.fromString("00001115-0000-1000-8000-00805F9B34FB");

    /** Network Access Point (NAP) UUID. */
    public static final UUID UUID_NAP = UUID.fromString("00001116-0000-1000-8000-00805F9B34FB");

    /** Group Ad-hoc Network (GN) UUID. */
    public static final UUID UUID_GN = UUID.fromString("00001117-0000-1000-8000-00805F9B34FB");

    /** Hands-Free (HFP-HF) UUID. */
    public static final UUID UUID_HFP_HF = UUID.fromString("0000111E-0000-1000-8000-00805F9B34FB");

    /** Hands-Free Audio Gateway (HFP-AG) UUID. */
    public static final UUID UUID_HFP_AG = UUID.fromString("0000111F-0000-1000-8000-00805F9B34FB");

    /** Human Interface Device (HID) UUID. */
    public static final UUID UUID_HID = UUID.fromString("00001124-0000-1000-8000-00805F9B34FB");

    /** SIM Access (SAP) UUID. */
    public static final UUID UUID_SAP = UUID.fromString("0000112D-0000-1000-8000-00805F9B34FB");

    /** Phonebook Access Client (PBAP-PCE) UUID. */
    public static final UUID UUID_PBAP_PCE = UUID.fromString("0000112E-0000-1000-8000-00805F9B34FB");

    /** Phonebook Access Server (PBAP-PSE) UUID. */
    public static final UUID UUID_PBAP_PSE = UUID.fromString("0000112F-0000-1000-8000-00805F9B34FB");

    /** Phonebook Access Profile UUID. */
    public static final UUID UUID_PBAP = UUID.fromString("00001130-0000-1000-8000-00805F9B34FB");

    /** Message Access Server (MAP-MAS) UUID. */
    public static final UUID UUID_MAP_MAS = UUID.fromString("00001132-0000-1000-8000-00805F9B34FB");

    /** Message Notification Server (MAP-MNS) UUID. */
    public static final UUID UUID_MAP_MNS = UUID.fromString("00001133-0000-1000-8000-00805F9B34FB");

    /** Message Access Profile UUID. */
    public static final UUID UUID_MAP = UUID.fromString("00001134-0000-1000-8000-00805F9B34FB");

    /** PnP Information UUID. */
    public static final UUID UUID_PNP_INFO = UUID.fromString("00001200-0000-1000-8000-00805F9B34FB");

    /** Generic Networking UUID. */
    public static final UUID UUID_GENERIC_NETWORKING = UUID.fromString("00001201-0000-1000-8000-00805F9B34FB");

    /** Generic File Transfer UUID. */
    public static final UUID UUID_GENERIC_FILE_TRANSFER = UUID.fromString("00001202-0000-1000-8000-00805F9B34FB");

    /** Generic Audio UUID. */
    public static final UUID UUID_GENERIC_AUDIO = UUID.fromString("00001203-0000-1000-8000-00805F9B34FB");

    /** Generic Telephony UUID. */
    public static final UUID UUID_GENERIC_TELEPHONY = UUID.fromString("00001204-0000-1000-8000-00805F9B34FB");

    // Browse Group UUIDs
    /** Public Browse Root UUID. */
    public static final UUID UUID_PUBLIC_BROWSE_ROOT = UUID.fromString("00001002-0000-1000-8000-00805F9B34FB");

    // ==================== Error Codes (Section 4.4.1) ====================

    /** Invalid/unsupported SDP version. */
    public static final int ERR_INVALID_SDP_VERSION = 0x0001;

    /** Invalid service record handle. */
    public static final int ERR_INVALID_SERVICE_RECORD_HANDLE = 0x0002;

    /** Invalid request syntax. */
    public static final int ERR_INVALID_REQUEST_SYNTAX = 0x0003;

    /** Invalid PDU size. */
    public static final int ERR_INVALID_PDU_SIZE = 0x0004;

    /** Invalid continuation state. */
    public static final int ERR_INVALID_CONTINUATION_STATE = 0x0005;

    /** Insufficient resources to satisfy request. */
    public static final int ERR_INSUFFICIENT_RESOURCES = 0x0006;

    // ==================== Utility Methods ====================

    /**
     * Returns human-readable name for a service UUID.
     *
     * @param uuid service UUID
     * @return service name or UUID string if unknown
     */
    public static String getServiceName(UUID uuid) {
        if (uuid == null) return "null";

        // Check common services
        if (uuid.equals(UUID_SPP)) return "Serial Port";
        if (uuid.equals(UUID_DUN)) return "Dialup Networking";
        if (uuid.equals(UUID_OBEX_PUSH)) return "OBEX Object Push";
        if (uuid.equals(UUID_OBEX_FTP)) return "OBEX File Transfer";
        if (uuid.equals(UUID_HSP_HS)) return "Headset";
        if (uuid.equals(UUID_HSP_AG)) return "Headset AG";
        if (uuid.equals(UUID_HFP_HF)) return "Hands-Free";
        if (uuid.equals(UUID_HFP_AG)) return "Hands-Free AG";
        if (uuid.equals(UUID_A2DP_SOURCE)) return "A2DP Source";
        if (uuid.equals(UUID_A2DP_SINK)) return "A2DP Sink";
        if (uuid.equals(UUID_AVRCP_TARGET)) return "AVRCP Target";
        if (uuid.equals(UUID_AVRCP_CONTROLLER)) return "AVRCP Controller";
        if (uuid.equals(UUID_AVRCP)) return "AVRCP";
        if (uuid.equals(UUID_HID)) return "HID";
        if (uuid.equals(UUID_PBAP_PSE)) return "Phonebook Access Server";
        if (uuid.equals(UUID_PBAP_PCE)) return "Phonebook Access Client";
        if (uuid.equals(UUID_PBAP)) return "Phonebook Access";
        if (uuid.equals(UUID_MAP_MAS)) return "Message Access Server";
        if (uuid.equals(UUID_MAP_MNS)) return "Message Notification Server";
        if (uuid.equals(UUID_MAP)) return "Message Access";
        if (uuid.equals(UUID_PNP_INFO)) return "PnP Information";
        if (uuid.equals(UUID_PANU)) return "PAN User";
        if (uuid.equals(UUID_NAP)) return "Network Access Point";
        if (uuid.equals(UUID_GN)) return "Group Ad-hoc Network";
        if (uuid.equals(UUID_SAP)) return "SIM Access";

        // Check protocols
        if (uuid.equals(UUID_SDP)) return "SDP";
        if (uuid.equals(UUID_L2CAP)) return "L2CAP";
        if (uuid.equals(UUID_RFCOMM)) return "RFCOMM";
        if (uuid.equals(UUID_OBEX)) return "OBEX";
        if (uuid.equals(UUID_BNEP)) return "BNEP";
        if (uuid.equals(UUID_AVDTP)) return "AVDTP";
        if (uuid.equals(UUID_AVCTP)) return "AVCTP";
        if (uuid.equals(UUID_HIDP)) return "HIDP";

        // Return UUID string for unknown
        return uuid.toString();
    }

    /**
     * Returns human-readable name for a PDU ID.
     *
     * @param pduId PDU identifier
     * @return PDU name
     */
    public static String getPduName(int pduId) {
        switch (pduId) {
            case SDP_ERROR_RESPONSE: return "ErrorResponse";
            case SDP_SERVICE_SEARCH_REQUEST: return "ServiceSearchRequest";
            case SDP_SERVICE_SEARCH_RESPONSE: return "ServiceSearchResponse";
            case SDP_SERVICE_ATTR_REQUEST: return "ServiceAttributeRequest";
            case SDP_SERVICE_ATTR_RESPONSE: return "ServiceAttributeResponse";
            case SDP_SERVICE_SEARCH_ATTR_REQUEST: return "ServiceSearchAttributeRequest";
            case SDP_SERVICE_SEARCH_ATTR_RESPONSE: return "ServiceSearchAttributeResponse";
            default: return String.format("Unknown(0x%02X)", pduId);
        }
    }

    /**
     * Returns human-readable name for a data element type.
     *
     * @param type data element type
     * @return type name
     */
    public static String getDataElementTypeName(int type) {
        switch (type) {
            case DE_NIL: return "Nil";
            case DE_UINT: return "Unsigned Integer";
            case DE_INT: return "Signed Integer";
            case DE_UUID: return "UUID";
            case DE_STRING: return "Text String";
            case DE_BOOL: return "Boolean";
            case DE_SEQ: return "Sequence";
            case DE_ALT: return "Alternative";
            case DE_URL: return "URL";
            default: return String.format("Unknown(%d)", type);
        }
    }

    /**
     * Returns human-readable name for an attribute ID.
     *
     * @param attributeId attribute identifier
     * @return attribute name
     */
    public static String getAttributeName(int attributeId) {
        switch (attributeId) {
            case ATTR_SERVICE_RECORD_HANDLE: return "ServiceRecordHandle";
            case ATTR_SERVICE_CLASS_ID_LIST: return "ServiceClassIDList";
            case ATTR_SERVICE_RECORD_STATE: return "ServiceRecordState";
            case ATTR_SERVICE_ID: return "ServiceID";
            case ATTR_PROTOCOL_DESCRIPTOR_LIST: return "ProtocolDescriptorList";
            case ATTR_BROWSE_GROUP_LIST: return "BrowseGroupList";
            case ATTR_LANGUAGE_BASE_ATTR_LIST: return "LanguageBaseAttributeIDList";
            case ATTR_SERVICE_INFO_TIME_TO_LIVE: return "ServiceInfoTimeToLive";
            case ATTR_SERVICE_AVAILABILITY: return "ServiceAvailability";
            case ATTR_BT_PROFILE_DESCRIPTOR_LIST: return "BluetoothProfileDescriptorList";
            case ATTR_DOCUMENTATION_URL: return "DocumentationURL";
            case ATTR_CLIENT_EXECUTABLE_URL: return "ClientExecutableURL";
            case ATTR_ICON_URL: return "IconURL";
            case ATTR_ADDITIONAL_PROTOCOL_DESC_LISTS: return "AdditionalProtocolDescriptorLists";
            case ATTR_SERVICE_NAME: return "ServiceName";
            case ATTR_SERVICE_DESCRIPTION: return "ServiceDescription";
            case ATTR_PROVIDER_NAME: return "ProviderName";
            case ATTR_GOEP_L2CAP_PSM: return "GoepL2capPsm";
            case ATTR_SUPPORTED_FEATURES: return "SupportedFeatures";
            default: return String.format("Attribute(0x%04X)", attributeId);
        }
    }

    /**
     * Returns human-readable SDP error description.
     *
     * @param errorCode SDP error code
     * @return error description
     */
    public static String getErrorString(int errorCode) {
        switch (errorCode) {
            case ERR_INVALID_SDP_VERSION: return "Invalid SDP Version";
            case ERR_INVALID_SERVICE_RECORD_HANDLE: return "Invalid Service Record Handle";
            case ERR_INVALID_REQUEST_SYNTAX: return "Invalid Request Syntax";
            case ERR_INVALID_PDU_SIZE: return "Invalid PDU Size";
            case ERR_INVALID_CONTINUATION_STATE: return "Invalid Continuation State";
            case ERR_INSUFFICIENT_RESOURCES: return "Insufficient Resources";
            default: return String.format("Unknown Error (0x%04X)", errorCode);
        }
    }

    /**
     * Creates a UUID from a 16-bit Bluetooth UUID.
     *
     * @param uuid16 16-bit UUID
     * @return full 128-bit UUID
     */
    public static UUID uuidFromShort(int uuid16) {
        return new UUID(((long) uuid16 << 32) | 0x00001000L, 0x800000805F9B34FBL);
    }

    /**
     * Extracts the 16-bit short form of a Bluetooth Base UUID.
     *
     * @param uuid UUID to extract from
     * @return 16-bit UUID, or -1 if not a Bluetooth Base UUID
     */
    public static int uuidToShort(UUID uuid) {
        if (uuid == null) return -1;

        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();

        if ((msb & 0x0000FFFFFFFFFFFFL) == 0x00001000L && lsb == 0x800000805F9B34FBL) {
            int short32 = (int) ((msb >> 32) & 0xFFFFFFFFL);
            if ((short32 & 0xFFFF0000) == 0) {
                return short32;
            }
        }

        return -1;
    }
}