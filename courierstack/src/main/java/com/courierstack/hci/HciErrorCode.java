package com.courierstack.hci;

/**
 * HCI error codes as defined in Bluetooth Core Spec v5.3, Vol 1, Part F.
 *
 * <p>These error codes are returned in Command Complete and Command Status
 * events, as well as in various HCI event parameters.
 */
public final class HciErrorCode {

    private HciErrorCode() {
        // Utility class - prevent instantiation
    }

    /** Command succeeded. */
    public static final int SUCCESS = 0x00;
    /** Unknown HCI command. */
    public static final int UNKNOWN_HCI_COMMAND = 0x01;
    /** Unknown connection identifier. */
    public static final int UNKNOWN_CONNECTION_ID = 0x02;
    /** Hardware failure. */
    public static final int HARDWARE_FAILURE = 0x03;
    /** Page timeout. */
    public static final int PAGE_TIMEOUT = 0x04;
    /** Authentication failure. */
    public static final int AUTHENTICATION_FAILURE = 0x05;
    /** PIN or key missing. */
    public static final int PIN_OR_KEY_MISSING = 0x06;
    /** Memory capacity exceeded. */
    public static final int MEMORY_CAPACITY_EXCEEDED = 0x07;
    /** Connection timeout. */
    public static final int CONNECTION_TIMEOUT = 0x08;
    /** Connection limit exceeded. */
    public static final int CONNECTION_LIMIT_EXCEEDED = 0x09;
    /** Synchronous connection limit exceeded. */
    public static final int SYNC_CONNECTION_LIMIT_EXCEEDED = 0x0A;
    /** Connection already exists. */
    public static final int CONNECTION_ALREADY_EXISTS = 0x0B;
    /** Command disallowed. */
    public static final int COMMAND_DISALLOWED = 0x0C;
    /** Connection rejected due to limited resources. */
    public static final int CONNECTION_REJECTED_LIMITED_RESOURCES = 0x0D;
    /** Connection rejected due to security reasons. */
    public static final int CONNECTION_REJECTED_SECURITY = 0x0E;
    /** Connection rejected due to unacceptable BD_ADDR. */
    public static final int CONNECTION_REJECTED_UNACCEPTABLE_BD_ADDR = 0x0F;
    /** Connection accept timeout exceeded. */
    public static final int CONNECTION_ACCEPT_TIMEOUT = 0x10;
    /** Unsupported feature or parameter value. */
    public static final int UNSUPPORTED_FEATURE_OR_PARAMETER = 0x11;
    /** Invalid HCI command parameters. */
    public static final int INVALID_HCI_COMMAND_PARAMETERS = 0x12;
    /** Remote user terminated connection. */
    public static final int REMOTE_USER_TERMINATED = 0x13;
    /** Remote device terminated due to low resources. */
    public static final int REMOTE_DEVICE_TERMINATED_LOW_RESOURCES = 0x14;
    /** Remote device terminated due to power off. */
    public static final int REMOTE_DEVICE_TERMINATED_POWER_OFF = 0x15;
    /** Connection terminated by local host. */
    public static final int CONNECTION_TERMINATED_BY_LOCAL_HOST = 0x16;
    /** Repeated attempts. */
    public static final int REPEATED_ATTEMPTS = 0x17;
    /** Pairing not allowed. */
    public static final int PAIRING_NOT_ALLOWED = 0x18;
    /** Unknown LMP PDU. */
    public static final int UNKNOWN_LMP_PDU = 0x19;
    /** Unsupported remote feature. */
    public static final int UNSUPPORTED_REMOTE_FEATURE = 0x1A;
    /** SCO offset rejected. */
    public static final int SCO_OFFSET_REJECTED = 0x1B;
    /** SCO interval rejected. */
    public static final int SCO_INTERVAL_REJECTED = 0x1C;
    /** SCO air mode rejected. */
    public static final int SCO_AIR_MODE_REJECTED = 0x1D;
    /** Invalid LMP parameters. */
    public static final int INVALID_LMP_PARAMETERS = 0x1E;
    /** Unspecified error. */
    public static final int UNSPECIFIED_ERROR = 0x1F;
    /** Unsupported LMP parameter value. */
    public static final int UNSUPPORTED_LMP_PARAMETER_VALUE = 0x20;
    /** Role change not allowed. */
    public static final int ROLE_CHANGE_NOT_ALLOWED = 0x21;
    /** LMP response timeout. */
    public static final int LMP_RESPONSE_TIMEOUT = 0x22;
    /** LMP error transaction collision. */
    public static final int LMP_ERROR_TRANSACTION_COLLISION = 0x23;
    /** LMP PDU not allowed. */
    public static final int LMP_PDU_NOT_ALLOWED = 0x24;
    /** Encryption mode not acceptable. */
    public static final int ENCRYPTION_MODE_NOT_ACCEPTABLE = 0x25;
    /** Link key cannot be changed. */
    public static final int LINK_KEY_CANNOT_BE_CHANGED = 0x26;
    /** Requested QoS not supported. */
    public static final int REQUESTED_QOS_NOT_SUPPORTED = 0x27;
    /** Instant passed. */
    public static final int INSTANT_PASSED = 0x28;
    /** Pairing with unit key not supported. */
    public static final int PAIRING_WITH_UNIT_KEY_NOT_SUPPORTED = 0x29;
    /** Different transaction collision. */
    public static final int DIFFERENT_TRANSACTION_COLLISION = 0x2A;
    /** QoS unacceptable parameter. */
    public static final int QOS_UNACCEPTABLE_PARAMETER = 0x2C;
    /** QoS rejected. */
    public static final int QOS_REJECTED = 0x2D;
    /** Channel classification not supported. */
    public static final int CHANNEL_CLASSIFICATION_NOT_SUPPORTED = 0x2E;
    /** Insufficient security. */
    public static final int INSUFFICIENT_SECURITY = 0x2F;
    /** Parameter out of mandatory range. */
    public static final int PARAMETER_OUT_OF_MANDATORY_RANGE = 0x30;
    /** Role switch pending. */
    public static final int ROLE_SWITCH_PENDING = 0x32;
    /** Reserved slot violation. */
    public static final int RESERVED_SLOT_VIOLATION = 0x34;
    /** Role switch failed. */
    public static final int ROLE_SWITCH_FAILED = 0x35;
    /** Extended inquiry response too large. */
    public static final int EIR_TOO_LARGE = 0x36;
    /** Secure simple pairing not supported by host. */
    public static final int SSP_NOT_SUPPORTED_BY_HOST = 0x37;
    /** Host busy - pairing. */
    public static final int HOST_BUSY_PAIRING = 0x38;
    /** Connection rejected due to no suitable channel. */
    public static final int CONNECTION_REJECTED_NO_SUITABLE_CHANNEL = 0x39;
    /** Controller busy. */
    public static final int CONTROLLER_BUSY = 0x3A;
    /** Unacceptable connection parameters. */
    public static final int UNACCEPTABLE_CONNECTION_PARAMETERS = 0x3B;
    /** Advertising timeout. */
    public static final int ADVERTISING_TIMEOUT = 0x3C;
    /** Connection terminated due to MIC failure. */
    public static final int CONNECTION_TERMINATED_MIC_FAILURE = 0x3D;
    /** Connection failed to be established. */
    public static final int CONNECTION_FAILED_TO_BE_ESTABLISHED = 0x3E;
    /** Coarse clock adjustment rejected. */
    public static final int COARSE_CLOCK_ADJUSTMENT_REJECTED = 0x40;
    /** Type0 submap not defined. */
    public static final int TYPE0_SUBMAP_NOT_DEFINED = 0x41;
    /** Unknown advertising identifier. */
    public static final int UNKNOWN_ADVERTISING_IDENTIFIER = 0x42;
    /** Limit reached. */
    public static final int LIMIT_REACHED = 0x43;
    /** Operation cancelled by host. */
    public static final int OPERATION_CANCELLED_BY_HOST = 0x44;
    /** Packet too long. */
    public static final int PACKET_TOO_LONG = 0x45;

    /**
     * Returns a human-readable description of an HCI error code.
     *
     * @param errorCode HCI error code
     * @return description string
     */
    public static String getDescription(int errorCode) {
        switch (errorCode) {
            case SUCCESS: return "Success";
            case UNKNOWN_HCI_COMMAND: return "Unknown HCI Command";
            case UNKNOWN_CONNECTION_ID: return "Unknown Connection Identifier";
            case HARDWARE_FAILURE: return "Hardware Failure";
            case PAGE_TIMEOUT: return "Page Timeout";
            case AUTHENTICATION_FAILURE: return "Authentication Failure";
            case PIN_OR_KEY_MISSING: return "PIN or Key Missing";
            case MEMORY_CAPACITY_EXCEEDED: return "Memory Capacity Exceeded";
            case CONNECTION_TIMEOUT: return "Connection Timeout";
            case CONNECTION_LIMIT_EXCEEDED: return "Connection Limit Exceeded";
            case SYNC_CONNECTION_LIMIT_EXCEEDED: return "Synchronous Connection Limit Exceeded";
            case CONNECTION_ALREADY_EXISTS: return "Connection Already Exists";
            case COMMAND_DISALLOWED: return "Command Disallowed";
            case CONNECTION_REJECTED_LIMITED_RESOURCES: return "Connection Rejected (Limited Resources)";
            case CONNECTION_REJECTED_SECURITY: return "Connection Rejected (Security)";
            case CONNECTION_REJECTED_UNACCEPTABLE_BD_ADDR: return "Connection Rejected (Unacceptable BD_ADDR)";
            case CONNECTION_ACCEPT_TIMEOUT: return "Connection Accept Timeout Exceeded";
            case UNSUPPORTED_FEATURE_OR_PARAMETER: return "Unsupported Feature or Parameter Value";
            case INVALID_HCI_COMMAND_PARAMETERS: return "Invalid HCI Command Parameters";
            case REMOTE_USER_TERMINATED: return "Remote User Terminated Connection";
            case REMOTE_DEVICE_TERMINATED_LOW_RESOURCES: return "Remote Device Terminated (Low Resources)";
            case REMOTE_DEVICE_TERMINATED_POWER_OFF: return "Remote Device Terminated (Power Off)";
            case CONNECTION_TERMINATED_BY_LOCAL_HOST: return "Connection Terminated By Local Host";
            case REPEATED_ATTEMPTS: return "Repeated Attempts";
            case PAIRING_NOT_ALLOWED: return "Pairing Not Allowed";
            case UNKNOWN_LMP_PDU: return "Unknown LMP PDU";
            case UNSUPPORTED_REMOTE_FEATURE: return "Unsupported Remote Feature";
            case UNSPECIFIED_ERROR: return "Unspecified Error";
            case LMP_RESPONSE_TIMEOUT: return "LMP Response Timeout";
            case LMP_PDU_NOT_ALLOWED: return "LMP PDU Not Allowed";
            case ENCRYPTION_MODE_NOT_ACCEPTABLE: return "Encryption Mode Not Acceptable";
            case LINK_KEY_CANNOT_BE_CHANGED: return "Link Key Cannot Be Changed";
            case INSTANT_PASSED: return "Instant Passed";
            case PAIRING_WITH_UNIT_KEY_NOT_SUPPORTED: return "Pairing With Unit Key Not Supported";
            case DIFFERENT_TRANSACTION_COLLISION: return "Different Transaction Collision";
            case INSUFFICIENT_SECURITY: return "Insufficient Security";
            case PARAMETER_OUT_OF_MANDATORY_RANGE: return "Parameter Out Of Mandatory Range";
            case EIR_TOO_LARGE: return "Extended Inquiry Response Too Large";
            case SSP_NOT_SUPPORTED_BY_HOST: return "Secure Simple Pairing Not Supported By Host";
            case HOST_BUSY_PAIRING: return "Host Busy - Pairing";
            case CONTROLLER_BUSY: return "Controller Busy";
            case UNACCEPTABLE_CONNECTION_PARAMETERS: return "Unacceptable Connection Parameters";
            case ADVERTISING_TIMEOUT: return "Advertising Timeout";
            case CONNECTION_TERMINATED_MIC_FAILURE: return "Connection Terminated (MIC Failure)";
            case CONNECTION_FAILED_TO_BE_ESTABLISHED: return "Connection Failed to be Established";
            default: return String.format("Unknown Error (0x%02X)", errorCode);
        }
    }

    /**
     * Returns whether the error code indicates success.
     *
     * @param errorCode HCI error code
     * @return true if the operation succeeded
     */
    public static boolean isSuccess(int errorCode) {
        return errorCode == SUCCESS;
    }
}