package com.courierstack.gatt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Represents an active GATT connection to a remote device.
 *
 * <p>This class maintains the state of a GATT connection including
 * the negotiated MTU, discovered services, and discovery progress.
 *
 * <p>Thread safety: The state fields use volatile for visibility,
 * but compound operations require external synchronization.
 *
 * @see GattManager
 */
public class GattConnection {

    /**
     * GATT service discovery states.
     */
    public enum DiscoveryState {
        /** No discovery in progress. */
        IDLE,
        /** Discovering primary services. */
        DISCOVERING_SERVICES,
        /** Discovering characteristics within services. */
        DISCOVERING_CHARACTERISTICS,
        /** Discovering descriptors within characteristics. */
        DISCOVERING_DESCRIPTORS,
        /** Discovery completed successfully. */
        COMPLETE,
        /** Discovery failed. */
        FAILED
    }

    // ==================== Connection Identity ====================

    /** HCI connection handle. */
    public final int connectionHandle;

    /** Peer device address (6 bytes). */
    private final byte[] peerAddress;

    /** Peer address type (0=Public, 1=Random). */
    public final int peerAddressType;

    /** Connection creation timestamp. */
    public final long createdAt;

    // ==================== MTU State ====================

    /** Current negotiated MTU. */
    private volatile int mtu = GattConstants.ATT_DEFAULT_LE_MTU;

    /** Whether MTU exchange has been performed. */
    private volatile boolean mtuExchanged = false;

    // ==================== Discovery State ====================

    /** Current discovery state. */
    private volatile DiscoveryState discoveryState = DiscoveryState.IDLE;

    /** Whether service discovery has completed. */
    private volatile boolean servicesDiscovered = false;

    /** Discovered services. */
    private final List<GattService> services = new ArrayList<>();

    // ==================== Constructors ====================

    /**
     * Creates a new GATT connection.
     *
     * @param connectionHandle HCI connection handle
     * @param peerAddress      peer device address (6 bytes)
     * @param peerAddressType  address type (0=Public, 1=Random)
     * @throws NullPointerException if peerAddress is null
     * @throws IllegalArgumentException if peerAddress is not 6 bytes
     */
    public GattConnection(int connectionHandle, byte[] peerAddress, int peerAddressType) {
        Objects.requireNonNull(peerAddress, "peerAddress must not be null");
        if (peerAddress.length != 6) {
            throw new IllegalArgumentException("peerAddress must be 6 bytes");
        }

        this.connectionHandle = connectionHandle;
        this.peerAddress = peerAddress.clone();
        this.peerAddressType = peerAddressType;
        this.createdAt = System.currentTimeMillis();
    }

    /**
     * Creates a new GATT connection with public address type.
     *
     * @param connectionHandle HCI connection handle
     * @param peerAddress      peer device address (6 bytes)
     */
    public GattConnection(int connectionHandle, byte[] peerAddress) {
        this(connectionHandle, peerAddress, 0);
    }

    // ==================== Peer Address ====================

    /**
     * Returns a copy of the peer address.
     *
     * @return 6-byte peer address
     */
    public byte[] getPeerAddress() {
        return peerAddress.clone();
    }

    /**
     * Returns the formatted peer address string.
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
     * Checks if the address type is public.
     *
     * @return true if public address
     */
    public boolean isPublicAddress() {
        return peerAddressType == 0;
    }

    /**
     * Checks if the address type is random.
     *
     * @return true if random address
     */
    public boolean isRandomAddress() {
        return peerAddressType == 1;
    }

    // ==================== MTU ====================

    /**
     * Returns the current negotiated MTU.
     *
     * @return MTU value
     */
    public int getMtu() {
        return mtu;
    }

    /**
     * Sets the negotiated MTU.
     *
     * @param mtu MTU value
     */
    public void setMtu(int mtu) {
        this.mtu = mtu;
    }

    /**
     * Returns whether MTU exchange has been performed.
     *
     * @return true if MTU exchanged
     */
    public boolean isMtuExchanged() {
        return mtuExchanged;
    }

    /**
     * Sets whether MTU exchange has been performed.
     *
     * @param exchanged true if MTU exchanged
     */
    public void setMtuExchanged(boolean exchanged) {
        this.mtuExchanged = exchanged;
    }

    /**
     * Returns the maximum ATT payload size for this connection.
     *
     * <p>This is MTU minus the ATT header (3 bytes for most operations).
     *
     * @return maximum payload size
     */
    public int getMaxPayloadSize() {
        return mtu - 3;
    }

    // ==================== Discovery State ====================

    /**
     * Returns the current discovery state.
     *
     * @return discovery state
     */
    public DiscoveryState getDiscoveryState() {
        return discoveryState;
    }

    /**
     * Sets the discovery state.
     *
     * @param state new discovery state
     */
    public void setDiscoveryState(DiscoveryState state) {
        this.discoveryState = state;
    }

    /**
     * Returns whether service discovery has completed.
     *
     * @return true if services discovered
     */
    public boolean isServicesDiscovered() {
        return servicesDiscovered;
    }

    /**
     * Sets whether service discovery has completed.
     *
     * @param discovered true if services discovered
     */
    public void setServicesDiscovered(boolean discovered) {
        this.servicesDiscovered = discovered;
    }

    /**
     * Checks if discovery is currently in progress.
     *
     * @return true if discovering
     */
    public boolean isDiscovering() {
        DiscoveryState state = discoveryState;
        return state == DiscoveryState.DISCOVERING_SERVICES ||
                state == DiscoveryState.DISCOVERING_CHARACTERISTICS ||
                state == DiscoveryState.DISCOVERING_DESCRIPTORS;
    }

    // ==================== Services ====================

    /**
     * Returns an unmodifiable list of discovered services.
     *
     * @return services list
     */
    public List<GattService> getServices() {
        synchronized (services) {
            return Collections.unmodifiableList(new ArrayList<>(services));
        }
    }

    /**
     * Returns the number of discovered services.
     *
     * @return service count
     */
    public int getServiceCount() {
        synchronized (services) {
            return services.size();
        }
    }

    /**
     * Adds a discovered service.
     *
     * @param service service to add
     */
    public void addService(GattService service) {
        synchronized (services) {
            services.add(service);
        }
    }

    /**
     * Clears all discovered services.
     */
    public void clearServices() {
        synchronized (services) {
            services.clear();
        }
    }

    /**
     * Finds a service by UUID.
     *
     * @param uuid service UUID
     * @return service, or null if not found
     */
    public GattService getService(UUID uuid) {
        synchronized (services) {
            for (GattService service : services) {
                if (Objects.equals(service.getUuid(), uuid)) {
                    return service;
                }
            }
        }
        return null;
    }

    /**
     * Finds a service by 16-bit UUID.
     *
     * @param uuid16 16-bit service UUID
     * @return service, or null if not found
     */
    public GattService getService(int uuid16) {
        return getService(GattConstants.uuidFrom16Bit(uuid16));
    }

    /**
     * Finds all services with a specific UUID.
     *
     * @param uuid service UUID
     * @return list of matching services (may be empty)
     */
    public List<GattService> getServices(UUID uuid) {
        List<GattService> result = new ArrayList<>();
        synchronized (services) {
            for (GattService service : services) {
                if (Objects.equals(service.getUuid(), uuid)) {
                    result.add(service);
                }
            }
        }
        return result;
    }

    // ==================== Characteristic Lookup ====================

    /**
     * Finds a characteristic by handle across all services.
     *
     * @param handle characteristic handle (declaration or value)
     * @return characteristic, or null if not found
     */
    public GattCharacteristic getCharacteristic(int handle) {
        synchronized (services) {
            for (GattService service : services) {
                GattCharacteristic characteristic = service.getCharacteristic(handle);
                if (characteristic != null) {
                    return characteristic;
                }
            }
        }
        return null;
    }

    /**
     * Finds a characteristic by UUID across all services.
     *
     * @param serviceUuid service UUID
     * @param charUuid    characteristic UUID
     * @return characteristic, or null if not found
     */
    public GattCharacteristic getCharacteristic(UUID serviceUuid, UUID charUuid) {
        GattService service = getService(serviceUuid);
        if (service != null) {
            return service.getCharacteristic(charUuid);
        }
        return null;
    }

    // ==================== Descriptor Lookup ====================

    /**
     * Finds a descriptor by handle across all services.
     *
     * @param handle descriptor handle
     * @return descriptor, or null if not found
     */
    public GattDescriptor getDescriptor(int handle) {
        synchronized (services) {
            for (GattService service : services) {
                GattDescriptor descriptor = service.getDescriptor(handle);
                if (descriptor != null) {
                    return descriptor;
                }
            }
        }
        return null;
    }

    // ==================== Connection Age ====================

    /**
     * Returns the connection age in milliseconds.
     *
     * @return age in milliseconds
     */
    public long getAgeMillis() {
        return System.currentTimeMillis() - createdAt;
    }

    /**
     * Returns the connection age in seconds.
     *
     * @return age in seconds
     */
    public long getAgeSeconds() {
        return getAgeMillis() / 1000;
    }

    // ==================== Object Methods ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GattConnection that = (GattConnection) o;
        return connectionHandle == that.connectionHandle;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(connectionHandle);
    }

    @Override
    public String toString() {
        return String.format("GattConnection{handle=0x%04X, addr=%s, mtu=%d, services=%d, state=%s}",
                connectionHandle, getFormattedAddress(), mtu, getServiceCount(), discoveryState);
    }
}