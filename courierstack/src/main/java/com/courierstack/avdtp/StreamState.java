package com.courierstack.avdtp;

/**
 * AVDTP stream states per AVDTP Spec v1.3.
 *
 * <p>State transitions:
 * <pre>
 * IDLE → (SET_CONFIGURATION) → CONFIGURED
 * CONFIGURED → (OPEN) → OPEN
 * OPEN → (START) → STREAMING
 * STREAMING → (SUSPEND) → OPEN
 * STREAMING/OPEN → (CLOSE) → CLOSING → IDLE
 * Any → (ABORT) → ABORTING → IDLE
 * </pre>
 */
public enum StreamState {

    /**
     * Initial state - endpoint is available but not configured.
     */
    IDLE,

    /**
     * Configuration has been set via SET_CONFIGURATION.
     * Stream is not yet open for media transport.
     */
    CONFIGURED,

    /**
     * Stream is open and media channel is established.
     * Ready to start streaming.
     */
    OPEN,

    /**
     * Actively streaming media data.
     */
    STREAMING,

    /**
     * Close operation in progress.
     */
    CLOSING,

    /**
     * Abort operation in progress.
     */
    ABORTING;

    /**
     * Returns true if streaming is active.
     */
    public boolean isStreaming() {
        return this == STREAMING;
    }

    /**
     * Returns true if the stream is open (ready for streaming or streaming).
     */
    public boolean isOpen() {
        return this == OPEN || this == STREAMING;
    }

    /**
     * Returns true if the stream is configured.
     */
    public boolean isConfigured() {
        return this != IDLE;
    }

    /**
     * Returns true if the stream is in a transitional state.
     */
    public boolean isTransitioning() {
        return this == CLOSING || this == ABORTING;
    }
}