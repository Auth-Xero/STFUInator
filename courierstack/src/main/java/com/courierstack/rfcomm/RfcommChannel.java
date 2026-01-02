package com.courierstack.rfcomm;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents an RFCOMM data link connection (DLC).
 *
 * <p>Each channel corresponds to a DLCI (Data Link Connection Identifier)
 * where DLCI = serverChannel * 2 (+ 1 if responder initiated).
 *
 * <p>Thread Safety: State management and credit tracking use atomic operations.
 * Other mutable fields use volatile for visibility.
 */
public class RfcommChannel {

    // ==================== Immutable Fields ====================

    /** Data Link Connection Identifier (0-61). */
    public final int dlci;

    /** Parent RFCOMM multiplexer session. */
    public final RfcommSession session;

    /** Server channel number (1-30), derived from DLCI. */
    public final int serverChannel;

    /** Channel creation timestamp. */
    public final long createdAt;

    // ==================== State Management ====================

    private final AtomicReference<RfcommChannelState> state;

    // ==================== Configuration ====================

    /** Maximum information field size (N1 parameter). */
    private volatile int frameSize = RfcommConstants.DEFAULT_MTU;

    /** Channel priority (0-63). */
    private volatile int priority = RfcommConstants.DEFAULT_PRIORITY;

    /** Credit-based flow control enabled (PN CL=0xF0). */
    private volatile boolean creditBasedFlowEnabled;

    // ==================== Flow Control ====================

    /** Local credits available for peer to send. */
    private final AtomicInteger localCredits;

    /** Remote credits available for us to send. */
    private final AtomicInteger remoteCredits;

    // ==================== Modem Status ====================

    /** Local modem status (MSC signals we send). */
    private volatile int localModemStatus =
            RfcommConstants.MSC_RTC | RfcommConstants.MSC_RTR | RfcommConstants.MSC_DV;

    /** Remote modem status (MSC signals from peer). */
    private volatile int remoteModemStatus;

    // ==================== Remote Device ====================

    /** Remote device BD_ADDR. */
    private volatile byte[] remoteAddress;

    // ==================== Constructor ====================

    /**
     * Creates a new RFCOMM channel.
     *
     * @param dlci    data link connection identifier (0-61)
     * @param session parent multiplexer session (must not be null)
     * @throws NullPointerException     if session is null
     * @throws IllegalArgumentException if dlci is invalid
     */
    public RfcommChannel(int dlci, RfcommSession session) {
        if (dlci < 0 || dlci > 61) {
            throw new IllegalArgumentException("Invalid DLCI: " + dlci);
        }
        Objects.requireNonNull(session, "session must not be null");

        this.dlci = dlci;
        this.session = session;
        this.serverChannel = dlci >> 1;
        this.createdAt = System.currentTimeMillis();
        this.state = new AtomicReference<>(RfcommChannelState.CLOSED);
        this.localCredits = new AtomicInteger(RfcommConstants.DEFAULT_CREDITS);
        this.remoteCredits = new AtomicInteger(0);
    }

    // ==================== State Management ====================

    /**
     * Returns the current channel state.
     *
     * @return channel state
     */
    public RfcommChannelState getState() {
        return state.get();
    }

    /**
     * Sets the channel state.
     *
     * @param newState new state
     */
    public void setState(RfcommChannelState newState) {
        state.set(newState);
    }

    /**
     * Atomically sets the state if current state matches expected.
     *
     * @param expected expected current state
     * @param newState new state to set
     * @return true if state was updated
     */
    public boolean compareAndSetState(RfcommChannelState expected, RfcommChannelState newState) {
        return state.compareAndSet(expected, newState);
    }

    /**
     * Returns whether the channel is connected and ready for data.
     *
     * @return true if channel is CONNECTED
     */
    public boolean isConnected() {
        return state.get() == RfcommChannelState.CONNECTED;
    }

    // ==================== Configuration ====================

    /**
     * Returns the maximum frame size (N1 parameter).
     *
     * @return frame size in bytes
     */
    public int getFrameSize() {
        return frameSize;
    }

    /**
     * Sets the maximum frame size.
     *
     * @param frameSize frame size in bytes
     */
    public void setFrameSize(int frameSize) {
        this.frameSize = frameSize;
    }

    /**
     * Returns the channel priority.
     *
     * @return priority (0-63)
     */
    public int getPriority() {
        return priority;
    }

    /**
     * Sets the channel priority.
     *
     * @param priority priority (0-63)
     */
    public void setPriority(int priority) {
        this.priority = priority;
    }

    /**
     * Returns whether credit-based flow control is enabled.
     *
     * @return true if credit-based flow enabled
     */
    public boolean isCreditBasedFlowEnabled() {
        return creditBasedFlowEnabled;
    }

    /**
     * Sets whether credit-based flow control is enabled.
     *
     * @param enabled true to enable
     */
    public void setCreditBasedFlowEnabled(boolean enabled) {
        this.creditBasedFlowEnabled = enabled;
    }

    // ==================== Flow Control ====================

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

    // ==================== Modem Status ====================

    /**
     * Returns the local modem status signals.
     *
     * @return modem status bits
     */
    public int getLocalModemStatus() {
        return localModemStatus;
    }

    /**
     * Sets the local modem status signals.
     *
     * @param status modem status bits
     */
    public void setLocalModemStatus(int status) {
        this.localModemStatus = status;
    }

    /**
     * Returns the remote modem status signals.
     *
     * @return modem status bits
     */
    public int getRemoteModemStatus() {
        return remoteModemStatus;
    }

    /**
     * Sets the remote modem status signals.
     *
     * @param status modem status bits
     */
    public void setRemoteModemStatus(int status) {
        this.remoteModemStatus = status;
    }

    /**
     * Returns whether peer has flow control asserted (cannot receive).
     *
     * @return true if peer is flow controlled
     */
    public boolean isPeerFlowControlled() {
        return (remoteModemStatus & RfcommConstants.MSC_FC) != 0;
    }

    // ==================== Remote Address ====================

    /**
     * Returns a copy of the remote device address.
     *
     * @return remote BD_ADDR (6 bytes) or null if not set
     */
    public byte[] getRemoteAddress() {
        byte[] addr = remoteAddress;
        return addr != null ? Arrays.copyOf(addr, addr.length) : null;
    }

    /**
     * Sets the remote device address.
     *
     * @param address remote BD_ADDR (6 bytes)
     */
    public void setRemoteAddress(byte[] address) {
        this.remoteAddress = address != null ? Arrays.copyOf(address, address.length) : null;
    }

    /**
     * Returns the remote address as a formatted string.
     *
     * @return address string or "??:??:??:??:??:??" if not set
     */
    public String getRemoteAddressString() {
        byte[] addr = remoteAddress;
        if (addr == null || addr.length != 6) {
            return "??:??:??:??:??:??";
        }
        return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                addr[5] & 0xFF, addr[4] & 0xFF, addr[3] & 0xFF,
                addr[2] & 0xFF, addr[1] & 0xFF, addr[0] & 0xFF);
    }

    // ==================== Utility Methods ====================

    /**
     * Returns whether the channel can send data.
     *
     * <p>Channel must be connected and have remote credits (if credit-based flow enabled).
     *
     * @return true if channel can send
     */
    public boolean canSend() {
        return state.get() == RfcommChannelState.CONNECTED &&
                (!creditBasedFlowEnabled || remoteCredits.get() > 0);
    }

    /**
     * Returns the elapsed time since channel creation.
     *
     * @return elapsed time in milliseconds
     */
    public long getElapsedTime() {
        return System.currentTimeMillis() - createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RfcommChannel)) return false;
        RfcommChannel that = (RfcommChannel) o;
        return dlci == that.dlci &&
                session.getL2capChannel().localCid == that.session.getL2capChannel().localCid;
    }

    @Override
    public int hashCode() {
        return Objects.hash(dlci, session.getL2capChannel().localCid);
    }

    @Override
    public String toString() {
        return String.format("RfcommChannel{dlci=%d, ch=%d, state=%s, credits=%d/%d}",
                dlci, serverChannel, state.get(),
                localCredits.get(), remoteCredits.get());
    }
}