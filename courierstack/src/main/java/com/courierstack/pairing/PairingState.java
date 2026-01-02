package com.courierstack.pairing;

/**
 * BR/EDR pairing state machine states.
 *
 * <p>Represents the various states during the BR/EDR SSP pairing process,
 * from initial idle state through key exchange to final paired or failed state.
 *
 * <p>State transitions follow the Bluetooth Core Spec v5.3, Vol 2, Part C
 * pairing state machine.
 */
public enum PairingState {
    /** Initial idle state - no pairing in progress. */
    IDLE,

    /** Waiting for IO capability exchange. */
    WAITING_IO_CAP,

    /** Waiting for user confirmation. */
    WAITING_CONFIRM,

    /** Waiting for passkey entry. */
    WAITING_PASSKEY,

    /** Waiting for link key. */
    WAITING_LINK_KEY,

    /** Pairing in progress. */
    PAIRING,

    /** Authentication in progress. */
    AUTHENTICATING,

    /** Encryption being established. */
    ENCRYPTING,

    /** Pairing completed successfully. */
    PAIRED,

    /** Pairing failed. */
    FAILED,

    /** IO capability request received. */
    IO_CAP_REQUEST,

    /** IO capabilities exchanged. */
    IO_CAP_EXCHANGED,

    /** Waiting for user confirmation of numeric value. */
    USER_CONFIRM,

    /** User confirmed numeric comparison. */
    CONFIRMED,

    /** Passkey input requested. */
    PASSKEY_REQUEST,

    /** Passkey being displayed. */
    PASSKEY_DISPLAY,

    /** Passkey entered. */
    KEY_ENTERED,

    /** Authentication completed. */
    AUTHENTICATED;

    /**
     * Returns whether pairing is in progress.
     *
     * @return true if pairing is active
     */
    public boolean isPairingInProgress() {
        return this != IDLE && this != PAIRED && this != FAILED;
    }

    /**
     * Returns whether this is a terminal state.
     *
     * @return true if terminal (PAIRED or FAILED)
     */
    public boolean isTerminal() {
        return this == PAIRED || this == FAILED;
    }

    /**
     * Returns whether pairing succeeded.
     *
     * @return true if paired
     */
    public boolean isSuccess() {
        return this == PAIRED;
    }

    /**
     * Returns whether waiting for user input.
     *
     * @return true if waiting for user
     */
    public boolean isWaitingForUser() {
        switch (this) {
            case WAITING_CONFIRM:
            case WAITING_PASSKEY:
            case USER_CONFIRM:
            case PASSKEY_REQUEST:
                return true;
            default:
                return false;
        }
    }

    /**
     * Returns whether waiting for peer response.
     *
     * @return true if waiting for peer
     */
    public boolean isWaitingForPeer() {
        switch (this) {
            case WAITING_IO_CAP:
            case WAITING_LINK_KEY:
            case AUTHENTICATING:
            case ENCRYPTING:
                return true;
            default:
                return false;
        }
    }
}