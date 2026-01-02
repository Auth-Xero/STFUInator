package com.courierstack.l2cap;

/**
 * L2CAP channel connection states.
 *
 * <p>Represents the lifecycle of an L2CAP channel from creation through
 * configuration to open state and eventual disconnection.
 *
 * <p>State transitions:
 * <pre>
 * CLOSED → WAIT_CONNECT → WAIT_CONNECT_RSP → CONFIG → OPEN → WAIT_DISCONNECT → CLOSED
 *                                              ↑                     ↓
 *                                              └─────────────────────┘
 * </pre>
 */
public enum ChannelState {
    /** Channel is closed or not yet created. */
    CLOSED,

    /** Waiting for local connection initiation. */
    WAIT_CONNECT,

    /** Connection request sent, waiting for response. */
    WAIT_CONNECT_RSP,

    /** Channel is in configuration phase. */
    CONFIG,

    /** Channel is open and ready for data transfer. */
    OPEN,

    /** Disconnection request sent, waiting for response. */
    WAIT_DISCONNECT;

    /**
     * Returns whether the channel is in a connected state.
     *
     * @return true if connected (CONFIG or OPEN)
     */
    public boolean isConnected() {
        return this == CONFIG || this == OPEN;
    }

    /**
     * Returns whether the channel can send/receive data.
     *
     * @return true if channel is OPEN
     */
    public boolean canTransferData() {
        return this == OPEN;
    }
}