package com.courierstack.hci;

/**
 * HCI HAL initialization status codes.
 *
 * <p>These status codes represent the result of initializing the Bluetooth
 * HAL and correspond to the status values defined in the Android Bluetooth
 * HIDL/AIDL interfaces.
 */
public enum HciStatus {
    /** Initialization successful. */
    SUCCESS("Success"),
    /** HAL already initialized. */
    ALREADY_INITIALIZED("Already Initialized"),
    /** Failed to open transport interface. */
    UNABLE_TO_OPEN_INTERFACE("Unable to Open Interface"),
    /** Controller initialization failed. */
    INITIALIZATION_ERROR("Initialization Error"),
    /** Transport layer error. */
    TRANSPORT_ERROR("Transport Error"),
    /** Unknown status. */
    UNKNOWN("Unknown");

    /** Human-readable status label. */
    public final String label;

    HciStatus(String label) {
        this.label = label;
    }

    /**
     * Returns whether this status indicates successful initialization.
     *
     * @return true if initialization succeeded
     */
    public boolean isSuccess() {
        return this == SUCCESS || this == ALREADY_INITIALIZED;
    }

    @Override
    public String toString() {
        return label;
    }
}