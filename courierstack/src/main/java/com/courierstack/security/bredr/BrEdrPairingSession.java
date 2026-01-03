package com.courierstack.security.bredr;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * BR/EDR pairing session state.
 *
 * <p>Holds all state for an ongoing BR/EDR pairing procedure including
 * IO capabilities, cryptographic values, and completion status.
 *
 * <p>Thread Safety: State management uses atomic references. Mutable fields
 * should only be modified by the PairingManager thread.
 */
public final class BrEdrPairingSession {

    // ==================== Immutable Fields ====================

    /** ACL connection handle. */
    public final int connectionHandle;

    /** Peer Bluetooth address (6 bytes). */
    private final byte[] peerAddress;

    /** Session start time. */
    public final long startTime;

    // ==================== State Management ====================

    private final AtomicReference<BrEdrPairingState> state = new AtomicReference<>(BrEdrPairingState.IDLE);
    private final AtomicReference<BrEdrPairingMode> mode = new AtomicReference<>();

    // ==================== Local Pairing Parameters ====================

    /** Local IO capability. */
    public volatile int localIoCap = BrEdrPairingConstants.IO_CAP_NO_INPUT_NO_OUTPUT;

    /** Local OOB present flag. */
    public volatile int localOobPresent = 0;

    /** Local authentication requirements. */
    public volatile int localAuthReq = BrEdrPairingConstants.AUTH_REQ_GENERAL_BONDING;

    // ==================== Peer Pairing Parameters ====================

    /** Peer IO capability. */
    public volatile int peerIoCap;

    /** Peer OOB present flag. */
    public volatile int peerOobPresent;

    /** Peer authentication requirements. */
    public volatile int peerAuthReq;

    // ==================== Pairing Values ====================

    /** Passkey value (0-999999). */
    public volatile int passkey;

    /** Numeric comparison value (0-999999). */
    public volatile int numericValue;

    /** Link key (16 bytes). */
    public final byte[] linkKey = new byte[16];

    /** Link key type. */
    public volatile int linkKeyType;

    /** Whether pairing was authenticated (MITM protected). */
    public volatile boolean authenticated;

    /** Whether encryption is enabled. */
    public volatile boolean encrypted;

    // ==================== Callback ====================

    /** Callback for async pairing. */
    public volatile IBrEdrPairingCallback callback;

    // ==================== Constructor ====================

    /**
     * Creates a new pairing session.
     *
     * @param connectionHandle ACL connection handle
     * @param peerAddress      peer Bluetooth address (6 bytes)
     * @throws NullPointerException     if peerAddress is null
     * @throws IllegalArgumentException if peerAddress length is not 6
     */
    public BrEdrPairingSession(int connectionHandle, byte[] peerAddress) {
        Objects.requireNonNull(peerAddress, "peerAddress must not be null");
        if (peerAddress.length != 6) {
            throw new IllegalArgumentException("peerAddress must be 6 bytes");
        }

        this.connectionHandle = connectionHandle;
        this.peerAddress = Arrays.copyOf(peerAddress, 6);
        this.startTime = System.currentTimeMillis();
    }

    // ==================== State Management ====================

    /**
     * Gets the current pairing state.
     *
     * @return current state
     */
    public BrEdrPairingState getState() {
        return state.get();
    }

    /**
     * Sets the pairing state.
     *
     * @param newState new state
     */
    public void setState(BrEdrPairingState newState) {
        state.set(newState);
    }

    /**
     * Atomically sets the state if current state matches expected.
     *
     * @param expected expected current state
     * @param update   new state
     * @return true if updated
     */
    public boolean compareAndSetState(BrEdrPairingState expected, BrEdrPairingState update) {
        return state.compareAndSet(expected, update);
    }

    /**
     * Gets the pairing mode.
     *
     * @return pairing mode or null if not determined
     */
    public BrEdrPairingMode getMode() {
        return mode.get();
    }

    /**
     * Sets the pairing mode.
     *
     * @param newMode pairing mode
     */
    public void setMode(BrEdrPairingMode newMode) {
        mode.set(newMode);
    }

    // ==================== Address Accessors ====================

    /**
     * Returns a copy of the peer address.
     *
     * @return peer address (6 bytes)
     */
    public byte[] getPeerAddress() {
        return Arrays.copyOf(peerAddress, 6);
    }

    /**
     * Returns the peer address as a formatted string.
     *
     * @return address string (XX:XX:XX:XX:XX:XX)
     */
    public String getPeerAddressString() {
        return BrEdrPairingConstants.formatAddress(peerAddress);
    }

    // ==================== Utility Methods ====================

    /**
     * Returns the elapsed time since session start.
     *
     * @return elapsed time in milliseconds
     */
    public long getElapsedTime() {
        return System.currentTimeMillis() - startTime;
    }

    /**
     * Returns whether pairing is still in progress.
     *
     * @return true if pairing active
     */
    public boolean isActive() {
        BrEdrPairingState currentState = getState();
        return currentState != BrEdrPairingState.IDLE &&
                currentState != BrEdrPairingState.PAIRED &&
                currentState != BrEdrPairingState.FAILED;
    }

    /**
     * Returns whether the link key provides MITM protection.
     *
     * @return true if MITM protected
     */
    public boolean isMitmProtected() {
        return linkKeyType == BrEdrPairingConstants.KEY_TYPE_AUTHENTICATED_P192 ||
                linkKeyType == BrEdrPairingConstants.KEY_TYPE_AUTHENTICATED_P256;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BrEdrPairingSession)) return false;
        BrEdrPairingSession that = (BrEdrPairingSession) o;
        return connectionHandle == that.connectionHandle;
    }

    @Override
    public int hashCode() {
        return connectionHandle;
    }

    @Override
    public String toString() {
        return String.format("PairingSession{handle=0x%04X, peer=%s, state=%s, mode=%s}",
                connectionHandle, getPeerAddressString(), getState(), getMode());
    }
}