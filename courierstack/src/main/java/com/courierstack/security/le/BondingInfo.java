package com.courierstack.security.le;

import java.util.Arrays;

/**
 * Bonding information stored after successful pairing.
 *
 * <p>Contains the cryptographic keys and metadata needed to re-establish
 * an encrypted connection with a previously paired device.
 *
 * <p>Thread Safety: This class is effectively immutable after construction
 * via the Builder. All byte array getters return defensive copies.
 */
public final class BondingInfo {

    private final byte[] address;
    private final int addressType;
    private final byte[] ltk;
    private final byte[] ediv;
    private final byte[] rand;
    private final byte[] irk;
    private final byte[] csrk;
    private final byte[] identityAddress;
    private final int identityAddressType;
    private final boolean hasLtk;
    private final boolean hasIrk;
    private final boolean hasCsrk;
    private final int keySize;
    private final boolean authenticated;
    private final boolean secureConnections;
    private final long timestamp;

    private BondingInfo(Builder builder) {
        this.address = builder.address != null ? Arrays.copyOf(builder.address, 6) : new byte[6];
        this.addressType = builder.addressType;
        this.ltk = builder.ltk != null ? Arrays.copyOf(builder.ltk, 16) : new byte[16];
        this.ediv = builder.ediv != null ? Arrays.copyOf(builder.ediv, 2) : new byte[2];
        this.rand = builder.rand != null ? Arrays.copyOf(builder.rand, 8) : new byte[8];
        this.irk = builder.irk != null ? Arrays.copyOf(builder.irk, 16) : new byte[16];
        this.csrk = builder.csrk != null ? Arrays.copyOf(builder.csrk, 16) : new byte[16];
        this.identityAddress = builder.identityAddress != null
                ? Arrays.copyOf(builder.identityAddress, 6) : null;
        this.identityAddressType = builder.identityAddressType;
        this.hasLtk = builder.hasLtk;
        this.hasIrk = builder.hasIrk;
        this.hasCsrk = builder.hasCsrk;
        this.keySize = builder.keySize;
        this.authenticated = builder.authenticated;
        this.secureConnections = builder.secureConnections;
        this.timestamp = builder.timestamp > 0 ? builder.timestamp : System.currentTimeMillis();
    }

    // ==================== Getters ====================

    /** Returns a copy of the peer address. */
    public byte[] getAddress() {
        return Arrays.copyOf(address, 6);
    }

    /** Returns the address type. */
    public int getAddressType() {
        return addressType;
    }

    /** Returns a copy of the Long Term Key. */
    public byte[] getLtk() {
        return Arrays.copyOf(ltk, 16);
    }

    /** Returns a copy of the EDIV. */
    public byte[] getEdiv() {
        return Arrays.copyOf(ediv, 2);
    }

    /** Returns a copy of the Rand value. */
    public byte[] getRand() {
        return Arrays.copyOf(rand, 8);
    }

    /** Returns a copy of the Identity Resolving Key. */
    public byte[] getIrk() {
        return Arrays.copyOf(irk, 16);
    }

    /** Returns a copy of the Connection Signature Resolving Key. */
    public byte[] getCsrk() {
        return Arrays.copyOf(csrk, 16);
    }

    /** Returns a copy of the identity address (may be null). */
    public byte[] getIdentityAddress() {
        return identityAddress != null ? Arrays.copyOf(identityAddress, 6) : null;
    }

    /** Returns the identity address type. */
    public int getIdentityAddressType() {
        return identityAddressType;
    }

    /** Returns whether LTK is available. */
    public boolean hasLtk() {
        return hasLtk;
    }

    /** Returns whether IRK is available. */
    public boolean hasIrk() {
        return hasIrk;
    }

    /** Returns whether CSRK is available. */
    public boolean hasCsrk() {
        return hasCsrk;
    }

    /** Returns the negotiated encryption key size. */
    public int getKeySize() {
        return keySize;
    }

    /** Returns whether pairing was authenticated (MITM protected). */
    public boolean isAuthenticated() {
        return authenticated;
    }

    /** Returns whether Secure Connections was used. */
    public boolean isSecureConnections() {
        return secureConnections;
    }

    /** Returns the timestamp when bonding was created. */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Returns the address formatted as a string.
     *
     * @return address string (XX:XX:XX:XX:XX:XX)
     */
    public String getAddressString() {
        return SmpConstants.formatAddress(address);
    }

    /**
     * Returns the identity address formatted as a string.
     *
     * @return identity address string or null
     */
    public String getIdentityAddressString() {
        return identityAddress != null ? SmpConstants.formatAddress(identityAddress) : null;
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
     * <p>Security levels (from lowest to highest):
     * <ul>
     *   <li>1: Unauthenticated, no encryption (not applicable here)</li>
     *   <li>2: Unauthenticated with encryption</li>
     *   <li>3: Authenticated with encryption (MITM protected)</li>
     *   <li>4: Authenticated LE Secure Connections</li>
     * </ul>
     *
     * @return security level (2-4)
     */
    public int getSecurityLevel() {
        if (secureConnections && authenticated) {
            return 4;
        } else if (authenticated) {
            return 3;
        } else {
            return 2;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BondingInfo)) return false;
        BondingInfo that = (BondingInfo) o;
        return Arrays.equals(address, that.address) && addressType == that.addressType;
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(address);
        result = 31 * result + addressType;
        return result;
    }

    @Override
    public String toString() {
        return String.format("BondingInfo{address=%s, SC=%b, auth=%b, keySize=%d}",
                getAddressString(), secureConnections, authenticated, keySize);
    }

    // ==================== Builder ====================

    /**
     * Builder for creating BondingInfo instances.
     */
    public static class Builder {
        private byte[] address;
        private int addressType;
        private byte[] ltk;
        private byte[] ediv;
        private byte[] rand;
        private byte[] irk;
        private byte[] csrk;
        private byte[] identityAddress;
        private int identityAddressType;
        private boolean hasLtk;
        private boolean hasIrk;
        private boolean hasCsrk;
        private int keySize = SmpConstants.MAX_ENC_KEY_SIZE;
        private boolean authenticated;
        private boolean secureConnections;
        private long timestamp;

        public Builder() {}

        public Builder address(byte[] address) {
            this.address = address;
            return this;
        }

        public Builder addressType(int addressType) {
            this.addressType = addressType;
            return this;
        }

        public Builder ltk(byte[] ltk) {
            this.ltk = ltk;
            this.hasLtk = ltk != null;
            return this;
        }

        public Builder ediv(byte[] ediv) {
            this.ediv = ediv;
            return this;
        }

        public Builder rand(byte[] rand) {
            this.rand = rand;
            return this;
        }

        public Builder irk(byte[] irk) {
            this.irk = irk;
            this.hasIrk = irk != null;
            return this;
        }

        public Builder csrk(byte[] csrk) {
            this.csrk = csrk;
            this.hasCsrk = csrk != null;
            return this;
        }

        public Builder identityAddress(byte[] identityAddress, int type) {
            this.identityAddress = identityAddress;
            this.identityAddressType = type;
            return this;
        }

        public Builder keySize(int keySize) {
            this.keySize = keySize;
            return this;
        }

        public Builder authenticated(boolean authenticated) {
            this.authenticated = authenticated;
            return this;
        }

        public Builder secureConnections(boolean secureConnections) {
            this.secureConnections = secureConnections;
            return this;
        }

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
}