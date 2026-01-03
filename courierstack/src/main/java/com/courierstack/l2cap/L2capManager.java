package com.courierstack.l2cap;

import android.content.Context;

import com.courierstack.util.CourierLogger;
import com.courierstack.hci.HciCommandManager;
import com.courierstack.hci.HciCommands;
import com.courierstack.hci.HciErrorCode;
import com.courierstack.hci.IHciCommandListener;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * L2CAP Protocol Layer Manager.
 *
 * <p>Provides connection-oriented and connectionless data services per
 * Bluetooth Core Spec v5.3, Vol 3, Part A.
 *
 * <p>Features:
 * <ul>
 *   <li>BR/EDR connection-oriented channels</li>
 *   <li>LE Credit-Based Connection (LE CoC)</li>
 *   <li>Fixed channel support (ATT, SMP, Signaling)</li>
 *   <li>L2CAP PDU fragmentation/reassembly</li>
 *   <li>Server (incoming connection) support</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * L2capManager l2cap = new L2capManager(context, listener);
 * if (l2cap.initialize()) {
 *     // Create connection
 *     l2cap.createConnection(address, callback);
 *
 *     // Open channel on existing connection
 *     l2cap.connectChannel(connection, PSM_RFCOMM, callback);
 *
 *     // Send data
 *     l2cap.sendData(channel, data);
 * }
 * l2cap.close();
 * }</pre>
 *
 * <p>Thread safety: This class is thread-safe. All public methods may be
 * called from any thread.
 */
public class L2capManager implements IHciCommandListener, Closeable {

    private static final String TAG = "L2capManager";
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_MS = 5000;

    /**
     * Raw HCI event listener interface.
     */
    public interface IRawEventListener {
        /**
         * Called for every raw HCI event received.
         *
         * @param event raw event data
         */
        void onRawEvent(byte[] event);
    }

    /**
     * Fixed channel data listener interface.
     */
    public interface IFixedChannelListener {
        /**
         * Called when data is received on a fixed channel.
         *
         * @param connectionHandle ACL connection handle
         * @param peerAddress      peer device address
         * @param peerAddressType  peer address type (for LE)
         * @param data             received data
         */
        void onFixedChannelData(int connectionHandle, byte[] peerAddress,
                                int peerAddressType, byte[] data);
    }

    // Context and dependencies
    private final Context mContext;
    private final HciCommandManager mHciManager;
    private final IL2capListener mListener;
    private final ExecutorService mExecutor;

    // Listener collections
    private final List<IL2capListener> mAdditionalListeners;
    private final List<IRawEventListener> mRawEventListeners;
    private final Map<Integer, List<IFixedChannelListener>> mFixedChannelListeners;

    // Connection and channel state
    private final Map<Integer, AclConnection> mAclConnections;
    private final Map<Integer, L2capChannel> mChannels;
    private final Map<Integer, IL2capServerListener> mServerListeners;
    private final Map<Integer, PendingConnection> mPendingConnections;
    private final Map<String, IL2capConnectionCallback> mPendingAclCallbacks;

    // ID allocators
    private final AtomicInteger mNextLocalCid;
    private final AtomicInteger mNextSignalingId;

    // State
    private final AtomicBoolean mInitialized;
    private final AtomicBoolean mClosed;

    // Whether we own (created) the HciCommandManager and should close it
    private final boolean mOwnsHciManager;

    // Buffer sizes (read from controller)
    private volatile int mAclBufferSize = L2capConstants.DEFAULT_MTU;
    private volatile int mAclBufferCount = 8;

    /**
     * Creates a new L2CAP manager with its own HciCommandManager.
     *
     * @param context  application context (must not be null)
     * @param listener primary event listener (must not be null)
     * @throws NullPointerException if context or listener is null
     */
    public L2capManager(Context context, IL2capListener listener) {
        this(context, listener, null);
    }

    /**
     * Creates a new L2CAP manager with an optional shared HciCommandManager.
     *
     * <p>If an existing HciCommandManager is provided, it will be used instead of
     * creating a new one. This allows sharing a single HAL connection across
     * multiple components. When using a shared manager, the caller is responsible
     * for ensuring it remains initialized for the lifetime of this L2capManager.
     *
     * @param context    application context (must not be null)
     * @param listener   primary event listener (must not be null)
     * @param hciManager existing HciCommandManager to use, or null to create a new one
     * @throws NullPointerException if context or listener is null
     */
    public L2capManager(Context context, IL2capListener listener, HciCommandManager hciManager) {
        mContext = Objects.requireNonNull(context, "context must not be null")
                .getApplicationContext();
        mListener = Objects.requireNonNull(listener, "listener must not be null");

        mExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "L2CAP-Worker");
            t.setDaemon(true);
            return t;
        });

        mAdditionalListeners = new CopyOnWriteArrayList<>();
        mRawEventListeners = new CopyOnWriteArrayList<>();
        mFixedChannelListeners = new ConcurrentHashMap<>();

        mAclConnections = new ConcurrentHashMap<>();
        mChannels = new ConcurrentHashMap<>();
        mServerListeners = new ConcurrentHashMap<>();
        mPendingConnections = new ConcurrentHashMap<>();
        mPendingAclCallbacks = new ConcurrentHashMap<>();

        mNextLocalCid = new AtomicInteger(L2capConstants.CID_DYNAMIC_START);
        mNextSignalingId = new AtomicInteger(1);

        mInitialized = new AtomicBoolean(false);
        mClosed = new AtomicBoolean(false);

        // Use provided HciCommandManager or create our own
        if (hciManager != null) {
            mHciManager = hciManager;
            mOwnsHciManager = false;
            // Register ourselves as a listener on the shared manager
            mHciManager.addListener(this);
        } else {
            mHciManager = new HciCommandManager(this);
            mOwnsHciManager = true;
        }
    }

    // ==================== Initialization ====================

    /**
     * Initializes the L2CAP layer.
     *
     * @return true if successful
     */
    public boolean initialize() {
        if (mClosed.get()) {
            mListener.onError("L2CAP manager is closed");
            return false;
        }

        if (mInitialized.get()) {
            return true;
        }

        // If using a shared HciCommandManager, check if it's already initialized
        if (!mOwnsHciManager) {
            if (!mHciManager.isInitialized()) {
                mListener.onError("Shared HCI manager is not initialized");
                return false;
            }
            // Shared manager is already initialized, we're good
        } else {
            // We own the HciCommandManager, initialize it
            if (!mHciManager.initialize()) {
                mListener.onError("HCI initialization failed");
                return false;
            }
        }

        mInitialized.set(true);
        mListener.onMessage("L2CAP initialized");

        // Read controller buffer sizes
        mExecutor.execute(this::readBufferSize);

        return true;
    }

    /**
     * Returns whether L2CAP is initialized.
     *
     * @return true if initialized
     */
    public boolean isInitialized() {
        return mInitialized.get() && !mClosed.get();
    }

    /**
     * Returns the underlying HCI manager.
     *
     * @return HCI command manager
     */
    public HciCommandManager getHciManager() {
        return mHciManager;
    }

    // ==================== Listener Management ====================

    /**
     * Adds an L2CAP event listener.
     *
     * @param listener listener to add
     */
    public void addListener(IL2capListener listener) {
        if (listener != null && !mAdditionalListeners.contains(listener)) {
            mAdditionalListeners.add(listener);
        }
    }

    /**
     * Removes an L2CAP event listener.
     *
     * @param listener listener to remove
     */
    public void removeListener(IL2capListener listener) {
        mAdditionalListeners.remove(listener);
    }

    /**
     * Adds a raw HCI event listener.
     *
     * @param listener listener to add
     */
    public void addRawEventListener(IRawEventListener listener) {
        if (listener != null && !mRawEventListeners.contains(listener)) {
            mRawEventListeners.add(listener);
        }
    }

    /**
     * Removes a raw HCI event listener.
     *
     * @param listener listener to remove
     */
    public void removeRawEventListener(IRawEventListener listener) {
        mRawEventListeners.remove(listener);
    }

    /**
     * Registers a listener for a fixed L2CAP channel.
     *
     * @param cid      fixed channel ID (e.g., CID_ATT, CID_SMP)
     * @param listener listener to receive data
     */
    public void registerFixedChannelListener(int cid, IFixedChannelListener listener) {
        if (listener == null) return;
        mFixedChannelListeners.computeIfAbsent(cid, k -> new CopyOnWriteArrayList<>())
                .add(listener);
        CourierLogger.d(TAG, String.format("Registered fixed channel listener for CID 0x%04X", cid));
    }

    /**
     * Unregisters a listener for a fixed L2CAP channel.
     *
     * @param cid      fixed channel ID
     * @param listener listener to remove
     */
    public void unregisterFixedChannelListener(int cid, IFixedChannelListener listener) {
        List<IFixedChannelListener> listeners = mFixedChannelListeners.get(cid);
        if (listeners != null) {
            listeners.remove(listener);
        }
    }

    /**
     * Registers a server listener for a PSM.
     *
     * @param psm      Protocol/Service Multiplexer
     * @param listener server listener
     */
    public void registerServer(int psm, IL2capServerListener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        mServerListeners.put(psm, listener);
        mListener.onMessage("Server registered on " + L2capConstants.getPsmName(psm));
    }

    /**
     * Unregisters a server listener.
     *
     * @param psm Protocol/Service Multiplexer
     */
    public void unregisterServer(int psm) {
        mServerListeners.remove(psm);
    }

    // ==================== Connection Management ====================

    /**
     * Creates a BR/EDR ACL connection.
     *
     * @param bdAddr   6-byte Bluetooth address
     * @param callback connection result callback
     */
    public void createConnection(byte[] bdAddr, IL2capConnectionCallback callback) {
        Objects.requireNonNull(bdAddr, "bdAddr must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        if (!checkInitialized(callback)) return;

        // Check for existing connection
        AclConnection existing = findConnectionByAddress(bdAddr);
        if (existing != null) {
            callback.onSuccess(new L2capChannel(0, 0, existing));
            return;
        }

        String addrKey = formatAddress(bdAddr);
        mPendingAclCallbacks.put(addrKey, callback);
        CourierLogger.d(TAG, "Creating BR/EDR connection to " + addrKey);

        mExecutor.execute(() -> {
            byte[] cmd = HciCommands.createConnection(
                    bdAddr,
                    0xCC18,  // Packet types: DM1, DH1, DM3, DH3, DM5, DH5
                    0x02,    // Page scan repetition mode R2
                    0x00,    // Reserved
                    0x0000,  // Clock offset
                    0x01     // Allow role switch
            );
            mHciManager.sendCommand(cmd);
        });
    }

    /**
     * Creates an LE ACL connection.
     *
     * @param peerAddr     6-byte peer address
     * @param peerAddrType address type (0=public, 1=random)
     * @param callback     connection result callback
     */
    public void createLeConnection(byte[] peerAddr, int peerAddrType,
                                   IL2capConnectionCallback callback) {
        Objects.requireNonNull(peerAddr, "peerAddr must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        if (!checkInitialized(callback)) return;

        String addrKey = formatAddress(peerAddr);
        mPendingAclCallbacks.put(addrKey, callback);
        CourierLogger.d(TAG, "Creating LE connection to " + addrKey +
                " (type=" + peerAddrType + ")");

        mExecutor.execute(() -> {
            byte[] cmd = HciCommands.leCreateConnection(
                    0x0060,      // Scan interval (60ms)
                    0x0030,      // Scan window (30ms)
                    0x00,        // Filter policy: use peer address
                    peerAddrType,
                    peerAddr,
                    0x00,        // Own address type: public
                    0x0018,      // Min connection interval (30ms)
                    0x0028,      // Max connection interval (50ms)
                    0x0000,      // Slave latency
                    0x01F4,      // Supervision timeout (5s)
                    0x0000,      // Min CE length
                    0x0000       // Max CE length
            );
            mHciManager.sendCommand(cmd);
        });
    }

    /**
     * Cancels an ongoing LE connection attempt.
     */
    public void cancelLeConnection() {
        mHciManager.sendCommand(HciCommands.leCreateConnectionCancel());
    }

    /**
     * Disconnects an ACL connection.
     *
     * @param handle connection handle
     * @param reason HCI reason code (e.g., 0x13 = Remote User Terminated)
     */
    public void disconnect(int handle, int reason) {
        mHciManager.sendCommand(HciCommands.disconnect(handle, reason));
    }

    /**
     * Opens an L2CAP channel on an existing ACL connection.
     *
     * @param connection ACL connection
     * @param psm        Protocol/Service Multiplexer
     * @param callback   channel result callback
     */
    public void connectChannel(AclConnection connection, int psm,
                               IL2capConnectionCallback callback) {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        if (!checkInitialized(callback)) return;

        int localCid = allocateLocalCid();
        int identifier = allocateSignalingId();

        CourierLogger.d(TAG, "connectChannel: PSM=0x" + Integer.toHexString(psm) +
                " localCid=0x" + Integer.toHexString(localCid) +
                " identifier=" + identifier + " handle=0x" + Integer.toHexString(connection.handle));

        L2capChannel channel = new L2capChannel(localCid, psm, connection);
        channel.setState(ChannelState.WAIT_CONNECT_RSP);
        mChannels.put(localCid, channel);

        mPendingConnections.put(identifier, new PendingConnection(
                identifier, psm, localCid, connection.handle, callback));

        // Build Connection Request
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) L2capConstants.CMD_CONNECTION_REQUEST);
        buf.put((byte) identifier);
        buf.putShort((short) 4);  // Length
        buf.putShort((short) psm);
        buf.putShort((short) localCid);

        sendL2capData(connection.handle, L2capConstants.CID_SIGNALING, buf.array());
        CourierLogger.d(TAG, "Sent Connection Request for PSM=0x" + Integer.toHexString(psm));
    }

    /**
     * Opens an LE Credit-Based Connection (LE CoC) channel.
     *
     * @param connection     ACL connection
     * @param psm            LE PSM (Simplified PSM)
     * @param mtu            local MTU
     * @param mps            local MPS
     * @param initialCredits initial credits to offer
     * @param callback       channel result callback
     */
    public void connectLeCocChannel(AclConnection connection, int psm, int mtu, int mps,
                                    int initialCredits, IL2capConnectionCallback callback) {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        if (!checkInitialized(callback)) return;

        int localCid = allocateLocalCid();
        int identifier = allocateSignalingId();

        L2capChannel channel = new L2capChannel(localCid, psm, connection);
        channel.setState(ChannelState.WAIT_CONNECT_RSP);
        channel.setCreditBased(true);
        channel.setMtu(mtu);
        channel.setMps(mps);
        channel.setLocalCredits(initialCredits);
        mChannels.put(localCid, channel);

        mPendingConnections.put(identifier, new PendingConnection(
                identifier, psm, localCid, connection.handle, callback));

        // Build LE Credit Based Connection Request
        ByteBuffer buf = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) L2capConstants.CMD_LE_CREDIT_BASED_CONNECTION_REQUEST);
        buf.put((byte) identifier);
        buf.putShort((short) 10); // Length
        buf.putShort((short) psm);
        buf.putShort((short) localCid);
        buf.putShort((short) mtu);
        buf.putShort((short) mps);
        buf.putShort((short) initialCredits);

        sendL2capData(connection.handle, L2capConstants.CID_LE_SIGNALING, buf.array());
    }

    /**
     * Sends data on an L2CAP channel.
     *
     * @param channel the channel
     * @param data    data to send
     */
    public void sendData(L2capChannel channel, byte[] data) {
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(data, "data must not be null");

        if (!channel.isOpen()) {
            mListener.onError("Channel not open");
            return;
        }

        if (channel.isCreditBased()) {
            // For LE CoC, check credits
            if (!channel.consumeRemoteCredit()) {
                mListener.onError("No credits available");
                return;
            }
        }

        sendL2capData(channel.connection.handle, channel.getRemoteCid(), data);
    }

    /**
     * Sends data on a fixed L2CAP channel.
     *
     * @param connectionHandle ACL connection handle
     * @param cid              fixed channel ID
     * @param data             data to send
     */
    public void sendFixedChannelData(int connectionHandle, int cid, byte[] data) {
        AclConnection conn = mAclConnections.get(connectionHandle);
        if (conn == null) {
            mListener.onError("No ACL for handle 0x" + Integer.toHexString(connectionHandle));
            return;
        }
        sendL2capData(connectionHandle, cid, data);
    }

    /**
     * Closes an L2CAP channel.
     *
     * @param channel the channel to close
     */
    public void closeChannel(L2capChannel channel) {
        Objects.requireNonNull(channel, "channel must not be null");

        if (channel.getState() == ChannelState.CLOSED) {
            return;
        }

        int identifier = allocateSignalingId();
        channel.setState(ChannelState.WAIT_DISCONNECT);

        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) L2capConstants.CMD_DISCONNECTION_REQUEST);
        buf.put((byte) identifier);
        buf.putShort((short) 4);
        buf.putShort((short) channel.getRemoteCid());
        buf.putShort((short) channel.localCid);

        int sigCid = channel.connection.type == ConnectionType.LE
                ? L2capConstants.CID_LE_SIGNALING
                : L2capConstants.CID_SIGNALING;
        sendL2capData(channel.connection.handle, sigCid, buf.array());
    }

    /**
     * Sends flow control credits to the peer (LE CoC).
     *
     * @param channel the credit-based channel
     * @param credits number of credits to send
     */
    public void sendCredits(L2capChannel channel, int credits) {
        Objects.requireNonNull(channel, "channel must not be null");

        if (!channel.isCreditBased()) {
            mListener.onError("Not a credit-based channel");
            return;
        }

        channel.addLocalCredits(credits);

        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) L2capConstants.CMD_FLOW_CONTROL_CREDIT);
        buf.put((byte) allocateSignalingId());
        buf.putShort((short) 4);
        buf.putShort((short) channel.localCid);
        buf.putShort((short) credits);

        sendL2capData(channel.connection.handle, L2capConstants.CID_LE_SIGNALING, buf.array());
    }

    // ==================== Query Methods ====================

    /**
     * Finds a connection by peer address.
     *
     * @param bdAddr 6-byte address
     * @return connection or null
     */
    public AclConnection findConnectionByAddress(byte[] bdAddr) {
        if (bdAddr == null || bdAddr.length != 6) return null;
        for (AclConnection conn : mAclConnections.values()) {
            if (conn.matchesAddress(bdAddr)) {
                return conn;
            }
        }
        return null;
    }

    /**
     * Returns all active connections.
     *
     * @return unmodifiable map of connections by handle
     */
    public Map<Integer, AclConnection> getConnections() {
        return Collections.unmodifiableMap(mAclConnections);
    }

    /**
     * Returns a connection by handle.
     *
     * @param handle connection handle
     * @return connection or null
     */
    public AclConnection getConnection(int handle) {
        return mAclConnections.get(handle);
    }

    /**
     * Returns all open channels.
     *
     * @return unmodifiable map of channels by local CID
     */
    public Map<Integer, L2capChannel> getChannels() {
        return Collections.unmodifiableMap(mChannels);
    }

    // ==================== Shutdown ====================

    /**
     * Shuts down the L2CAP manager.
     *
     * @deprecated Use {@link #close()} instead
     */
    @Deprecated
    public void shutdown() {
        close();
    }

    @Override
    public void close() {
        if (!mClosed.compareAndSet(false, true)) {
            return;
        }

        mInitialized.set(false);

        // Close all channels
        for (L2capChannel channel : mChannels.values()) {
            channel.setState(ChannelState.CLOSED);
        }
        mChannels.clear();
        mAclConnections.clear();
        mPendingConnections.clear();
        mPendingAclCallbacks.clear();
        mServerListeners.clear();
        mFixedChannelListeners.clear();

        // Only close HCI if we own it; otherwise just remove our listener
        if (mOwnsHciManager) {
            mHciManager.close();
        } else {
            mHciManager.removeListener(this);
        }

        // Shutdown executor
        mExecutor.shutdown();
        try {
            if (!mExecutor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                mExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            mExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        mListener.onMessage("L2CAP shutdown");
    }

    // ==================== Internal: HCI Callbacks ====================

    @Override
    public void onEvent(byte[] event) {
        // Forward to raw listeners
        for (IRawEventListener l : mRawEventListeners) {
            try {
                l.onRawEvent(event);
            } catch (Exception e) {
                CourierLogger.e(TAG, "Raw event listener error", e);
            }
        }

        if (event == null || event.length < 2) return;

        int eventCode = event[0] & 0xFF;
        switch (eventCode) {
            case 0x03: // Connection Complete
                handleConnectionComplete(event);
                break;
            case 0x05: // Disconnection Complete
                handleDisconnectionComplete(event);
                break;
            case 0x3E: // LE Meta Event
                handleLeMetaEvent(event);
                break;
        }
    }

    @Override
    public void onAclData(byte[] data) {
        if (data == null || data.length < 8) return;

        // Parse ACL header
        int handleAndFlags = ((data[1] & 0xFF) << 8) | (data[0] & 0xFF);
        int handle = handleAndFlags & 0x0FFF;
        int pbFlag = (handleAndFlags >> 12) & 0x03;
        int bcFlag = (handleAndFlags >> 14) & 0x03;
        int totalLength = ((data[3] & 0xFF) << 8) | (data[2] & 0xFF);

        AclConnection conn = mAclConnections.get(handle);
        if (conn == null) {
            CourierLogger.w(TAG, "ACL data for unknown handle 0x" + Integer.toHexString(handle));
            return;
        }

        // Handle fragmentation
        if (pbFlag == L2capConstants.PB_FIRST_FLUSH || pbFlag == L2capConstants.PB_FIRST_NON_FLUSH) {
            // First packet of PDU
            if (data.length < 8) return;

            int l2capLength = ((data[5] & 0xFF) << 8) | (data[4] & 0xFF);
            int cid = ((data[7] & 0xFF) << 8) | (data[6] & 0xFF);

            int expectedTotal = l2capLength + 4; // L2CAP header is 4 bytes
            if (totalLength >= expectedTotal) {
                // Complete PDU
                byte[] payload = new byte[l2capLength];
                System.arraycopy(data, 8, payload, 0, Math.min(l2capLength, data.length - 8));
                handleL2capPdu(conn, cid, payload);
            } else {
                // Start reassembly
                conn.startReassembly(expectedTotal);
                conn.addReassemblyData(java.util.Arrays.copyOfRange(data, 4, data.length));
            }
        } else if (pbFlag == L2capConstants.PB_CONTINUING) {
            // Continuation packet
            if (conn.isReassembling()) {
                conn.addReassemblyData(java.util.Arrays.copyOfRange(data, 4, data.length));
                if (conn.isReassemblyComplete()) {
                    byte[] pdu = conn.getReassembledData();
                    if (pdu != null && pdu.length >= 4) {
                        int l2capLength = ((pdu[1] & 0xFF) << 8) | (pdu[0] & 0xFF);
                        int cid = ((pdu[3] & 0xFF) << 8) | (pdu[2] & 0xFF);
                        byte[] payload = new byte[Math.min(l2capLength, pdu.length - 4)];
                        System.arraycopy(pdu, 4, payload, 0, payload.length);
                        handleL2capPdu(conn, cid, payload);
                    }
                }
            }
        }
    }

    @Override
    public void onScoData(byte[] data) {
        // SCO data not handled at L2CAP layer
    }

    @Override
    public void onIsoData(byte[] data) {
        // ISO data not handled at L2CAP layer
    }

    @Override
    public void onError(String message) {
        mListener.onError(message);
    }

    @Override
    public void onMessage(String message) {
        mListener.onMessage(message);
    }

    // ==================== Internal: Event Handlers ====================

    private void handleConnectionComplete(byte[] event) {
        if (event.length < 11) return;

        int status = event[2] & 0xFF;
        int handle = ((event[4] & 0xFF) << 8) | (event[3] & 0xFF);
        byte[] addr = new byte[6];
        System.arraycopy(event, 5, addr, 0, 6);
        String addrStr = formatAddress(addr);

        IL2capConnectionCallback callback = mPendingAclCallbacks.remove(addrStr);

        if (status == HciErrorCode.SUCCESS) {
            AclConnection conn = new AclConnection(handle, addr, ConnectionType.BR_EDR, true);
            mAclConnections.put(handle, conn);

            mListener.onConnectionComplete(conn);
            for (IL2capListener l : mAdditionalListeners) {
                l.onConnectionComplete(conn);
            }

            if (callback != null) {
                callback.onSuccess(new L2capChannel(0, 0, conn));
            }
        } else {
            String reason = "Connection failed: " + HciErrorCode.getDescription(status);
            CourierLogger.w(TAG, reason);
            if (callback != null) {
                callback.onFailure(reason);
            }
        }
    }

    private void handleDisconnectionComplete(byte[] event) {
        if (event.length < 6) return;

        int status = event[2] & 0xFF;
        int handle = ((event[4] & 0xFF) << 8) | (event[3] & 0xFF);
        int reason = event[5] & 0xFF;

        if (status == HciErrorCode.SUCCESS) {
            AclConnection conn = mAclConnections.remove(handle);

            // Close all channels on this connection
            mChannels.values().removeIf(ch -> {
                if (ch.connection.handle == handle) {
                    ch.setState(ChannelState.CLOSED);
                    mListener.onChannelClosed(ch);
                    return true;
                }
                return false;
            });

            mListener.onDisconnectionComplete(handle, reason);
            for (IL2capListener l : mAdditionalListeners) {
                l.onDisconnectionComplete(handle, reason);
            }
        }
    }

    private void handleLeMetaEvent(byte[] event) {
        if (event.length < 3) return;

        int subEvent = event[2] & 0xFF;

        if (subEvent == 0x01 && event.length >= 21) {
            // LE Connection Complete
            int status = event[3] & 0xFF;
            int handle = ((event[5] & 0xFF) << 8) | (event[4] & 0xFF);
            int addrType = event[7] & 0xFF;
            byte[] addr = new byte[6];
            System.arraycopy(event, 8, addr, 0, 6);
            String addrStr = formatAddress(addr);

            IL2capConnectionCallback callback = mPendingAclCallbacks.remove(addrStr);

            if (status == HciErrorCode.SUCCESS) {
                AclConnection conn = new AclConnection(handle, addr, addrType,
                        ConnectionType.LE, true);
                mAclConnections.put(handle, conn);

                mListener.onConnectionComplete(conn);
                for (IL2capListener l : mAdditionalListeners) {
                    l.onConnectionComplete(conn);
                }

                if (callback != null) {
                    callback.onSuccess(new L2capChannel(0, 0, conn));
                }
            } else {
                String reason = "LE connection failed: " + HciErrorCode.getDescription(status);
                CourierLogger.w(TAG, reason);
                if (callback != null) {
                    callback.onFailure(reason);
                }
            }
        } else if (subEvent == 0x0A && event.length >= 21) {
            // LE Enhanced Connection Complete (BT 5.0+)
            handleLeMetaEvent(event); // Same handling
        }
    }

    // ==================== Internal: L2CAP PDU Handling ====================

    private void handleL2capPdu(AclConnection conn, int cid, byte[] payload) {
        // Handle signaling channels
        if (cid == L2capConstants.CID_SIGNALING || cid == L2capConstants.CID_LE_SIGNALING) {
            handleSignaling(conn, cid, payload);
            return;
        }

        // Handle fixed channels
        List<IFixedChannelListener> fixedListeners = mFixedChannelListeners.get(cid);
        if (fixedListeners != null && !fixedListeners.isEmpty()) {
            for (IFixedChannelListener listener : fixedListeners) {
                try {
                    listener.onFixedChannelData(conn.handle, conn.getPeerAddress(),
                            conn.peerAddressType, payload);
                } catch (Exception e) {
                    CourierLogger.e(TAG, "Fixed channel listener error", e);
                }
            }
            return;
        }

        // Handle dynamic channels
        L2capChannel channel = mChannels.get(cid);
        if (channel != null && channel.isOpen()) {
            // For credit-based channels, decrement local credits
            if (channel.isCreditBased()) {
                int remaining = channel.addLocalCredits(-1);
                if (remaining < L2capConstants.DEFAULT_LE_CREDITS / 2) {
                    // Auto-send more credits
                    sendCredits(channel, L2capConstants.DEFAULT_LE_CREDITS);
                }
            }

            mListener.onDataReceived(channel, payload);
            for (IL2capListener l : mAdditionalListeners) {
                l.onDataReceived(channel, payload);
            }

            // Also notify server listener if applicable
            IL2capServerListener server = mServerListeners.get(channel.psm);
            if (server != null) {
                server.onDataReceived(channel, payload);
            }
        } else if (L2capConstants.isFixedChannel(cid)) {
            CourierLogger.w(TAG, String.format("No listener for fixed CID 0x%04X", cid));
        }
    }

    private void handleSignaling(AclConnection conn, int sigCid, byte[] data) {
        if (data.length < 4) return;

        int code = data[0] & 0xFF;
        int id = data[1] & 0xFF;
        int len = ((data[3] & 0xFF) << 8) | (data[2] & 0xFF);
        byte[] payload = new byte[Math.min(len, data.length - 4)];
        if (data.length > 4) {
            System.arraycopy(data, 4, payload, 0, payload.length);
        }

        CourierLogger.d(TAG, "Signaling: " + L2capConstants.getCommandName(code) +
                " id=" + id + " len=" + len);

        switch (code) {
            case L2capConstants.CMD_CONNECTION_REQUEST:
                handleConnReq(conn, id, payload);
                break;
            case L2capConstants.CMD_CONNECTION_RESPONSE:
                handleConnRsp(conn, id, payload);
                break;
            case L2capConstants.CMD_CONFIGURATION_REQUEST:
                handleConfigReq(conn, id, payload);
                break;
            case L2capConstants.CMD_CONFIGURATION_RESPONSE:
                handleConfigRsp(conn, id, payload);
                break;
            case L2capConstants.CMD_DISCONNECTION_REQUEST:
                handleDiscReq(conn, id, payload);
                break;
            case L2capConstants.CMD_DISCONNECTION_RESPONSE:
                handleDiscRsp(conn, id, payload);
                break;
            case L2capConstants.CMD_LE_CREDIT_BASED_CONNECTION_REQUEST:
                handleLeCocConnReq(conn, id, payload);
                break;
            case L2capConstants.CMD_LE_CREDIT_BASED_CONNECTION_RESPONSE:
                handleLeCocConnRsp(conn, id, payload);
                break;
            case L2capConstants.CMD_FLOW_CONTROL_CREDIT:
                handleFlowControlCredit(conn, payload);
                break;
            case L2capConstants.CMD_ECHO_REQUEST:
                handleEchoRequest(conn, id, payload);
                break;
            case L2capConstants.CMD_INFORMATION_REQUEST:
                handleInfoRequest(conn, id, payload);
                break;
            case L2capConstants.CMD_INFORMATION_RESPONSE:
                // We don't send Info Requests, so just log if we receive a response
                CourierLogger.d(TAG, "Received unsolicited Information Response");
                break;
            default:
                CourierLogger.d(TAG, "Unhandled signaling command: 0x" +
                        Integer.toHexString(code));
                break;
        }
    }

    // [Continued in next part due to length...]

    // ==================== Internal: Signaling Handlers ====================

    private void handleConnReq(AclConnection conn, int id, byte[] data) {
        if (data.length < 4) return;

        int psm = ((data[1] & 0xFF) << 8) | (data[0] & 0xFF);
        int srcCid = ((data[3] & 0xFF) << 8) | (data[2] & 0xFF);

        CourierLogger.d(TAG, "Connection Request PSM=" + psm +
                " (0x" + Integer.toHexString(psm) + ") srcCid=0x" + Integer.toHexString(srcCid));

        mListener.onConnectionRequest(conn.handle, psm, srcCid);

        IL2capServerListener server = mServerListeners.get(psm);
        int result = L2capConstants.CR_PSM_NOT_SUPPORTED;
        int status = L2capConstants.CS_NO_FURTHER_INFO;
        int localCid = 0;

        if (server != null) {
            // We have a registered server for this PSM
            localCid = allocateLocalCid();
            L2capChannel channel = new L2capChannel(localCid, psm, conn);
            channel.setRemoteCid(srcCid);
            channel.setState(ChannelState.WAIT_CONNECT);

            if (server.onConnectionRequest(channel)) {
                channel.setState(ChannelState.CONFIG);
                mChannels.put(localCid, channel);
                result = L2capConstants.CR_SUCCESS;
            }
        } else if (psm == L2capConstants.PSM_SDP) {
            // ========== SDP SPECIAL HANDLING ==========
            // For SDP requests during pairing, respond with PENDING + Authentication Pending
            // instead of outright rejection. This prevents some devices from aborting pairing.
            //
            // Many Bluetooth devices send an SDP query during the pairing process to discover
            // services. If we reject this immediately, some devices abort the pairing entirely
            // with error 0x16 (Connection Terminated By Local Host).
            //
            // By responding with PENDING, we give the pairing process time to complete.
            // The remote device will either:
            // a) Retry after pairing completes (if SDP server is started)
            // b) Time out gracefully without affecting pairing
            //
            // For best results, start SdpManager.startServer() during initialization.
            // ==========================================
            localCid = allocateLocalCid();
            result = L2capConstants.CR_PENDING;
            status = L2capConstants.CS_AUTHENTICATION_PENDING;
            CourierLogger.d(TAG, "SDP connection request during pairing - responding with PENDING");
        }

        // Send Connection Response
        ByteBuffer rsp = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        rsp.put((byte) L2capConstants.CMD_CONNECTION_RESPONSE);
        rsp.put((byte) id);
        rsp.putShort((short) 8);
        rsp.putShort((short) localCid);
        rsp.putShort((short) srcCid);
        rsp.putShort((short) result);
        rsp.putShort((short) status);
        sendL2capData(conn.handle, L2capConstants.CID_SIGNALING, rsp.array());
    }

    private void handleConnRsp(AclConnection conn, int id, byte[] data) {
        if (data.length < 8) return;

        int destCid = ((data[1] & 0xFF) << 8) | (data[0] & 0xFF);
        int srcCid = ((data[3] & 0xFF) << 8) | (data[2] & 0xFF);
        int result = ((data[5] & 0xFF) << 8) | (data[4] & 0xFF);

        CourierLogger.d(TAG, "Connection Response: id=" + id + " destCid=0x" +
                Integer.toHexString(destCid) + " srcCid=0x" + Integer.toHexString(srcCid) +
                " result=" + L2capConstants.getConnectionResultName(result));

        PendingConnection pending = mPendingConnections.get(id);

        // If not found by id, try to find by localCid (srcCid in response)
        // Some devices send follow-up SUCCESS with different id after PENDING
        if (pending == null) {
            for (PendingConnection p : mPendingConnections.values()) {
                if (p.localCid == srcCid && p.handle == conn.handle) {
                    pending = p;
                    CourierLogger.d(TAG, "Connection Response: Found pending by localCid=0x" +
                            Integer.toHexString(srcCid) + " (id mismatch: expected " + p.identifier + ")");
                    break;
                }
            }
        }

        if (pending == null) {
            CourierLogger.w(TAG, "Connection Response: No pending connection for id=" + id +
                    " or srcCid=0x" + Integer.toHexString(srcCid));
            return;
        }

        L2capChannel channel = mChannels.get(pending.localCid);
        if (channel == null) {
            CourierLogger.w(TAG, "Connection Response: No channel for localCid=0x" +
                    Integer.toHexString(pending.localCid));
            mPendingConnections.remove(pending.identifier);
            return;
        }

        CourierLogger.d(TAG, "Connection Response: PSM=0x" + Integer.toHexString(pending.psm) +
                " localCid=0x" + Integer.toHexString(pending.localCid));

        if (result == L2capConstants.CR_SUCCESS) {
            channel.setRemoteCid(destCid);
            channel.setState(ChannelState.CONFIG);
            CourierLogger.d(TAG, "Connection successful, sending config request");
            sendConfigReq(channel);
        } else if (result == L2capConstants.CR_PENDING) {
            // Keep waiting - already in map
            CourierLogger.d(TAG, "Connection pending, waiting...");
        } else {
            mPendingConnections.remove(pending.identifier);
            mChannels.remove(channel.localCid);
            CourierLogger.w(TAG, "Connection refused: " +
                    L2capConstants.getConnectionResultName(result));
            if (pending.callback != null) {
                pending.callback.onFailure("Connection refused: " +
                        L2capConstants.getConnectionResultName(result));
            }
        }
    }

    private void sendConfigReq(L2capChannel channel) {
        int id = allocateSignalingId();
        ByteBuffer req = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        req.put((byte) L2capConstants.CMD_CONFIGURATION_REQUEST);
        req.put((byte) id);
        req.putShort((short) 8);
        req.putShort((short) channel.getRemoteCid());
        req.putShort((short) 0); // Flags
        req.put((byte) L2capConstants.CONF_OPT_MTU);
        req.put((byte) 2);
        req.putShort((short) channel.getMtu());
        sendL2capData(channel.connection.handle, L2capConstants.CID_SIGNALING, req.array());
    }

    private void handleConfigReq(AclConnection conn, int id, byte[] data) {
        if (data.length < 4) return;

        int destCid = ((data[1] & 0xFF) << 8) | (data[0] & 0xFF);
        CourierLogger.d(TAG, "Configuration Request: id=" + id + " destCid=0x" +
                Integer.toHexString(destCid));

        L2capChannel channel = mChannels.get(destCid);
        if (channel == null) {
            CourierLogger.w(TAG, "Configuration Request: No channel for destCid=0x" +
                    Integer.toHexString(destCid));
            return;
        }

        // Parse options
        int offset = 4;
        while (offset + 2 <= data.length) {
            int optType = data[offset] & 0x7F;
            int optLen = data[offset + 1] & 0xFF;
            if (offset + 2 + optLen > data.length) break;

            if (optType == L2capConstants.CONF_OPT_MTU && optLen >= 2) {
                int peerMtu = ((data[offset + 3] & 0xFF) << 8) | (data[offset + 2] & 0xFF);
                channel.setPeerMtu(peerMtu);
            }
            offset += 2 + optLen;
        }

        channel.setRemoteConfigDone(true);
        CourierLogger.d(TAG, "Remote config done for localCid=0x" + Integer.toHexString(destCid));

        // Send Config Response
        ByteBuffer rsp = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
        rsp.put((byte) L2capConstants.CMD_CONFIGURATION_RESPONSE);
        rsp.put((byte) id);
        rsp.putShort((short) 6);
        rsp.putShort((short) channel.getRemoteCid());
        rsp.putShort((short) 0); // Flags
        rsp.putShort((short) L2capConstants.CONF_SUCCESS);
        sendL2capData(conn.handle, L2capConstants.CID_SIGNALING, rsp.array());

        checkChannelOpen(channel);
    }

    private void handleConfigRsp(AclConnection conn, int id, byte[] data) {
        if (data.length < 6) return;

        int srcCid = ((data[1] & 0xFF) << 8) | (data[0] & 0xFF);
        int result = ((data[5] & 0xFF) << 8) | (data[4] & 0xFF);

        CourierLogger.d(TAG, "Configuration Response: id=" + id + " srcCid=0x" +
                Integer.toHexString(srcCid) + " result=" + result);

        L2capChannel channel = findChannelByRemoteCid(conn, srcCid);
        if (channel == null) {
            // Try finding by local CID (some implementations send our localCid here)
            channel = mChannels.get(srcCid);
            if (channel != null && channel.connection.handle == conn.handle) {
                CourierLogger.d(TAG, "Configuration Response: Found channel by localCid=0x" +
                        Integer.toHexString(srcCid));
            } else {
                CourierLogger.w(TAG, "Configuration Response: No channel for srcCid=0x" +
                        Integer.toHexString(srcCid));
                return;
            }
        }

        if (result == L2capConstants.CONF_SUCCESS) {
            channel.setLocalConfigDone(true);
            CourierLogger.d(TAG, "Local config done for localCid=0x" +
                    Integer.toHexString(channel.localCid));
            checkChannelOpen(channel);
        } else {
            CourierLogger.w(TAG, "Config rejected: " + result);
        }
    }

    private void checkChannelOpen(L2capChannel channel) {
        CourierLogger.d(TAG, "checkChannelOpen: localCid=0x" + Integer.toHexString(channel.localCid) +
                " state=" + channel.getState() +
                " localConfigDone=" + channel.isLocalConfigDone() +
                " remoteConfigDone=" + channel.isRemoteConfigDone());

        if (channel.isConfigComplete() && channel.getState() == ChannelState.CONFIG) {
            channel.setState(ChannelState.OPEN);
            CourierLogger.d(TAG, "Channel opened: localCid=0x" + Integer.toHexString(channel.localCid) +
                    " psm=0x" + Integer.toHexString(channel.psm));

            mListener.onChannelOpened(channel);
            for (IL2capListener l : mAdditionalListeners) {
                l.onChannelOpened(channel);
            }

            // Notify server listener
            IL2capServerListener server = mServerListeners.get(channel.psm);
            if (server != null) {
                server.onChannelOpened(channel);
            }

            // Complete pending connection callback
            boolean foundPending = false;
            for (PendingConnection p : mPendingConnections.values()) {
                if (p.localCid == channel.localCid && p.callback != null) {
                    CourierLogger.d(TAG, "Found pending connection, calling onSuccess for localCid=0x" +
                            Integer.toHexString(channel.localCid));
                    mPendingConnections.remove(p.identifier);
                    p.callback.onSuccess(channel);
                    foundPending = true;
                    break;
                }
            }
            if (!foundPending) {
                CourierLogger.d(TAG, "No pending connection found for localCid=0x" +
                        Integer.toHexString(channel.localCid) +
                        " (pendingConnections size=" + mPendingConnections.size() + ")");
            }
        }
    }

    private void handleDiscReq(AclConnection conn, int id, byte[] data) {
        if (data.length < 4) return;

        int destCid = ((data[1] & 0xFF) << 8) | (data[0] & 0xFF);
        int srcCid = ((data[3] & 0xFF) << 8) | (data[2] & 0xFF);

        L2capChannel channel = mChannels.remove(destCid);

        // Send Disconnection Response
        ByteBuffer rsp = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        rsp.put((byte) L2capConstants.CMD_DISCONNECTION_RESPONSE);
        rsp.put((byte) id);
        rsp.putShort((short) 4);
        rsp.putShort((short) destCid);
        rsp.putShort((short) srcCid);
        sendL2capData(conn.handle, L2capConstants.CID_SIGNALING, rsp.array());

        if (channel != null) {
            channel.setState(ChannelState.CLOSED);
            mListener.onChannelClosed(channel);
            for (IL2capListener l : mAdditionalListeners) {
                l.onChannelClosed(channel);
            }

            IL2capServerListener server = mServerListeners.get(channel.psm);
            if (server != null) {
                server.onChannelClosed(channel);
            }
        }
    }

    private void handleDiscRsp(AclConnection conn, int id, byte[] data) {
        if (data.length < 4) return;

        int destCid = ((data[1] & 0xFF) << 8) | (data[0] & 0xFF);
        L2capChannel channel = mChannels.remove(destCid);

        if (channel != null) {
            channel.setState(ChannelState.CLOSED);
            mListener.onChannelClosed(channel);
            for (IL2capListener l : mAdditionalListeners) {
                l.onChannelClosed(channel);
            }
        }
    }

    private void handleLeCocConnReq(AclConnection conn, int id, byte[] data) {
        if (data.length < 10) return;

        int psm = ((data[1] & 0xFF) << 8) | (data[0] & 0xFF);
        int srcCid = ((data[3] & 0xFF) << 8) | (data[2] & 0xFF);
        int mtu = ((data[5] & 0xFF) << 8) | (data[4] & 0xFF);
        int mps = ((data[7] & 0xFF) << 8) | (data[6] & 0xFF);
        int credits = ((data[9] & 0xFF) << 8) | (data[8] & 0xFF);

        IL2capServerListener server = mServerListeners.get(psm);
        int result = L2capConstants.LE_CR_SPSM_NOT_SUPPORTED;
        int localCid = 0;
        int localMtu = L2capConstants.DEFAULT_MTU;
        int localMps = L2capConstants.DEFAULT_LE_MPS;
        int localCredits = L2capConstants.DEFAULT_LE_CREDITS;

        if (server != null) {
            localCid = allocateLocalCid();
            L2capChannel channel = new L2capChannel(localCid, psm, conn);
            channel.setRemoteCid(srcCid);
            channel.setCreditBased(true);
            channel.setPeerMtu(mtu);
            channel.setMps(mps);
            channel.setRemoteCredits(credits);
            channel.setState(ChannelState.WAIT_CONNECT);

            if (server.onConnectionRequest(channel)) {
                channel.setState(ChannelState.OPEN);
                mChannels.put(localCid, channel);
                result = L2capConstants.LE_CR_SUCCESS;

                localMtu = channel.getMtu();
                localMps = channel.getMps();
                localCredits = channel.getLocalCredits();
            }
        }

        // Send LE Credit Based Connection Response
        ByteBuffer rsp = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN);
        rsp.put((byte) L2capConstants.CMD_LE_CREDIT_BASED_CONNECTION_RESPONSE);
        rsp.put((byte) id);
        rsp.putShort((short) 10);
        rsp.putShort((short) localCid);
        rsp.putShort((short) localMtu);
        rsp.putShort((short) localMps);
        rsp.putShort((short) localCredits);
        rsp.putShort((short) result);
        sendL2capData(conn.handle, L2capConstants.CID_LE_SIGNALING, rsp.array());
    }

    private void handleLeCocConnRsp(AclConnection conn, int id, byte[] data) {
        if (data.length < 10) return;

        int destCid = ((data[1] & 0xFF) << 8) | (data[0] & 0xFF);
        int mtu = ((data[3] & 0xFF) << 8) | (data[2] & 0xFF);
        int mps = ((data[5] & 0xFF) << 8) | (data[4] & 0xFF);
        int credits = ((data[7] & 0xFF) << 8) | (data[6] & 0xFF);
        int result = ((data[9] & 0xFF) << 8) | (data[8] & 0xFF);

        PendingConnection pending = mPendingConnections.remove(id);
        if (pending == null) return;

        L2capChannel channel = mChannels.get(pending.localCid);
        if (channel == null) return;

        if (result == L2capConstants.LE_CR_SUCCESS) {
            channel.setRemoteCid(destCid);
            channel.setPeerMtu(mtu);
            channel.setMps(mps);
            channel.setRemoteCredits(credits);
            channel.setState(ChannelState.OPEN);

            mListener.onChannelOpened(channel);
            for (IL2capListener l : mAdditionalListeners) {
                l.onChannelOpened(channel);
            }

            if (pending.callback != null) {
                pending.callback.onSuccess(channel);
            }
        } else {
            mChannels.remove(channel.localCid);
            if (pending.callback != null) {
                pending.callback.onFailure("LE CoC connection refused: " + result);
            }
        }
    }

    private void handleFlowControlCredit(AclConnection conn, byte[] data) {
        if (data.length < 4) return;

        int cid = ((data[1] & 0xFF) << 8) | (data[0] & 0xFF);
        int credits = ((data[3] & 0xFF) << 8) | (data[2] & 0xFF);

        L2capChannel channel = mChannels.get(cid);
        if (channel != null && channel.isCreditBased()) {
            channel.addRemoteCredits(credits);
            CourierLogger.d(TAG, "Received " + credits + " credits for CID 0x" +
                    Integer.toHexString(cid));
        }
    }

    private void handleEchoRequest(AclConnection conn, int id, byte[] data) {
        // Send Echo Response with same data
        ByteBuffer rsp = ByteBuffer.allocate(4 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        rsp.put((byte) L2capConstants.CMD_ECHO_RESPONSE);
        rsp.put((byte) id);
        rsp.putShort((short) data.length);
        rsp.put(data);
        sendL2capData(conn.handle, L2capConstants.CID_SIGNALING, rsp.array());
    }

    /**
     * Handles L2CAP Information Request.
     *
     * Per Bluetooth Core Spec v5.3, Vol 3, Part A, Section 4.10-4.11:
     * Info Types:
     *   0x0001 - Connectionless MTU
     *   0x0002 - Extended Features Supported
     *   0x0003 - Fixed Channels Supported
     *
     * Result:
     *   0x0000 - Success
     *   0x0001 - Not Supported
     */
    private void handleInfoRequest(AclConnection conn, int id, byte[] data) {
        if (data.length < 2) return;

        int infoType = ((data[1] & 0xFF) << 8) | (data[0] & 0xFF);
        CourierLogger.d(TAG, "Information Request: type=0x" + Integer.toHexString(infoType));

        ByteBuffer rsp;

        switch (infoType) {
            case L2capConstants.INFO_CONNECTIONLESS_MTU:
                // Return our connectionless MTU (default 672 for BR/EDR)
                rsp = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
                rsp.put((byte) L2capConstants.CMD_INFORMATION_RESPONSE);
                rsp.put((byte) id);
                rsp.putShort((short) 6);  // Length: InfoType(2) + Result(2) + MTU(2)
                rsp.putShort((short) infoType);
                rsp.putShort((short) 0x0000);  // Result: Success
                rsp.putShort((short) L2capConstants.DEFAULT_MTU);
                break;

            case L2capConstants.INFO_EXTENDED_FEATURES:
                // Extended features mask (4 bytes)
                // Bit 0: Flow control mode
                // Bit 1: Retransmission mode
                // Bit 2: Bi-directional QoS
                // Bit 3: Enhanced Retransmission Mode
                // Bit 4: Streaming Mode
                // Bit 5: FCS Option
                // Bit 6: Extended Flow Specification for BR/EDR
                // Bit 7: Fixed Channels
                // Bit 8: Extended Window Size
                // Bit 9: Unicast Connectionless Data Reception
                // We support basic features: Fixed Channels (bit 7)
                int features = 0x0080;  // Fixed Channels supported
                rsp = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
                rsp.put((byte) L2capConstants.CMD_INFORMATION_RESPONSE);
                rsp.put((byte) id);
                rsp.putShort((short) 8);  // Length: InfoType(2) + Result(2) + Features(4)
                rsp.putShort((short) infoType);
                rsp.putShort((short) 0x0000);  // Result: Success
                rsp.putInt(features);
                break;

            case L2capConstants.INFO_FIXED_CHANNELS:
                // Fixed channels supported (8 bytes bitmask)
                // Bit 1: L2CAP Signaling channel (CID 0x0001) - always supported
                // Bit 2: Connectionless reception (CID 0x0002)
                // Bit 3: AMP Manager (CID 0x0003)
                // Bit 4: ATT (CID 0x0004)
                // Bit 5: LE Signaling (CID 0x0005)
                // Bit 6: SMP (CID 0x0006)
                // Bit 7: BR/EDR SMP (CID 0x0007)
                // We support: Signaling (bit 1), ATT (bit 4), SMP (bit 6), BR/EDR SMP (bit 7)
                long fixedChannels = 0x00000072L;  // Bits 1, 4, 5, 6 = 0x72
                rsp = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
                rsp.put((byte) L2capConstants.CMD_INFORMATION_RESPONSE);
                rsp.put((byte) id);
                rsp.putShort((short) 12);  // Length: InfoType(2) + Result(2) + Channels(8)
                rsp.putShort((short) infoType);
                rsp.putShort((short) 0x0000);  // Result: Success
                rsp.putLong(fixedChannels);
                break;

            default:
                // Unknown info type - respond with "Not Supported"
                CourierLogger.w(TAG, "Unknown Information Request type: 0x" +
                        Integer.toHexString(infoType));
                rsp = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
                rsp.put((byte) L2capConstants.CMD_INFORMATION_RESPONSE);
                rsp.put((byte) id);
                rsp.putShort((short) 4);  // Length: InfoType(2) + Result(2)
                rsp.putShort((short) infoType);
                rsp.putShort((short) 0x0001);  // Result: Not Supported
                break;
        }

        sendL2capData(conn.handle, L2capConstants.CID_SIGNALING, rsp.array());
        CourierLogger.d(TAG, "Sent Information Response for type=0x" + Integer.toHexString(infoType));
    }

    private L2capChannel findChannelByRemoteCid(AclConnection conn, int remoteCid) {
        for (L2capChannel ch : mChannels.values()) {
            if (ch.connection.handle == conn.handle && ch.getRemoteCid() == remoteCid) {
                return ch;
            }
        }
        return null;
    }

    // ==================== Internal: Helpers ====================

    private void sendL2capData(int handle, int cid, byte[] data) {
        // Build L2CAP PDU: [Length(2)][CID(2)][Payload]
        // Then wrap in ACL packet: [Handle+Flags(2)][Total Length(2)][L2CAP PDU]
        int l2capLength = data.length;
        int totalLength = l2capLength + 4;

        ByteBuffer pkt = ByteBuffer.allocate(4 + totalLength).order(ByteOrder.LITTLE_ENDIAN);
        // ACL Header: Handle (12 bits) + PB flag (2 bits) + BC flag (2 bits)
        pkt.putShort((short) ((handle & 0x0FFF) | (L2capConstants.PB_FIRST_FLUSH << 12)));
        pkt.putShort((short) totalLength);
        // L2CAP Header
        pkt.putShort((short) l2capLength);
        pkt.putShort((short) cid);
        // Payload
        pkt.put(data);

        mHciManager.sendAclData(pkt.array());
    }

    private void readBufferSize() {
        byte[] rsp = mHciManager.sendCommandSync(HciCommands.readBufferSize(), 2000);
        if (rsp != null && rsp.length >= 11 && rsp[5] == 0) {
            mAclBufferSize = ((rsp[7] & 0xFF) << 8) | (rsp[6] & 0xFF);
            mAclBufferCount = ((rsp[10] & 0xFF) << 8) | (rsp[9] & 0xFF);
            mListener.onMessage("ACL buffer: " + mAclBufferSize + " bytes x " + mAclBufferCount);
        }
    }

    private int allocateLocalCid() {
        int cid = mNextLocalCid.getAndIncrement();
        if (cid > L2capConstants.CID_DYNAMIC_END) {
            mNextLocalCid.set(L2capConstants.CID_DYNAMIC_START);
            cid = mNextLocalCid.getAndIncrement();
        }
        return cid;
    }

    private int allocateSignalingId() {
        int id = mNextSignalingId.getAndIncrement();
        if (id > 255) {
            mNextSignalingId.set(1);
            id = mNextSignalingId.getAndIncrement();
        }
        return id;
    }

    private boolean checkInitialized(IL2capConnectionCallback callback) {
        if (!isInitialized()) {
            callback.onFailure("L2CAP not initialized");
            return false;
        }
        return true;
    }

    private static String formatAddress(byte[] addr) {
        if (addr == null || addr.length != 6) {
            return "??:??:??:??:??:??";
        }
        return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                addr[5] & 0xFF, addr[4] & 0xFF, addr[3] & 0xFF,
                addr[2] & 0xFF, addr[1] & 0xFF, addr[0] & 0xFF);
    }

    // ==================== Internal: Helper Classes ====================

    private static final class PendingConnection {
        final int identifier;
        final int psm;
        final int localCid;
        final int handle;
        final IL2capConnectionCallback callback;

        PendingConnection(int identifier, int psm, int localCid, int handle,
                          IL2capConnectionCallback callback) {
            this.identifier = identifier;
            this.psm = psm;
            this.localCid = localCid;
            this.handle = handle;
            this.callback = callback;
        }
    }
}