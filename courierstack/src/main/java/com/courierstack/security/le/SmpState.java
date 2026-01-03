package com.courierstack.security.le;

/**
 * SMP pairing state machine states.
 *
 * <p>Represents the various states during the SMP pairing process,
 * from initial idle state through key exchange to final paired or failed state.
 *
 * <p>State transitions follow the Bluetooth Core Spec v5.3, Vol 3, Part H
 * pairing state machine.
 */
public enum SmpState {
    /** Initial idle state - no pairing in progress. */
    IDLE,
    /** Waiting for Pairing Response. */
    WAIT_PAIRING_RSP,
    /** Waiting for peer's public key (SC). */
    WAIT_PUBLIC_KEY,
    /** Waiting for DHKey generation (SC). */
    WAIT_DHKEY,
    /** Waiting for Pairing Confirm. */
    WAIT_CONFIRM,
    /** Waiting for Pairing Random. */
    WAIT_RANDOM,
    /** Waiting for DHKey Check (SC). */
    WAIT_DHKEY_CHECK,
    /** Waiting for LTK Request from controller. */
    WAIT_LTK_REQUEST,
    /** Waiting for encryption to be established. */
    WAIT_ENCRYPTION,
    /** Exchanging keys after pairing. */
    KEY_DISTRIBUTION,
    /** Pairing completed successfully. */
    PAIRED,
    /** Pairing failed. */
    FAILED;

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
     * Returns whether waiting for peer data.
     *
     * @return true if waiting for peer
     */
    public boolean isWaitingForPeer() {
        switch (this) {
            case WAIT_PAIRING_RSP:
            case WAIT_PUBLIC_KEY:
            case WAIT_CONFIRM:
            case WAIT_RANDOM:
            case WAIT_DHKEY_CHECK:
                return true;
            default:
                return false;
        }
    }
}