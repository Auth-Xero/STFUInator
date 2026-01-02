package com.courierstack.gatt;

import com.courierstack.core.CourierLogger;
import com.courierstack.l2cap.AclConnection;
import com.courierstack.l2cap.ConnectionType;
import com.courierstack.l2cap.IL2capListener;
import com.courierstack.l2cap.L2capChannel;
import com.courierstack.l2cap.L2capManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.courierstack.gatt.GattConstants.*;

/**
 * GATT (Generic Attribute Profile) Manager.
 *
 * <p>Implements the ATT protocol for BLE attribute access including:
 * <ul>
 *   <li>MTU exchange</li>
 *   <li>Service discovery</li>
 *   <li>Characteristic read/write operations</li>
 *   <li>Descriptor operations</li>
 *   <li>Notifications and indications</li>
 *   <li>GATT server functionality</li>
 * </ul>
 *
 * <p>Reference: Bluetooth Core Spec v5.3, Vol 3, Part F (ATT) and Part G (GATT)
 *
 * @see GattConnection
 * @see GattConstants
 */
public class GattManager implements IL2capListener {

    private static final String TAG = "GattManager";

    /** Internal pending operation tracking. */
    private static class PendingOperation {
        final int opcode;
        final long timestamp;
        int handle;
        int startHandle;
        int endHandle;
        UUID uuid;
        int currentServiceIndex;
        GattCallback.Operation callback;
        GattCallback.Discovery discoveryCallback;

        PendingOperation(int opcode) {
            this.opcode = opcode;
            this.timestamp = System.currentTimeMillis();
        }
    }

    /** Connection state wrapper. */
    private static class ConnectionState {
        final GattConnection connection;
        volatile PendingOperation pendingOperation;

        ConnectionState(GattConnection connection) {
            this.connection = connection;
        }
    }

    // Instance variables
    private final L2capManager mL2capManager;
    private final IGattListener mListener;
    private final ExecutorService mExecutor;
    private final AtomicBoolean mInitialized = new AtomicBoolean(false);
    private final AtomicInteger mNextHandle = new AtomicInteger(1);
    private final Map<Integer, ConnectionState> mConnections = new ConcurrentHashMap<>();
    private final List<GattService> mServerServices = new CopyOnWriteArrayList<>();
    private final Map<Integer, Object> mServerAttributes = new ConcurrentHashMap<>();
    private final List<GattCallback.Notification> mNotificationCallbacks = new CopyOnWriteArrayList<>();
    private volatile GattCallback.Server mServerCallback;
    private volatile int mLocalMtu = ATT_MAX_MTU;

    /**
     * Creates a new GATT manager.
     *
     * @param l2capManager L2CAP manager
     * @param listener     event listener
     */
    public GattManager(L2capManager l2capManager, IGattListener listener) {
        mL2capManager = Objects.requireNonNull(l2capManager);
        mListener = Objects.requireNonNull(listener);
        mExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "GattManager-Worker");
            t.setDaemon(true);
            return t;
        });
    }

    // ==================== Lifecycle ====================

    public boolean initialize() {
        if (mInitialized.getAndSet(true)) return true;
        mL2capManager.addListener(this);
        registerDefaultServices();
        mListener.onMessage("GATT Manager initialized");
        return true;
    }

    public void shutdown() {
        if (!mInitialized.getAndSet(false)) return;
        mL2capManager.removeListener(this);
        mConnections.clear();
        mServerServices.clear();
        mServerAttributes.clear();
        mNotificationCallbacks.clear();
        mExecutor.shutdown();
        mListener.onMessage("GATT Manager shutdown");
    }

    public boolean isInitialized() { return mInitialized.get(); }

    private void registerDefaultServices() {
        GattService gapService = createService(UUID_GAP_SERVICE, true);
        addCharacteristic(gapService, UUID_DEVICE_NAME, CHAR_PROP_READ, CHAR_PERM_READ, "CourierStack".getBytes());
        addCharacteristic(gapService, UUID_APPEARANCE, CHAR_PROP_READ, CHAR_PERM_READ, new byte[]{0, 0});
        registerService(gapService);

        GattService gattService = createService(UUID_GATT_SERVICE, true);
        GattCharacteristic serviceChanged = addCharacteristic(gattService, UUID_SERVICE_CHANGED,
                CHAR_PROP_INDICATE, CHAR_PERM_READ, new byte[4]);
        addCccd(serviceChanged);
        registerService(gattService);
    }

    // ==================== L2CAP Listener ====================

    @Override
    public void onConnectionComplete(AclConnection connection) {
        if (connection.type != ConnectionType.LE) return;
        GattConnection gattConn = new GattConnection(connection.handle, connection.getPeerAddress(), connection.peerAddressType);
        mConnections.put(connection.handle, new ConnectionState(gattConn));
        mListener.onConnectionStateChanged(connection.handle, true);
    }

    @Override
    public void onDisconnectionComplete(int handle, int reason) {
        ConnectionState state = mConnections.remove(handle);
        if (state != null) mListener.onConnectionStateChanged(handle, false);
    }

    @Override public void onChannelOpened(L2capChannel channel) {}
    @Override public void onChannelClosed(L2capChannel channel) {}
    @Override public void onDataReceived(L2capChannel channel, byte[] data) {}
    @Override public void onConnectionRequest(int handle, int psm, int sourceCid) {}
    @Override public void onError(String message) { mListener.onError(message); }
    @Override public void onMessage(String message) {}

    @Override
    public void onFixedChannelData(int handle, int cid, byte[] peerAddress, int peerAddressType, byte[] data) {
        if (cid == ATT_CID) onAttDataReceived(handle, data);
    }

    // ==================== ATT Processing ====================

    public void onAttDataReceived(int connectionHandle, byte[] data) {
        if (data == null || data.length < 1) return;
        int opcode = data[0] & 0xFF;
        ConnectionState state = mConnections.get(connectionHandle);

        CourierLogger.d(TAG, String.format("ATT RX handle=0x%04X opcode=0x%02X len=%d", connectionHandle, opcode, data.length));

        if (isRequest(opcode) || isCommand(opcode)) {
            handleServerRequest(connectionHandle, opcode, data);
        } else if (isResponse(opcode)) {
            handleClientResponse(state, opcode, data);
        } else if (opcode == ATT_HANDLE_VALUE_NTF) {
            handleNotification(state, data);
        } else if (opcode == ATT_HANDLE_VALUE_IND) {
            handleIndication(state, connectionHandle, data);
        }
    }

    // ==================== Client Operations ====================

    public void exchangeMtu(int connectionHandle, int clientMtu, GattCallback.Operation callback) {
        Objects.requireNonNull(callback);
        ConnectionState state = mConnections.get(connectionHandle);
        if (state == null) { callback.onError(ATT_ERR_UNLIKELY_ERROR, "No connection"); return; }

        GattConnection conn = state.connection;
        if (conn.isMtuExchanged()) { callback.onSuccess(intToLeBytes(conn.getMtu())); return; }

        ByteBuffer pdu = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN);
        pdu.put((byte) ATT_EXCHANGE_MTU_REQ).putShort((short) clientMtu);

        PendingOperation op = new PendingOperation(ATT_EXCHANGE_MTU_REQ);
        op.callback = callback;
        state.pendingOperation = op;
        sendAttPdu(connectionHandle, pdu.array());
    }

    public void discoverServices(int connectionHandle, GattCallback.Discovery callback) {
        Objects.requireNonNull(callback);
        ConnectionState state = mConnections.get(connectionHandle);
        if (state == null) { callback.onError(ATT_ERR_UNLIKELY_ERROR, "No connection"); return; }

        GattConnection conn = state.connection;
        conn.clearServices();
        conn.setDiscoveryState(GattConnection.DiscoveryState.DISCOVERING_SERVICES);
        discoverPrimaryServices(state, 0x0001, 0xFFFF, callback);
    }

    public void readCharacteristic(int connectionHandle, GattCharacteristic characteristic, GattCallback.Operation callback) {
        Objects.requireNonNull(characteristic); Objects.requireNonNull(callback);
        ConnectionState state = mConnections.get(connectionHandle);
        if (state == null) { callback.onError(ATT_ERR_UNLIKELY_ERROR, "No connection"); return; }
        if (!characteristic.isReadable()) { callback.onError(ATT_ERR_READ_NOT_PERMITTED, "Read not permitted"); return; }

        ByteBuffer pdu = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN);
        pdu.put((byte) ATT_READ_REQ).putShort((short) characteristic.getValueHandle());

        PendingOperation op = new PendingOperation(ATT_READ_REQ);
        op.handle = characteristic.getValueHandle();
        op.callback = callback;
        state.pendingOperation = op;
        sendAttPdu(connectionHandle, pdu.array());
    }

    public void writeCharacteristic(int connectionHandle, GattCharacteristic characteristic,
                                    byte[] value, boolean withResponse, GattCallback.Operation callback) {
        Objects.requireNonNull(characteristic); Objects.requireNonNull(value);
        ConnectionState state = mConnections.get(connectionHandle);
        if (state == null) { if (callback != null) callback.onError(ATT_ERR_UNLIKELY_ERROR, "No connection"); return; }

        GattConnection conn = state.connection;
        int maxLen = conn.getMaxPayloadSize();
        if (value.length > maxLen) { if (callback != null) callback.onError(ATT_ERR_INVALID_ATTRIBUTE_LENGTH, "Value too long"); return; }

        if (withResponse) {
            if (!characteristic.supportsWriteWithResponse()) { if (callback != null) callback.onError(ATT_ERR_WRITE_NOT_PERMITTED, "Write not permitted"); return; }
            ByteBuffer pdu = ByteBuffer.allocate(3 + value.length).order(ByteOrder.LITTLE_ENDIAN);
            pdu.put((byte) ATT_WRITE_REQ).putShort((short) characteristic.getValueHandle()).put(value);
            PendingOperation op = new PendingOperation(ATT_WRITE_REQ);
            op.handle = characteristic.getValueHandle(); op.callback = callback;
            state.pendingOperation = op;
            sendAttPdu(connectionHandle, pdu.array());
        } else {
            if (!characteristic.supportsWriteWithoutResponse()) { if (callback != null) callback.onError(ATT_ERR_WRITE_NOT_PERMITTED, "Write cmd not permitted"); return; }
            ByteBuffer pdu = ByteBuffer.allocate(3 + value.length).order(ByteOrder.LITTLE_ENDIAN);
            pdu.put((byte) ATT_WRITE_CMD).putShort((short) characteristic.getValueHandle()).put(value);
            sendAttPdu(connectionHandle, pdu.array());
            if (callback != null) callback.onSuccess(null);
        }
    }

    public void setNotification(int connectionHandle, GattCharacteristic characteristic, boolean enable, GattCallback.Operation callback) {
        Objects.requireNonNull(characteristic); Objects.requireNonNull(callback);
        GattDescriptor cccd = characteristic.getCccd();
        if (cccd == null) { callback.onError(ATT_ERR_ATTRIBUTE_NOT_FOUND, "CCCD not found"); return; }
        int val = enable ? CCCD_NOTIFICATION : CCCD_NONE;
        writeDescriptor(connectionHandle, cccd, new byte[]{(byte)(val & 0xFF), (byte)((val >> 8) & 0xFF)}, callback);
    }

    public void setIndication(int connectionHandle, GattCharacteristic characteristic, boolean enable, GattCallback.Operation callback) {
        Objects.requireNonNull(characteristic); Objects.requireNonNull(callback);
        GattDescriptor cccd = characteristic.getCccd();
        if (cccd == null) { callback.onError(ATT_ERR_ATTRIBUTE_NOT_FOUND, "CCCD not found"); return; }
        int val = enable ? CCCD_INDICATION : CCCD_NONE;
        writeDescriptor(connectionHandle, cccd, new byte[]{(byte)(val & 0xFF), (byte)((val >> 8) & 0xFF)}, callback);
    }

    public void readDescriptor(int connectionHandle, GattDescriptor descriptor, GattCallback.Operation callback) {
        Objects.requireNonNull(descriptor); Objects.requireNonNull(callback);
        ConnectionState state = mConnections.get(connectionHandle);
        if (state == null) { callback.onError(ATT_ERR_UNLIKELY_ERROR, "No connection"); return; }

        ByteBuffer pdu = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN);
        pdu.put((byte) ATT_READ_REQ).putShort((short) descriptor.getHandle());

        PendingOperation op = new PendingOperation(ATT_READ_REQ);
        op.handle = descriptor.getHandle(); op.callback = callback;
        state.pendingOperation = op;
        sendAttPdu(connectionHandle, pdu.array());
    }

    /**
     * Reads a long characteristic value using Read Blob requests.
     *
     * <p>This automatically handles values longer than MTU-1 by issuing
     * multiple Read Blob requests.
     *
     * @param connectionHandle connection handle
     * @param characteristic   characteristic to read
     * @param callback         result callback with complete value
     */
    public void readLongCharacteristic(int connectionHandle, GattCharacteristic characteristic,
                                       GattCallback.Operation callback) {
        Objects.requireNonNull(characteristic); Objects.requireNonNull(callback);
        ConnectionState state = mConnections.get(connectionHandle);
        if (state == null) { callback.onError(ATT_ERR_UNLIKELY_ERROR, "No connection"); return; }
        if (!characteristic.isReadable()) { callback.onError(ATT_ERR_READ_NOT_PERMITTED, "Read not permitted"); return; }

        // Start with regular read, then continue with blobs if needed
        readCharacteristic(connectionHandle, characteristic, new GattCallback.Operation() {
            private byte[] accumulated = new byte[0];

            @Override
            public void onSuccess(byte[] data) {
                accumulated = concatenate(accumulated, data);
                GattConnection conn = state.connection;

                // If we got a full MTU-1 worth of data, there might be more
                if (data != null && data.length == conn.getMaxPayloadSize()) {
                    readBlob(connectionHandle, characteristic.getValueHandle(), accumulated.length, this);
                } else {
                    callback.onSuccess(accumulated);
                }
            }

            @Override
            public void onError(int errorCode, String message) {
                if (errorCode == ATT_ERR_INVALID_OFFSET && accumulated.length > 0) {
                    // No more data, return what we have
                    callback.onSuccess(accumulated);
                } else {
                    callback.onError(errorCode, message);
                }
            }
        });
    }

    private void readBlob(int connectionHandle, int handle, int offset, GattCallback.Operation callback) {
        ConnectionState state = mConnections.get(connectionHandle);
        if (state == null) { callback.onError(ATT_ERR_UNLIKELY_ERROR, "No connection"); return; }

        ByteBuffer pdu = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN);
        pdu.put((byte) ATT_READ_BLOB_REQ).putShort((short) handle).putShort((short) offset);

        PendingOperation op = new PendingOperation(ATT_READ_BLOB_REQ);
        op.handle = handle; op.callback = callback;
        state.pendingOperation = op;
        sendAttPdu(connectionHandle, pdu.array());
    }

    private static byte[] concatenate(byte[] a, byte[] b) {
        if (a == null || a.length == 0) return b != null ? b : new byte[0];
        if (b == null || b.length == 0) return a;
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    public void writeDescriptor(int connectionHandle, GattDescriptor descriptor, byte[] value, GattCallback.Operation callback) {
        Objects.requireNonNull(descriptor); Objects.requireNonNull(value); Objects.requireNonNull(callback);
        ConnectionState state = mConnections.get(connectionHandle);
        if (state == null) { callback.onError(ATT_ERR_UNLIKELY_ERROR, "No connection"); return; }

        ByteBuffer pdu = ByteBuffer.allocate(3 + value.length).order(ByteOrder.LITTLE_ENDIAN);
        pdu.put((byte) ATT_WRITE_REQ).putShort((short) descriptor.getHandle()).put(value);

        PendingOperation op = new PendingOperation(ATT_WRITE_REQ);
        op.handle = descriptor.getHandle(); op.callback = callback;
        state.pendingOperation = op;
        sendAttPdu(connectionHandle, pdu.array());
    }

    // ==================== Discovery Implementation ====================

    private void discoverPrimaryServices(ConnectionState state, int startHandle, int endHandle, GattCallback.Discovery callback) {
        ByteBuffer pdu = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN);
        pdu.put((byte) ATT_READ_BY_GROUP_TYPE_REQ).putShort((short) startHandle).putShort((short) endHandle).putShort((short) GATT_PRIMARY_SERVICE_UUID);

        PendingOperation op = new PendingOperation(ATT_READ_BY_GROUP_TYPE_REQ);
        op.startHandle = startHandle; op.endHandle = endHandle; op.discoveryCallback = callback;
        state.pendingOperation = op;
        sendAttPdu(state.connection.connectionHandle, pdu.array());
    }

    private void discoverCharacteristics(ConnectionState state, int serviceIndex, GattCallback.Discovery callback) {
        GattConnection conn = state.connection;
        List<GattService> services = conn.getServices();
        if (serviceIndex >= services.size()) {
            conn.setDiscoveryState(GattConnection.DiscoveryState.DISCOVERING_DESCRIPTORS);
            discoverDescriptors(state, 0, 0, callback);
            return;
        }

        GattService service = services.get(serviceIndex);
        ByteBuffer pdu = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN);
        pdu.put((byte) ATT_READ_BY_TYPE_REQ).putShort((short) service.getStartHandle()).putShort((short) service.getEndHandle()).putShort((short) GATT_CHARACTERISTIC_UUID);

        PendingOperation op = new PendingOperation(ATT_READ_BY_TYPE_REQ);
        op.startHandle = service.getStartHandle(); op.endHandle = service.getEndHandle();
        op.currentServiceIndex = serviceIndex; op.discoveryCallback = callback;
        state.pendingOperation = op;
        sendAttPdu(conn.connectionHandle, pdu.array());
    }

    private void discoverDescriptors(ConnectionState state, int serviceIndex, int charIndex, GattCallback.Discovery callback) {
        GattConnection conn = state.connection;
        List<GattService> services = conn.getServices();

        while (serviceIndex < services.size()) {
            GattService service = services.get(serviceIndex);
            List<GattCharacteristic> chars = service.getCharacteristics();
            while (charIndex < chars.size()) {
                GattCharacteristic c = chars.get(charIndex);
                int startH = c.getValueHandle() + 1;
                int endH = (charIndex + 1 < chars.size()) ? chars.get(charIndex + 1).getHandle() - 1 : service.getEndHandle();
                if (startH <= endH) {
                    ByteBuffer pdu = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN);
                    pdu.put((byte) ATT_FIND_INFORMATION_REQ).putShort((short) startH).putShort((short) endH);
                    PendingOperation op = new PendingOperation(ATT_FIND_INFORMATION_REQ);
                    op.startHandle = startH; op.endHandle = endH; op.currentServiceIndex = serviceIndex; op.discoveryCallback = callback;
                    state.pendingOperation = op;
                    sendAttPdu(conn.connectionHandle, pdu.array());
                    return;
                }
                charIndex++;
            }
            serviceIndex++; charIndex = 0;
        }
        completeDiscovery(state, callback);
    }

    private void completeDiscovery(ConnectionState state, GattCallback.Discovery callback) {
        GattConnection conn = state.connection;
        conn.setDiscoveryState(GattConnection.DiscoveryState.COMPLETE);
        conn.setServicesDiscovered(true);
        List<GattService> services = conn.getServices();
        mListener.onServicesDiscovered(conn.connectionHandle, services.toArray(new GattService[0]));
        callback.onServicesDiscovered(services);
    }

    // ==================== Response Handling ====================

    private void handleClientResponse(ConnectionState state, int opcode, byte[] data) {
        if (state == null || state.pendingOperation == null) return;
        switch (opcode) {
            case ATT_ERROR_RSP: handleErrorResponse(state, data); break;
            case ATT_EXCHANGE_MTU_RSP: handleMtuResponse(state, data); break;
            case ATT_READ_BY_GROUP_TYPE_RSP: handleReadByGroupTypeResponse(state, data); break;
            case ATT_READ_BY_TYPE_RSP: handleReadByTypeResponse(state, data); break;
            case ATT_FIND_INFORMATION_RSP: handleFindInformationResponse(state, data); break;
            case ATT_READ_RSP: handleReadResponse(state, data); break;
            case ATT_READ_BLOB_RSP: handleReadBlobResponse(state, data); break;
            case ATT_WRITE_RSP: handleWriteResponse(state); break;
        }
    }

    private void handleErrorResponse(ConnectionState state, byte[] data) {
        if (data.length < 5) return;
        int reqOpcode = data[1] & 0xFF;
        int handle = readUint16LE(data, 2);
        int errorCode = data[4] & 0xFF;
        String errorMsg = getAttErrorString(errorCode);

        PendingOperation op = state.pendingOperation;
        state.pendingOperation = null;
        if (op == null) return;

        if (op.callback != null) op.callback.onError(errorCode, errorMsg);
        if (op.discoveryCallback != null) {
            GattConnection conn = state.connection;
            if (errorCode == ATT_ERR_ATTRIBUTE_NOT_FOUND) {
                GattConnection.DiscoveryState ds = conn.getDiscoveryState();
                if (ds == GattConnection.DiscoveryState.DISCOVERING_SERVICES) {
                    conn.setDiscoveryState(GattConnection.DiscoveryState.DISCOVERING_CHARACTERISTICS);
                    discoverCharacteristics(state, 0, op.discoveryCallback);
                } else if (ds == GattConnection.DiscoveryState.DISCOVERING_CHARACTERISTICS) {
                    discoverCharacteristics(state, op.currentServiceIndex + 1, op.discoveryCallback);
                } else if (ds == GattConnection.DiscoveryState.DISCOVERING_DESCRIPTORS) {
                    discoverDescriptors(state, op.currentServiceIndex, 0, op.discoveryCallback);
                }
            } else {
                conn.setDiscoveryState(GattConnection.DiscoveryState.FAILED);
                op.discoveryCallback.onError(errorCode, errorMsg);
            }
        }
    }

    private void handleMtuResponse(ConnectionState state, byte[] data) {
        if (data.length < 3) return;
        int serverMtu = readUint16LE(data, 1);
        GattConnection conn = state.connection;
        int negotiatedMtu = Math.min(mLocalMtu, serverMtu);
        conn.setMtu(negotiatedMtu); conn.setMtuExchanged(true);
        mListener.onMtuChanged(conn.connectionHandle, negotiatedMtu);

        PendingOperation op = state.pendingOperation;
        state.pendingOperation = null;
        if (op != null && op.callback != null) op.callback.onSuccess(intToLeBytes(negotiatedMtu));
    }

    private void handleReadByGroupTypeResponse(ConnectionState state, byte[] data) {
        if (data.length < 2) return;
        int length = data[1] & 0xFF, offset = 2, lastEndHandle = 0;
        PendingOperation op = state.pendingOperation;
        GattConnection conn = state.connection;

        while (offset + length <= data.length) {
            int startH = readUint16LE(data, offset), endH = readUint16LE(data, offset + 2);
            UUID uuid = (length == 6) ? uuidFrom16Bit(readUint16LE(data, offset + 4)) : (length == 20) ? extractUuid128(data, offset + 4) : null;
            if (uuid != null) {
                conn.addService(new GattService(uuid, startH, endH, true));
                mListener.onMessage(String.format("Found service: %s handles=0x%04X-0x%04X", getShortUuid(uuid), startH, endH));
            }
            lastEndHandle = endH; offset += length;
        }
        if (lastEndHandle < 0xFFFF && op != null && op.discoveryCallback != null)
            discoverPrimaryServices(state, lastEndHandle + 1, 0xFFFF, op.discoveryCallback);
    }

    private void handleReadByTypeResponse(ConnectionState state, byte[] data) {
        if (data.length < 2) return;
        int length = data[1] & 0xFF, offset = 2, lastHandle = 0;
        PendingOperation op = state.pendingOperation;
        if (op == null) return;
        GattConnection conn = state.connection;
        List<GattService> services = conn.getServices();
        if (op.currentServiceIndex >= services.size()) return;
        GattService service = services.get(op.currentServiceIndex);

        while (offset + length <= data.length) {
            int handle = readUint16LE(data, offset), props = data[offset + 2] & 0xFF, valHandle = readUint16LE(data, offset + 3);
            UUID uuid = (length == 7) ? uuidFrom16Bit(readUint16LE(data, offset + 5)) : (length == 21) ? extractUuid128(data, offset + 5) : null;
            if (uuid != null) {
                GattCharacteristic c = new GattCharacteristic(uuid, handle, valHandle, props);
                c.setService(service); service.addCharacteristic(c);
            }
            lastHandle = handle; offset += length;
        }
        if (lastHandle < service.getEndHandle() && op.discoveryCallback != null) {
            ByteBuffer pdu = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN);
            pdu.put((byte) ATT_READ_BY_TYPE_REQ).putShort((short)(lastHandle + 1)).putShort((short) service.getEndHandle()).putShort((short) GATT_CHARACTERISTIC_UUID);
            op.startHandle = lastHandle + 1;
            sendAttPdu(conn.connectionHandle, pdu.array());
        }
    }

    private void handleFindInformationResponse(ConnectionState state, byte[] data) {
        if (data.length < 2) return;
        int format = data[1] & 0xFF, offset = 2, entrySize = (format == 1) ? 4 : 18, lastHandle = 0;
        PendingOperation op = state.pendingOperation;
        if (op == null) return;
        GattConnection conn = state.connection;
        List<GattService> services = conn.getServices();
        if (op.currentServiceIndex >= services.size()) return;
        GattService service = services.get(op.currentServiceIndex);

        while (offset + entrySize <= data.length) {
            int handle = readUint16LE(data, offset);
            UUID uuid = (format == 1) ? uuidFrom16Bit(readUint16LE(data, offset + 2)) : extractUuid128(data, offset + 2);
            for (GattCharacteristic c : service.getCharacteristics()) {
                if (handle > c.getValueHandle()) {
                    List<GattCharacteristic> chars = service.getCharacteristics();
                    int idx = chars.indexOf(c);
                    int charEnd = (idx + 1 < chars.size()) ? chars.get(idx + 1).getHandle() - 1 : service.getEndHandle();
                    if (handle <= charEnd) {
                        GattDescriptor d = new GattDescriptor(uuid, handle);
                        d.setCharacteristic(c); c.addDescriptor(d);
                        break;
                    }
                }
            }
            lastHandle = handle; offset += entrySize;
        }
        if (lastHandle < op.endHandle && op.discoveryCallback != null) {
            ByteBuffer pdu = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN);
            pdu.put((byte) ATT_FIND_INFORMATION_REQ).putShort((short)(lastHandle + 1)).putShort((short) op.endHandle);
            op.startHandle = lastHandle + 1;
            sendAttPdu(conn.connectionHandle, pdu.array());
        }
    }

    private void handleReadResponse(ConnectionState state, byte[] data) {
        PendingOperation op = state.pendingOperation;
        state.pendingOperation = null;
        byte[] value = Arrays.copyOfRange(data, 1, data.length);
        GattConnection conn = state.connection;
        GattCharacteristic c = conn.getCharacteristic(op.handle);
        if (c != null) { c.setValue(value); mListener.onCharacteristicRead(conn.connectionHandle, c, value, 0); }
        else {
            GattDescriptor d = conn.getDescriptor(op.handle);
            if (d != null) { d.setValue(value); mListener.onDescriptorRead(conn.connectionHandle, d, value, 0); }
        }
        if (op.callback != null) op.callback.onSuccess(value);
    }

    private void handleReadBlobResponse(ConnectionState state, byte[] data) {
        PendingOperation op = state.pendingOperation;
        state.pendingOperation = null;
        byte[] value = Arrays.copyOfRange(data, 1, data.length);
        // Read Blob response goes directly to callback (used for long reads)
        if (op.callback != null) op.callback.onSuccess(value);
    }

    private void handleWriteResponse(ConnectionState state) {
        PendingOperation op = state.pendingOperation;
        state.pendingOperation = null;
        GattConnection conn = state.connection;
        GattCharacteristic c = conn.getCharacteristic(op.handle);
        if (c != null) mListener.onCharacteristicWrite(conn.connectionHandle, c, 0);
        else {
            GattDescriptor d = conn.getDescriptor(op.handle);
            if (d != null) mListener.onDescriptorWrite(conn.connectionHandle, d, 0);
        }
        if (op.callback != null) op.callback.onSuccess(null);
    }

    private void handleNotification(ConnectionState state, byte[] data) {
        if (data.length < 3) return;
        int handle = readUint16LE(data, 1);
        byte[] value = Arrays.copyOfRange(data, 3, data.length);
        GattConnection conn = state != null ? state.connection : null;
        int connHandle = conn != null ? conn.connectionHandle : 0;
        GattCharacteristic c = conn != null ? conn.getCharacteristic(handle) : null;
        if (c != null) { c.setValue(value); mListener.onNotification(connHandle, c, value); }
        for (GattCallback.Notification cb : mNotificationCallbacks) cb.onNotification(connHandle, handle, value);
    }

    private void handleIndication(ConnectionState state, int connectionHandle, byte[] data) {
        if (data.length < 3) return;
        int handle = readUint16LE(data, 1);
        byte[] value = Arrays.copyOfRange(data, 3, data.length);
        sendAttPdu(connectionHandle, new byte[]{(byte) ATT_HANDLE_VALUE_CFM});
        GattConnection conn = state != null ? state.connection : null;
        GattCharacteristic c = conn != null ? conn.getCharacteristic(handle) : null;
        if (c != null) { c.setValue(value); mListener.onIndication(connectionHandle, c, value); }
        for (GattCallback.Notification cb : mNotificationCallbacks) cb.onIndication(connectionHandle, handle, value);
    }

    // ==================== Server Request Handling ====================

    private void handleServerRequest(int connectionHandle, int opcode, byte[] data) {
        switch (opcode) {
            case ATT_EXCHANGE_MTU_REQ: handleMtuRequest(connectionHandle, data); break;
            case ATT_READ_BY_GROUP_TYPE_REQ: handleReadByGroupTypeRequest(connectionHandle, data); break;
            case ATT_READ_BY_TYPE_REQ: handleReadByTypeRequest(connectionHandle, data); break;
            case ATT_FIND_INFORMATION_REQ: handleFindInformationRequest(connectionHandle, data); break;
            case ATT_READ_REQ: handleReadRequest(connectionHandle, data); break;
            case ATT_READ_BLOB_REQ: handleReadBlobRequest(connectionHandle, data); break;
            case ATT_WRITE_REQ: handleWriteRequest(connectionHandle, data, true); break;
            case ATT_WRITE_CMD: handleWriteRequest(connectionHandle, data, false); break;
            default: sendErrorResponse(connectionHandle, opcode, 0, ATT_ERR_REQUEST_NOT_SUPPORTED);
        }
    }

    private void handleMtuRequest(int connectionHandle, byte[] data) {
        if (data.length < 3) { sendErrorResponse(connectionHandle, ATT_EXCHANGE_MTU_REQ, 0, ATT_ERR_INVALID_PDU); return; }
        int clientMtu = readUint16LE(data, 1);
        int negotiatedMtu = Math.min(mLocalMtu, clientMtu);
        ConnectionState state = mConnections.get(connectionHandle);
        if (state != null) { state.connection.setMtu(negotiatedMtu); state.connection.setMtuExchanged(true); mListener.onMtuChanged(connectionHandle, negotiatedMtu); }
        ByteBuffer rsp = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN);
        rsp.put((byte) ATT_EXCHANGE_MTU_RSP).putShort((short) mLocalMtu);
        sendAttPdu(connectionHandle, rsp.array());
    }

    private void handleReadByGroupTypeRequest(int connectionHandle, byte[] data) {
        if (data.length < 5) { sendErrorResponse(connectionHandle, ATT_READ_BY_GROUP_TYPE_REQ, 0, ATT_ERR_INVALID_PDU); return; }
        int startH = readUint16LE(data, 1), endH = readUint16LE(data, 3);
        UUID typeUuid = (data.length == 7) ? uuidFrom16Bit(readUint16LE(data, 5)) : (data.length == 21) ? extractUuid128(data, 5) : null;
        if (typeUuid == null || !typeUuid.equals(uuidFrom16Bit(GATT_PRIMARY_SERVICE_UUID))) {
            sendErrorResponse(connectionHandle, ATT_READ_BY_GROUP_TYPE_REQ, startH, typeUuid == null ? ATT_ERR_INVALID_PDU : ATT_ERR_UNSUPPORTED_GROUP_TYPE); return;
        }

        int mtu = getMtuForConnection(connectionHandle);
        ByteBuffer rsp = ByteBuffer.allocate(mtu).order(ByteOrder.LITTLE_ENDIAN);
        rsp.put((byte) ATT_READ_BY_GROUP_TYPE_RSP).put((byte) 0);
        int length = 0; boolean found = false;
        for (GattService s : mServerServices) {
            if (s.getStartHandle() >= startH && s.getStartHandle() <= endH && s.isPrimary()) {
                int entryLen = is16BitUuid(s.getUuid()) ? 6 : 20;
                if (length != 0 && entryLen != length) break;
                if (rsp.position() + entryLen > mtu) break;
                length = entryLen; found = true;
                rsp.putShort((short) s.getStartHandle()).putShort((short) s.getEndHandle());
                if (entryLen == 6) rsp.putShort((short) uuidTo16Bit(s.getUuid())); else putUuid128(rsp, s.getUuid());
            }
        }
        if (!found) { sendErrorResponse(connectionHandle, ATT_READ_BY_GROUP_TYPE_REQ, startH, ATT_ERR_ATTRIBUTE_NOT_FOUND); return; }
        rsp.put(1, (byte) length);
        sendAttPdu(connectionHandle, Arrays.copyOf(rsp.array(), rsp.position()));
    }

    private void handleReadByTypeRequest(int connectionHandle, byte[] data) {
        if (data.length < 5) { sendErrorResponse(connectionHandle, ATT_READ_BY_TYPE_REQ, 0, ATT_ERR_INVALID_PDU); return; }
        int startH = readUint16LE(data, 1), endH = readUint16LE(data, 3);
        UUID typeUuid = (data.length == 7) ? uuidFrom16Bit(readUint16LE(data, 5)) : (data.length == 21) ? extractUuid128(data, 5) : null;
        if (typeUuid == null) { sendErrorResponse(connectionHandle, ATT_READ_BY_TYPE_REQ, startH, ATT_ERR_INVALID_PDU); return; }

        int mtu = getMtuForConnection(connectionHandle);
        ByteBuffer rsp = ByteBuffer.allocate(mtu).order(ByteOrder.LITTLE_ENDIAN);
        rsp.put((byte) ATT_READ_BY_TYPE_RSP).put((byte) 0);
        int length = 0; boolean found = false;

        if (typeUuid.equals(uuidFrom16Bit(GATT_CHARACTERISTIC_UUID))) {
            for (GattService s : mServerServices) {
                if (s.getStartHandle() > endH) break;
                if (s.getEndHandle() < startH) continue;
                for (GattCharacteristic c : s.getCharacteristics()) {
                    if (c.getHandle() >= startH && c.getHandle() <= endH) {
                        int entryLen = 2 + 1 + 2 + (is16BitUuid(c.getUuid()) ? 2 : 16);
                        if (length != 0 && entryLen != length) break;
                        if (rsp.position() + entryLen > mtu) break;
                        length = entryLen; found = true;
                        rsp.putShort((short) c.getHandle()).put((byte) c.getProperties()).putShort((short) c.getValueHandle());
                        if (is16BitUuid(c.getUuid())) rsp.putShort((short) uuidTo16Bit(c.getUuid())); else putUuid128(rsp, c.getUuid());
                    }
                }
            }
        }
        if (!found) { sendErrorResponse(connectionHandle, ATT_READ_BY_TYPE_REQ, startH, ATT_ERR_ATTRIBUTE_NOT_FOUND); return; }
        rsp.put(1, (byte) length);
        sendAttPdu(connectionHandle, Arrays.copyOf(rsp.array(), rsp.position()));
    }

    private void handleFindInformationRequest(int connectionHandle, byte[] data) {
        if (data.length < 5) { sendErrorResponse(connectionHandle, ATT_FIND_INFORMATION_REQ, 0, ATT_ERR_INVALID_PDU); return; }
        int startH = readUint16LE(data, 1), endH = readUint16LE(data, 3);
        int mtu = getMtuForConnection(connectionHandle);
        ByteBuffer rsp = ByteBuffer.allocate(mtu).order(ByteOrder.LITTLE_ENDIAN);
        rsp.put((byte) ATT_FIND_INFORMATION_RSP).put((byte) 0);
        int format = 0; boolean found = false;

        for (GattService s : mServerServices) {
            for (GattCharacteristic c : s.getCharacteristics()) {
                for (GattDescriptor d : c.getDescriptors()) {
                    if (d.getHandle() >= startH && d.getHandle() <= endH) {
                        int entryFmt = is16BitUuid(d.getUuid()) ? 1 : 2, entryLen = entryFmt == 1 ? 4 : 18;
                        if (format != 0 && entryFmt != format) break;
                        if (rsp.position() + entryLen > mtu) break;
                        format = entryFmt; found = true;
                        rsp.putShort((short) d.getHandle());
                        if (format == 1) rsp.putShort((short) uuidTo16Bit(d.getUuid())); else putUuid128(rsp, d.getUuid());
                    }
                }
            }
        }
        if (!found) { sendErrorResponse(connectionHandle, ATT_FIND_INFORMATION_REQ, startH, ATT_ERR_ATTRIBUTE_NOT_FOUND); return; }
        rsp.put(1, (byte) format);
        sendAttPdu(connectionHandle, Arrays.copyOf(rsp.array(), rsp.position()));
    }

    private void handleReadRequest(int connectionHandle, byte[] data) {
        if (data.length < 3) { sendErrorResponse(connectionHandle, ATT_READ_REQ, 0, ATT_ERR_INVALID_PDU); return; }
        int handle = readUint16LE(data, 1);
        byte[] value = null;
        if (mServerCallback != null) value = mServerCallback.onRead(connectionHandle, handle);
        if (value == null) {
            Object attr = mServerAttributes.get(handle);
            if (attr instanceof GattCharacteristic) value = ((GattCharacteristic) attr).getValue();
            else if (attr instanceof GattDescriptor) value = ((GattDescriptor) attr).getValue();
        }
        if (value == null) { sendErrorResponse(connectionHandle, ATT_READ_REQ, handle, ATT_ERR_ATTRIBUTE_NOT_FOUND); return; }
        int mtu = getMtuForConnection(connectionHandle);
        if (value.length > mtu - 1) value = Arrays.copyOf(value, mtu - 1);
        ByteBuffer rsp = ByteBuffer.allocate(1 + value.length);
        rsp.put((byte) ATT_READ_RSP).put(value);
        sendAttPdu(connectionHandle, rsp.array());
    }

    private void handleReadBlobRequest(int connectionHandle, byte[] data) {
        if (data.length < 5) { sendErrorResponse(connectionHandle, ATT_READ_BLOB_REQ, 0, ATT_ERR_INVALID_PDU); return; }
        int handle = readUint16LE(data, 1);
        int offset = readUint16LE(data, 3);

        byte[] value = null;
        if (mServerCallback != null) value = mServerCallback.onRead(connectionHandle, handle);
        if (value == null) {
            Object attr = mServerAttributes.get(handle);
            if (attr instanceof GattCharacteristic) value = ((GattCharacteristic) attr).getValue();
            else if (attr instanceof GattDescriptor) value = ((GattDescriptor) attr).getValue();
        }
        if (value == null) { sendErrorResponse(connectionHandle, ATT_READ_BLOB_REQ, handle, ATT_ERR_ATTRIBUTE_NOT_FOUND); return; }
        if (offset > value.length) { sendErrorResponse(connectionHandle, ATT_READ_BLOB_REQ, handle, ATT_ERR_INVALID_OFFSET); return; }

        int mtu = getMtuForConnection(connectionHandle);
        int maxLen = mtu - 1;
        int remaining = value.length - offset;
        int sendLen = Math.min(remaining, maxLen);

        ByteBuffer rsp = ByteBuffer.allocate(1 + sendLen);
        rsp.put((byte) ATT_READ_BLOB_RSP);
        rsp.put(value, offset, sendLen);
        sendAttPdu(connectionHandle, rsp.array());
    }

    private void handleWriteRequest(int connectionHandle, byte[] data, boolean needResponse) {
        if (data.length < 3) { if (needResponse) sendErrorResponse(connectionHandle, ATT_WRITE_REQ, 0, ATT_ERR_INVALID_PDU); return; }
        int handle = readUint16LE(data, 1);
        byte[] value = Arrays.copyOfRange(data, 3, data.length);
        int result = 0;
        if (mServerCallback != null) result = mServerCallback.onWrite(connectionHandle, handle, value, needResponse);
        if (result != 0) { if (needResponse) sendErrorResponse(connectionHandle, ATT_WRITE_REQ, handle, result); return; }
        Object attr = mServerAttributes.get(handle);
        if (attr instanceof GattCharacteristic) ((GattCharacteristic) attr).setValue(value);
        else if (attr instanceof GattDescriptor) ((GattDescriptor) attr).setValue(value);
        if (needResponse) sendAttPdu(connectionHandle, new byte[]{(byte) ATT_WRITE_RSP});
    }

    // ==================== Server Service Building ====================

    public GattService createService(UUID uuid, boolean isPrimary) {
        int startHandle = mNextHandle.getAndIncrement();
        return new GattService(uuid, startHandle, startHandle, isPrimary);
    }

    public GattCharacteristic addCharacteristic(GattService service, UUID uuid, int properties, int permissions, byte[] value) {
        Objects.requireNonNull(service); Objects.requireNonNull(uuid);
        int declHandle = mNextHandle.getAndIncrement(), valHandle = mNextHandle.getAndIncrement();
        GattCharacteristic c = new GattCharacteristic(uuid, declHandle, valHandle, properties, permissions, value);
        c.setService(service); service.addCharacteristic(c); service.setEndHandle(valHandle);
        return c;
    }

    public GattDescriptor addCccd(GattCharacteristic characteristic) {
        return addDescriptor(characteristic, uuidFrom16Bit(GATT_CLIENT_CHAR_CONFIG_UUID), CHAR_PERM_READ | CHAR_PERM_WRITE, new byte[]{0, 0});
    }

    public GattDescriptor addDescriptor(GattCharacteristic characteristic, UUID uuid, int permissions, byte[] value) {
        Objects.requireNonNull(characteristic); Objects.requireNonNull(uuid);
        int handle = mNextHandle.getAndIncrement();
        GattDescriptor d = new GattDescriptor(uuid, handle, permissions, value);
        d.setCharacteristic(characteristic); characteristic.addDescriptor(d);
        if (characteristic.getService() != null) characteristic.getService().setEndHandle(handle);
        return d;
    }

    public void registerService(GattService service) {
        Objects.requireNonNull(service);
        mServerServices.add(service);
        mServerAttributes.put(service.getStartHandle(), service);
        for (GattCharacteristic c : service.getCharacteristics()) {
            mServerAttributes.put(c.getHandle(), c);
            mServerAttributes.put(c.getValueHandle(), c);
            for (GattDescriptor d : c.getDescriptors()) mServerAttributes.put(d.getHandle(), d);
        }
    }

    public boolean unregisterService(GattService service) {
        if (!mServerServices.remove(service)) return false;
        mServerAttributes.remove(service.getStartHandle());
        for (GattCharacteristic c : service.getCharacteristics()) {
            mServerAttributes.remove(c.getHandle()); mServerAttributes.remove(c.getValueHandle());
            for (GattDescriptor d : c.getDescriptors()) mServerAttributes.remove(d.getHandle());
        }
        return true;
    }

    // ==================== Server Notifications ====================

    public boolean sendNotification(int connectionHandle, int charHandle, byte[] value) {
        ConnectionState state = mConnections.get(connectionHandle);
        if (state == null) return false;
        int maxLen = state.connection.getMaxPayloadSize();
        if (value.length > maxLen) value = Arrays.copyOf(value, maxLen);
        ByteBuffer pdu = ByteBuffer.allocate(3 + value.length).order(ByteOrder.LITTLE_ENDIAN);
        pdu.put((byte) ATT_HANDLE_VALUE_NTF).putShort((short) charHandle).put(value);
        sendAttPdu(connectionHandle, pdu.array());
        if (mServerCallback != null) mServerCallback.onNotificationSent(connectionHandle, charHandle, true);
        return true;
    }

    public boolean sendIndication(int connectionHandle, int charHandle, byte[] value) {
        ConnectionState state = mConnections.get(connectionHandle);
        if (state == null) return false;
        int maxLen = state.connection.getMaxPayloadSize();
        if (value.length > maxLen) value = Arrays.copyOf(value, maxLen);
        ByteBuffer pdu = ByteBuffer.allocate(3 + value.length).order(ByteOrder.LITTLE_ENDIAN);
        pdu.put((byte) ATT_HANDLE_VALUE_IND).putShort((short) charHandle).put(value);
        sendAttPdu(connectionHandle, pdu.array());
        return true;
    }

    // ==================== Accessors ====================

    /**
     * Sets the server callback for handling client requests.
     *
     * @param callback server callback, or null to clear
     */
    public void setServerCallback(GattCallback.Server callback) {
        mServerCallback = callback;
    }

    /**
     * Adds a notification callback.
     *
     * @param callback callback to add
     */
    public void addNotificationCallback(GattCallback.Notification callback) {
        if (callback != null && !mNotificationCallbacks.contains(callback)) {
            mNotificationCallbacks.add(callback);
        }
    }

    /**
     * Removes a notification callback.
     *
     * @param callback callback to remove
     */
    public void removeNotificationCallback(GattCallback.Notification callback) {
        mNotificationCallbacks.remove(callback);
    }

    /**
     * Gets a connection by handle.
     *
     * @param connectionHandle connection handle
     * @return connection, or null if not found
     */
    public GattConnection getConnection(int connectionHandle) {
        ConnectionState s = mConnections.get(connectionHandle);
        return s != null ? s.connection : null;
    }

    /**
     * Gets all active connections.
     *
     * @return list of active connections
     */
    public List<GattConnection> getAllConnections() {
        List<GattConnection> result = new ArrayList<>();
        for (ConnectionState state : mConnections.values()) {
            result.add(state.connection);
        }
        return result;
    }

    /**
     * Gets the number of active connections.
     *
     * @return connection count
     */
    public int getConnectionCount() {
        return mConnections.size();
    }

    /**
     * Checks if a connection exists.
     *
     * @param connectionHandle connection handle
     * @return true if connected
     */
    public boolean isConnected(int connectionHandle) {
        return mConnections.containsKey(connectionHandle);
    }

    /**
     * Returns a copy of the server services list.
     *
     * @return list of registered server services
     */
    public List<GattService> getServerServices() {
        return new ArrayList<>(mServerServices);
    }

    /**
     * Gets a server service by UUID.
     *
     * @param uuid service UUID
     * @return service, or null if not found
     */
    public GattService getServerService(UUID uuid) {
        for (GattService service : mServerServices) {
            if (Objects.equals(service.getUuid(), uuid)) {
                return service;
            }
        }
        return null;
    }

    /**
     * Sets the preferred local MTU.
     *
     * @param mtu MTU value (will be clamped to 23-517)
     */
    public void setLocalMtu(int mtu) {
        mLocalMtu = Math.min(Math.max(mtu, ATT_DEFAULT_LE_MTU), ATT_MAX_MTU);
    }

    /**
     * Returns the preferred local MTU.
     *
     * @return local MTU
     */
    public int getLocalMtu() {
        return mLocalMtu;
    }

    /**
     * Gets the negotiated MTU for a connection.
     *
     * @param connectionHandle connection handle
     * @return negotiated MTU, or default if not connected
     */
    public int getConnectionMtu(int connectionHandle) {
        return getMtuForConnection(connectionHandle);
    }

    // ==================== Utilities ====================

    private void sendAttPdu(int connectionHandle, byte[] pdu) {
        mL2capManager.sendFixedChannelData(connectionHandle, ATT_CID, pdu);
        CourierLogger.d(TAG, String.format("ATT TX handle=0x%04X opcode=0x%02X len=%d", connectionHandle, pdu[0] & 0xFF, pdu.length));
    }

    private void sendErrorResponse(int connectionHandle, int requestOpcode, int handle, int errorCode) {
        ByteBuffer pdu = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN);
        pdu.put((byte) ATT_ERROR_RSP).put((byte) requestOpcode).putShort((short) handle).put((byte) errorCode);
        sendAttPdu(connectionHandle, pdu.array());
    }

    private int getMtuForConnection(int connectionHandle) {
        ConnectionState s = mConnections.get(connectionHandle);
        return s != null ? s.connection.getMtu() : ATT_DEFAULT_LE_MTU;
    }

    private static int readUint16LE(byte[] data, int offset) { return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8); }
    private static byte[] intToLeBytes(int value) { return new byte[]{(byte)(value & 0xFF), (byte)((value >> 8) & 0xFF), (byte)((value >> 16) & 0xFF), (byte)((value >> 24) & 0xFF)}; }
    private static UUID extractUuid128(byte[] data, int offset) { ByteBuffer buf = ByteBuffer.wrap(data, offset, 16).order(ByteOrder.LITTLE_ENDIAN); return new UUID(buf.getLong(8), buf.getLong(0)); }
    private static void putUuid128(ByteBuffer buf, UUID uuid) { buf.order(ByteOrder.LITTLE_ENDIAN).putLong(uuid.getLeastSignificantBits()).putLong(uuid.getMostSignificantBits()); }
}