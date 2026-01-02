package com.courierstack.gatt;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a GATT descriptor.
 *
 * <p>Descriptors provide additional information about a characteristic's value.
 * Common descriptors include the Client Characteristic Configuration Descriptor
 * (CCCD) for enabling notifications/indications.
 *
 * <p>Thread safety: This class is not thread-safe. External synchronization
 * is required if instances are shared across threads.
 *
 * @see GattCharacteristic
 * @see GattConstants#GATT_CLIENT_CHAR_CONFIG_UUID
 */
public class GattDescriptor {

    private UUID uuid;
    private int handle;
    private GattCharacteristic characteristic;
    private byte[] value;
    private int permissions;

    // ==================== Constructors ====================

    /**
     * Creates an empty descriptor.
     */
    public GattDescriptor() {
    }

    /**
     * Creates a descriptor with UUID and handle.
     *
     * @param uuid   descriptor UUID
     * @param handle attribute handle
     */
    public GattDescriptor(UUID uuid, int handle) {
        this.uuid = uuid;
        this.handle = handle;
    }

    /**
     * Creates a fully initialized descriptor.
     *
     * @param uuid        descriptor UUID
     * @param handle      attribute handle
     * @param permissions access permissions
     * @param value       initial value
     */
    public GattDescriptor(UUID uuid, int handle, int permissions, byte[] value) {
        this.uuid = uuid;
        this.handle = handle;
        this.permissions = permissions;
        this.value = value != null ? value.clone() : null;
    }

    // ==================== UUID and Handle ====================

    /**
     * Returns the descriptor UUID.
     *
     * @return UUID
     */
    public UUID getUuid() {
        return uuid;
    }

    /**
     * Sets the descriptor UUID.
     *
     * @param uuid UUID
     */
    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }

    /**
     * Returns the attribute handle.
     *
     * @return handle
     */
    public int getHandle() {
        return handle;
    }

    /**
     * Sets the attribute handle.
     *
     * @param handle handle
     */
    public void setHandle(int handle) {
        this.handle = handle;
    }

    // ==================== Parent Characteristic ====================

    /**
     * Returns the parent characteristic.
     *
     * @return parent characteristic, or null if not set
     */
    public GattCharacteristic getCharacteristic() {
        return characteristic;
    }

    /**
     * Sets the parent characteristic.
     *
     * @param characteristic parent characteristic
     */
    public void setCharacteristic(GattCharacteristic characteristic) {
        this.characteristic = characteristic;
    }

    // ==================== Value ====================

    /**
     * Returns a copy of the descriptor value.
     *
     * @return value copy, or null if not set
     */
    public byte[] getValue() {
        return value != null ? value.clone() : null;
    }

    /**
     * Sets the descriptor value.
     *
     * @param value new value (will be cloned)
     */
    public void setValue(byte[] value) {
        this.value = value != null ? value.clone() : null;
    }

    /**
     * Returns the value length.
     *
     * @return value length, or 0 if null
     */
    public int getValueLength() {
        return value != null ? value.length : 0;
    }

    // ==================== Permissions ====================

    /**
     * Returns the access permissions.
     *
     * @return permissions bitmask
     * @see GattConstants#CHAR_PERM_READ
     * @see GattConstants#CHAR_PERM_WRITE
     */
    public int getPermissions() {
        return permissions;
    }

    /**
     * Sets the access permissions.
     *
     * @param permissions permissions bitmask
     */
    public void setPermissions(int permissions) {
        this.permissions = permissions;
    }

    /**
     * Checks if the descriptor is readable.
     *
     * @return true if readable
     */
    public boolean isReadable() {
        return (permissions & (GattConstants.CHAR_PERM_READ |
                GattConstants.CHAR_PERM_READ_ENCRYPTED |
                GattConstants.CHAR_PERM_READ_ENCRYPTED_MITM)) != 0;
    }

    /**
     * Checks if the descriptor is writable.
     *
     * @return true if writable
     */
    public boolean isWritable() {
        return (permissions & (GattConstants.CHAR_PERM_WRITE |
                GattConstants.CHAR_PERM_WRITE_ENCRYPTED |
                GattConstants.CHAR_PERM_WRITE_ENCRYPTED_MITM)) != 0;
    }

    // ==================== CCCD Utilities ====================

    /**
     * Checks if this is a Client Characteristic Configuration Descriptor.
     *
     * @return true if CCCD
     */
    public boolean isCccd() {
        return GattConstants.uuidTo16Bit(uuid) == GattConstants.GATT_CLIENT_CHAR_CONFIG_UUID;
    }

    /**
     * Returns the CCCD value as an integer (for CCCD descriptors).
     *
     * @return CCCD value, or 0 if not a CCCD or value is null
     */
    public int getCccdValue() {
        if (!isCccd() || value == null || value.length < 2) {
            return 0;
        }
        return (value[0] & 0xFF) | ((value[1] & 0xFF) << 8);
    }

    /**
     * Checks if notifications are enabled (for CCCD descriptors).
     *
     * @return true if notifications enabled
     */
    public boolean isNotificationsEnabled() {
        return (getCccdValue() & GattConstants.CCCD_NOTIFICATION) != 0;
    }

    /**
     * Checks if indications are enabled (for CCCD descriptors).
     *
     * @return true if indications enabled
     */
    public boolean isIndicationsEnabled() {
        return (getCccdValue() & GattConstants.CCCD_INDICATION) != 0;
    }

    // ==================== Object Methods ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GattDescriptor that = (GattDescriptor) o;
        return handle == that.handle && Objects.equals(uuid, that.uuid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uuid, handle);
    }

    @Override
    public String toString() {
        return String.format("GattDescriptor{uuid=%s, handle=0x%04X, permissions=0x%02X}",
                GattConstants.getShortUuid(uuid), handle, permissions);
    }

    // ==================== Builder ====================

    /**
     * Creates a new builder for GattDescriptor.
     *
     * @return new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating GattDescriptor instances.
     */
    public static class Builder {
        private UUID uuid;
        private int handle;
        private int permissions;
        private byte[] value;
        private GattCharacteristic characteristic;

        public Builder uuid(UUID uuid) {
            this.uuid = uuid;
            return this;
        }

        public Builder uuid16(int uuid16) {
            this.uuid = GattConstants.uuidFrom16Bit(uuid16);
            return this;
        }

        public Builder handle(int handle) {
            this.handle = handle;
            return this;
        }

        public Builder permissions(int permissions) {
            this.permissions = permissions;
            return this;
        }

        public Builder value(byte[] value) {
            this.value = value;
            return this;
        }

        public Builder characteristic(GattCharacteristic characteristic) {
            this.characteristic = characteristic;
            return this;
        }

        public GattDescriptor build() {
            GattDescriptor descriptor = new GattDescriptor(uuid, handle, permissions, value);
            descriptor.setCharacteristic(characteristic);
            return descriptor;
        }
    }
}