package com.courierstack.gatt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a GATT service.
 *
 * <p>A service is a collection of characteristics that together provide
 * a specific functionality. Services can be primary (directly accessible)
 * or secondary (included by other services).
 *
 * <p>Thread safety: This class is not thread-safe. External synchronization
 * is required if instances are shared across threads.
 *
 * @see GattCharacteristic
 * @see GattConstants
 */
public class GattService {

    private UUID uuid;
    private int startHandle;
    private int endHandle;
    private boolean primary;
    private final List<GattCharacteristic> characteristics = new ArrayList<>();
    private final List<GattService> includedServices = new ArrayList<>();

    // ==================== Constructors ====================

    /**
     * Creates an empty service.
     */
    public GattService() {
    }

    /**
     * Creates a service with basic attributes.
     *
     * @param uuid        service UUID
     * @param startHandle start of handle range
     * @param endHandle   end of handle range
     * @param primary     true for primary service
     */
    public GattService(UUID uuid, int startHandle, int endHandle, boolean primary) {
        this.uuid = uuid;
        this.startHandle = startHandle;
        this.endHandle = endHandle;
        this.primary = primary;
    }

    // ==================== UUID ====================

    /**
     * Returns the service UUID.
     *
     * @return UUID
     */
    public UUID getUuid() {
        return uuid;
    }

    /**
     * Sets the service UUID.
     *
     * @param uuid UUID
     */
    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }

    /**
     * Returns the 16-bit UUID if this is a standard Bluetooth service.
     *
     * @return 16-bit UUID, or -1 if not a standard UUID
     */
    public int getUuid16() {
        return GattConstants.uuidTo16Bit(uuid);
    }

    /**
     * Checks if this service uses a standard 16-bit Bluetooth UUID.
     *
     * @return true if 16-bit UUID
     */
    public boolean is16BitUuid() {
        return GattConstants.is16BitUuid(uuid);
    }

    // ==================== Handle Range ====================

    /**
     * Returns the start of the handle range.
     *
     * @return start handle
     */
    public int getStartHandle() {
        return startHandle;
    }

    /**
     * Sets the start of the handle range.
     *
     * @param startHandle start handle
     */
    public void setStartHandle(int startHandle) {
        this.startHandle = startHandle;
    }

    /**
     * Returns the end of the handle range.
     *
     * @return end handle
     */
    public int getEndHandle() {
        return endHandle;
    }

    /**
     * Sets the end of the handle range.
     *
     * @param endHandle end handle
     */
    public void setEndHandle(int endHandle) {
        this.endHandle = endHandle;
    }

    /**
     * Returns the number of handles in this service's range.
     *
     * @return handle count
     */
    public int getHandleCount() {
        return endHandle - startHandle + 1;
    }

    /**
     * Checks if a handle falls within this service's range.
     *
     * @param handle handle to check
     * @return true if handle is in range
     */
    public boolean containsHandle(int handle) {
        return handle >= startHandle && handle <= endHandle;
    }

    // ==================== Service Type ====================

    /**
     * Returns whether this is a primary service.
     *
     * @return true for primary service
     */
    public boolean isPrimary() {
        return primary;
    }

    /**
     * Sets whether this is a primary service.
     *
     * @param primary true for primary service
     */
    public void setPrimary(boolean primary) {
        this.primary = primary;
    }

    /**
     * Returns whether this is a secondary service.
     *
     * @return true for secondary service
     */
    public boolean isSecondary() {
        return !primary;
    }

    // ==================== Characteristics ====================

    /**
     * Returns an unmodifiable list of characteristics.
     *
     * @return characteristics list
     */
    public List<GattCharacteristic> getCharacteristics() {
        return Collections.unmodifiableList(characteristics);
    }

    /**
     * Returns the number of characteristics.
     *
     * @return characteristic count
     */
    public int getCharacteristicCount() {
        return characteristics.size();
    }

    /**
     * Adds a characteristic to this service.
     *
     * @param characteristic characteristic to add
     * @throws NullPointerException if characteristic is null
     */
    public void addCharacteristic(GattCharacteristic characteristic) {
        Objects.requireNonNull(characteristic, "characteristic must not be null");
        characteristic.setService(this);
        characteristics.add(characteristic);

        // Update end handle if needed
        int maxHandle = characteristic.getValueHandle();
        for (GattDescriptor descriptor : characteristic.getDescriptors()) {
            maxHandle = Math.max(maxHandle, descriptor.getHandle());
        }
        if (maxHandle > endHandle) {
            endHandle = maxHandle;
        }
    }

    /**
     * Removes a characteristic from this service.
     *
     * @param characteristic characteristic to remove
     * @return true if removed
     */
    public boolean removeCharacteristic(GattCharacteristic characteristic) {
        if (characteristics.remove(characteristic)) {
            characteristic.setService(null);
            return true;
        }
        return false;
    }

    /**
     * Finds a characteristic by UUID.
     *
     * @param uuid characteristic UUID
     * @return characteristic, or null if not found
     */
    public GattCharacteristic getCharacteristic(UUID uuid) {
        for (GattCharacteristic characteristic : characteristics) {
            if (Objects.equals(characteristic.getUuid(), uuid)) {
                return characteristic;
            }
        }
        return null;
    }

    /**
     * Finds a characteristic by handle (declaration or value handle).
     *
     * @param handle characteristic handle
     * @return characteristic, or null if not found
     */
    public GattCharacteristic getCharacteristic(int handle) {
        for (GattCharacteristic characteristic : characteristics) {
            if (characteristic.getHandle() == handle ||
                    characteristic.getValueHandle() == handle) {
                return characteristic;
            }
        }
        return null;
    }

    /**
     * Finds all characteristics with a specific UUID.
     *
     * @param uuid characteristic UUID
     * @return list of matching characteristics (may be empty)
     */
    public List<GattCharacteristic> getCharacteristics(UUID uuid) {
        List<GattCharacteristic> result = new ArrayList<>();
        for (GattCharacteristic characteristic : characteristics) {
            if (Objects.equals(characteristic.getUuid(), uuid)) {
                result.add(characteristic);
            }
        }
        return result;
    }

    // ==================== Included Services ====================

    /**
     * Returns an unmodifiable list of included services.
     *
     * @return included services list
     */
    public List<GattService> getIncludedServices() {
        return Collections.unmodifiableList(includedServices);
    }

    /**
     * Adds an included service.
     *
     * @param service service to include
     * @throws NullPointerException if service is null
     */
    public void addIncludedService(GattService service) {
        Objects.requireNonNull(service, "service must not be null");
        includedServices.add(service);
    }

    /**
     * Removes an included service.
     *
     * @param service service to remove
     * @return true if removed
     */
    public boolean removeIncludedService(GattService service) {
        return includedServices.remove(service);
    }

    // ==================== Descriptor Lookup ====================

    /**
     * Finds a descriptor by handle within any characteristic of this service.
     *
     * @param handle descriptor handle
     * @return descriptor, or null if not found
     */
    public GattDescriptor getDescriptor(int handle) {
        for (GattCharacteristic characteristic : characteristics) {
            GattDescriptor descriptor = characteristic.getDescriptor(handle);
            if (descriptor != null) {
                return descriptor;
            }
        }
        return null;
    }

    // ==================== Common Service Checks ====================

    /**
     * Checks if this is the GAP (Generic Access Profile) service.
     *
     * @return true if GAP service
     */
    public boolean isGapService() {
        return GattConstants.UUID_GAP_SERVICE.equals(uuid);
    }

    /**
     * Checks if this is the GATT (Generic Attribute Profile) service.
     *
     * @return true if GATT service
     */
    public boolean isGattService() {
        return GattConstants.UUID_GATT_SERVICE.equals(uuid);
    }

    /**
     * Checks if this is the Device Information service.
     *
     * @return true if Device Information service
     */
    public boolean isDeviceInfoService() {
        return GattConstants.UUID_DEVICE_INFO_SERVICE.equals(uuid);
    }

    /**
     * Checks if this is the Battery service.
     *
     * @return true if Battery service
     */
    public boolean isBatteryService() {
        return GattConstants.UUID_BATTERY_SERVICE.equals(uuid);
    }

    // ==================== Object Methods ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GattService that = (GattService) o;
        return startHandle == that.startHandle && endHandle == that.endHandle &&
                Objects.equals(uuid, that.uuid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uuid, startHandle, endHandle);
    }

    @Override
    public String toString() {
        return String.format("GattService{uuid=%s, handles=0x%04X-0x%04X, %s, characteristics=%d}",
                GattConstants.getShortUuid(uuid), startHandle, endHandle,
                primary ? "Primary" : "Secondary", characteristics.size());
    }

    // ==================== Builder ====================

    /**
     * Creates a new builder for GattService.
     *
     * @return new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating GattService instances.
     */
    public static class Builder {
        private UUID uuid;
        private int startHandle;
        private int endHandle;
        private boolean primary = true;
        private final List<GattCharacteristic> characteristics = new ArrayList<>();
        private final List<GattService> includedServices = new ArrayList<>();

        public Builder uuid(UUID uuid) {
            this.uuid = uuid;
            return this;
        }

        public Builder uuid16(int uuid16) {
            this.uuid = GattConstants.uuidFrom16Bit(uuid16);
            return this;
        }

        public Builder startHandle(int startHandle) {
            this.startHandle = startHandle;
            return this;
        }

        public Builder endHandle(int endHandle) {
            this.endHandle = endHandle;
            return this;
        }

        public Builder handles(int start, int end) {
            this.startHandle = start;
            this.endHandle = end;
            return this;
        }

        public Builder primary(boolean primary) {
            this.primary = primary;
            return this;
        }

        public Builder addCharacteristic(GattCharacteristic characteristic) {
            this.characteristics.add(characteristic);
            return this;
        }

        public Builder addIncludedService(GattService service) {
            this.includedServices.add(service);
            return this;
        }

        public GattService build() {
            GattService service = new GattService(uuid, startHandle, endHandle, primary);
            for (GattCharacteristic characteristic : characteristics) {
                service.addCharacteristic(characteristic);
            }
            for (GattService included : includedServices) {
                service.addIncludedService(included);
            }
            return service;
        }
    }
}