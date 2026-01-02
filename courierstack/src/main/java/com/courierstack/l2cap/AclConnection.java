package com.courierstack.l2cap;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * Represents an HCI ACL connection.
 *
 * <p>An ACL connection is the underlying transport for L2CAP channels.
 * Each connection is identified by a 12-bit handle and maintains
 * state for L2CAP PDU reassembly.
 *
 * <p>Thread safety: This class uses synchronization for reassembly
 * operations. Other fields are effectively immutable or volatile.
 */
public class AclConnection {

    /** HCI connection handle (12 bits, 0x0000-0x0EFF). */
    public final int handle;

    /** Peer device address (6 bytes, little-endian). */
    private final byte[] peerAddress;

    /** Peer address type (0=Public, 1=Random). */
    public final int peerAddressType;

    /** Transport type (BR/EDR or LE). */
    public final ConnectionType type;

    /** True if local device initiated the connection. */
    public final boolean isInitiator;

    /** Connection creation timestamp. */
    public final long createdAt;

    /** Local ACL MTU. */
    private volatile int mtu = L2capConstants.DEFAULT_MTU;

    /** Peer's ACL MTU. */
    private volatile int peerMtu = L2capConstants.DEFAULT_MTU;

    // Reassembly state for fragmented L2CAP PDUs
    private final Object reassemblyLock = new Object();
    private ByteBuffer reassemblyBuffer;
    private int expectedLength;

    /**
     * Creates a new ACL connection.
     *
     * @param handle          HCI connection handle (0x0000-0x0EFF)
     * @param peerAddress     peer BD_ADDR (6 bytes, must not be null)
     * @param peerAddressType address type (0=public, 1=random)
     * @param type            transport type (must not be null)
     * @param isInitiator     true if local device initiated
     * @throws NullPointerException     if peerAddress or type is null
     * @throws IllegalArgumentException if peerAddress is not 6 bytes or handle is invalid
     */
    public AclConnection(int handle, byte[] peerAddress, int peerAddressType,
                         ConnectionType type, boolean isInitiator) {
        Objects.requireNonNull(peerAddress, "peerAddress must not be null");
        Objects.requireNonNull(type, "type must not be null");

        if (peerAddress.length != 6) {
            throw new IllegalArgumentException("peerAddress must be 6 bytes");
        }
        if (handle < 0 || handle > 0x0EFF) {
            throw new IllegalArgumentException("Invalid connection handle: 0x" +
                    Integer.toHexString(handle));
        }

        this.handle = handle;
        this.peerAddress = peerAddress.clone();
        this.peerAddressType = peerAddressType;
        this.type = type;
        this.isInitiator = isInitiator;
        this.createdAt = System.currentTimeMillis();
    }

    /**
     * Creates a new ACL connection with public address type.
     *
     * @param handle      HCI connection handle
     * @param peerAddress peer BD_ADDR (6 bytes)
     * @param type        transport type
     * @param isInitiator true if local device initiated
     */
    public AclConnection(int handle, byte[] peerAddress, ConnectionType type, boolean isInitiator) {
        this(handle, peerAddress, 0, type, isInitiator);
    }

    /**
     * Returns a copy of the peer address.
     *
     * @return 6-byte peer address
     */
    public byte[] getPeerAddress() {
        return peerAddress.clone();
    }

    /**
     * Returns formatted peer address string.
     *
     * @return address in XX:XX:XX:XX:XX:XX format
     */
    public String getFormattedAddress() {
        return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                peerAddress[5] & 0xFF, peerAddress[4] & 0xFF,
                peerAddress[3] & 0xFF, peerAddress[2] & 0xFF,
                peerAddress[1] & 0xFF, peerAddress[0] & 0xFF);
    }

    /**
     * Returns whether the peer address matches the given address.
     *
     * @param address address to compare (6 bytes)
     * @return true if addresses match
     */
    public boolean matchesAddress(byte[] address) {
        return address != null && Arrays.equals(peerAddress, address);
    }

    /**
     * Returns the local ACL MTU.
     *
     * @return local MTU
     */
    public int getMtu() {
        return mtu;
    }

    /**
     * Sets the local ACL MTU.
     *
     * @param mtu local MTU
     */
    public void setMtu(int mtu) {
        this.mtu = mtu;
    }

    /**
     * Returns the peer's ACL MTU.
     *
     * @return peer MTU
     */
    public int getPeerMtu() {
        return peerMtu;
    }

    /**
     * Sets the peer's ACL MTU.
     *
     * @param peerMtu peer MTU
     */
    public void setPeerMtu(int peerMtu) {
        this.peerMtu = peerMtu;
    }

    /**
     * Returns the effective MTU (minimum of local and peer).
     *
     * @return effective MTU
     */
    public int getEffectiveMtu() {
        return Math.min(mtu, peerMtu);
    }

    // ==================== Reassembly Methods ====================

    /**
     * Starts L2CAP PDU reassembly.
     *
     * @param totalLength expected total length of the PDU
     */
    public void startReassembly(int totalLength) {
        synchronized (reassemblyLock) {
            reassemblyBuffer = ByteBuffer.allocate(totalLength);
            expectedLength = totalLength;
        }
    }

    /**
     * Adds fragment data to the reassembly buffer.
     *
     * @param data fragment data
     */
    public void addReassemblyData(byte[] data) {
        synchronized (reassemblyLock) {
            if (reassemblyBuffer != null && data != null) {
                int remaining = reassemblyBuffer.remaining();
                int toCopy = Math.min(data.length, remaining);
                reassemblyBuffer.put(data, 0, toCopy);
            }
        }
    }

    /**
     * Returns whether reassembly is in progress.
     *
     * @return true if reassembly is in progress
     */
    public boolean isReassembling() {
        synchronized (reassemblyLock) {
            return reassemblyBuffer != null;
        }
    }

    /**
     * Returns whether reassembly is complete.
     *
     * @return true if all expected data has been received
     */
    public boolean isReassemblyComplete() {
        synchronized (reassemblyLock) {
            return reassemblyBuffer != null && reassemblyBuffer.position() >= expectedLength;
        }
    }

    /**
     * Returns the reassembled data and clears the buffer.
     *
     * @return reassembled data, or null if not complete
     */
    public byte[] getReassembledData() {
        synchronized (reassemblyLock) {
            if (reassemblyBuffer == null) {
                return null;
            }
            byte[] data = new byte[reassemblyBuffer.position()];
            reassemblyBuffer.flip();
            reassemblyBuffer.get(data);
            reassemblyBuffer = null;
            expectedLength = 0;
            return data;
        }
    }

    /**
     * Clears the reassembly state.
     */
    public void clearReassembly() {
        synchronized (reassemblyLock) {
            reassemblyBuffer = null;
            expectedLength = 0;
        }
    }

    // ==================== Object Methods ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AclConnection that = (AclConnection) o;
        return handle == that.handle;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(handle);
    }

    @Override
    public String toString() {
        return String.format("AclConnection{handle=0x%04X, addr=%s, type=%s, initiator=%s}",
                handle, getFormattedAddress(), type, isInitiator);
    }
}