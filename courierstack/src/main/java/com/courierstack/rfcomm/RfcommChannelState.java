package com.courierstack.rfcomm;

/**
 * RFCOMM channel (DLCI) connection states.
 *
 * <p>Represents the lifecycle of an RFCOMM data link connection from
 * creation through parameter negotiation to connected state.
 *
 * <p>State transitions:
 * <pre>
 * CLOSED → CONFIG → CONNECTING → CONNECTED → DISCONNECTING → CLOSED
 * </pre>
 */
public enum RfcommChannelState {
    /** Channel is closed or not yet created. */
    CLOSED,

    /** Parameter negotiation (PN) in progress. */
    CONFIG,

    /** SABM sent, awaiting UA response. */
    CONNECTING,

    /** Channel is open and ready for data transfer. */
    CONNECTED,

    /** DISC sent, awaiting UA response. */
    DISCONNECTING;

    /**
     * Returns whether the channel can send/receive data.
     *
     * @return true if channel is CONNECTED
     */
    public boolean canTransferData() {
        return this == CONNECTED;
    }

    /**
     * Returns whether the channel is in a connecting state.
     *
     * @return true if CONFIG or CONNECTING
     */
    public boolean isConnecting() {
        return this == CONFIG || this == CONNECTING;
    }

    /**
     * Returns whether this is a terminal state.
     *
     * @return true if CLOSED
     */
    public boolean isTerminal() {
        return this == CLOSED;
    }
}