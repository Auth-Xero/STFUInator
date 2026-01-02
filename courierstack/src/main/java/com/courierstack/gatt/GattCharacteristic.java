package com.courierstack.gatt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a GATT characteristic.
 *
 * <p>A characteristic is a container for a value and optional descriptors.
 * It defines how the value can be accessed (read, write, notify, etc.)
 * through its properties bitmask.
 *
 * <p>Thread safety: This class is not thread-safe. External synchronization
 * is required if instances are shared across threads.
 *
 * @see GattService
 * @see GattDescriptor
 * @see GattConstants
 */
public class GattCharacteristic {

    private UUID uuid;
    private int handle;
    private int valueHandle;
    private int properties;
    private int permissions;
    private GattService service;
    private final List<GattDescriptor> descriptors = new ArrayList<>();
    private byte[] value;

    // ==================== Constructors ====================

    /**
     * Creates an empty characteristic.
     */
    public GattCharacteristic() {
    }

    /**
     * Creates a characteristic with basic attributes.
     *
     * @param uuid        characteristic UUID
     * @param handle      declaration handle
     * @param valueHandle value handle
     * @param properties  properties bitmask
     */
    public GattCharacteristic(UUID uuid, int handle, int valueHandle, int properties) {
        this.uuid = uuid;
        this.handle = handle;
        this.valueHandle = valueHandle;
        this.properties = properties;
    }

    /**
     * Creates a fully initialized characteristic.
     *
     * @param uuid        characteristic UUID
     * @param handle      declaration handle
     * @param valueHandle value handle
     * @param properties  properties bitmask
     * @param permissions access permissions
     * @param value       initial value
     */
    public GattCharacteristic(UUID uuid, int handle, int valueHandle,
                              int properties, int permissions, byte[] value) {
        this.uuid = uuid;
        this.handle = handle;
        this.valueHandle = valueHandle;
        this.properties = properties;
        this.permissions = permissions;
        this.value = value != null ? value.clone() : null;
    }

    // ==================== UUID ====================

    /**
     * Returns the characteristic UUID.
     *
     * @return UUID
     */
    public UUID getUuid() {
        return uuid;
    }

    /**
     * Sets the characteristic UUID.
     *
     * @param uuid UUID
     */
    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }

    // ==================== Handles ====================

    /**
     * Returns the characteristic declaration handle.
     *
     * @return declaration handle
     */
    public int getHandle() {
        return handle;
    }

    /**
     * Sets the characteristic declaration handle.
     *
     * @param handle declaration handle
     */
    public void setHandle(int handle) {
        this.handle = handle;
    }

    /**
     * Returns the characteristic value handle.
     *
     * @return value handle
     */
    public int getValueHandle() {
        return valueHandle;
    }

    /**
     * Sets the characteristic value handle.
     *
     * @param valueHandle value handle
     */
    public void setValueHandle(int valueHandle) {
        this.valueHandle = valueHandle;
    }

    // ==================== Properties ====================

    /**
     * Returns the characteristic properties bitmask.
     *
     * @return properties
     * @see GattConstants#CHAR_PROP_READ
     * @see GattConstants#CHAR_PROP_WRITE
     * @see GattConstants#CHAR_PROP_NOTIFY
     */
    public int getProperties() {
        return properties;
    }

    /**
     * Sets the characteristic properties bitmask.
     *
     * @param properties properties
     */
    public void setProperties(int properties) {
        this.properties = properties;
    }

    /**
     * Checks if the characteristic is readable.
     *
     * @return true if readable
     */
    public boolean isReadable() {
        return GattConstants.isReadable(properties);
    }

    /**
     * Checks if the characteristic is writable.
     *
     * @return true if writable (with or without response)
     */
    public boolean isWritable() {
        return GattConstants.isWritable(properties);
    }

    /**
     * Checks if write with response is supported.
     *
     * @return true if write request supported
     */
    public boolean supportsWriteWithResponse() {
        return (properties & GattConstants.CHAR_PROP_WRITE) != 0;
    }

    /**
     * Checks if write without response is supported.
     *
     * @return true if write command supported
     */
    public boolean supportsWriteWithoutResponse() {
        return (properties & GattConstants.CHAR_PROP_WRITE_NO_RSP) != 0;
    }

    /**
     * Checks if notifications are supported.
     *
     * @return true if notifications supported
     */
    public boolean supportsNotify() {
        return GattConstants.supportsNotify(properties);
    }

    /**
     * Checks if indications are supported.
     *
     * @return true if indications supported
     */
    public boolean supportsIndicate() {
        return GattConstants.supportsIndicate(properties);
    }

    /**
     * Returns a human-readable properties string.
     *
     * @return properties description
     */
    public String getPropertiesString() {
        return GattConstants.getPropertiesString(properties);
    }

    // ==================== Permissions ====================

    /**
     * Returns the access permissions bitmask.
     *
     * @return permissions
     */
    public int getPermissions() {
        return permissions;
    }

    /**
     * Sets the access permissions bitmask.
     *
     * @param permissions permissions
     */
    public void setPermissions(int permissions) {
        this.permissions = permissions;
    }

    // ==================== Parent Service ====================

    /**
     * Returns the parent service.
     *
     * @return parent service, or null if not set
     */
    public GattService getService() {
        return service;
    }

    /**
     * Sets the parent service.
     *
     * @param service parent service
     */
    public void setService(GattService service) {
        this.service = service;
    }

    // ==================== Value ====================

    /**
     * Returns a copy of the characteristic value.
     *
     * @return value copy, or null if not set
     */
    public byte[] getValue() {
        return value != null ? value.clone() : null;
    }

    /**
     * Sets the characteristic value.
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

    // ==================== Descriptors ====================

    /**
     * Returns an unmodifiable list of descriptors.
     *
     * @return descriptors list
     */
    public List<GattDescriptor> getDescriptors() {
        return Collections.unmodifiableList(descriptors);
    }

    /**
     * Returns the number of descriptors.
     *
     * @return descriptor count
     */
    public int getDescriptorCount() {
        return descriptors.size();
    }

    /**
     * Adds a descriptor to this characteristic.
     *
     * @param descriptor descriptor to add
     * @throws NullPointerException if descriptor is null
     */
    public void addDescriptor(GattDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        descriptor.setCharacteristic(this);
        descriptors.add(descriptor);
    }

    /**
     * Removes a descriptor from this characteristic.
     *
     * @param descriptor descriptor to remove
     * @return true if removed
     */
    public boolean removeDescriptor(GattDescriptor descriptor) {
        if (descriptors.remove(descriptor)) {
            descriptor.setCharacteristic(null);
            return true;
        }
        return false;
    }

    /**
     * Finds a descriptor by UUID.
     *
     * @param uuid descriptor UUID
     * @return descriptor, or null if not found
     */
    public GattDescriptor getDescriptor(UUID uuid) {
        for (GattDescriptor descriptor : descriptors) {
            if (Objects.equals(descriptor.getUuid(), uuid)) {
                return descriptor;
            }
        }
        return null;
    }

    /**
     * Finds a descriptor by handle.
     *
     * @param handle descriptor handle
     * @return descriptor, or null if not found
     */
    public GattDescriptor getDescriptor(int handle) {
        for (GattDescriptor descriptor : descriptors) {
            if (descriptor.getHandle() == handle) {
                return descriptor;
            }
        }
        return null;
    }

    /**
     * Finds the Client Characteristic Configuration Descriptor (CCCD).
     *
     * @return CCCD, or null if not present
     */
    public GattDescriptor getCccd() {
        return getDescriptor(GattConstants.uuidFrom16Bit(GattConstants.GATT_CLIENT_CHAR_CONFIG_UUID));
    }

    /**
     * Checks if this characteristic has a CCCD.
     *
     * @return true if CCCD present
     */
    public boolean hasCccd() {
        return getCccd() != null;
    }

    // ==================== Object Methods ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GattCharacteristic that = (GattCharacteristic) o;
        return handle == that.handle && valueHandle == that.valueHandle &&
                Objects.equals(uuid, that.uuid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uuid, handle, valueHandle);
    }

    @Override
    public String toString() {
        return String.format("GattCharacteristic{uuid=%s, handle=0x%04X, valueHandle=0x%04X, " +
                        "properties=0x%02X (%s), descriptors=%d}",
                GattConstants.getShortUuid(uuid), handle, valueHandle,
                properties, getPropertiesString(), descriptors.size());
    }

    // ==================== Builder ====================

    /**
     * Creates a new builder for GattCharacteristic.
     *
     * @return new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating GattCharacteristic instances.
     */
    public static class Builder {
        private UUID uuid;
        private int handle;
        private int valueHandle;
        private int properties;
        private int permissions;
        private byte[] value;
        private GattService service;
        private final List<GattDescriptor> descriptors = new ArrayList<>();

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

        public Builder valueHandle(int valueHandle) {
            this.valueHandle = valueHandle;
            return this;
        }

        public Builder properties(int properties) {
            this.properties = properties;
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

        public Builder service(GattService service) {
            this.service = service;
            return this;
        }

        public Builder addDescriptor(GattDescriptor descriptor) {
            this.descriptors.add(descriptor);
            return this;
        }

        public GattCharacteristic build() {
            GattCharacteristic characteristic = new GattCharacteristic(
                    uuid, handle, valueHandle, properties, permissions, value);
            characteristic.setService(service);
            for (GattDescriptor descriptor : descriptors) {
                characteristic.addDescriptor(descriptor);
            }
            return characteristic;
        }
    }
}