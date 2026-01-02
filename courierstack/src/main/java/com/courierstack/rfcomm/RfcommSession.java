package com.courierstack.rfcomm;

import com.courierstack.l2cap.L2capChannel;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents an RFCOMM multiplexer session over L2CAP PSM 0x0003.
 *
 * <p>The multiplexer is established on DLCI 0 before any data channels
 * can be opened. Multiple DLCIs share a single L2CAP channel.
 *
 * <p>Thread Safety: State management uses atomic references. Pending
 * channels use a concurrent queue.
 */
public class RfcommSession {

    // ==================== Immutable Fields ====================

    /** Underlying L2CAP channel (PSM=RFCOMM). */
    private final L2capChannel l2capChannel;

    /** True if we initiated the multiplexer (sent SABM on DLCI 0 first). */
    private final boolean isInitiator;

    /** Session creation timestamp. */
    public final long createdAt;

    // ==================== State Management ====================

    private final AtomicReference<MuxState> state;

    // ==================== Configuration ====================

    /** Maximum frame size (N1) for this session. */
    private volatile int maxFrameSize = RfcommConstants.DEFAULT_MTU;

    /** Credit-based flow control enabled for this session. */
    private volatile boolean creditBasedFlow = true;

    // ==================== Pending Operations ====================

    /** Pending PN (parameter negotiation) DLCI. */
    private volatile int pendingPnDlci = -1;

    /** Pending PN callback. */
    private volatile IRfcommChannelCallback pendingPnCallback;

    /** Queue of channels waiting for mux to open. */
    private final Queue<PendingChannel> pendingChannels;

    // ==================== Constructor ====================

    /**
     * Creates a new RFCOMM session.
     *
     * @param l2capChannel underlying L2CAP channel (must not be null)
     * @param isInitiator  true if we initiated the connection
     * @throws NullPointerException if l2capChannel is null
     */
    public RfcommSession(L2capChannel l2capChannel, boolean isInitiator) {
        this.l2capChannel = Objects.requireNonNull(l2capChannel, "l2capChannel must not be null");
        this.isInitiator = isInitiator;
        this.createdAt = System.currentTimeMillis();
        this.state = new AtomicReference<>(MuxState.CLOSED);
        this.pendingChannels = new ConcurrentLinkedQueue<>();
    }

    // ==================== Accessors ====================

    /**
     * Returns the underlying L2CAP channel.
     *
     * @return L2CAP channel
     */
    public L2capChannel getL2capChannel() {
        return l2capChannel;
    }

    /**
     * Returns whether we initiated the multiplexer.
     *
     * @return true if initiator
     */
    public boolean isInitiator() {
        return isInitiator;
    }

    // ==================== State Management ====================

    /**
     * Returns the current multiplexer state.
     *
     * @return mux state
     */
    public MuxState getState() {
        return state.get();
    }

    /**
     * Sets the multiplexer state.
     *
     * @param newState new state
     */
    public void setState(MuxState newState) {
        state.set(newState);
    }

    /**
     * Atomically sets the state if current state matches expected.
     *
     * @param expected expected current state
     * @param newState new state to set
     * @return true if state was updated
     */
    public boolean compareAndSetState(MuxState expected, MuxState newState) {
        return state.compareAndSet(expected, newState);
    }

    /**
     * Returns whether the multiplexer is open.
     *
     * @return true if OPEN
     */
    public boolean isOpen() {
        return state.get() == MuxState.OPEN;
    }

    // ==================== Configuration ====================

    /**
     * Returns the maximum frame size (N1).
     *
     * @return max frame size in bytes
     */
    public int getMaxFrameSize() {
        return maxFrameSize;
    }

    /**
     * Sets the maximum frame size.
     *
     * @param maxFrameSize max frame size in bytes
     */
    public void setMaxFrameSize(int maxFrameSize) {
        this.maxFrameSize = maxFrameSize;
    }

    /**
     * Returns whether credit-based flow control is enabled.
     *
     * @return true if credit-based flow enabled
     */
    public boolean isCreditBasedFlow() {
        return creditBasedFlow;
    }

    /**
     * Sets whether credit-based flow control is enabled.
     *
     * @param creditBasedFlow true to enable
     */
    public void setCreditBasedFlow(boolean creditBasedFlow) {
        this.creditBasedFlow = creditBasedFlow;
    }

    // ==================== Pending Channels ====================

    /**
     * Queues a channel to open after multiplexer is established.
     *
     * @param serverChannel server channel number (1-30)
     * @param callback      connection callback
     */
    public void queuePendingChannel(int serverChannel, IRfcommChannelCallback callback) {
        pendingChannels.offer(new PendingChannel(serverChannel, callback));
    }

    /**
     * Polls and removes the next pending channel.
     *
     * @return pending channel or null if queue is empty
     */
    public PendingChannel pollPendingChannel() {
        return pendingChannels.poll();
    }

    /**
     * Returns whether there are pending channels waiting.
     *
     * @return true if pending channels exist
     */
    public boolean hasPendingChannels() {
        return !pendingChannels.isEmpty();
    }

    // ==================== Pending PN State ====================

    /**
     * Sets pending PN (parameter negotiation) state.
     *
     * @param dlci     DLCI being negotiated
     * @param callback connection callback
     */
    public void setPendingPn(int dlci, IRfcommChannelCallback callback) {
        this.pendingPnDlci = dlci;
        this.pendingPnCallback = callback;
    }

    /**
     * Returns the DLCI being negotiated, or -1 if none.
     *
     * @return pending PN DLCI
     */
    public int getPendingPnDlci() {
        return pendingPnDlci;
    }

    /**
     * Returns the pending PN callback, or null if none.
     *
     * @return pending PN callback
     */
    public IRfcommChannelCallback getPendingPnCallback() {
        return pendingPnCallback;
    }

    /**
     * Clears pending PN state.
     */
    public void clearPendingPn() {
        pendingPnDlci = -1;
        pendingPnCallback = null;
    }

    // ==================== Utility Methods ====================

    /**
     * Returns the elapsed time since session creation.
     *
     * @return elapsed time in milliseconds
     */
    public long getElapsedTime() {
        return System.currentTimeMillis() - createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RfcommSession)) return false;
        RfcommSession that = (RfcommSession) o;
        return l2capChannel.localCid == that.l2capChannel.localCid;
    }

    @Override
    public int hashCode() {
        return l2capChannel.localCid;
    }

    @Override
    public String toString() {
        return String.format("RfcommSession{cid=0x%04X, state=%s, initiator=%s}",
                l2capChannel.localCid, state.get(), isInitiator);
    }

    // ==================== Pending Channel Holder ====================

    /**
     * Pending channel request holder.
     */
    public static class PendingChannel {
        /** Server channel number (1-30). */
        public final int serverChannel;

        /** Connection callback. */
        public final IRfcommChannelCallback callback;

        /**
         * Creates a pending channel request.
         *
         * @param serverChannel server channel number
         * @param callback      connection callback
         */
        public PendingChannel(int serverChannel, IRfcommChannelCallback callback) {
            this.serverChannel = serverChannel;
            this.callback = callback;
        }
    }
}