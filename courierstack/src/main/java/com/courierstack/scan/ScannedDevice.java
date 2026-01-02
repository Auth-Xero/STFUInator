package com.courierstack.scan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a discovered Bluetooth device from inquiry or LE scan.
 *
 * <p>A single device may be discovered via multiple transports (BR/EDR and LE)
 * and this class aggregates information from all sources.
 *
 * <p>Thread safety: This class uses volatile fields and synchronized methods
 * for thread-safe updates.
 */
public class ScannedDevice {

    /** BD_ADDR as string (XX:XX:XX:XX:XX:XX). */
    private final String address;

    /** Device name from EIR/AD data. */
    private volatile String name;

    /** RSSI in dBm. */
    private volatile int rssi;

    /** Class of Device (BR/EDR). */
    private volatile int classOfDevice;

    /** True if discovered via LE scan. */
    private volatile boolean isLe;

    /** True if discovered via BR/EDR inquiry. */
    private volatile boolean isBrEdr;

    /** True if AD flags indicate BR/EDR support (dual-mode). */
    private volatile boolean supportsBrEdrFromAdFlags;

    /** LE address type (0=Public, 1=Random). */
    private volatile int addressType;

    /** Raw advertising data (LE). */
    private volatile byte[] advData;

    /** Last seen timestamp (millis). */
    private volatile long lastSeen;

    /** TX Power level from advertising data (or Integer.MIN_VALUE if not present). */
    private volatile int txPower = Integer.MIN_VALUE;

    /** Extended Inquiry Response data segments. */
    private final List<byte[]> eirData;

    /** Service UUIDs discovered from advertising/EIR data. */
    private final List<String> serviceUuids;

    /** Manufacturer specific data. */
    private volatile byte[] manufacturerData;

    /** Manufacturer ID. */
    private volatile int manufacturerId = -1;

    /**
     * Creates a device with the given address.
     *
     * @param address BD_ADDR in XX:XX:XX:XX:XX:XX format
     * @throws NullPointerException     if address is null
     * @throws IllegalArgumentException if address format is invalid
     */
    public ScannedDevice(String address) {
        Objects.requireNonNull(address, "address must not be null");
        if (!isValidAddress(address)) {
            throw new IllegalArgumentException("Invalid Bluetooth address: " + address);
        }
        this.address = address.toUpperCase();
        this.lastSeen = System.currentTimeMillis();
        this.eirData = Collections.synchronizedList(new ArrayList<>());
        this.serviceUuids = Collections.synchronizedList(new ArrayList<>());
    }

    // ==================== Getters ====================

    /** Returns the Bluetooth address. */
    public String getAddress() {
        return address;
    }

    /** Returns the device name or null if unknown. */
    public String getName() {
        return name;
    }

    /** Returns the display name (name if available, otherwise address). */
    public String getDisplayName() {
        return name != null && !name.isEmpty() ? name : address;
    }

    /** Returns the RSSI in dBm. */
    public int getRssi() {
        return rssi;
    }

    /** Returns the Class of Device (BR/EDR). */
    public int getClassOfDevice() {
        return classOfDevice;
    }

    /** Returns whether discovered via LE scan. */
    public boolean isLe() {
        return isLe;
    }

    /** Returns whether discovered via BR/EDR inquiry. */
    public boolean isBrEdr() {
        return isBrEdr;
    }

    /** Returns the LE address type (0=Public, 1=Random). */
    public int getAddressType() {
        return addressType;
    }

    /** Returns the raw advertising data or null. */
    public byte[] getAdvData() {
        byte[] data = advData;
        return data != null ? data.clone() : null;
    }

    /** Returns the last seen timestamp in milliseconds. */
    public long getLastSeen() {
        return lastSeen;
    }

    /** Returns the TX power in dBm, or Integer.MIN_VALUE if not available. */
    public int getTxPower() {
        return txPower;
    }

    /** Returns the manufacturer ID or -1 if not present. */
    public int getManufacturerId() {
        return manufacturerId;
    }

    /** Returns the manufacturer-specific data or null. */
    public byte[] getManufacturerData() {
        byte[] data = manufacturerData;
        return data != null ? data.clone() : null;
    }

    /** Returns discovered service UUIDs. */
    public List<String> getServiceUuids() {
        synchronized (serviceUuids) {
            return new ArrayList<>(serviceUuids);
        }
    }

    // ==================== Capability Checks ====================

    /** Returns whether the device supports BR/EDR. */
    public boolean supportsBrEdr() {
        return isBrEdr || (isLe && addressType == 0) || supportsBrEdrFromAdFlags;
    }

    /** Returns whether the device supports LE. */
    public boolean supportsLe() {
        return isLe;
    }

    /** Returns whether this is a dual-mode device. */
    public boolean isDualMode() {
        return supportsBrEdr() && supportsLe();
    }

    /** Returns whether inquiry is needed to get BR/EDR address. */
    public boolean needsInquiryForBrEdr() {
        return supportsBrEdrFromAdFlags && addressType != 0 && !isBrEdr;
    }

    // ==================== Class of Device Parsing ====================

    /** Returns the major device class from CoD (0-31). */
    public int getMajorDeviceClass() {
        return (classOfDevice >> 8) & 0x1F;
    }

    /** Returns the minor device class from CoD (0-63). */
    public int getMinorDeviceClass() {
        return (classOfDevice >> 2) & 0x3F;
    }

    /** Returns the service classes from CoD. */
    public int getServiceClasses() {
        return (classOfDevice >> 13) & 0x7FF;
    }

    /** Returns the major device class name. */
    public String getMajorClassName() {
        switch (getMajorDeviceClass()) {
            case 0x01: return "Computer";
            case 0x02: return "Phone";
            case 0x03: return "LAN/Network";
            case 0x04: return "Audio/Video";
            case 0x05: return "Peripheral";
            case 0x06: return "Imaging";
            case 0x07: return "Wearable";
            case 0x08: return "Toy";
            case 0x09: return "Health";
            case 0x1F: return "Uncategorized";
            default: return "Unknown";
        }
    }

    // ==================== Address Conversion ====================

    /** Returns the BD_ADDR as a byte array (little-endian). */
    public byte[] getAddressBytes() {
        String[] parts = address.split(":");
        byte[] bytes = new byte[6];
        for (int i = 0; i < 6; i++) {
            bytes[5 - i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return bytes;
    }

    // ==================== Update Methods ====================

    /**
     * Updates the device from a scan result.
     */
    public synchronized void update(String name, int rssi, int cod, boolean isLe, int addressType) {
        if (name != null && !name.isEmpty()) {
            this.name = name;
        }
        this.rssi = rssi;
        if (cod != 0) {
            this.classOfDevice = cod;
        }
        if (isLe) {
            this.isLe = true;
            this.addressType = addressType;
        } else {
            this.isBrEdr = true;
        }
        this.lastSeen = System.currentTimeMillis();
    }

    /** Updates from BR/EDR inquiry result. */
    public void updateBrEdr(String name, int rssi, int cod) {
        update(name, rssi, cod, false, 0);
    }

    /** Updates from LE advertising report. */
    public synchronized void updateLe(String name, int rssi, int addressType, boolean supportsBrEdr) {
        if (name != null && !name.isEmpty()) {
            this.name = name;
        }
        this.rssi = rssi;
        this.isLe = true;
        this.addressType = addressType;
        if (supportsBrEdr) {
            this.supportsBrEdrFromAdFlags = true;
            if (addressType == 0) {
                this.isBrEdr = true;
            }
        }
        this.lastSeen = System.currentTimeMillis();
    }

    /** Sets the advertising data. */
    public void setAdvData(byte[] data) {
        this.advData = data != null ? data.clone() : null;
    }

    /** Sets the TX power level. */
    public void setTxPower(int txPower) {
        this.txPower = txPower;
    }

    /** Sets the manufacturer data. */
    public void setManufacturerData(int manufacturerId, byte[] data) {
        this.manufacturerId = manufacturerId;
        this.manufacturerData = data != null ? data.clone() : null;
    }

    /** Adds a service UUID. */
    public void addServiceUuid(String uuid) {
        if (uuid != null && !serviceUuids.contains(uuid)) {
            serviceUuids.add(uuid);
        }
    }

    /** Adds EIR data segment. */
    public void addEirData(byte[] eir) {
        if (eir != null) {
            eirData.add(eir.clone());
        }
    }

    // ==================== Object Methods ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ScannedDevice that = (ScannedDevice) o;
        return address.equals(that.address);
    }

    @Override
    public int hashCode() {
        return address.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ScannedDevice{");
        sb.append("addr=").append(address);
        if (name != null) {
            sb.append(", name='").append(name).append('\'');
        }
        sb.append(", rssi=").append(rssi);
        if (isLe) sb.append(", LE");
        if (isBrEdr) sb.append(", BR/EDR");
        sb.append('}');
        return sb.toString();
    }

    // ==================== Validation ====================

    private static boolean isValidAddress(String address) {
        if (address == null || address.length() != 17) {
            return false;
        }
        for (int i = 0; i < 17; i++) {
            char c = address.charAt(i);
            if (i % 3 == 2) {
                if (c != ':') return false;
            } else {
                if (!isHexChar(c)) return false;
            }
        }
        return true;
    }

    private static boolean isHexChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
    }
}