package com.courierstack.security.le;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SMP pairing session state.
 *
 * <p>Holds all state for an ongoing SMP pairing procedure including
 * pairing parameters, cryptographic values, and completion status.
 *
 * <p>Thread Safety: State management uses atomic references. Cryptographic
 * arrays should only be modified by the SmpManager thread.
 */
public final class SmpSession {

    // ==================== Immutable Fields ====================

    /** ACL connection handle. */
    public final int connectionHandle;

    /** Peer Bluetooth address (6 bytes). */
    private final byte[] peerAddress;

    /** Peer address type (0=public, 1=random). */
    public final int peerAddressType;

    /** Our role in pairing. */
    public final SmpRole role;

    /** Session start time. */
    public final long startTime;

    /** Completion latch for synchronous pairing. */
    public final CountDownLatch completionLatch = new CountDownLatch(1);

    // ==================== State Management ====================

    private final AtomicReference<SmpState> state = new AtomicReference<>(SmpState.IDLE);
    private final AtomicReference<SmpPairingMethod> method = new AtomicReference<>();

    // ==================== Local Pairing Parameters ====================

    /** Local IO capability. */
    public volatile int localIoCap = SmpConstants.IO_CAP_NO_INPUT_NO_OUTPUT;

    /** Local OOB flag. */
    public volatile int localOobFlag = SmpConstants.OOB_AUTH_DATA_NOT_PRESENT;

    /** Local authentication requirements. */
    public volatile int localAuthReq = SmpConstants.AUTH_REQ_BONDING;

    /** Local maximum encryption key size. */
    public volatile int localMaxKeySize = SmpConstants.MAX_ENC_KEY_SIZE;

    /** Local initiator key distribution. */
    public volatile int localInitKeyDist = SmpConstants.KEY_DIST_ENC_KEY | SmpConstants.KEY_DIST_ID_KEY;

    /** Local responder key distribution. */
    public volatile int localRespKeyDist = SmpConstants.KEY_DIST_ENC_KEY | SmpConstants.KEY_DIST_ID_KEY;

    // ==================== Peer Pairing Parameters ====================

    /** Peer IO capability. */
    public volatile int peerIoCap;

    /** Peer OOB flag. */
    public volatile int peerOobFlag;

    /** Peer authentication requirements. */
    public volatile int peerAuthReq;

    /** Peer maximum encryption key size. */
    public volatile int peerMaxKeySize;

    /** Peer initiator key distribution. */
    public volatile int peerInitKeyDist;

    /** Peer responder key distribution. */
    public volatile int peerRespKeyDist;

    // ==================== Negotiated Parameters ====================

    /** Negotiated initiator key distribution. */
    public volatile int negotiatedInitKeyDist;

    /** Negotiated responder key distribution. */
    public volatile int negotiatedRespKeyDist;

    /** Negotiated encryption key size. */
    public volatile int negotiatedKeySize;

    /** Whether using Secure Connections. */
    public volatile boolean useSecureConnections = false;

    // ==================== Cryptographic Values ====================

    /** Temporary Key (Legacy pairing). */
    public final byte[] tk = new byte[16];

    /** Local random value (Mconfirm/Sconfirm). */
    public final byte[] localRandom = new byte[16];

    /** Peer random value. */
    public final byte[] peerRandom = new byte[16];

    /** Local confirm value. */
    public final byte[] localConfirm = new byte[16];

    /** Peer confirm value. */
    public final byte[] peerConfirm = new byte[16];

    /** Short Term Key (Legacy pairing). */
    public final byte[] stk = new byte[16];

    /** Long Term Key. */
    public final byte[] ltk = new byte[16];

    /** Encrypted Diversifier (Legacy). */
    public final byte[] ediv = new byte[2];

    /** Random number for LTK (Legacy). */
    public final byte[] rand = new byte[8];

    /** Identity Resolving Key (local). */
    public final byte[] irk = new byte[16];

    /** Connection Signature Resolving Key (local). */
    public final byte[] csrk = new byte[16];

    // ==================== Peer Keys ====================

    /** Peer Long Term Key. */
    public final byte[] peerLtk = new byte[16];

    /** Peer EDIV. */
    public final byte[] peerEdiv = new byte[2];

    /** Peer Rand. */
    public final byte[] peerRand = new byte[8];

    /** Peer IRK. */
    public final byte[] peerIrk = new byte[16];

    /** Peer identity address. */
    public final byte[] peerIdentityAddress = new byte[6];

    /** Peer identity address type. */
    public volatile int peerIdentityAddressType;

    /** Peer CSRK. */
    public final byte[] peerCsrk = new byte[16];

    // ==================== Secure Connections ECDH ====================

    /** Local P-256 public key X coordinate. */
    public final byte[] localPublicKeyX = new byte[32];

    /** Local P-256 public key Y coordinate. */
    public final byte[] localPublicKeyY = new byte[32];

    /** Peer P-256 public key X coordinate. */
    public final byte[] peerPublicKeyX = new byte[32];

    /** Peer P-256 public key Y coordinate. */
    public final byte[] peerPublicKeyY = new byte[32];

    /** Diffie-Hellman shared secret. */
    public final byte[] dhKey = new byte[32];

    /** MAC key for DHKey check. */
    public final byte[] macKey = new byte[16];

    /** Local DHKey check value. */
    public final byte[] localDhKeyCheck = new byte[16];

    /** Peer DHKey check value. */
    public final byte[] peerDhKeyCheck = new byte[16];

    /** Initiator nonce (Na). */
    public final byte[] na = new byte[16];

    /** Responder nonce (Nb). */
    public final byte[] nb = new byte[16];

    // ==================== Passkey State ====================

    /** Passkey value (0-999999). */
    public volatile int passkey;

    /** Current passkey bit index (SC passkey entry). */
    public volatile int passkeyBitIndex;

    /** Whether SC initiator has already sent confirm (for race condition prevention). */
    public volatile boolean scInitiatorConfirmSent = false;

    /** Latch to wait for DHKey generation completion (for async DHKey generation). */
    public volatile CountDownLatch dhKeyLatch = null;

    /** Whether DHKey generation succeeded. */
    public volatile boolean dhKeyGenerated = false;

    // ==================== Key Distribution State ====================

    /** Bit mask of keys received from peer. */
    private final AtomicInteger keysReceived = new AtomicInteger(0);

    /** Whether local key distribution has started. */
    public volatile boolean localKeysDistributionStarted = false;

    // ==================== Completion State ====================

    /** Whether pairing succeeded. */
    public volatile boolean success;

    /** Error code if failed. */
    public volatile int errorCode;

    /** Error message if failed. */
    public volatile String errorMessage;

    /** Callback for async pairing. */
    public volatile ISmpCallback callback;

    // ==================== Constructor ====================

    /**
     * Creates a new SMP session.
     *
     * @param connectionHandle ACL connection handle
     * @param peerAddress peer Bluetooth address (6 bytes)
     * @param peerAddressType peer address type
     * @param role our role in pairing
     * @throws NullPointerException if peerAddress or role is null
     * @throws IllegalArgumentException if peerAddress length is not 6
     */
    public SmpSession(int connectionHandle, byte[] peerAddress, int peerAddressType, SmpRole role) {
        Objects.requireNonNull(peerAddress, "peerAddress must not be null");
        Objects.requireNonNull(role, "role must not be null");
        if (peerAddress.length != 6) {
            throw new IllegalArgumentException("peerAddress must be 6 bytes");
        }

        this.connectionHandle = connectionHandle;
        this.peerAddress = Arrays.copyOf(peerAddress, 6);
        this.peerAddressType = peerAddressType;
        this.role = role;
        this.startTime = System.currentTimeMillis();
    }

    // ==================== State Management ====================

    /**
     * Gets the current pairing state.
     *
     * @return current state
     */
    public SmpState getState() {
        return state.get();
    }

    /**
     * Sets the pairing state.
     *
     * @param newState new state
     */
    public void setState(SmpState newState) {
        state.set(newState);
    }

    /**
     * Atomically sets the state if current state matches expected.
     *
     * @param expected expected current state
     * @param update new state
     * @return true if updated
     */
    public boolean compareAndSetState(SmpState expected, SmpState update) {
        return state.compareAndSet(expected, update);
    }

    /**
     * Gets the pairing method.
     *
     * @return pairing method or null if not determined
     */
    public SmpPairingMethod getMethod() {
        return method.get();
    }

    /**
     * Sets the pairing method.
     *
     * @param newMethod pairing method
     */
    public void setMethod(SmpPairingMethod newMethod) {
        method.set(newMethod);
    }

    // ==================== Key Distribution ====================

    /**
     * Marks a key type as received.
     *
     * @param keyType key distribution flag
     */
    public void markKeyReceived(int keyType) {
        keysReceived.getAndUpdate(current -> current | keyType);
    }

    /**
     * Gets the bitmask of received keys.
     *
     * @return received keys mask
     */
    public int getKeysReceived() {
        return keysReceived.get();
    }

    /**
     * Checks if a specific key has been received.
     *
     * @param keyType key distribution flag
     * @return true if key received
     */
    public boolean hasReceivedKey(int keyType) {
        return (keysReceived.get() & keyType) != 0;
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
        return SmpConstants.formatAddress(peerAddress);
    }

    /**
     * Returns the peer identity address if available.
     *
     * @return identity address or null if not received
     */
    public byte[] getPeerIdentityAddressIfPresent() {
        // Check if identity address is set (not all zeros)
        for (byte b : peerIdentityAddress) {
            if (b != 0) {
                return Arrays.copyOf(peerIdentityAddress, 6);
            }
        }
        return null;
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
        return getState().isPairingInProgress();
    }

    /**
     * Returns whether we are the initiator.
     *
     * @return true if initiator
     */
    public boolean isInitiator() {
        return role == SmpRole.INITIATOR;
    }

    /**
     * Returns whether we are the responder.
     *
     * @return true if responder
     */
    public boolean isResponder() {
        return role == SmpRole.RESPONDER;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SmpSession)) return false;
        SmpSession that = (SmpSession) o;
        return connectionHandle == that.connectionHandle;
    }

    @Override
    public int hashCode() {
        return connectionHandle;
    }

    @Override
    public String toString() {
        return String.format("SmpSession{handle=0x%04X, peer=%s, role=%s, state=%s}",
                connectionHandle, getPeerAddressString(), role, getState());
    }


}