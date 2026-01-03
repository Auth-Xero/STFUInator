package com.courierstack.security.bredr;

import java.util.Arrays;

/**
 * BR/EDR bonding information stored after successful pairing.
 *
 * <p>Contains the link key and metadata needed to re-establish
 * an authenticated/encrypted connection with a previously paired device.
 *
 * <p>Thread Safety: This class is effectively immutable after construction
 * via the Builder. All byte array getters return defensive copies.
 */
public final class BondingInfo {

    private final byte[] address;
    private final byte[] linkKey;
    private final int linkKeyType;
    private final boolean authenticated;
    private final long timestamp;

    private BondingInfo(Builder builder) {
        this.address = builder.address != null ? Arrays.copyOf(builder.address, 6) : new byte[6];
        this.linkKey = builder.linkKey != null ? Arrays.copyOf(builder.linkKey, 16) : new byte[16];
        this.linkKeyType = builder.linkKeyType;
        this.authenticated = builder.authenticated;
        this.timestamp = builder.timestamp > 0 ? builder.timestamp : System.currentTimeMillis();
    }

    // ==================== Getters ====================

    /**
     * Returns a copy of the peer address.
     *
     * @return peer address (6 bytes)
     */
    public byte[] getAddress() {
        return Arrays.copyOf(address, 6);
    }

    /**
     * Returns a copy of the link key.
     *
     * @return link key (16 bytes)
     */
    public byte[] getLinkKey() {
        return Arrays.copyOf(linkKey, 16);
    }

    /**
     * Returns the link key type.
     *
     * @return link key type constant
     */
    public int getLinkKeyType() {
        return linkKeyType;
    }

    /**
     * Returns whether pairing was authenticated (MITM protected).
     *
     * @return true if authenticated
     */
    public boolean isAuthenticated() {
        return authenticated;
    }

    /**
     * Returns the timestamp when bonding was created.
     *
     * @return creation timestamp in milliseconds
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Returns the address formatted as a string.
     *
     * @return address string (XX:XX:XX:XX:XX:XX)
     */
    public String getAddressString() {
        return BrEdrPairingConstants.formatAddress(address);
    }

    /**
     * Returns whether the link key provides MITM protection.
     *
     * @return true if MITM protected
     */
    public boolean isMitmProtected() {
        return BrEdrPairingConstants.isMitmProtected(linkKeyType);
    }

    /**
     * Returns a human-readable description of the link key type.
     *
     * @return link key type description
     */
    public String getLinkKeyTypeString() {
        return BrEdrPairingConstants.getLinkKeyTypeString(linkKeyType);
    }

    /**
     * Checks if this bonding info matches the given address.
     *
     * @param addr address to check
     * @return true if addresses match
     */
    public boolean matchesAddress(byte[] addr) {
        return Arrays.equals(address, addr);
    }

    /**
     * Returns the security level of this bonding.
     *
     * <p>Security levels:
     * <ul>
     *   <li>1: Unauthenticated pairing</li>
     *   <li>2: Authenticated pairing (MITM protected)</li>
     *   <li>3: Authenticated pairing with P-256</li>
     * </ul>
     *
     * @return security level (1-3)
     */
    public int getSecurityLevel() {
        if (linkKeyType == BrEdrPairingConstants.KEY_TYPE_AUTHENTICATED_P256) {
            return 3;
        } else if (authenticated || isMitmProtected()) {
            return 2;
        } else {
            return 1;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BondingInfo)) return false;
        BondingInfo that = (BondingInfo) o;
        return Arrays.equals(address, that.address);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(address);
    }

    @Override
    public String toString() {
        return String.format("BondingInfo{address=%s, keyType=%s, auth=%b}",
                getAddressString(), getLinkKeyTypeString(), authenticated);
    }

    // ==================== Builder ====================

    /**
     * Builder for creating BondingInfo instances.
     */
    public static class Builder {
        private byte[] address;
        private byte[] linkKey;
        private int linkKeyType;
        private boolean authenticated;
        private long timestamp;

        public Builder() {
        }

        /**
         * Sets the peer address.
         *
         * @param address 6-byte address
         * @return this builder
         */
        public Builder address(byte[] address) {
            this.address = address;
            return this;
        }

        /**
         * Sets the link key.
         *
         * @param linkKey 16-byte link key
         * @return this builder
         */
        public Builder linkKey(byte[] linkKey) {
            this.linkKey = linkKey;
            return this;
        }

        /**
         * Sets the link key type.
         *
         * @param linkKeyType link key type constant
         * @return this builder
         */
        public Builder linkKeyType(int linkKeyType) {
            this.linkKeyType = linkKeyType;
            return this;
        }

        /**
         * Sets whether pairing was authenticated.
         *
         * @param authenticated true if authenticated
         * @return this builder
         */
        public Builder authenticated(boolean authenticated) {
            this.authenticated = authenticated;
            return this;
        }

        /**
         * Sets the creation timestamp.
         *
         * @param timestamp timestamp in milliseconds
         * @return this builder
         */
        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /**
         * Builds the BondingInfo instance.
         *
         * @return new BondingInfo
         */
        public BondingInfo build() {
            return new BondingInfo(this);
        }
    }

    /**
     * Creates a new Builder instance.
     *
     * @return new Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a BondingInfo from raw pairing data.
     *
     * <p>Convenience factory method for creating BondingInfo directly.
     *
     * @param address     peer address (6 bytes)
     * @param linkKey     link key (16 bytes)
     * @param linkKeyType link key type
     * @return new BondingInfo
     */
    public static BondingInfo create(byte[] address, byte[] linkKey, int linkKeyType) {
        return builder()
                .address(address)
                .linkKey(linkKey)
                .linkKeyType(linkKeyType)
                .authenticated(BrEdrPairingConstants.isMitmProtected(linkKeyType))
                .build();
    }
}