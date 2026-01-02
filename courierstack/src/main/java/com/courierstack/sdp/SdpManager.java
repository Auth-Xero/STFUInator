package com.courierstack.sdp;

import com.courierstack.l2cap.AclConnection;
import com.courierstack.l2cap.ChannelState;
import com.courierstack.l2cap.IL2capConnectionCallback;
import com.courierstack.l2cap.IL2capListener;
import com.courierstack.l2cap.IL2capServerListener;
import com.courierstack.l2cap.L2capChannel;
import com.courierstack.l2cap.L2capConstants;
import com.courierstack.l2cap.L2capManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SDP Manager - Service Discovery Protocol implementation.
 *
 * <p>Provides both SDP client (querying remote devices) and SDP server
 * (responding to queries) functionality per Bluetooth Core Spec v5.3,
 * Vol 3, Part B.
 *
 * <p>Features:
 * <ul>
 *   <li>ServiceSearch, ServiceAttribute, and ServiceSearchAttribute queries</li>
 *   <li>Continuation state handling for large responses</li>
 *   <li>Result caching for improved performance</li>
 *   <li>SDP server for local service advertisement</li>
 *   <li>Connection pooling for multiple queries to same device</li>
 * </ul>
 *
 * <p>Usage example:
 * <pre>{@code
 * SdpManager sdp = new SdpManager(l2capManager, listener);
 * sdp.initialize();
 *
 * // Query a remote device
 * sdp.queryService(remoteAddress, SdpConstants.UUID_SPP, new ISdpQueryCallback() {
 *     public void onServiceFound(ServiceRecord record) {
 *         int channel = record.getRfcommChannel();
 *     }
 *     public void onQueryComplete(List<ServiceRecord> records) { }
 *     public void onError(String message) { }
 * });
 *
 * // Register a local service
 * ServiceRecord record = ServiceRecord.builder()
 *     .serviceClass(SdpConstants.UUID_SPP)
 *     .rfcommProtocol(1)
 *     .serviceName("My Service")
 *     .build();
 * sdp.registerService(record);
 * }</pre>
 *
 * <p>Thread safety: This class is thread-safe.
 */
public class SdpManager implements IL2capListener {

    private static final String TAG = "SdpManager";
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_MS = 5000;
    private static final int MAX_ATTRIBUTE_BYTE_COUNT = 672;
    private static final long CACHE_EXPIRY_MS = 60000; // 1 minute

    /**
     * Listener interface for SDP events.
     */
    public interface ISdpListener {
        /**
         * Called for informational messages.
         *
         * @param message info message
         */
        void onMessage(String message);

        /**
         * Called when an error occurs.
         *
         * @param message error description
         */
        void onError(String message);
    }

    // ==================== Internal State Classes ====================

    private static class PendingQuery {
        final UUID targetUuid;
        final boolean searchAll;
        final ISdpQueryCallback callback;
        final List<ServiceRecord> results = new ArrayList<>();
        final ByteBuffer responseBuffer;
        L2capChannel channel;
        int transactionId;
        byte[] continuationState;
        String addressStr;
        long startTime;

        PendingQuery(UUID uuid, boolean searchAll, ISdpQueryCallback callback) {
            this.targetUuid = uuid;
            this.searchAll = searchAll;
            this.callback = callback;
            this.responseBuffer = ByteBuffer.allocate(65536).order(ByteOrder.BIG_ENDIAN);
            this.startTime = System.currentTimeMillis();
        }
    }

    private static class CachedResult {
        final List<ServiceRecord> records;
        final long timestamp;

        CachedResult(List<ServiceRecord> records) {
            this.records = new ArrayList<>(records);
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_EXPIRY_MS;
        }
    }

    // ==================== Dependencies ====================

    private final L2capManager mL2capManager;
    private final ISdpListener mListener;
    private final ExecutorService mExecutor;
    private final SdpDatabase mDatabase;

    // ==================== State ====================

    private final AtomicBoolean mInitialized = new AtomicBoolean(false);
    private final AtomicInteger mTransactionId = new AtomicInteger(1);
    private final AtomicBoolean mServerEnabled = new AtomicBoolean(false);

    // Pending queries by local CID
    private final Map<Integer, PendingQuery> mPendingQueries = new ConcurrentHashMap<>();

    // Query queue by device address
    private final Map<String, Queue<PendingQuery>> mQueryQueueByAddress = new ConcurrentHashMap<>();

    // Service cache by device address and UUID
    private final Map<String, Map<UUID, CachedResult>> mServiceCache = new ConcurrentHashMap<>();

    // ==================== Constructors ====================

    /**
     * Creates a new SDP manager.
     *
     * @param l2capManager L2CAP manager for transport (must not be null)
     * @param listener     event listener (must not be null)
     * @throws NullPointerException if any parameter is null
     */
    public SdpManager(L2capManager l2capManager, ISdpListener listener) {
        if (l2capManager == null) {
            throw new NullPointerException("l2capManager must not be null");
        }
        if (listener == null) {
            throw new NullPointerException("listener must not be null");
        }

        mL2capManager = l2capManager;
        mListener = listener;
        mDatabase = new SdpDatabase();
        mExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "SDP-Worker");
            t.setDaemon(true);
            return t;
        });
    }

    // ==================== Initialization ====================

    /**
     * Initializes the SDP manager.
     *
     * @return true if successful
     */
    public boolean initialize() {
        if (mInitialized.get()) {
            return true;
        }

        mL2capManager.addListener(this);
        mInitialized.set(true);
        mListener.onMessage("SDP Manager initialized");

        return true;
    }

    /**
     * Starts the SDP server to respond to incoming queries.
     *
     * <p>Registers the SDP PSM (0x0001) with L2CAP.
     */
    public void startServer() {
        if (!mInitialized.get()) {
            mListener.onError("SDP not initialized");
            return;
        }

        if (mServerEnabled.getAndSet(true)) {
            return; // Already started
        }

        mDatabase.registerSdpServer();

        mL2capManager.registerServer(SdpConstants.PSM_SDP, new IL2capServerListener() {
            @Override
            public boolean onConnectionRequest(L2capChannel channel) {
                mListener.onMessage("SDP server: connection from " +
                        channel.connection.getFormattedAddress());
                return true;
            }

            @Override
            public void onChannelOpened(L2capChannel channel) {
                mListener.onMessage("SDP server: channel opened");
            }

            @Override
            public void onDataReceived(L2capChannel channel, byte[] data) {
                handleServerRequest(channel, data);
            }

            @Override
            public void onChannelClosed(L2capChannel channel) {
                mListener.onMessage("SDP server: channel closed");
            }

            @Override
            public void onError(L2capChannel channel, String message) {
                mListener.onError("SDP server error: " + message);
            }
        });

        mListener.onMessage("SDP server started");
    }

    /**
     * Stops the SDP server.
     */
    public void stopServer() {
        if (!mServerEnabled.getAndSet(false)) {
            return;
        }

        mL2capManager.unregisterServer(SdpConstants.PSM_SDP);
        mListener.onMessage("SDP server stopped");
    }

    /**
     * Shuts down the SDP manager.
     */
    public void shutdown() {
        mInitialized.set(false);
        stopServer();
        mL2capManager.removeListener(this);
        mPendingQueries.clear();
        mQueryQueueByAddress.clear();

        mExecutor.shutdown();
        try {
            if (!mExecutor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                mExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            mExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Returns whether SDP is initialized.
     */
    public boolean isInitialized() {
        return mInitialized.get();
    }

    /**
     * Returns the local service database.
     */
    public SdpDatabase getDatabase() {
        return mDatabase;
    }

    // ==================== Service Registration ====================

    /**
     * Registers a local service.
     *
     * @param record service record to register
     * @return service record handle, or -1 on failure
     */
    public int registerService(ServiceRecord record) {
        return mDatabase.registerService(record);
    }

    /**
     * Unregisters a local service.
     *
     * @param handle service record handle
     * @return true if service was removed
     */
    public boolean unregisterService(int handle) {
        return mDatabase.unregisterService(handle);
    }

    // ==================== Service Query (Client) ====================

    /**
     * Queries a remote device for a specific service UUID.
     *
     * @param remoteAddress 6-byte Bluetooth address
     * @param serviceUuid   service UUID to search for
     * @param callback      query result callback
     */
    public void queryService(byte[] remoteAddress, UUID serviceUuid, ISdpQueryCallback callback) {
        if (!mInitialized.get()) {
            callback.onError("SDP not initialized");
            return;
        }

        if (remoteAddress == null || remoteAddress.length != 6) {
            callback.onError("Invalid remote address");
            return;
        }

        if (serviceUuid == null) {
            callback.onError("Service UUID required");
            return;
        }

        String addrStr = formatAddress(remoteAddress);
        mListener.onMessage("SDP query: " + SdpConstants.getServiceName(serviceUuid) + " on " + addrStr);

        // Check cache
        Map<UUID, CachedResult> deviceCache = mServiceCache.get(addrStr);
        if (deviceCache != null) {
            CachedResult cached = deviceCache.get(serviceUuid);
            if (cached != null && !cached.isExpired()) {
                mListener.onMessage("Using cached result");
                for (ServiceRecord record : cached.records) {
                    callback.onServiceFound(record);
                }
                callback.onQueryComplete(new ArrayList<>(cached.records));
                return;
            }
        }

        // Queue the query
        PendingQuery query = new PendingQuery(serviceUuid, false, callback);
        queueQuery(remoteAddress, addrStr, query);
    }

    /**
     * Queries a remote device for all services (browse).
     *
     * @param remoteAddress 6-byte Bluetooth address
     * @param callback      query result callback
     */
    public void browseServices(byte[] remoteAddress, ISdpQueryCallback callback) {
        if (!mInitialized.get()) {
            callback.onError("SDP not initialized");
            return;
        }

        String addrStr = formatAddress(remoteAddress);
        mListener.onMessage("SDP browse on " + addrStr);

        PendingQuery query = new PendingQuery(SdpDatabase.PUBLIC_BROWSE_ROOT, true, callback);
        queueQuery(remoteAddress, addrStr, query);
    }

    private void queueQuery(byte[] remoteAddress, String addrStr, PendingQuery query) {
        query.addressStr = addrStr;

        synchronized (mQueryQueueByAddress) {
            Queue<PendingQuery> queue = mQueryQueueByAddress.get(addrStr);

            if (queue != null && !queue.isEmpty()) {
                // Already have a connection in progress
                mListener.onMessage("Queuing SDP query");
                queue.add(query);
                return;
            }

            // Start new connection
            queue = new ConcurrentLinkedQueue<>();
            queue.add(query);
            mQueryQueueByAddress.put(addrStr, queue);
        }

        // Create ACL connection
        byte[] addr = remoteAddress.clone();
        mL2capManager.createConnection(addr, new IL2capConnectionCallback() {
            @Override
            public void onSuccess(L2capChannel channel) {
                mListener.onMessage("ACL connected, opening SDP channel");
                openSdpChannel(channel.connection.handle, addr, addrStr);
            }

            @Override
            public void onFailure(String reason) {
                failAllQueries(addrStr, "Connection failed: " + reason);
            }
        });
    }

    private void openSdpChannel(int aclHandle, byte[] remoteAddress, String addrStr) {
        AclConnection conn = mL2capManager.getConnections().get(aclHandle);
        if (conn == null) {
            failAllQueries(addrStr, "ACL connection not found");
            return;
        }

        mL2capManager.connectChannel(conn, SdpConstants.PSM_SDP, new IL2capConnectionCallback() {
            @Override
            public void onSuccess(L2capChannel channel) {
                mListener.onMessage("SDP channel opened");
                startNextQuery(channel, addrStr, remoteAddress);
            }

            @Override
            public void onFailure(String reason) {
                failAllQueries(addrStr, "SDP channel failed: " + reason);
            }
        });
    }

    private void startNextQuery(L2capChannel channel, String addrStr, byte[] remoteAddress) {
        PendingQuery query;

        synchronized (mQueryQueueByAddress) {
            Queue<PendingQuery> queue = mQueryQueueByAddress.get(addrStr);
            if (queue == null || queue.isEmpty()) {
                mL2capManager.closeChannel(channel);
                return;
            }
            query = queue.peek();
        }

        query.channel = channel;
        query.transactionId = mTransactionId.getAndIncrement() & 0xFFFF;
        mPendingQueries.put(channel.localCid, query);

        sendServiceSearchAttributeRequest(query, remoteAddress);
    }

    private void sendServiceSearchAttributeRequest(PendingQuery query, byte[] remoteAddress) {
        ByteBuffer pdu = ByteBuffer.allocate(256).order(ByteOrder.BIG_ENDIAN);

        pdu.put((byte) SdpConstants.SDP_SERVICE_SEARCH_ATTR_REQUEST);
        pdu.putShort((short) query.transactionId);

        int paramLenPos = pdu.position();
        pdu.putShort((short) 0); // Placeholder
        int paramStart = pdu.position();

        // Service Search Pattern (sequence of UUIDs)
        byte[] uuidElement = SdpDataElement.encodeUuid(query.targetUuid);
        byte[] searchPattern = SdpDataElement.encodeSequence(uuidElement);
        pdu.put(searchPattern);

        // Maximum Attribute Byte Count
        pdu.putShort((short) MAX_ATTRIBUTE_BYTE_COUNT);

        // Attribute ID List (request all attributes)
        pdu.put(SdpDataElement.encodeAllAttributesRange());

        // Continuation State
        if (query.continuationState != null) {
            pdu.put((byte) query.continuationState.length);
            pdu.put(query.continuationState);
        } else {
            pdu.put((byte) 0);
        }

        // Update parameter length
        int paramLen = pdu.position() - paramStart;
        pdu.putShort(paramLenPos, (short) paramLen);

        // Send
        byte[] data = new byte[pdu.position()];
        pdu.flip();
        pdu.get(data);

        mL2capManager.sendData(query.channel, data);
    }

    // ==================== Response Handling ====================

    @Override
    public void onDataReceived(L2capChannel channel, byte[] data) {
        PendingQuery query = mPendingQueries.get(channel.localCid);
        if (query != null) {
            handleClientResponse(query, data);
        }
    }

    private void handleClientResponse(PendingQuery query, byte[] data) {
        if (data.length < 5) {
            completeQuery(query, "SDP response too short");
            return;
        }

        SdpParser.PduHeader header = SdpParser.parsePduHeader(data);
        if (header == null) {
            completeQuery(query, "Invalid SDP PDU");
            return;
        }

        if (header.pduId == SdpConstants.SDP_ERROR_RESPONSE) {
            int errorCode = SdpParser.parseErrorResponse(
                    java.util.Arrays.copyOfRange(data, 5, data.length));
            completeQuery(query, "SDP error: " + SdpConstants.getErrorString(errorCode));
            return;
        }

        if (header.pduId != SdpConstants.SDP_SERVICE_SEARCH_ATTR_RESPONSE) {
            completeQuery(query, "Unexpected PDU type: 0x" + Integer.toHexString(header.pduId));
            return;
        }

        ByteBuffer buf = ByteBuffer.wrap(data, 5, data.length - 5).order(ByteOrder.BIG_ENDIAN);

        // Attribute List Byte Count
        int attrListByteCount = buf.getShort() & 0xFFFF;

        // Accumulate attribute data
        if (attrListByteCount > 0 && buf.remaining() >= attrListByteCount) {
            byte[] attrData = new byte[attrListByteCount];
            buf.get(attrData);
            query.responseBuffer.put(attrData);
        }

        // Continuation state
        int contStateLen = buf.hasRemaining() ? buf.get() & 0xFF : 0;
        if (contStateLen > 0 && buf.remaining() >= contStateLen) {
            query.continuationState = new byte[contStateLen];
            buf.get(query.continuationState);

            // Continue the query
            sendServiceSearchAttributeRequest(query, query.channel.connection.getPeerAddress());
        } else {
            // Query complete - parse accumulated response
            parseAccumulatedResponse(query);
        }
    }

    private void parseAccumulatedResponse(PendingQuery query) {
        query.responseBuffer.flip();
        byte[] responseData = new byte[query.responseBuffer.remaining()];
        query.responseBuffer.get(responseData);

        List<ServiceRecord> records = SdpParser.parseAttributeListResponse(responseData, query.targetUuid);

        for (ServiceRecord record : records) {
            query.results.add(record);
            query.callback.onServiceFound(record);
        }

        // Cache results
        if (!query.results.isEmpty()) {
            String addrStr = query.addressStr;
            mServiceCache.computeIfAbsent(addrStr, k -> new ConcurrentHashMap<>())
                    .put(query.targetUuid, new CachedResult(query.results));

            mListener.onMessage("Found " + query.results.size() + " service(s)");
        } else {
            mListener.onMessage("No services found");
        }

        // Complete this query and start next
        completeQuery(query, null);
    }

    private void completeQuery(PendingQuery query, String error) {
        mPendingQueries.remove(query.channel.localCid);

        if (error != null) {
            query.callback.onError(error);
        } else {
            query.callback.onQueryComplete(query.results);
        }

        // Process next query in queue
        String addrStr = query.addressStr;
        L2capChannel channel = query.channel;

        synchronized (mQueryQueueByAddress) {
            Queue<PendingQuery> queue = mQueryQueueByAddress.get(addrStr);
            if (queue != null) {
                queue.poll(); // Remove completed query

                if (queue.isEmpty()) {
                    mQueryQueueByAddress.remove(addrStr);
                    if (channel != null && channel.isOpen()) {
                        mL2capManager.closeChannel(channel);
                    }
                } else if (channel != null && channel.isOpen()) {
                    // Start next query
                    startNextQuery(channel, addrStr, channel.connection.getPeerAddress());
                } else {
                    // Channel closed, fail remaining
                    failAllQueries(addrStr, "Channel closed");
                }
            }
        }
    }

    private void failAllQueries(String addrStr, String reason) {
        synchronized (mQueryQueueByAddress) {
            Queue<PendingQuery> queue = mQueryQueueByAddress.remove(addrStr);
            if (queue != null) {
                for (PendingQuery q : queue) {
                    mPendingQueries.remove(q.channel != null ? q.channel.localCid : -1);
                    q.callback.onError(reason);
                }
            }
        }
    }

    // ==================== Server Request Handling ====================

    private void handleServerRequest(L2capChannel channel, byte[] data) {
        if (data.length < 5) {
            sendErrorResponse(channel, 0, SdpConstants.ERR_INVALID_PDU_SIZE);
            return;
        }

        SdpParser.PduHeader header = SdpParser.parsePduHeader(data);
        if (header == null) {
            sendErrorResponse(channel, 0, SdpConstants.ERR_INVALID_REQUEST_SYNTAX);
            return;
        }

        ByteBuffer buf = ByteBuffer.wrap(data, 5, data.length - 5).order(ByteOrder.BIG_ENDIAN);

        switch (header.pduId) {
            case SdpConstants.SDP_SERVICE_SEARCH_REQUEST:
                handleServiceSearchRequest(channel, header.transactionId, buf);
                break;

            case SdpConstants.SDP_SERVICE_ATTR_REQUEST:
                handleServiceAttributeRequest(channel, header.transactionId, buf);
                break;

            case SdpConstants.SDP_SERVICE_SEARCH_ATTR_REQUEST:
                handleServiceSearchAttributeRequest(channel, header.transactionId, buf);
                break;

            default:
                sendErrorResponse(channel, header.transactionId, SdpConstants.ERR_INVALID_REQUEST_SYNTAX);
        }
    }

    private void handleServiceSearchRequest(L2capChannel channel, int transactionId, ByteBuffer buf) {
        // Parse UUID search pattern
        List<UUID> uuidPattern = parseUuidPattern(buf);
        if (uuidPattern.isEmpty()) {
            sendErrorResponse(channel, transactionId, SdpConstants.ERR_INVALID_REQUEST_SYNTAX);
            return;
        }

        // Max service record count
        int maxCount = buf.remaining() >= 2 ? buf.getShort() & 0xFFFF : 100;

        // Continuation state (ignored for now - we send complete responses)
        int contStateLen = buf.hasRemaining() ? buf.get() & 0xFF : 0;

        // Search database
        List<Integer> handles = mDatabase.searchByUuidPattern(uuidPattern);
        if (handles.size() > maxCount) {
            handles = handles.subList(0, maxCount);
        }

        // Build response
        ByteBuffer rsp = ByteBuffer.allocate(9 + handles.size() * 4).order(ByteOrder.BIG_ENDIAN);
        rsp.put((byte) SdpConstants.SDP_SERVICE_SEARCH_RESPONSE);
        rsp.putShort((short) transactionId);
        rsp.putShort((short) (4 + handles.size() * 4 + 1)); // Param length
        rsp.putShort((short) handles.size()); // Total count
        rsp.putShort((short) handles.size()); // Current count

        for (int handle : handles) {
            rsp.putInt(handle);
        }

        rsp.put((byte) 0); // No continuation state

        mL2capManager.sendData(channel, rsp.array());
    }

    private void handleServiceAttributeRequest(L2capChannel channel, int transactionId, ByteBuffer buf) {
        // Service record handle
        if (buf.remaining() < 4) {
            sendErrorResponse(channel, transactionId, SdpConstants.ERR_INVALID_PDU_SIZE);
            return;
        }
        int handle = buf.getInt();

        // Max attribute byte count
        int maxBytes = buf.remaining() >= 2 ? buf.getShort() & 0xFFFF : MAX_ATTRIBUTE_BYTE_COUNT;

        // Get record
        ServiceRecord record = mDatabase.getServiceRecord(handle);
        if (record == null) {
            sendErrorResponse(channel, transactionId, SdpConstants.ERR_INVALID_SERVICE_RECORD_HANDLE);
            return;
        }

        // Encode and send
        byte[] encoded = record.encode();
        sendAttributeResponse(channel, transactionId, SdpConstants.SDP_SERVICE_ATTR_RESPONSE,
                encoded, maxBytes);
    }

    private void handleServiceSearchAttributeRequest(L2capChannel channel, int transactionId, ByteBuffer buf) {
        // Parse UUID search pattern
        List<UUID> uuidPattern = parseUuidPattern(buf);

        // Max attribute byte count
        int maxBytes = buf.remaining() >= 2 ? buf.getShort() & 0xFFFF : MAX_ATTRIBUTE_BYTE_COUNT;

        // Search database
        List<Integer> handles = mDatabase.searchByUuidPattern(uuidPattern);

        // Encode matching records
        List<byte[]> encodedRecords = new ArrayList<>();
        for (int handle : handles) {
            ServiceRecord record = mDatabase.getServiceRecord(handle);
            if (record != null) {
                encodedRecords.add(record.encode());
            }
        }

        byte[] encoded = SdpDataElement.encodeSequence(encodedRecords);
        sendAttributeResponse(channel, transactionId, SdpConstants.SDP_SERVICE_SEARCH_ATTR_RESPONSE,
                encoded, maxBytes);
    }

    private void sendAttributeResponse(L2capChannel channel, int transactionId, int pduId,
                                       byte[] attrData, int maxBytes) {
        // For simplicity, send complete response without continuation
        // A full implementation would fragment large responses

        int attrLen = Math.min(attrData.length, maxBytes);

        ByteBuffer rsp = ByteBuffer.allocate(8 + attrLen).order(ByteOrder.BIG_ENDIAN);
        rsp.put((byte) pduId);
        rsp.putShort((short) transactionId);
        rsp.putShort((short) (2 + attrLen + 1)); // Param length
        rsp.putShort((short) attrLen);
        rsp.put(attrData, 0, attrLen);
        rsp.put((byte) 0); // No continuation

        mL2capManager.sendData(channel, rsp.array());
    }

    private void sendErrorResponse(L2capChannel channel, int transactionId, int errorCode) {
        ByteBuffer rsp = ByteBuffer.allocate(7).order(ByteOrder.BIG_ENDIAN);
        rsp.put((byte) SdpConstants.SDP_ERROR_RESPONSE);
        rsp.putShort((short) transactionId);
        rsp.putShort((short) 2); // Param length
        rsp.putShort((short) errorCode);

        mL2capManager.sendData(channel, rsp.array());
    }

    private List<UUID> parseUuidPattern(ByteBuffer buf) {
        List<UUID> uuids = new ArrayList<>();

        if (!buf.hasRemaining()) return uuids;

        int header = buf.get() & 0xFF;
        int type = header >> 3;

        if (type != SdpConstants.DE_SEQ) return uuids;

        int len = getDataElementLength(buf, header & 0x07);
        int end = buf.position() + len;

        while (buf.position() < end && buf.hasRemaining()) {
            UUID uuid = SdpDataElement.decodeUuid(buf);
            if (uuid != null) {
                uuids.add(uuid);
            } else {
                break;
            }
        }

        return uuids;
    }

    // ==================== IL2capListener Implementation ====================

    @Override
    public void onConnectionComplete(AclConnection connection) {
    }

    @Override
    public void onDisconnectionComplete(int handle, int reason) {
    }

    @Override
    public void onChannelOpened(L2capChannel channel) {
    }

    @Override
    public void onChannelClosed(L2capChannel channel) {
        PendingQuery query = mPendingQueries.remove(channel.localCid);
        if (query != null && query.addressStr != null) {
            failAllQueries(query.addressStr, "Channel closed unexpectedly");
        }
    }

    @Override
    public void onConnectionRequest(int handle, int psm, int sourceCid) {
    }

    @Override
    public void onError(String message) {
        mListener.onError(message);
    }

    @Override
    public void onMessage(String message) {
    }

    // ==================== Cache Management ====================

    /**
     * Clears all cached service records.
     */
    public void clearCache() {
        mServiceCache.clear();
    }

    /**
     * Clears cached records for a specific device.
     *
     * @param address device address string (XX:XX:XX:XX:XX:XX)
     */
    public void clearCache(String address) {
        mServiceCache.remove(address);
    }

    /**
     * Clears cached records for a specific device.
     *
     * @param address 6-byte device address
     */
    public void clearCache(byte[] address) {
        clearCache(formatAddress(address));
    }

    // ==================== Utility Methods ====================

    private static int getDataElementLength(ByteBuffer buf, int sizeIndex) {
        switch (sizeIndex) {
            case 0: return 1;
            case 1: return 2;
            case 2: return 4;
            case 3: return 8;
            case 4: return 16;
            case 5: return buf.hasRemaining() ? buf.get() & 0xFF : 0;
            case 6: return buf.remaining() >= 2 ? buf.getShort() & 0xFFFF : 0;
            case 7: return buf.remaining() >= 4 ? buf.getInt() : 0;
            default: return 0;
        }
    }

    private static String formatAddress(byte[] addr) {
        if (addr == null || addr.length != 6) {
            return "??:??:??:??:??:??";
        }
        return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                addr[5] & 0xFF, addr[4] & 0xFF, addr[3] & 0xFF,
                addr[2] & 0xFF, addr[1] & 0xFF, addr[0] & 0xFF);
    }
}