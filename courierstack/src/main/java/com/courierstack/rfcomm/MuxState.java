package com.courierstack.rfcomm;

/**
 * RFCOMM multiplexer (DLCI 0) states per TS 27.010.
 */
public enum MuxState {
    /** Multiplexer not established. */
    CLOSED,
    /** SABM sent on DLCI 0, awaiting UA. */
    CONNECTING,
    /** Multiplexer established, DLCIs can be opened. */
    OPEN,
    /** DISC sent on DLCI 0, awaiting UA. */
    DISCONNECTING
}