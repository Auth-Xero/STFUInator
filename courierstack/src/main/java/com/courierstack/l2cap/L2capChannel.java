package com.courierstack.l2cap;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents an L2CAP channel (connection-oriented or credit-based).
 *
 * <p>An L2CAP channel provides a logical connection over an ACL link.
 * Channels can be either connection-oriented (BR/EDR) or credit-based
 * flow control (LE CoC).
 *
 * <p>Thread safety: State changes and credit updates are atomic.
 * The channel should be considered immutable after reaching OPEN state
 * except for credit management.
 */
public class L2capChannel {

    /** Local channel identifier. */
    public final int localCid;

    /** Protocol/Service Multiplexer. */
    public final int psm;

    /** Underlying ACL connection. */
    public final AclConnection connection;

    /** Channel creation timestamp. */
    public final long createdAt;

    /** Remote channel identifier. */
    private volatile int remoteCid;

    /** Current channel state. */
    private final AtomicReference<ChannelState> state;

    /** Local MTU (Maximum Transmission Unit). */
    private volatile int mtu = L2capConstants.DEFAULT_MTU;

    /** Peer's MTU. */
    private volatile int peerMtu = L2capConstants.DEFAULT_MTU;

    /** Local configuration complete flag. */
    private volatile boolean localConfigDone;

    /** Remote configuration complete flag. */
    private volatile boolean remoteConfigDone;

    /** Local credits for LE Credit-based flow control. */
    private final AtomicInteger localCredits;

    /** Remote credits for LE Credit-based flow control. */
    private final AtomicInteger remoteCredits;

    /** Maximum PDU Size for LE CoC (Credit-based Connection). */
    private volatile int mps = L2capConstants.DEFAULT_LE_MPS;

    /** Whether this is a credit-based (LE CoC) channel. */
    private volatile boolean creditBased;

    /**
     * Creates a new L2CAP channel.
     *
     * @param localCid   local channel identifier
     * @param psm        protocol/service multiplexer
     * @param connection underlying ACL connection (must not be null)
     * @throws NullPointerException if connection is null
     */
    public L2capChannel(int localCid, int psm, AclConnection connection) {
        this.localCid = localCid;
        this.psm = psm;
        this.connection = Objects.requireNonNull(connection, "connection must not be null");
        this.createdAt = System.currentTimeMillis();
        this.state = new AtomicReference<>(ChannelState.CLOSED);
        this.localCredits = new AtomicInteger(L2capConstants.DEFAULT_LE_CREDITS);
        this.remoteCredits = new AtomicInteger(0);
    }

    // ==================== State Management ====================

    /**
     * Returns the current channel state.
     *
     * @return channel state
     */
    public ChannelState getState() {
        return state.get();
    }

    /**
     * Sets the channel state.
     *
     * @param newState new state
     */
    public void setState(ChannelState newState) {
        state.set(newState);
    }

    /**
     * Atomically sets the state if current state matches expected.
     *
     * @param expected expected current state
     * @param newState new state to set
     * @return true if state was updated
     */
    public boolean compareAndSetState(ChannelState expected, ChannelState newState) {
        return state.compareAndSet(expected, newState);
    }

    /**
     * Returns whether the channel is open and ready for data.
     *
     * @return true if channel is OPEN
     */
    public boolean isOpen() {
        return state.get() == ChannelState.OPEN;
    }

    /**
     * Returns whether both sides have completed configuration.
     *
     * @return true if configuration is complete
     */
    public boolean isConfigComplete() {
        return localConfigDone && remoteConfigDone;
    }

    // ==================== CID and MTU ====================

    /**
     * Returns the remote CID.
     *
     * @return remote channel identifier
     */
    public int getRemoteCid() {
        return remoteCid;
    }

    /**
     * Sets the remote CID.
     *
     * @param remoteCid remote channel identifier
     */
    public void setRemoteCid(int remoteCid) {
        this.remoteCid = remoteCid;
    }

    /**
     * Returns the local MTU.
     *
     * @return local MTU
     */
    public int getMtu() {
        return mtu;
    }

    /**
     * Sets the local MTU.
     *
     * @param mtu local MTU
     */
    public void setMtu(int mtu) {
        this.mtu = mtu;
    }

    /**
     * Returns the peer's MTU.
     *
     * @return peer MTU
     */
    public int getPeerMtu() {
        return peerMtu;
    }

    /**
     * Sets the peer's MTU.
     *
     * @param peerMtu peer MTU
     */
    public void setPeerMtu(int peerMtu) {
        this.peerMtu = peerMtu;
    }

    /**
     * Returns the effective MTU (minimum of local and peer).
     *
     * @return effective MTU for data transfer
     */
    public int getEffectiveMtu() {
        return Math.min(mtu, peerMtu);
    }

    // ==================== Configuration ====================

    /**
     * Returns whether local configuration is complete.
     *
     * @return true if local config done
     */
    public boolean isLocalConfigDone() {
        return localConfigDone;
    }

    /**
     * Sets the local configuration complete flag.
     *
     * @param done true if complete
     */
    public void setLocalConfigDone(boolean done) {
        this.localConfigDone = done;
    }

    /**
     * Returns whether remote configuration is complete.
     *
     * @return true if remote config done
     */
    public boolean isRemoteConfigDone() {
        return remoteConfigDone;
    }

    /**
     * Sets the remote configuration complete flag.
     *
     * @param done true if complete
     */
    public void setRemoteConfigDone(boolean done) {
        this.remoteConfigDone = done;
    }

    // ==================== Credit-Based Flow Control ====================

    /**
     * Returns whether this is a credit-based channel.
     *
     * @return true if credit-based (LE CoC)
     */
    public boolean isCreditBased() {
        return creditBased;
    }

    /**
     * Sets whether this is a credit-based channel.
     *
     * @param creditBased true for LE CoC
     */
    public void setCreditBased(boolean creditBased) {
        this.creditBased = creditBased;
    }

    /**
     * Returns the current local credit count.
     *
     * @return local credits
     */
    public int getLocalCredits() {
        return localCredits.get();
    }

    /**
     * Sets the local credit count.
     *
     * @param credits new credit count
     */
    public void setLocalCredits(int credits) {
        localCredits.set(credits);
    }

    /**
     * Adds credits to the local count.
     *
     * @param credits credits to add
     * @return new credit count
     */
    public int addLocalCredits(int credits) {
        return localCredits.addAndGet(credits);
    }

    /**
     * Returns the current remote credit count.
     *
     * @return remote credits
     */
    public int getRemoteCredits() {
        return remoteCredits.get();
    }

    /**
     * Sets the remote credit count.
     *
     * @param credits new credit count
     */
    public void setRemoteCredits(int credits) {
        remoteCredits.set(credits);
    }

    /**
     * Consumes one remote credit for sending data.
     *
     * @return true if credit was available and consumed
     */
    public boolean consumeRemoteCredit() {
        while (true) {
            int current = remoteCredits.get();
            if (current <= 0) {
                return false;
            }
            if (remoteCredits.compareAndSet(current, current - 1)) {
                return true;
            }
        }
    }

    /**
     * Adds credits to the remote count.
     *
     * @param credits credits to add
     * @return new credit count
     */
    public int addRemoteCredits(int credits) {
        return remoteCredits.addAndGet(credits);
    }

    /**
     * Returns the Maximum PDU Size (MPS).
     *
     * @return MPS value
     */
    public int getMps() {
        return mps;
    }

    /**
     * Sets the Maximum PDU Size (MPS).
     *
     * @param mps MPS value
     */
    public void setMps(int mps) {
        this.mps = mps;
    }

    // ==================== Object Methods ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        L2capChannel that = (L2capChannel) o;
        return localCid == that.localCid && connection.handle == that.connection.handle;
    }

    @Override
    public int hashCode() {
        return Objects.hash(localCid, connection.handle);
    }

    @Override
    public String toString() {
        return String.format("L2capChannel{localCid=0x%04X, remoteCid=0x%04X, psm=0x%04X, state=%s, handle=0x%04X}",
                localCid, remoteCid, psm, state.get(), connection.handle);
    }
}