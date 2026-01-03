package com.courierstack.security.le;

/**
 * SMP pairing role per Bluetooth Core Spec v5.3, Vol 3, Part H, Section 2.3.1.
 */
public enum SmpRole {
    /** Device initiating pairing (typically central). */
    INITIATOR,
    /** Device responding to pairing (typically peripheral). */
    RESPONDER;

    /**
     * Returns the opposite role.
     *
     * @return INITIATOR ↔ RESPONDER
     */
    public SmpRole opposite() {
        return this == INITIATOR ? RESPONDER : INITIATOR;
    }

    /**
     * Returns whether this is the initiator role.
     *
     * @return true if initiator
     */
    public boolean isInitiator() {
        return this == INITIATOR;
    }

    /**
     * Returns whether this is the responder role.
     *
     * @return true if responder
     */
    public boolean isResponder() {
        return this == RESPONDER;
    }
}