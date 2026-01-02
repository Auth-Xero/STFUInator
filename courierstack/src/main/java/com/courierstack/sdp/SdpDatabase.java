package com.courierstack.sdp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SDP Service Record Database.
 *
 * <p>Manages local service records for SDP server functionality.
 * Supports registering, updating, and removing service records,
 * as well as searching by UUID or browsing.
 *
 * <p>Thread safety: This class is thread-safe. All operations are atomic.
 *
 * <p>Usage example:
 * <pre>{@code
 * SdpDatabase db = new SdpDatabase();
 *
 * // Register a service
 * ServiceRecord record = ServiceRecord.builder()
 *     .serviceClass(SdpConstants.UUID_SPP)
 *     .rfcommProtocol(1)
 *     .serviceName("My Serial Port")
 *     .build();
 * int handle = db.registerService(record);
 *
 * // Search for services
 * List<ServiceRecord> matches = db.searchByUuid(SdpConstants.UUID_SPP);
 *
 * // Remove service
 * db.unregisterService(handle);
 * }</pre>
 */
public class SdpDatabase {

    /** Reserved handle for SDP server itself. */
    public static final int SDP_SERVER_HANDLE = 0x00000000;

    /** First allocatable service record handle. */
    private static final int FIRST_RECORD_HANDLE = 0x00010000;

    /** Maximum number of service records. */
    private static final int MAX_RECORDS = 256;

    /** Public Browse Root UUID. */
    public static final UUID PUBLIC_BROWSE_ROOT =
            UUID.fromString("00001002-0000-1000-8000-00805F9B34FB");

    // Service record storage
    private final Map<Integer, ServiceRecord> mRecords = new ConcurrentHashMap<>();

    // Handle allocator
    private final AtomicInteger mNextHandle = new AtomicInteger(FIRST_RECORD_HANDLE);

    // Database state listener
    private volatile IDatabaseListener mListener;

    /**
     * Listener for database changes.
     */
    public interface IDatabaseListener {
        /**
         * Called when a service is registered.
         *
         * @param handle service record handle
         * @param record the registered record
         */
        void onServiceRegistered(int handle, ServiceRecord record);

        /**
         * Called when a service is unregistered.
         *
         * @param handle service record handle
         */
        void onServiceUnregistered(int handle);
    }

    /**
     * Creates a new SDP database.
     *
     * <p>The database is initially empty. Call {@link #registerSdpServer()}
     * to add the standard SDP server service record.
     */
    public SdpDatabase() {
    }

    /**
     * Sets the database listener.
     *
     * @param listener listener for database changes (may be null)
     */
    public void setListener(IDatabaseListener listener) {
        mListener = listener;
    }

    // ==================== Service Registration ====================

    /**
     * Registers a service record.
     *
     * <p>A unique handle will be assigned to the record.
     *
     * @param record service record to register (must not be null)
     * @return assigned service record handle, or -1 if database is full
     * @throws NullPointerException if record is null
     */
    public int registerService(ServiceRecord record) {
        if (record == null) {
            throw new NullPointerException("record must not be null");
        }

        if (mRecords.size() >= MAX_RECORDS) {
            return -1;
        }

        int handle = allocateHandle();
        record.setServiceRecordHandle(handle);

        // Update the handle attribute in the record
        record.setAttribute(SdpConstants.ATTR_SERVICE_RECORD_HANDLE,
                SdpDataElement.encodeUint32(handle));

        mRecords.put(handle, record);

        IDatabaseListener listener = mListener;
        if (listener != null) {
            listener.onServiceRegistered(handle, record);
        }

        return handle;
    }

    /**
     * Registers a service record with a specific handle.
     *
     * <p>This is primarily for internal use (e.g., SDP server record).
     *
     * @param handle specific handle to use
     * @param record service record to register
     * @return true if successful, false if handle is already in use
     */
    public boolean registerServiceWithHandle(int handle, ServiceRecord record) {
        if (record == null || mRecords.containsKey(handle)) {
            return false;
        }

        record.setServiceRecordHandle(handle);
        record.setAttribute(SdpConstants.ATTR_SERVICE_RECORD_HANDLE,
                SdpDataElement.encodeUint32(handle));

        mRecords.put(handle, record);

        IDatabaseListener listener = mListener;
        if (listener != null) {
            listener.onServiceRegistered(handle, record);
        }

        return true;
    }

    /**
     * Unregisters a service record.
     *
     * @param handle service record handle
     * @return true if service was removed, false if not found
     */
    public boolean unregisterService(int handle) {
        ServiceRecord removed = mRecords.remove(handle);

        if (removed != null) {
            IDatabaseListener listener = mListener;
            if (listener != null) {
                listener.onServiceUnregistered(handle);
            }
            return true;
        }

        return false;
    }

    /**
     * Updates an existing service record.
     *
     * @param handle service record handle
     * @param record new service record data
     * @return true if updated, false if handle not found
     */
    public boolean updateService(int handle, ServiceRecord record) {
        if (!mRecords.containsKey(handle)) {
            return false;
        }

        record.setServiceRecordHandle(handle);
        record.setAttribute(SdpConstants.ATTR_SERVICE_RECORD_HANDLE,
                SdpDataElement.encodeUint32(handle));

        mRecords.put(handle, record);
        return true;
    }

    /**
     * Registers the SDP server service record (handle 0x00000000).
     *
     * <p>This record describes the SDP server itself as required by
     * the Bluetooth specification.
     */
    public void registerSdpServer() {
        ServiceRecord record = ServiceRecord.builder()
                .serviceRecordHandle(SDP_SERVER_HANDLE)
                .serviceClass(SdpConstants.UUID_SDP)
                .serviceName("Service Discovery Server")
                .publicBrowseRoot()
                .build();

        registerServiceWithHandle(SDP_SERVER_HANDLE, record);
    }

    // ==================== Service Lookup ====================

    /**
     * Returns a service record by handle.
     *
     * @param handle service record handle
     * @return service record, or null if not found
     */
    public ServiceRecord getServiceRecord(int handle) {
        return mRecords.get(handle);
    }

    /**
     * Returns whether a service record exists.
     *
     * @param handle service record handle
     * @return true if record exists
     */
    public boolean hasServiceRecord(int handle) {
        return mRecords.containsKey(handle);
    }

    /**
     * Returns all service record handles.
     *
     * @return unmodifiable collection of handles
     */
    public Iterable<Integer> getServiceRecordHandles() {
        return Collections.unmodifiableSet(mRecords.keySet());
    }

    /**
     * Returns all service records.
     *
     * @return unmodifiable collection of records
     */
    public Iterable<ServiceRecord> getServiceRecords() {
        return Collections.unmodifiableCollection(mRecords.values());
    }

    /**
     * Returns the number of registered services.
     *
     * @return service count
     */
    public int getServiceCount() {
        return mRecords.size();
    }

    // ==================== Service Search ====================

    /**
     * Searches for services matching a UUID pattern.
     *
     * <p>A service matches if any of the UUIDs in the pattern appear in
     * its ServiceClassIDList, ProtocolDescriptorList, or
     * BluetoothProfileDescriptorList.
     *
     * @param uuidPattern list of UUIDs to search for
     * @return list of matching service record handles
     */
    public List<Integer> searchByUuidPattern(List<UUID> uuidPattern) {
        List<Integer> matches = new ArrayList<>();

        for (Map.Entry<Integer, ServiceRecord> entry : mRecords.entrySet()) {
            if (matchesUuidPattern(entry.getValue(), uuidPattern)) {
                matches.add(entry.getKey());
            }
        }

        return matches;
    }

    /**
     * Searches for services matching a single UUID.
     *
     * @param uuid UUID to search for
     * @return list of matching service records
     */
    public List<ServiceRecord> searchByUuid(UUID uuid) {
        List<ServiceRecord> matches = new ArrayList<>();

        for (ServiceRecord record : mRecords.values()) {
            if (matchesUuid(record, uuid)) {
                matches.add(record);
            }
        }

        return matches;
    }

    /**
     * Returns all services in a browse group.
     *
     * @param browseGroupUuid browse group UUID
     * @return list of service records in the group
     */
    public List<ServiceRecord> browseServices(UUID browseGroupUuid) {
        List<ServiceRecord> matches = new ArrayList<>();

        for (ServiceRecord record : mRecords.values()) {
            if (record.getBrowseGroupUuids().contains(browseGroupUuid)) {
                matches.add(record);
            }
        }

        return matches;
    }

    /**
     * Returns all publicly browsable services.
     *
     * @return list of service records in PublicBrowseRoot
     */
    public List<ServiceRecord> browsePublicServices() {
        return browseServices(PUBLIC_BROWSE_ROOT);
    }

    // ==================== Pattern Matching ====================

    /**
     * Checks if a service record matches a UUID pattern.
     */
    private boolean matchesUuidPattern(ServiceRecord record, List<UUID> pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return true; // Empty pattern matches all
        }

        for (UUID uuid : pattern) {
            if (!matchesUuid(record, uuid)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Checks if a service record contains a UUID.
     */
    private boolean matchesUuid(ServiceRecord record, UUID uuid) {
        if (uuid == null) return true;

        // Check service class UUIDs
        if (record.getServiceClassUuids().contains(uuid)) {
            return true;
        }

        // Check protocol UUIDs
        if (record.getProtocolUuids().contains(uuid)) {
            return true;
        }

        // Check profile UUIDs
        if (record.getProfileDescriptors().containsKey(uuid)) {
            return true;
        }

        return false;
    }

    // ==================== Utility Methods ====================

    /**
     * Clears all service records.
     */
    public void clear() {
        List<Integer> handles = new ArrayList<>(mRecords.keySet());
        for (int handle : handles) {
            unregisterService(handle);
        }
    }

    /**
     * Allocates a new service record handle.
     */
    private int allocateHandle() {
        int handle;
        do {
            handle = mNextHandle.getAndIncrement();
            if (handle < 0) {
                // Wrapped around, reset
                mNextHandle.set(FIRST_RECORD_HANDLE);
                handle = mNextHandle.getAndIncrement();
            }
        } while (mRecords.containsKey(handle));

        return handle;
    }

    @Override
    public String toString() {
        return "SdpDatabase{records=" + mRecords.size() + "}";
    }
}