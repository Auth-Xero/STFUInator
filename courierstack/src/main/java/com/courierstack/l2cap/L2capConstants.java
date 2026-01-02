package com.courierstack.l2cap;

/**
 * L2CAP protocol constants per Bluetooth Core Spec v5.3, Vol 3, Part A.
 *
 * <p>This class contains all constants needed for L2CAP protocol implementation
 * including channel IDs, signaling commands, result codes, and common PSM values.
 */
public final class L2capConstants {

    private L2capConstants() {
        // Utility class - prevent instantiation
    }

    // ===== Fixed Channel IDs (Section 2.1) =====

    /** Null CID (invalid). */
    public static final int CID_NULL = 0x0000;
    /** BR/EDR Signaling channel. */
    public static final int CID_SIGNALING = 0x0001;
    /** Connectionless reception channel. */
    public static final int CID_CONNECTIONLESS = 0x0002;
    /** AMP Manager Protocol. */
    public static final int CID_AMP_MANAGER = 0x0003;
    /** Attribute Protocol (ATT). */
    public static final int CID_ATT = 0x0004;
    /** LE Signaling channel. */
    public static final int CID_LE_SIGNALING = 0x0005;
    /** Security Manager Protocol (SMP) for LE. */
    public static final int CID_SMP = 0x0006;
    /** Security Manager Protocol for BR/EDR. */
    public static final int CID_BR_EDR_SMP = 0x0007;

    // ===== Dynamic CID Ranges =====

    /** Start of dynamic CID range for BR/EDR. */
    public static final int CID_DYNAMIC_START = 0x0040;
    /** End of dynamic CID range. */
    public static final int CID_DYNAMIC_END = 0xFFFF;
    /** Start of dynamic CID range for LE. */
    public static final int CID_LE_DYNAMIC_START = 0x0040;
    /** End of dynamic CID range for LE. */
    public static final int CID_LE_DYNAMIC_END = 0x007F;

    // ===== Signaling Command Codes (Section 4) =====

    /** Command Reject. */
    public static final int CMD_REJECT = 0x01;
    /** Connection Request. */
    public static final int CMD_CONNECTION_REQUEST = 0x02;
    /** Connection Response. */
    public static final int CMD_CONNECTION_RESPONSE = 0x03;
    /** Configuration Request. */
    public static final int CMD_CONFIGURATION_REQUEST = 0x04;
    /** Configuration Response. */
    public static final int CMD_CONFIGURATION_RESPONSE = 0x05;
    /** Disconnection Request. */
    public static final int CMD_DISCONNECTION_REQUEST = 0x06;
    /** Disconnection Response. */
    public static final int CMD_DISCONNECTION_RESPONSE = 0x07;
    /** Echo Request. */
    public static final int CMD_ECHO_REQUEST = 0x08;
    /** Echo Response. */
    public static final int CMD_ECHO_RESPONSE = 0x09;
    /** Information Request. */
    public static final int CMD_INFORMATION_REQUEST = 0x0A;
    /** Information Response. */
    public static final int CMD_INFORMATION_RESPONSE = 0x0B;
    /** Create Channel Request (AMP). */
    public static final int CMD_CREATE_CHANNEL_REQUEST = 0x0C;
    /** Create Channel Response (AMP). */
    public static final int CMD_CREATE_CHANNEL_RESPONSE = 0x0D;
    /** Move Channel Request (AMP). */
    public static final int CMD_MOVE_CHANNEL_REQUEST = 0x0E;
    /** Move Channel Response (AMP). */
    public static final int CMD_MOVE_CHANNEL_RESPONSE = 0x0F;
    /** Move Channel Confirmation (AMP). */
    public static final int CMD_MOVE_CHANNEL_CONFIRM = 0x10;
    /** Move Channel Confirmation Response (AMP). */
    public static final int CMD_MOVE_CHANNEL_CONFIRM_RESPONSE = 0x11;
    /** Connection Parameter Update Request (LE). */
    public static final int CMD_CONNECTION_PARAMETER_UPDATE_REQUEST = 0x12;
    /** Connection Parameter Update Response (LE). */
    public static final int CMD_CONNECTION_PARAMETER_UPDATE_RESPONSE = 0x13;
    /** LE Credit Based Connection Request. */
    public static final int CMD_LE_CREDIT_BASED_CONNECTION_REQUEST = 0x14;
    /** LE Credit Based Connection Response. */
    public static final int CMD_LE_CREDIT_BASED_CONNECTION_RESPONSE = 0x15;
    /** Flow Control Credit. */
    public static final int CMD_FLOW_CONTROL_CREDIT = 0x16;
    /** Credit Based Connection Request (Enhanced). */
    public static final int CMD_CREDIT_BASED_CONNECTION_REQUEST = 0x17;
    /** Credit Based Connection Response (Enhanced). */
    public static final int CMD_CREDIT_BASED_CONNECTION_RESPONSE = 0x18;
    /** Credit Based Reconfigure Request. */
    public static final int CMD_CREDIT_BASED_RECONFIGURE_REQUEST = 0x19;
    /** Credit Based Reconfigure Response. */
    public static final int CMD_CREDIT_BASED_RECONFIGURE_RESPONSE = 0x1A;

    // ===== Connection Response Results (Section 4.3) =====

    /** Connection successful. */
    public static final int CR_SUCCESS = 0x0000;
    /** Connection pending. */
    public static final int CR_PENDING = 0x0001;
    /** PSM not supported. */
    public static final int CR_PSM_NOT_SUPPORTED = 0x0002;
    /** Security block. */
    public static final int CR_SECURITY_BLOCK = 0x0003;
    /** No resources available. */
    public static final int CR_NO_RESOURCES = 0x0004;
    /** Invalid Source CID. */
    public static final int CR_INVALID_SOURCE_CID = 0x0006;
    /** Source CID already allocated. */
    public static final int CR_SOURCE_CID_ALREADY_ALLOCATED = 0x0007;

    // ===== Connection Response Status (when result=pending) =====

    /** No further information available. */
    public static final int CS_NO_FURTHER_INFO = 0x0000;
    /** Authentication pending. */
    public static final int CS_AUTHENTICATION_PENDING = 0x0001;
    /** Authorization pending. */
    public static final int CS_AUTHORIZATION_PENDING = 0x0002;

    // ===== Configuration Response Results (Section 4.5) =====

    /** Configuration successful. */
    public static final int CONF_SUCCESS = 0x0000;
    /** Unacceptable parameters. */
    public static final int CONF_UNACCEPTABLE = 0x0001;
    /** Rejected (no reason given). */
    public static final int CONF_REJECTED = 0x0002;
    /** Unknown options. */
    public static final int CONF_UNKNOWN_OPTIONS = 0x0003;
    /** Configuration pending. */
    public static final int CONF_PENDING = 0x0004;
    /** Flow spec rejected. */
    public static final int CONF_FLOW_SPEC_REJECTED = 0x0005;

    // ===== Configuration Option Types (Section 5) =====

    /** MTU option. */
    public static final int CONF_OPT_MTU = 0x01;
    /** Flush timeout option. */
    public static final int CONF_OPT_FLUSH_TIMEOUT = 0x02;
    /** QoS option. */
    public static final int CONF_OPT_QOS = 0x03;
    /** Retransmission and flow control option. */
    public static final int CONF_OPT_RETRANSMISSION_FLOW_CONTROL = 0x04;
    /** FCS option. */
    public static final int CONF_OPT_FCS = 0x05;
    /** Extended flow spec option. */
    public static final int CONF_OPT_EXTENDED_FLOW_SPEC = 0x06;
    /** Extended window size option. */
    public static final int CONF_OPT_EXTENDED_WINDOW_SIZE = 0x07;

    // ===== Information Request Types (Section 4.11) =====

    /** Connectionless MTU. */
    public static final int INFO_CONNECTIONLESS_MTU = 0x0001;
    /** Extended features mask. */
    public static final int INFO_EXTENDED_FEATURES = 0x0002;
    /** Fixed channels supported. */
    public static final int INFO_FIXED_CHANNELS = 0x0003;

    // ===== Command Reject Reasons (Section 4.1) =====

    /** Command not understood. */
    public static final int REJECT_NOT_UNDERSTOOD = 0x0000;
    /** Signaling MTU exceeded. */
    public static final int REJECT_MTU_EXCEEDED = 0x0001;
    /** Invalid CID in request. */
    public static final int REJECT_INVALID_CID = 0x0002;

    // ===== LE Credit Based Connection Results =====

    /** LE CoC: All connections successful. */
    public static final int LE_CR_SUCCESS = 0x0000;
    /** LE CoC: SPSM not supported. */
    public static final int LE_CR_SPSM_NOT_SUPPORTED = 0x0002;
    /** LE CoC: No resources available. */
    public static final int LE_CR_NO_RESOURCES = 0x0004;
    /** LE CoC: Insufficient authentication. */
    public static final int LE_CR_INSUFFICIENT_AUTHENTICATION = 0x0005;
    /** LE CoC: Insufficient authorization. */
    public static final int LE_CR_INSUFFICIENT_AUTHORIZATION = 0x0006;
    /** LE CoC: Insufficient encryption key size. */
    public static final int LE_CR_INSUFFICIENT_KEY_SIZE = 0x0007;
    /** LE CoC: Insufficient encryption. */
    public static final int LE_CR_INSUFFICIENT_ENCRYPTION = 0x0008;
    /** LE CoC: Invalid Source CID. */
    public static final int LE_CR_INVALID_SOURCE_CID = 0x0009;
    /** LE CoC: Source CID already allocated. */
    public static final int LE_CR_SOURCE_CID_ALREADY_ALLOCATED = 0x000A;
    /** LE CoC: Unacceptable parameters. */
    public static final int LE_CR_UNACCEPTABLE_PARAMETERS = 0x000B;

    // ===== Default Values =====

    /** Default MTU for BR/EDR. */
    public static final int DEFAULT_MTU = 672;
    /** Minimum MTU for BR/EDR. */
    public static final int MIN_BR_EDR_MTU = 48;
    /** Minimum MTU for LE. */
    public static final int MIN_LE_MTU = 23;
    /** Maximum MTU for LE. */
    public static final int MAX_LE_MTU = 65535;
    /** Default flush timeout (infinite). */
    public static final int DEFAULT_FLUSH_TIMEOUT = 0xFFFF;
    /** Default initial credits for LE CoC. */
    public static final int DEFAULT_LE_CREDITS = 10;
    /** Default MPS for LE CoC. */
    public static final int DEFAULT_LE_MPS = 247;
    /** Minimum MPS for LE CoC. */
    public static final int MIN_LE_MPS = 23;
    /** Maximum MPS for LE CoC. */
    public static final int MAX_LE_MPS = 65533;

    // ===== Common PSM Values (Assigned Numbers) =====

    /** Service Discovery Protocol. */
    public static final int PSM_SDP = 0x0001;
    /** RFCOMM. */
    public static final int PSM_RFCOMM = 0x0003;
    /** Telephony Control Specification. */
    public static final int PSM_TCS_BIN = 0x0005;
    /** Telephony Control Specification (cordless). */
    public static final int PSM_TCS_BIN_CORDLESS = 0x0007;
    /** Bluetooth Network Encapsulation Protocol. */
    public static final int PSM_BNEP = 0x000F;
    /** HID Control. */
    public static final int PSM_HID_CONTROL = 0x0011;
    /** HID Interrupt. */
    public static final int PSM_HID_INTERRUPT = 0x0013;
    /** UPnP. */
    public static final int PSM_UPNP = 0x0015;
    /** Audio/Video Control Transport Protocol. */
    public static final int PSM_AVCTP = 0x0017;
    /** Audio/Video Distribution Transport Protocol. */
    public static final int PSM_AVDTP = 0x0019;
    /** AVCTP Browsing. */
    public static final int PSM_AVCTP_BROWSING = 0x001B;
    /** UDI C-Plane. */
    public static final int PSM_UDI_C_PLANE = 0x001D;
    /** Attribute Protocol. */
    public static final int PSM_ATT = 0x001F;
    /** 3D Synchronization Profile. */
    public static final int PSM_3DSP = 0x0021;
    /** Internet Protocol Support Profile. */
    public static final int PSM_LE_IPSP = 0x0023;
    /** Object Transfer Service. */
    public static final int PSM_OTS = 0x0025;
    /** Enhanced ATT. */
    public static final int PSM_EATT = 0x0027;

    // ===== ACL Packet Boundary Flags =====

    /** First non-automatically-flushable packet. */
    public static final int PB_FIRST_NON_FLUSH = 0x00;
    /** Continuing fragment. */
    public static final int PB_CONTINUING = 0x01;
    /** First automatically-flushable packet (default). */
    public static final int PB_FIRST_FLUSH = 0x02;

    /**
     * Returns human-readable name for a PSM.
     *
     * @param psm PSM value
     * @return PSM name
     */
    public static String getPsmName(int psm) {
        switch (psm) {
            case PSM_SDP: return "SDP";
            case PSM_RFCOMM: return "RFCOMM";
            case PSM_TCS_BIN: return "TCS-BIN";
            case PSM_BNEP: return "BNEP";
            case PSM_HID_CONTROL: return "HID Control";
            case PSM_HID_INTERRUPT: return "HID Interrupt";
            case PSM_AVCTP: return "AVCTP";
            case PSM_AVDTP: return "AVDTP";
            case PSM_AVCTP_BROWSING: return "AVCTP Browsing";
            case PSM_ATT: return "ATT";
            case PSM_LE_IPSP: return "IPSP";
            case PSM_OTS: return "OTS";
            case PSM_EATT: return "EATT";
            default:
                if (psm >= 0x1001) {
                    return String.format("Dynamic PSM 0x%04X", psm);
                }
                return String.format("PSM 0x%04X", psm);
        }
    }

    /**
     * Returns human-readable name for a signaling command code.
     *
     * @param code command code
     * @return command name
     */
    public static String getCommandName(int code) {
        switch (code) {
            case CMD_REJECT: return "Command Reject";
            case CMD_CONNECTION_REQUEST: return "Connection Request";
            case CMD_CONNECTION_RESPONSE: return "Connection Response";
            case CMD_CONFIGURATION_REQUEST: return "Configuration Request";
            case CMD_CONFIGURATION_RESPONSE: return "Configuration Response";
            case CMD_DISCONNECTION_REQUEST: return "Disconnection Request";
            case CMD_DISCONNECTION_RESPONSE: return "Disconnection Response";
            case CMD_ECHO_REQUEST: return "Echo Request";
            case CMD_ECHO_RESPONSE: return "Echo Response";
            case CMD_INFORMATION_REQUEST: return "Information Request";
            case CMD_INFORMATION_RESPONSE: return "Information Response";
            case CMD_LE_CREDIT_BASED_CONNECTION_REQUEST: return "LE Credit Connection Request";
            case CMD_LE_CREDIT_BASED_CONNECTION_RESPONSE: return "LE Credit Connection Response";
            case CMD_FLOW_CONTROL_CREDIT: return "Flow Control Credit";
            default: return String.format("Unknown (0x%02X)", code);
        }
    }

    /**
     * Returns human-readable description for a connection result.
     *
     * @param result connection result code
     * @return result description
     */
    public static String getConnectionResultName(int result) {
        switch (result) {
            case CR_SUCCESS: return "Success";
            case CR_PENDING: return "Pending";
            case CR_PSM_NOT_SUPPORTED: return "PSM Not Supported";
            case CR_SECURITY_BLOCK: return "Security Block";
            case CR_NO_RESOURCES: return "No Resources";
            case CR_INVALID_SOURCE_CID: return "Invalid Source CID";
            case CR_SOURCE_CID_ALREADY_ALLOCATED: return "Source CID Already Allocated";
            default: return String.format("Unknown (0x%04X)", result);
        }
    }

    /**
     * Returns whether the CID is a fixed channel.
     *
     * @param cid channel identifier
     * @return true if fixed channel
     */
    public static boolean isFixedChannel(int cid) {
        return cid >= CID_NULL && cid < CID_DYNAMIC_START;
    }

    /**
     * Returns whether the CID is a dynamic channel.
     *
     * @param cid channel identifier
     * @return true if dynamic channel
     */
    public static boolean isDynamicChannel(int cid) {
        return cid >= CID_DYNAMIC_START && cid <= CID_DYNAMIC_END;
    }
}