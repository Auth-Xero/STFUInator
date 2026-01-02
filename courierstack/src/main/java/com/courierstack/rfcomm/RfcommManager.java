package com.courierstack.rfcomm;

import android.content.Context;

import com.courierstack.core.CourierLogger;
import com.courierstack.hci.HciCommandManager;
import com.courierstack.l2cap.AclConnection;
import com.courierstack.l2cap.IL2capConnectionCallback;
import com.courierstack.l2cap.IL2capListener;
import com.courierstack.l2cap.IL2capServerListener;
import com.courierstack.l2cap.L2capChannel;
import com.courierstack.l2cap.L2capConstants;
import com.courierstack.l2cap.L2capManager;
import com.courierstack.sdp.ISdpQueryCallback;
import com.courierstack.sdp.SdpManager;
import com.courierstack.sdp.ServiceRecord;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RFCOMM Protocol Manager - Serial port emulation over Bluetooth.
 *
 * <p>Implements the RFCOMM protocol per Bluetooth Core Spec v5.3, Vol 3, Part F,
 * which adapts the ETSI TS 27.010 (GSM 07.10) multiplexer for Bluetooth.
 *
 * <p>RFCOMM provides a simple reliable data stream similar to TCP sockets,
 * running over L2CAP PSM 0x0003. Multiple virtual serial ports (DLCIs)
 * can be multiplexed over a single L2CAP channel.
 *
 * <p>Features:
 * <ul>
 *   <li>Full frame handling: SABM, UA, DM, DISC, UIH</li>
 *   <li>Parameter Negotiation (PN) for MTU and flow control</li>
 *   <li>Modem Status Commands (MSC) for RS-232 signals</li>
 *   <li>Credit-based flow control</li>
 *   <li>Client and server modes</li>
 *   <li>SDP-based service discovery</li>
 * </ul>
 *
 * <p>Thread Safety: This class is thread-safe. All public methods can be called
 * from any thread. Callbacks are dispatched on the executor thread.
 */
public class RfcommManager implements IL2capListener, IL2capServerListener, Closeable {

    private static final String TAG = "RfcommManager";

    /** Executor shutdown timeout (seconds). */
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;

    /**
     * FCS lookup table for CRC calculation (TS 27.010 Section 5.2.1.6).
     */
    private static final byte[] CRC_TABLE = generateCrcTable();

    // ==================== Dependencies ====================

    private final Context mContext;
    private final L2capManager mL2capManager;
    private final IRfcommListener mListener;
    private final ExecutorService mExecutor;
    private final CopyOnWriteArrayList<IRfcommListener> mAdditionalListeners;

    private SdpManager mSdpManager;

    // ==================== State ====================

    private final AtomicBoolean mInitialized = new AtomicBoolean(false);
    private final AtomicBoolean mClosed = new AtomicBoolean(false);

    /** Session management: L2CAP CID -> RfcommSession */
    private final Map<Integer, RfcommSession> mSessions = new ConcurrentHashMap<>();

    /** Channel management: (L2CAP CID << 8 | DLCI) -> RfcommChannel */
    private final Map<Integer, RfcommChannel> mChannels = new ConcurrentHashMap<>();

    /** Server registrations: server channel -> listener */
    private final Map<Integer, IRfcommServerListener> mServerListeners = new ConcurrentHashMap<>();

    /** Pending connection callbacks: DLCI -> callback */
    private final Map<Integer, IRfcommChannelCallback> mPendingCallbacks = new ConcurrentHashMap<>();

    /** Pending connections by address (for connection coalescing) */
    private final Map<String, Queue<RfcommSession.PendingChannel>> mPendingConnectionsByAddress =
            new ConcurrentHashMap<>();

    // ==================== Constructor ====================

    /**
     * Creates a new RFCOMM manager.
     *
     * @param context  application context
     * @param listener event listener
     * @throws NullPointerException if context or listener is null
     */
    public RfcommManager(Context context, IRfcommListener listener) {
        mContext = Objects.requireNonNull(context, "context must not be null");
        mListener = Objects.requireNonNull(listener, "listener must not be null");
        mExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "RfcommManager-Worker");
            t.setDaemon(true);
            return t;
        });
        mAdditionalListeners = new CopyOnWriteArrayList<>();
        mL2capManager = new L2capManager(context, new L2capListenerAdapter());
    }

    // ==================== Initialization ====================

    /**
     * Initializes the RFCOMM layer and underlying L2CAP/HCI.
     *
     * @return true if initialization succeeded
     */
    public boolean initialize() {
        if (mClosed.get()) {
            mListener.onError("Cannot initialize - already closed");
            return false;
        }

        if (mInitialized.getAndSet(true)) {
            return true;
        }

        if (!mL2capManager.initialize()) {
            mInitialized.set(false);
            return false;
        }

        mL2capManager.registerServer(L2capConstants.PSM_RFCOMM, new L2capServerAdapter());
        mSdpManager = new SdpManager(mL2capManager, new SdpManager.ISdpListener() {
            @Override
            public void onMessage(String msg) {
                mListener.onMessage(msg);
            }

            @Override
            public void onError(String msg) {
                mListener.onError(msg);
            }
        });
        mSdpManager.initialize();

        mListener.onMessage("RFCOMM initialized");
        return true;
    }

    private void checkInitialized() {
        if (!mInitialized.get()) {
            throw new IllegalStateException("RfcommManager not initialized");
        }
        if (mClosed.get()) {
            throw new IllegalStateException("RfcommManager is closed");
        }
    }

    /**
     * Returns true if RFCOMM is initialized.
     *
     * @return true if initialized
     */
    public boolean isInitialized() {
        return mInitialized.get() && !mClosed.get();
    }

    /**
     * Returns the underlying L2CAP manager.
     *
     * @return L2CAP manager
     */
    public L2capManager getL2capManager() {
        return mL2capManager;
    }

    /**
     * Returns the underlying HCI manager.
     *
     * @return HCI manager or null
     */
    public HciCommandManager getHciManager() {
        return mL2capManager.getHciManager();
    }

    /**
     * Returns the SDP manager.
     *
     * @return SDP manager or null if not initialized
     */
    public SdpManager getSdpManager() {
        return mSdpManager;
    }

    // ==================== Listener Management ====================

    /**
     * Adds an event listener.
     *
     * @param listener listener to add
     */
    public void addListener(IRfcommListener listener) {
        if (listener != null && !mAdditionalListeners.contains(listener)) {
            mAdditionalListeners.add(listener);
        }
    }

    /**
     * Removes an event listener.
     *
     * @param listener listener to remove
     */
    public void removeListener(IRfcommListener listener) {
        mAdditionalListeners.remove(listener);
    }

    private void notifyConnected(RfcommChannel ch) {
        mListener.onConnected(ch);
        for (IRfcommListener l : mAdditionalListeners) {
            try {
                l.onConnected(ch);
            } catch (Exception e) {
                CourierLogger.e(TAG, "Listener exception", e);
            }
        }
    }

    private void notifyDisconnected(RfcommChannel ch) {
        mListener.onDisconnected(ch);
        for (IRfcommListener l : mAdditionalListeners) {
            try {
                l.onDisconnected(ch);
            } catch (Exception e) {
                CourierLogger.e(TAG, "Listener exception", e);
            }
        }
    }

    private void notifyDataReceived(RfcommChannel ch, byte[] data) {
        mListener.onDataReceived(ch, data);
        for (IRfcommListener l : mAdditionalListeners) {
            try {
                l.onDataReceived(ch, data);
            } catch (Exception e) {
                CourierLogger.e(TAG, "Listener exception", e);
            }
        }
    }

    private void notifyModemStatusChanged(RfcommChannel ch, int status) {
        mListener.onModemStatusChanged(ch, status);
        for (IRfcommListener l : mAdditionalListeners) {
            try {
                l.onModemStatusChanged(ch, status);
            } catch (Exception e) {
                CourierLogger.e(TAG, "Listener exception", e);
            }
        }
    }

    // ==================== Server Registration ====================

    /**
     * Registers a server on a channel number.
     *
     * @param serverChannel channel number (1-30)
     * @param listener      server listener for incoming connections
     * @throws IllegalArgumentException if serverChannel is invalid
     */
    public void registerServer(int serverChannel, IRfcommServerListener listener) {
        if (serverChannel < 1 || serverChannel > RfcommConstants.MAX_SERVER_CHANNEL) {
            throw new IllegalArgumentException("Invalid server channel: must be 1-30");
        }
        mServerListeners.put(serverChannel, listener);
        mListener.onMessage("Server registered on channel " + serverChannel);
    }

    /**
     * Unregisters a server.
     *
     * @param serverChannel channel number
     */
    public void unregisterServer(int serverChannel) {
        mServerListeners.remove(serverChannel);
    }

    // ==================== Client Connection ====================

    /**
     * Connects to a remote RFCOMM server channel.
     *
     * @param remoteAddress peer BD_ADDR (6 bytes, little-endian)
     * @param serverChannel server channel number (1-30)
     * @param callback      result callback
     */
    public void connect(byte[] remoteAddress, int serverChannel, IRfcommChannelCallback callback) {
        Objects.requireNonNull(remoteAddress, "remoteAddress must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        if (!mInitialized.get()) {
            callback.onFailure("RFCOMM not initialized");
            return;
        }
        if (serverChannel < 1 || serverChannel > RfcommConstants.MAX_SERVER_CHANNEL) {
            callback.onFailure("Invalid server channel: must be 1-30");
            return;
        }

        final String addrStr = formatAddress(remoteAddress);

        mExecutor.execute(() -> {
            // Reuse existing session if available
            RfcommSession existingSession = findSessionByAddress(remoteAddress);
            if (existingSession != null) {
                mListener.onMessage("Reusing session to " + addrStr);
                MuxState state = existingSession.getState();
                if (state == MuxState.OPEN) {
                    connectDlci(existingSession, serverChannel, callback);
                } else if (state == MuxState.CONNECTING) {
                    existingSession.queuePendingChannel(serverChannel, callback);
                } else {
                    existingSession.queuePendingChannel(serverChannel, callback);
                    openMultiplexer(existingSession);
                }
                return;
            }

            // Check for pending L2CAP connection to coalesce requests
            synchronized (mPendingConnectionsByAddress) {
                Queue<RfcommSession.PendingChannel> pendingQueue = mPendingConnectionsByAddress.get(addrStr);
                if (pendingQueue != null) {
                    pendingQueue.offer(new RfcommSession.PendingChannel(serverChannel, callback));
                    return;
                }
                pendingQueue = new ConcurrentLinkedQueue<>();
                pendingQueue.offer(new RfcommSession.PendingChannel(serverChannel, callback));
                mPendingConnectionsByAddress.put(addrStr, pendingQueue);
            }

            mListener.onMessage("Connecting to " + addrStr + " channel " + serverChannel);
            mL2capManager.createConnection(remoteAddress, new IL2capConnectionCallback() {
                @Override
                public void onSuccess(L2capChannel l2cap) {
                    openL2capForRfcommWithPendingChannels(l2cap.connection.handle, addrStr);
                }

                @Override
                public void onFailure(String reason) {
                    failPendingChannels(addrStr, "ACL failed: " + reason);
                }
            });
        });
    }

    /**
     * Connects to a remote service by UUID via SDP lookup.
     *
     * @param remoteAddress peer BD_ADDR
     * @param serviceUuid   service UUID to find
     * @param callback      result callback
     */
    public void connectByUuid(byte[] remoteAddress, UUID serviceUuid, IRfcommChannelCallback callback) {
        Objects.requireNonNull(remoteAddress, "remoteAddress must not be null");
        Objects.requireNonNull(serviceUuid, "serviceUuid must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        if (!mInitialized.get()) {
            callback.onFailure("RFCOMM not initialized");
            return;
        }
        if (mSdpManager == null) {
            callback.onFailure("SDP not available");
            return;
        }

        final String addrStr = formatAddress(remoteAddress);
        mListener.onMessage("SDP lookup on " + addrStr + " for " + serviceUuid);

        mSdpManager.queryService(remoteAddress, serviceUuid, new ISdpQueryCallback() {
            @Override
            public void onServiceFound(ServiceRecord record) {
                // Wait for complete
            }

            @Override
            public void onQueryComplete(List<ServiceRecord> records) {
                if (records.isEmpty()) {
                    callback.onFailure("Service not found");
                    return;
                }
                for (ServiceRecord rec : records) {
                    if (rec.getRfcommChannel() > 0 && rec.getRfcommChannel() <= 30) {
                        mListener.onMessage("Found on channel " + rec.getRfcommChannel());
                        connect(remoteAddress, rec.getRfcommChannel(), callback);
                        return;
                    }
                }
                callback.onFailure("No RFCOMM channel in service record");
            }

            @Override
            public void onError(String message) {
                callback.onFailure("SDP failed: " + message);
            }
        });
    }

    /**
     * Connects using an existing ACL handle.
     *
     * @param aclHandle     ACL connection handle
     * @param serverChannel server channel (1-30)
     * @param callback      result callback
     */
    public void connectOnHandle(int aclHandle, int serverChannel, IRfcommChannelCallback callback) {
        Objects.requireNonNull(callback, "callback must not be null");

        if (!mInitialized.get()) {
            callback.onFailure("RFCOMM not initialized");
            return;
        }

        mExecutor.execute(() -> {
            RfcommSession session = findSessionByAclHandle(aclHandle);
            if (session != null) {
                MuxState state = session.getState();
                if (state == MuxState.OPEN) {
                    connectDlci(session, serverChannel, callback);
                } else if (state == MuxState.CONNECTING) {
                    session.queuePendingChannel(serverChannel, callback);
                } else {
                    session.queuePendingChannel(serverChannel, callback);
                    openMultiplexer(session);
                }
                return;
            }
            openL2capForRfcomm(aclHandle, serverChannel, callback);
        });
    }

    /**
     * Connects using an existing L2CAP channel.
     *
     * @param l2capChannel  L2CAP channel (must be PSM_RFCOMM)
     * @param serverChannel server channel (1-30)
     * @param callback      result callback
     */
    public void connectOnL2cap(L2capChannel l2capChannel, int serverChannel, IRfcommChannelCallback callback) {
        Objects.requireNonNull(l2capChannel, "l2capChannel must not be null");
        Objects.requireNonNull(callback, "callback must not be null");

        if (!mInitialized.get()) {
            callback.onFailure("RFCOMM not initialized");
            return;
        }

        mExecutor.execute(() -> {
            RfcommSession session = getOrCreateSession(l2capChannel, true);
            MuxState state = session.getState();
            if (state == MuxState.OPEN) {
                connectDlci(session, serverChannel, callback);
            } else if (state == MuxState.CONNECTING) {
                session.queuePendingChannel(serverChannel, callback);
            } else if (state == MuxState.CLOSED) {
                session.queuePendingChannel(serverChannel, callback);
                openMultiplexer(session);
            } else {
                callback.onFailure("Mux in invalid state: " + state);
            }
        });
    }

    // ==================== Data Transfer ====================

    /**
     * Sends data on an RFCOMM channel.
     * Data is fragmented according to the negotiated frame size.
     *
     * @param channel channel to send on
     * @param data    data to send
     */
    public void sendData(RfcommChannel channel, byte[] data) {
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(data, "data must not be null");

        if (channel.getState() != RfcommChannelState.CONNECTED) {
            mListener.onError("Channel not connected");
            return;
        }

        mExecutor.execute(() -> {
            int offset = 0;
            while (offset < data.length) {
                if (channel.isCreditBasedFlowEnabled() && channel.getRemoteCredits() <= 0) {
                    mListener.onMessage("Waiting for credits on DLCI " + channel.dlci);
                    return; // TODO: implement blocking/queueing
                }

                int chunkSize = Math.min(channel.getFrameSize(), data.length - offset);
                byte[] chunk = new byte[chunkSize];
                System.arraycopy(data, offset, chunk, 0, chunkSize);

                int creditsToSend = 0;
                if (channel.isCreditBasedFlowEnabled() &&
                        channel.getLocalCredits() < RfcommConstants.DEFAULT_CREDITS) {
                    creditsToSend = RfcommConstants.DEFAULT_CREDITS;
                    channel.addLocalCredits(creditsToSend);
                }

                sendUihFrame(channel.session, channel.dlci, chunk, creditsToSend);
                if (channel.isCreditBasedFlowEnabled()) {
                    channel.consumeRemoteCredit();
                }
                offset += chunkSize;
            }
        });
    }

    /**
     * Sends string as UTF-8 data.
     *
     * @param channel channel to send on
     * @param text    text to send
     */
    public void sendString(RfcommChannel channel, String text) {
        sendData(channel, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Sends string with CR/LF (for AT commands).
     *
     * @param channel channel to send on
     * @param line    line to send
     */
    public void sendLine(RfcommChannel channel, String line) {
        sendString(channel, line + "\r\n");
    }

    /**
     * Sends modem status (MSC command).
     *
     * @param channel channel to send on
     * @param status  modem status bits
     */
    public void sendModemStatus(RfcommChannel channel, int status) {
        if (channel == null || channel.getState() != RfcommChannelState.CONNECTED) return;
        mExecutor.execute(() -> {
            channel.setLocalModemStatus(status);
            sendMscCommand(channel.session, channel.dlci, status, true);
        });
    }

    // ==================== Disconnection ====================

    /**
     * Disconnects an RFCOMM channel (sends DISC frame).
     *
     * @param channel channel to disconnect
     */
    public void disconnect(RfcommChannel channel) {
        if (channel == null) return;
        mExecutor.execute(() -> {
            RfcommChannelState state = channel.getState();
            if (state == RfcommChannelState.CONNECTED || state == RfcommChannelState.CONNECTING) {
                channel.setState(RfcommChannelState.DISCONNECTING);
                sendDiscFrame(channel.session, channel.dlci);
            }
        });
    }

    /**
     * Closes entire RFCOMM session (all channels on the mux).
     *
     * @param session session to close
     */
    public void closeSession(RfcommSession session) {
        if (session == null) return;
        mExecutor.execute(() -> {
            for (RfcommChannel ch : mChannels.values()) {
                if (ch.session == session && ch.dlci != 0) {
                    ch.setState(RfcommChannelState.CLOSED);
                    notifyDisconnected(ch);
                }
            }
            if (session.getState() == MuxState.OPEN) {
                session.setState(MuxState.DISCONNECTING);
                sendDiscFrame(session, 0);
            }
        });
    }

    // ==================== Session Management ====================

    private RfcommSession findSessionByAddress(byte[] addr) {
        for (RfcommSession s : mSessions.values()) {
            L2capChannel l2cap = s.getL2capChannel();
            if (l2cap != null && l2cap.connection != null &&
                    l2cap.connection.matchesAddress(addr)) {
                return s;
            }
        }
        return null;
    }

    private RfcommSession findSessionByAclHandle(int handle) {
        for (RfcommSession s : mSessions.values()) {
            L2capChannel l2cap = s.getL2capChannel();
            if (l2cap != null && l2cap.connection != null &&
                    l2cap.connection.handle == handle) {
                return s;
            }
        }
        return null;
    }

    private void openL2capForRfcomm(int aclHandle, int serverChannel, IRfcommChannelCallback callback) {
        AclConnection conn = mL2capManager.getConnections().get(aclHandle);
        if (conn == null) {
            callback.onFailure("ACL not found");
            return;
        }

        mL2capManager.connectChannel(conn, L2capConstants.PSM_RFCOMM, new IL2capConnectionCallback() {
            @Override
            public void onSuccess(L2capChannel l2cap) {
                RfcommSession session = getOrCreateSession(l2cap, true);
                session.queuePendingChannel(serverChannel, callback);
                openMultiplexer(session);
            }

            @Override
            public void onFailure(String reason) {
                callback.onFailure("L2CAP failed: " + reason);
            }
        });
    }

    private void openL2capForRfcommWithPendingChannels(int aclHandle, String addrStr) {
        AclConnection conn = mL2capManager.getConnections().get(aclHandle);
        if (conn == null) {
            failPendingChannels(addrStr, "ACL not found");
            return;
        }

        mL2capManager.connectChannel(conn, L2capConstants.PSM_RFCOMM, new IL2capConnectionCallback() {
            @Override
            public void onSuccess(L2capChannel l2cap) {
                Queue<RfcommSession.PendingChannel> queue;
                synchronized (mPendingConnectionsByAddress) {
                    queue = mPendingConnectionsByAddress.remove(addrStr);
                }
                if (queue == null || queue.isEmpty()) return;

                RfcommSession session = getOrCreateSession(l2cap, true);
                RfcommSession.PendingChannel pending;
                while ((pending = queue.poll()) != null) {
                    session.queuePendingChannel(pending.serverChannel, pending.callback);
                }
                openMultiplexer(session);
            }

            @Override
            public void onFailure(String reason) {
                failPendingChannels(addrStr, "L2CAP failed: " + reason);
            }
        });
    }

    private void failPendingChannels(String addrStr, String reason) {
        Queue<RfcommSession.PendingChannel> queue;
        synchronized (mPendingConnectionsByAddress) {
            queue = mPendingConnectionsByAddress.remove(addrStr);
        }
        if (queue != null) {
            RfcommSession.PendingChannel p;
            while ((p = queue.poll()) != null) {
                p.callback.onFailure(reason);
            }
        }
    }

    private RfcommSession getOrCreateSession(L2capChannel l2cap, boolean isInitiator) {
        return mSessions.computeIfAbsent(l2cap.localCid,
                cid -> new RfcommSession(l2cap, isInitiator));
    }

    private void openMultiplexer(RfcommSession session) {
        session.setState(MuxState.CONNECTING);
        mListener.onMessage("Opening RFCOMM multiplexer");
        sendSabmFrame(session, 0);
    }

    private void connectDlci(RfcommSession session, int serverChannel, IRfcommChannelCallback callback) {
        int dlci = serverChannel * 2;
        if (!session.isInitiator()) {
            dlci |= 1;
        }

        int key = getChannelKey(session, dlci);
        RfcommChannel channel = new RfcommChannel(dlci, session);
        channel.setState(RfcommChannelState.CONFIG);
        mChannels.put(key, channel);
        mPendingCallbacks.put(dlci, callback);

        mListener.onMessage("Connecting DLCI " + dlci);
        sendPnCommand(session, dlci, session.getMaxFrameSize(), RfcommConstants.DEFAULT_CREDITS);
    }

    // ==================== Frame Construction (TS 27.010 Section 5.2) ====================

    private void sendFrame(RfcommSession session, int dlci, int type, boolean poll,
                           byte[] data, int credits) {
        int address = buildAddress(dlci, session.isInitiator(), true);
        int control = type | (poll ? 0x10 : 0x00);

        int dataLen = (data != null) ? data.length : 0;
        boolean hasCredits = (type == RfcommConstants.FRAME_UIH &&
                session.isCreditBasedFlow() && credits > 0);
        int infoLen = dataLen + (hasCredits ? 1 : 0);

        ByteBuffer frame;
        if (infoLen <= 127) {
            frame = ByteBuffer.allocate(4 + infoLen);
            frame.put((byte) address);
            frame.put((byte) control);
            frame.put((byte) ((infoLen << 1) | 1)); // EA=1
        } else {
            frame = ByteBuffer.allocate(5 + infoLen);
            frame.put((byte) address);
            frame.put((byte) control);
            frame.put((byte) ((infoLen << 1) & 0xFE)); // EA=0
            frame.put((byte) (infoLen >> 7));
        }

        if (hasCredits) frame.put((byte) credits);
        if (data != null && dataLen > 0) frame.put(data);

        byte[] frameData = frame.array();
        int fcsEnd = (type == RfcommConstants.FRAME_UIH) ? 3 : Math.min(3, frameData.length);
        if (frameData.length > 3 && (frameData[2] & 0x01) == 0) fcsEnd = 4;
        byte fcs = calculateFcs(frameData, 0, fcsEnd);

        byte[] pkt = new byte[frameData.length + 1];
        System.arraycopy(frameData, 0, pkt, 0, frameData.length);
        pkt[pkt.length - 1] = fcs;

        mL2capManager.sendData(session.getL2capChannel(), pkt);
    }

    private void sendSabmFrame(RfcommSession session, int dlci) {
        sendFrame(session, dlci, RfcommConstants.FRAME_SABM, true, null, 0);
    }

    private void sendUaFrame(RfcommSession session, int dlci) {
        sendFrame(session, dlci, RfcommConstants.FRAME_UA, true, null, 0);
    }

    private void sendDmFrame(RfcommSession session, int dlci, boolean pf) {
        sendFrame(session, dlci, RfcommConstants.FRAME_DM, pf, null, 0);
    }

    private void sendDiscFrame(RfcommSession session, int dlci) {
        sendFrame(session, dlci, RfcommConstants.FRAME_DISC, true, null, 0);
    }

    private void sendUihFrame(RfcommSession session, int dlci, byte[] data, int credits) {
        sendFrame(session, dlci, RfcommConstants.FRAME_UIH, credits > 0, data, credits);
    }

    // ==================== MCC Commands (TS 27.010 Section 5.4.6) ====================

    private void sendMccCommand(RfcommSession session, int type, boolean command, byte[] value) {
        int typeField = (type << 2) | (command ? 0x02 : 0x00) | 0x01;
        int len = (value != null) ? value.length : 0;
        ByteBuffer data = ByteBuffer.allocate(2 + len);
        data.put((byte) typeField);
        data.put((byte) ((len << 1) | 1));
        if (value != null) data.put(value);
        sendUihFrame(session, 0, data.array(), 0);
    }

    private void sendPnCommand(RfcommSession session, int dlci, int frameSize, int credits) {
        byte[] value = new byte[8];
        value[0] = (byte) (dlci & 0x3F);
        value[1] = (byte) RfcommConstants.PN_CREDIT_FLOW_REQ;
        value[2] = (byte) RfcommConstants.DEFAULT_PRIORITY;
        value[3] = 0;
        value[4] = (byte) (frameSize & 0xFF);
        value[5] = (byte) ((frameSize >> 8) & 0xFF);
        value[6] = 0;
        value[7] = (byte) credits;
        sendMccCommand(session, RfcommConstants.MCC_PN, true, value);
    }

    private void sendMscCommand(RfcommSession session, int dlci, int signals, boolean command) {
        int address = buildAddress(dlci, session.isInitiator(), true);
        byte[] value = new byte[]{(byte) ((address & 0xFC) | 0x03), (byte) (signals | 0x01)};
        sendMccCommand(session, RfcommConstants.MCC_MSC, command, value);
    }

    private void sendRpnCommand(RfcommSession session, int dlci, boolean command,
                                int baud, int dataBits, int stopBits, int parity, int flow) {
        int address = buildAddress(dlci, session.isInitiator(), true);
        ByteBuffer value = ByteBuffer.allocate(8);
        value.put((byte) ((address & 0xFC) | 0x03));
        value.put((byte) baud);
        value.put((byte) ((dataBits & 0x03) | ((stopBits & 0x01) << 2) | ((parity & 0x01) << 3)));
        value.put((byte) flow);
        value.put((byte) 0).put((byte) 0);
        value.putShort((short) 0x3F7F);
        sendMccCommand(session, RfcommConstants.MCC_RPN, command, value.array());
    }

    private void sendNscResponse(RfcommSession session, int cmdType) {
        sendMccCommand(session, RfcommConstants.MCC_NSC, false,
                new byte[]{(byte) ((cmdType << 2) | 0x01)});
    }

    // ==================== Frame Parsing ====================

    private void processRfcommFrame(RfcommSession session, byte[] data) {
        if (data.length < 3) {
            mListener.onError("Frame too short");
            return;
        }

        int offset = 0;
        int address = data[offset++] & 0xFF;
        int dlci = (address >> 2) & 0x3F;
        boolean cr = (address & 0x02) != 0;

        int control = data[offset++] & 0xFF;
        boolean pf = (control & 0x10) != 0;
        int frameType = control & 0xEF;

        int length;
        if ((data[offset] & 0x01) != 0) {
            length = (data[offset++] & 0xFF) >> 1;
        } else {
            length = ((data[offset] & 0xFF) >> 1) | ((data[offset + 1] & 0xFF) << 7);
            offset += 2;
        }

        int credits = 0;
        if (frameType == RfcommConstants.FRAME_UIH && session.isCreditBasedFlow() && pf && dlci != 0) {
            if (offset < data.length) {
                credits = data[offset++] & 0xFF;
                length--;
            }
        }

        byte[] info = null;
        if (length > 0 && offset + length <= data.length) {
            info = new byte[length];
            System.arraycopy(data, offset, info, 0, length);
        }

        // Verify FCS
        int fcsEnd = (frameType == RfcommConstants.FRAME_UIH) ? 2 : offset;
        if (data.length > 3 && (data[2] & 0x01) == 0) fcsEnd = Math.max(fcsEnd, 4);
        byte expectedFcs = calculateFcs(data, 0, fcsEnd);
        byte actualFcs = data[data.length - 1];
        if (expectedFcs != actualFcs) {
            mListener.onError("FCS mismatch: expected 0x" + Integer.toHexString(expectedFcs & 0xFF) +
                    " got 0x" + Integer.toHexString(actualFcs & 0xFF));
        }

        switch (frameType) {
            case RfcommConstants.FRAME_SABM:
                handleSabmFrame(session, dlci, cr);
                break;
            case RfcommConstants.FRAME_UA:
                handleUaFrame(session, dlci);
                break;
            case RfcommConstants.FRAME_DM:
                handleDmFrame(session, dlci, pf);
                break;
            case RfcommConstants.FRAME_DISC:
                handleDiscFrame(session, dlci);
                break;
            case RfcommConstants.FRAME_UIH:
                handleUihFrame(session, dlci, info, credits);
                break;
            default:
                mListener.onMessage("Unknown frame: " + RfcommConstants.getFrameTypeName(frameType));
                break;
        }
    }

    private void handleSabmFrame(RfcommSession session, int dlci, boolean cr) {
        mListener.onMessage("RX SABM DLCI=" + dlci);

        if (dlci == 0) {
            MuxState state = session.getState();
            if (state == MuxState.CLOSED || state == MuxState.CONNECTING) {
                session.setState(MuxState.OPEN);
                // Note: When responding to SABM, we become the responder
                sendUaFrame(session, 0);
                mListener.onMessage("Mux opened (responder)");
            }
        } else {
            int serverChannel = dlci >> 1;
            IRfcommServerListener server = mServerListeners.get(serverChannel);

            if (server != null) {
                byte[] remoteAddr = session.getL2capChannel().connection.getPeerAddress();
                if (server.onConnectionRequest(serverChannel, remoteAddr)) {
                    int key = getChannelKey(session, dlci);
                    RfcommChannel channel = new RfcommChannel(dlci, session);
                    channel.setRemoteAddress(remoteAddr);
                    channel.setState(RfcommChannelState.CONNECTED);
                    mChannels.put(key, channel);
                    sendUaFrame(session, dlci);
                    sendMscCommand(session, dlci, channel.getLocalModemStatus(), true);
                    server.onChannelOpened(channel);
                    notifyConnected(channel);
                } else {
                    sendDmFrame(session, dlci, true);
                }
            } else {
                sendDmFrame(session, dlci, true);
            }
        }
    }

    private void handleUaFrame(RfcommSession session, int dlci) {
        mListener.onMessage("RX UA DLCI=" + dlci);

        if (dlci == 0) {
            MuxState state = session.getState();
            if (state == MuxState.CONNECTING) {
                session.setState(MuxState.OPEN);
                mListener.onMessage("Mux opened (initiator)");
                RfcommSession.PendingChannel pending;
                while ((pending = session.pollPendingChannel()) != null) {
                    connectDlci(session, pending.serverChannel, pending.callback);
                }
            } else if (state == MuxState.DISCONNECTING) {
                session.setState(MuxState.CLOSED);
                cleanupSession(session);
            }
        } else {
            int key = getChannelKey(session, dlci);
            RfcommChannel channel = mChannels.get(key);
            if (channel != null) {
                RfcommChannelState channelState = channel.getState();
                if (channelState == RfcommChannelState.CONNECTING) {
                    channel.setState(RfcommChannelState.CONNECTED);
                    channel.setRemoteAddress(session.getL2capChannel().connection.getPeerAddress());
                    sendMscCommand(session, dlci, channel.getLocalModemStatus(), true);
                    IRfcommChannelCallback cb = mPendingCallbacks.remove(dlci);
                    if (cb != null) cb.onSuccess(channel);
                    notifyConnected(channel);
                } else if (channelState == RfcommChannelState.DISCONNECTING) {
                    channel.setState(RfcommChannelState.CLOSED);
                    mChannels.remove(key);
                    IRfcommServerListener server = mServerListeners.get(channel.serverChannel);
                    if (server != null) server.onChannelClosed(channel);
                    notifyDisconnected(channel);
                }
            }
        }
    }

    private void handleDmFrame(RfcommSession session, int dlci, boolean pf) {
        mListener.onMessage("RX DM DLCI=" + dlci);

        if (dlci == 0) {
            session.setState(MuxState.CLOSED);
            RfcommSession.PendingChannel pending;
            while ((pending = session.pollPendingChannel()) != null) {
                pending.callback.onFailure("Mux rejected");
            }
            cleanupSession(session);
        } else {
            int key = getChannelKey(session, dlci);
            RfcommChannel channel = mChannels.remove(key);
            IRfcommChannelCallback cb = mPendingCallbacks.remove(dlci);
            if (cb != null) cb.onFailure("Connection rejected (DM)");
            if (channel != null) {
                channel.setState(RfcommChannelState.CLOSED);
                notifyDisconnected(channel);
            }
        }
    }

    private void handleDiscFrame(RfcommSession session, int dlci) {
        mListener.onMessage("RX DISC DLCI=" + dlci);
        sendUaFrame(session, dlci);

        if (dlci == 0) {
            session.setState(MuxState.CLOSED);
            cleanupSession(session);
        } else {
            int key = getChannelKey(session, dlci);
            RfcommChannel channel = mChannels.remove(key);
            if (channel != null) {
                channel.setState(RfcommChannelState.CLOSED);
                IRfcommServerListener server = mServerListeners.get(channel.serverChannel);
                if (server != null) server.onChannelClosed(channel);
                notifyDisconnected(channel);
            }
        }
    }

    private void handleUihFrame(RfcommSession session, int dlci, byte[] data, int credits) {
        if (dlci == 0) {
            if (data != null && data.length >= 2) {
                processMccCommand(session, data);
            }
        } else {
            int key = getChannelKey(session, dlci);
            RfcommChannel channel = mChannels.get(key);
            if (channel != null) {
                if (credits > 0) {
                    channel.addRemoteCredits(credits);
                }
                if (data != null && data.length > 0) {
                    notifyDataReceived(channel, data);
                    IRfcommServerListener server = mServerListeners.get(channel.serverChannel);
                    if (server != null) server.onDataReceived(channel, data);

                    if (channel.isCreditBasedFlowEnabled()) {
                        // Consume a local credit (peer sent us data)
                        int remaining = channel.addLocalCredits(-1);
                        if (remaining < RfcommConstants.DEFAULT_CREDITS / 2) {
                            int newCredits = RfcommConstants.DEFAULT_CREDITS;
                            channel.addLocalCredits(newCredits);
                            sendUihFrame(session, dlci, new byte[0], newCredits);
                        }
                    }
                }
            }
        }
    }

    // ==================== MCC Processing ====================

    private void processMccCommand(RfcommSession session, byte[] data) {
        int typeField = data[0] & 0xFF;
        int cmdType = (typeField >> 2) & 0x3F;
        boolean isCommand = (typeField & 0x02) != 0;
        int len = (data[1] & 0xFF) >> 1;
        byte[] value = (len > 0 && data.length >= 2 + len) ?
                Arrays.copyOfRange(data, 2, 2 + len) : null;

        switch (cmdType) {
            case RfcommConstants.MCC_PN:
                handlePnMessage(session, value, isCommand);
                break;
            case RfcommConstants.MCC_MSC:
                handleMscMessage(session, value, isCommand);
                break;
            case RfcommConstants.MCC_RPN:
                handleRpnMessage(session, value, isCommand);
                break;
            case RfcommConstants.MCC_TEST:
                handleTestMessage(session, value, isCommand);
                break;
            case RfcommConstants.MCC_FCON:
                mListener.onMessage("Flow Control On");
                if (isCommand) sendMccCommand(session, RfcommConstants.MCC_FCON, false, null);
                break;
            case RfcommConstants.MCC_FCOFF:
                mListener.onMessage("Flow Control Off");
                if (isCommand) sendMccCommand(session, RfcommConstants.MCC_FCOFF, false, null);
                break;
            case RfcommConstants.MCC_RLS:
                handleRlsMessage(session, value, isCommand);
                break;
            default:
                mListener.onMessage("Unknown MCC: " + RfcommConstants.getMccTypeName(cmdType));
                if (isCommand) sendNscResponse(session, cmdType);
                break;
        }
    }

    private void handlePnMessage(RfcommSession session, byte[] value, boolean isCommand) {
        if (value == null || value.length < 8) return;

        int dlci = value[0] & 0x3F;
        int frameType = value[1] & 0xFF;
        int frameSize = (value[4] & 0xFF) | ((value[5] & 0xFF) << 8);
        int credits = value[7] & 0xFF;
        boolean creditFlow = (frameType & 0xF0) == RfcommConstants.PN_CREDIT_FLOW_REQ ||
                (frameType & 0xF0) == RfcommConstants.PN_CREDIT_FLOW_ACK;

        mListener.onMessage((isCommand ? "PN Req" : "PN Rsp") + " DLCI=" + dlci +
                " frameSize=" + frameSize + " credits=" + credits);

        int key = getChannelKey(session, dlci);
        RfcommChannel channel = mChannels.get(key);

        if (isCommand) {
            if (channel == null) {
                channel = new RfcommChannel(dlci, session);
                channel.setState(RfcommChannelState.CONFIG);
                mChannels.put(key, channel);
            }
            channel.setFrameSize(Math.min(frameSize, RfcommConstants.DEFAULT_MTU));
            channel.setCreditBasedFlowEnabled(creditFlow);
            channel.setRemoteCredits(credits);
            sendPnCommand(session, dlci, channel.getFrameSize(), channel.getLocalCredits());
        } else {
            if (channel != null) {
                channel.setFrameSize(Math.min(frameSize, channel.getFrameSize()));
                channel.setCreditBasedFlowEnabled(creditFlow);
                channel.setRemoteCredits(credits);
                if (channel.getState() == RfcommChannelState.CONFIG) {
                    channel.setState(RfcommChannelState.CONNECTING);
                    sendSabmFrame(session, dlci);
                }
            }
        }
    }

    private void handleMscMessage(RfcommSession session, byte[] value, boolean isCommand) {
        if (value == null || value.length < 2) return;
        int dlci = (value[0] >> 2) & 0x3F;
        int signals = value[1] & 0xFF;

        int key = getChannelKey(session, dlci);
        RfcommChannel channel = mChannels.get(key);
        if (channel != null) {
            channel.setRemoteModemStatus(signals);
            notifyModemStatusChanged(channel, signals);
        }
        if (isCommand) {
            int rspSignals = (channel != null) ? channel.getLocalModemStatus() :
                    (RfcommConstants.MSC_RTC | RfcommConstants.MSC_RTR | RfcommConstants.MSC_DV);
            sendMscCommand(session, dlci, rspSignals, false);
        }
    }

    private void handleRpnMessage(RfcommSession session, byte[] value, boolean isCommand) {
        if (value == null || value.length < 1) return;
        int dlci = (value[0] >> 2) & 0x3F;
        if (isCommand) {
            if (value.length == 1) {
                sendRpnCommand(session, dlci, false, 0x03, 0x03, 0, 0, 0);
            } else {
                sendMccCommand(session, RfcommConstants.MCC_RPN, false, value);
            }
        }
    }

    private void handleTestMessage(RfcommSession session, byte[] value, boolean isCommand) {
        if (isCommand) sendMccCommand(session, RfcommConstants.MCC_TEST, false, value);
    }

    private void handleRlsMessage(RfcommSession session, byte[] value, boolean isCommand) {
        if (value == null || value.length < 2) return;
        int dlci = (value[0] >> 2) & 0x3F;
        int status = value[1] & 0xFF;
        mListener.onMessage("RLS DLCI=" + dlci + " status=0x" + Integer.toHexString(status));
        if (isCommand) sendMccCommand(session, RfcommConstants.MCC_RLS, false, value);
    }

    // ==================== L2CAP Callbacks ====================

    @Override
    public void onConnectionComplete(AclConnection conn) {
        mListener.onMessage("ACL complete: 0x" + Integer.toHexString(conn.handle));
    }

    @Override
    public void onDisconnectionComplete(int handle, int reason) {
        mListener.onMessage("ACL disconnected: 0x" + Integer.toHexString(handle));
        for (RfcommSession s : mSessions.values()) {
            L2capChannel l2cap = s.getL2capChannel();
            if (l2cap != null && l2cap.connection != null && l2cap.connection.handle == handle) {
                cleanupSession(s);
            }
        }
    }

    @Override
    public void onChannelOpened(L2capChannel ch) {
        // No action needed
    }

    @Override
    public void onChannelClosed(L2capChannel ch) {
        RfcommSession session = mSessions.remove(ch.localCid);
        if (session != null) cleanupSession(session);
    }

    @Override
    public void onDataReceived(L2capChannel ch, byte[] data) {
        RfcommSession session = mSessions.get(ch.localCid);
        if (session != null) processRfcommFrame(session, data);
    }

    @Override
    public void onConnectionRequest(int handle, int psm, int sourceCid) {
        // No action needed
    }

    @Override
    public void onError(String msg) {
        mListener.onError(msg);
    }

    @Override
    public void onMessage(String msg) {
        CourierLogger.d(TAG, msg);
    }

    // L2CAP Server
    @Override
    public boolean onConnectionRequest(L2capChannel ch) {
        return true;
    }

    // ==================== Utility Methods ====================

    private int buildAddress(int dlci, boolean isInitiator, boolean ea) {
        return ((dlci & 0x3F) << 2) | (isInitiator ? 0x02 : 0x00) | (ea ? 0x01 : 0x00);
    }

    private int getChannelKey(RfcommSession session, int dlci) {
        return (session.getL2capChannel().localCid << 8) | dlci;
    }

    private void cleanupSession(RfcommSession session) {
        for (Map.Entry<Integer, RfcommChannel> e : mChannels.entrySet()) {
            RfcommChannel ch = e.getValue();
            if (ch.session == session) {
                ch.setState(RfcommChannelState.CLOSED);
                mChannels.remove(e.getKey());
                IRfcommServerListener server = mServerListeners.get(ch.serverChannel);
                if (server != null) server.onChannelClosed(ch);
                notifyDisconnected(ch);
            }
        }
        RfcommSession.PendingChannel p;
        while ((p = session.pollPendingChannel()) != null) {
            p.callback.onFailure("Session closed");
        }
        mSessions.remove(session.getL2capChannel().localCid);
    }

    private String formatAddress(byte[] addr) {
        if (addr == null || addr.length != 6) return "??:??:??:??:??:??";
        return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                addr[5] & 0xFF, addr[4] & 0xFF, addr[3] & 0xFF,
                addr[2] & 0xFF, addr[1] & 0xFF, addr[0] & 0xFF);
    }

    // ==================== FCS Calculation (TS 27.010 Section 5.2.1.6) ====================

    private static byte[] generateCrcTable() {
        byte[] table = new byte[256];
        for (int i = 0; i < 256; i++) {
            int crc = i;
            for (int j = 0; j < 8; j++) {
                crc = ((crc & 1) != 0) ? (crc >> 1) ^ 0x8C : crc >> 1;
            }
            table[i] = (byte) crc;
        }
        return table;
    }

    private byte calculateFcs(byte[] data, int start, int end) {
        int fcs = 0xFF;
        for (int i = start; i < end && i < data.length; i++) {
            fcs = CRC_TABLE[(fcs ^ data[i]) & 0xFF] & 0xFF;
        }
        return (byte) (0xFF - fcs);
    }

    // ==================== Public Accessors ====================

    /**
     * Returns all active sessions.
     *
     * @return unmodifiable map of sessions
     */
    public Map<Integer, RfcommSession> getSessions() {
        return Collections.unmodifiableMap(mSessions);
    }

    /**
     * Returns all connected channels.
     *
     * @return unmodifiable map of channels
     */
    public Map<Integer, RfcommChannel> getChannels() {
        return Collections.unmodifiableMap(mChannels);
    }

    /**
     * Gets a channel by DLCI.
     *
     * @param session RFCOMM session
     * @param dlci    DLCI
     * @return channel or null
     */
    public RfcommChannel getChannel(RfcommSession session, int dlci) {
        return mChannels.get(getChannelKey(session, dlci));
    }

    /**
     * Parses BD_ADDR from string (XX:XX:XX:XX:XX:XX).
     *
     * @param address address string
     * @return 6-byte address in little-endian
     * @throws IllegalArgumentException if format is invalid
     */
    public static byte[] parseAddress(String address) {
        String[] parts = address.split(":");
        if (parts.length != 6) throw new IllegalArgumentException("Invalid address format");
        byte[] addr = new byte[6];
        for (int i = 0; i < 6; i++) {
            addr[5 - i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return addr;
    }

    // ==================== Closeable Implementation ====================

    @Override
    public void close() {
        if (!mClosed.compareAndSet(false, true)) {
            return;
        }

        CourierLogger.i(TAG, "Closing RfcommManager");

        // Clean up all sessions
        for (RfcommSession s : mSessions.values()) {
            cleanupSession(s);
        }
        mSessions.clear();
        mChannels.clear();
        mServerListeners.clear();
        mPendingCallbacks.clear();
        mPendingConnectionsByAddress.clear();

        // Shutdown SDP
        if (mSdpManager != null) {
            mSdpManager.shutdown();
        }

        // Shutdown L2CAP
        mL2capManager.shutdown();

        // Shutdown executor
        mExecutor.shutdown();
        try {
            if (!mExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                mExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            mExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        mInitialized.set(false);
        mListener.onMessage("RFCOMM shutdown");
        CourierLogger.i(TAG, "RfcommManager closed");
    }

    /**
     * @deprecated Use {@link #close()} instead
     */
    @Deprecated
    public void shutdown() {
        close();
    }

    /**
     * Returns whether the manager is closed.
     *
     * @return true if closed
     */
    public boolean isClosed() {
        return mClosed.get();
    }

    // ==================== Inner Adapters ====================

    private class L2capListenerAdapter implements IL2capListener {
        @Override
        public void onConnectionComplete(AclConnection c) {
            RfcommManager.this.onConnectionComplete(c);
        }

        @Override
        public void onDisconnectionComplete(int h, int r) {
            RfcommManager.this.onDisconnectionComplete(h, r);
        }

        @Override
        public void onChannelOpened(L2capChannel c) {
            RfcommManager.this.onChannelOpened(c);
        }

        @Override
        public void onChannelClosed(L2capChannel c) {
            RfcommManager.this.onChannelClosed(c);
        }

        @Override
        public void onDataReceived(L2capChannel c, byte[] d) {
            RfcommManager.this.onDataReceived(c, d);
        }

        @Override
        public void onConnectionRequest(int h, int p, int s) {
            RfcommManager.this.onConnectionRequest(h, p, s);
        }

        @Override
        public void onError(String m) {
            mListener.onError(m);
        }

        @Override
        public void onMessage(String m) {
            RfcommManager.this.onMessage(m);
        }
    }

    private class L2capServerAdapter implements IL2capServerListener {
        @Override
        public boolean onConnectionRequest(L2capChannel c) {
            return RfcommManager.this.onConnectionRequest(c);
        }

        @Override
        public void onDataReceived(L2capChannel c, byte[] d) {
            RfcommManager.this.onDataReceived(c, d);
        }

        @Override
        public void onChannelClosed(L2capChannel c) {
            RfcommManager.this.onChannelClosed(c);
        }
    }
}