package com.courierstack.a2dp.avdtp;

import com.courierstack.l2cap.AclConnection;
import com.courierstack.l2cap.IL2capConnectionCallback;
import com.courierstack.l2cap.IL2capListener;
import com.courierstack.l2cap.IL2capServerListener;
import com.courierstack.l2cap.L2capChannel;
import com.courierstack.l2cap.L2capManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AVDTP Manager - Audio/Video Distribution Transport Protocol.
 *
 * <p>Implements A2DP streaming for Bluetooth audio per AVDTP Spec v1.3
 * and A2DP Spec v1.3.
 *
 * <p>Features:
 * <ul>
 *   <li>Stream endpoint discovery and capability exchange</li>
 *   <li>Stream configuration and management</li>
 *   <li>Media transport via RTP</li>
 *   <li>SBC codec support (other codecs via AudioCodec interface)</li>
 *   <li>Both initiator and acceptor roles</li>
 * </ul>
 *
 * <p>Usage example:
 * <pre>{@code
 * AvdtpManager avdtp = new AvdtpManager(l2capManager, listener);
 * avdtp.initialize();
 *
 * // Connect to a device
 * avdtp.connect(address, new IAvdtpSessionCallback() {
 *     public void onConnectionComplete(boolean success) {
 *         if (success) {
 *             avdtp.discoverEndpoints(handle);
 *         }
 *     }
 *     // ... other callbacks
 * });
 *
 * // After discovery and configuration
 * avdtp.start(handle);
 * avdtp.sendMedia(handle, encodedData);
 * }</pre>
 *
 * <p>Thread safety: This class is thread-safe.
 */
public class AvdtpManager implements IL2capListener {

    private static final String TAG = "AvdtpManager";
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_MS = 5000;

    // ==================== Dependencies ====================

    private final L2capManager mL2capManager;
    private final IAvdtpListener mListener;
    private final ExecutorService mExecutor;

    // ==================== State ====================

    private final AtomicBoolean mInitialized = new AtomicBoolean(false);
    private final AtomicInteger mTransactionLabel = new AtomicInteger(0);
    private final AtomicBoolean mStreaming = new AtomicBoolean(false);

    // Sessions by ACL handle
    private final Map<Integer, AvdtpSession> mSessions = new ConcurrentHashMap<>();

    // Sessions by L2CAP CID (for routing incoming data)
    private final Map<Integer, AvdtpSession> mSessionsByCid = new ConcurrentHashMap<>();

    // Pending get capabilities SEID per session (handle -> seid)
    private final Map<Integer, Integer> mPendingCapabilitiesSeid = new ConcurrentHashMap<>();

    // Default local endpoints
    private final List<StreamEndpoint> mLocalEndpoints = new CopyOnWriteArrayList<>();

    // Additional listeners
    private final List<IAvdtpListener> mAdditionalListeners = new CopyOnWriteArrayList<>();

    // ==================== Constructor ====================

    /**
     * Creates a new AVDTP manager.
     *
     * @param l2capManager L2CAP manager for transport
     * @param listener     event listener
     */
    public AvdtpManager(L2capManager l2capManager, IAvdtpListener listener) {
        if (l2capManager == null) throw new NullPointerException("l2capManager");
        if (listener == null) throw new NullPointerException("listener");

        mL2capManager = l2capManager;
        mListener = listener;
        mExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "AVDTP-Worker");
            t.setDaemon(true);
            return t;
        });

        registerDefaultEndpoints();
    }

    // ==================== Initialization ====================

    /**
     * Initializes the AVDTP manager.
     *
     * @return true if successful
     */
    public boolean initialize() {
        if (mInitialized.get()) return true;

        mL2capManager.addListener(this);
        mL2capManager.registerServer(AvdtpConstants.PSM_AVDTP, new AvdtpServerAdapter());

        mInitialized.set(true);
        mListener.onMessage("AVDTP Manager initialized");

        return true;
    }

    /**
     * Shuts down the AVDTP manager.
     */
    public void shutdown() {
        mInitialized.set(false);
        mStreaming.set(false);

        mL2capManager.removeListener(this);
        mL2capManager.unregisterServer(AvdtpConstants.PSM_AVDTP);

        mSessions.clear();
        mSessionsByCid.clear();

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
     * Returns whether AVDTP is initialized.
     */
    public boolean isInitialized() {
        return mInitialized.get();
    }

    // ==================== Listener Management ====================

    /**
     * Adds an additional listener.
     */
    public void addListener(IAvdtpListener listener) {
        if (listener != null && !mAdditionalListeners.contains(listener)) {
            mAdditionalListeners.add(listener);
        }
    }

    /**
     * Removes a listener.
     */
    public void removeListener(IAvdtpListener listener) {
        mAdditionalListeners.remove(listener);
    }

    // ==================== Endpoint Management ====================

    private void registerDefaultEndpoints() {
        // SBC Source endpoint
        StreamEndpoint sbcSource = StreamEndpoint.builder()
                .seid(1)
                .source()
                .audio()
                .codecSbc()
                .sbcAllCapabilities()
                .build();
        mLocalEndpoints.add(sbcSource);

        // SBC Sink endpoint
        StreamEndpoint sbcSink = StreamEndpoint.builder()
                .seid(2)
                .sink()
                .audio()
                .codecSbc()
                .sbcAllCapabilities()
                .build();
        mLocalEndpoints.add(sbcSink);
    }

    /**
     * Registers a local stream endpoint.
     *
     * @param endpoint endpoint to register
     */
    public void registerEndpoint(StreamEndpoint endpoint) {
        if (endpoint != null) {
            mLocalEndpoints.add(endpoint);
        }
    }

    /**
     * Unregisters a local stream endpoint.
     *
     * @param seid endpoint SEID
     */
    public void unregisterEndpoint(int seid) {
        mLocalEndpoints.removeIf(ep -> ep.getSeid() == seid);
    }

    /**
     * Returns all registered local endpoints.
     */
    public List<StreamEndpoint> getLocalEndpoints() {
        return new ArrayList<>(mLocalEndpoints);
    }

    // ==================== Connection ====================

    /**
     * Connects to a remote device.
     *
     * @param address  remote device address (6 bytes)
     * @param callback session callback
     */
    public void connect(byte[] address, AvdtpSession.IAvdtpSessionCallback callback) {
        if (!mInitialized.get()) {
            mListener.onError("AVDTP not initialized");
            if (callback != null) callback.onConnectionComplete(false);
            return;
        }

        String addrStr = formatAddress(address);
        mListener.onMessage("Connecting AVDTP to " + addrStr);

        mL2capManager.createConnection(address, new IL2capConnectionCallback() {
            @Override
            public void onSuccess(L2capChannel channel) {
                openSignalingChannel(channel.connection.handle, address, callback);
            }

            @Override
            public void onFailure(String reason) {
                mListener.onError("AVDTP connection failed: " + reason);
                if (callback != null) callback.onConnectionComplete(false);
            }
        });
    }

    private void openSignalingChannel(int aclHandle, byte[] address,
                                      AvdtpSession.IAvdtpSessionCallback callback) {
        AclConnection conn = mL2capManager.getConnections().get(aclHandle);
        if (conn == null) {
            if (callback != null) callback.onConnectionComplete(false);
            return;
        }

        mL2capManager.connectChannel(conn, AvdtpConstants.PSM_AVDTP,
                new IL2capConnectionCallback() {
                    @Override
                    public void onSuccess(L2capChannel channel) {
                        AvdtpSession session = new AvdtpSession(aclHandle, address);
                        session.setSignalingChannel(channel);
                        session.setCallback(callback);

                        // Copy local endpoints to session
                        for (StreamEndpoint ep : mLocalEndpoints) {
                            session.addLocalEndpoint(new StreamEndpoint(ep));
                        }

                        mSessions.put(aclHandle, session);
                        mSessionsByCid.put(channel.localCid, session);

                        mListener.onMessage("AVDTP signaling channel opened");
                        notifyConnected(aclHandle, address);

                        if (callback != null) callback.onConnectionComplete(true);
                    }

                    @Override
                    public void onFailure(String reason) {
                        mListener.onError("AVDTP signaling failed: " + reason);
                        if (callback != null) callback.onConnectionComplete(false);
                    }
                });
    }

    /**
     * Disconnects from a device.
     *
     * @param handle connection handle
     */
    public void disconnect(int handle) {
        AvdtpSession session = mSessions.remove(handle);
        if (session != null) {
            if (session.getSignalingChannel() != null) {
                mSessionsByCid.remove(session.getSignalingChannel().localCid);
                mL2capManager.closeChannel(session.getSignalingChannel());
            }
            if (session.getMediaChannel() != null) {
                mSessionsByCid.remove(session.getMediaChannel().localCid);
                mL2capManager.closeChannel(session.getMediaChannel());
            }
            session.close();
        }
    }

    /**
     * Returns a session by handle.
     */
    public AvdtpSession getSession(int handle) {
        return mSessions.get(handle);
    }

    // ==================== Signaling Commands ====================

    /**
     * Discovers remote stream endpoints.
     *
     * @param handle connection handle
     */
    public void discoverEndpoints(int handle) {
        AvdtpSession session = mSessions.get(handle);
        if (session == null) return;

        byte[] pdu = AvdtpSignal.buildDiscover(nextLabel());
        sendSignaling(session, pdu);
        mListener.onMessage("Sent AVDTP Discover");
    }

    /**
     * Gets capabilities of a remote endpoint.
     *
     * @param handle connection handle
     * @param seid   remote SEID
     */
    public void getCapabilities(int handle, int seid) {
        AvdtpSession session = mSessions.get(handle);
        if (session == null) return;

        // Track which SEID we're querying
        mPendingCapabilitiesSeid.put(handle, seid);

        byte[] pdu = AvdtpSignal.buildGetCapabilities(nextLabel(), seid);
        sendSignaling(session, pdu);
        mListener.onMessage("Sent Get Capabilities for SEID " + seid);
    }

    /**
     * Gets all capabilities of a remote endpoint (AVDTP 1.3+).
     *
     * @param handle connection handle
     * @param seid   remote SEID
     */
    public void getAllCapabilities(int handle, int seid) {
        AvdtpSession session = mSessions.get(handle);
        if (session == null) return;

        // Track which SEID we're querying
        mPendingCapabilitiesSeid.put(handle, seid);

        byte[] pdu = AvdtpSignal.buildGetAllCapabilities(nextLabel(), seid);
        sendSignaling(session, pdu);
        mListener.onMessage("Sent Get All Capabilities for SEID " + seid);
    }

    /**
     * Sets stream configuration.
     *
     * @param handle        connection handle
     * @param remoteSeid    remote endpoint SEID
     * @param localSeid     local endpoint SEID
     * @param configuration configuration data
     */
    public void setConfiguration(int handle, int remoteSeid, int localSeid,
                                 byte[] configuration) {
        AvdtpSession session = mSessions.get(handle);
        if (session == null) return;

        // Store active endpoints
        session.setActiveEndpoint(session.findLocalEndpoint(localSeid));
        session.setRemoteEndpoint(session.findRemoteEndpoint(remoteSeid));

        byte[] pdu = AvdtpSignal.buildSetConfiguration(nextLabel(), remoteSeid,
                localSeid, configuration);
        sendSignaling(session, pdu);
        mListener.onMessage("Sent Set Configuration");
    }

    /**
     * Sets default SBC configuration for a remote sink.
     *
     * @param handle     connection handle
     * @param remoteSeid remote sink SEID
     * @param localSeid  local source SEID
     * @param bitpool    SBC bitpool value
     */
    public void setDefaultSbcConfiguration(int handle, int remoteSeid, int localSeid,
                                           int bitpool) {
        byte[] config = AvdtpSignal.buildSbcDefaultConfiguration(bitpool);
        setConfiguration(handle, remoteSeid, localSeid, config);
    }

    /**
     * Finds an SBC sink endpoint among discovered remote endpoints.
     *
     * @param handle connection handle
     * @return SBC sink endpoint, or null if not found
     */
    public StreamEndpoint findRemoteSbcSink(int handle) {
        AvdtpSession session = mSessions.get(handle);
        if (session == null) return null;

        for (StreamEndpoint ep : session.getRemoteEndpoints()) {
            if (ep.isSink() && ep.getCodecType() == AvdtpConstants.CODEC_SBC) {
                return ep;
            }
        }
        return null;
    }

    /**
     * Finds a sink endpoint of any supported codec type.
     * Prefers SBC over other codecs.
     *
     * @param handle connection handle
     * @return sink endpoint, or null if not found
     */
    public StreamEndpoint findRemoteSink(int handle) {
        // First try SBC (mandatory, most compatible)
        StreamEndpoint sbcSink = findRemoteSbcSink(handle);
        if (sbcSink != null) return sbcSink;

        // Fall back to any sink
        AvdtpSession session = mSessions.get(handle);
        if (session == null) return null;

        for (StreamEndpoint ep : session.getRemoteEndpoints()) {
            if (ep.isSink()) {
                return ep;
            }
        }
        return null;
    }

    /**
     * Finds the local SBC source endpoint.
     *
     * @return local SBC source endpoint, or null if not found
     */
    public StreamEndpoint findLocalSbcSource() {
        for (StreamEndpoint ep : mLocalEndpoints) {
            if (ep.isSource() && ep.getCodecType() == AvdtpConstants.CODEC_SBC) {
                return ep;
            }
        }
        return null;
    }

    /**
     * Automatically configures a stream with the best available codec.
     * Prefers SBC for maximum compatibility.
     *
     * @param handle  connection handle
     * @param bitpool SBC bitpool (used if SBC is selected)
     * @return true if configuration was initiated, false if no compatible endpoint found
     */
    public boolean autoConfigureSbc(int handle, int bitpool) {
        StreamEndpoint remoteSink = findRemoteSbcSink(handle);
        if (remoteSink == null) {
            mListener.onError("No SBC sink endpoint found on remote device");
            return false;
        }

        StreamEndpoint localSource = findLocalSbcSource();
        if (localSource == null) {
            mListener.onError("No local SBC source endpoint");
            return false;
        }

        mListener.onMessage("Auto-configuring SBC: remote SEID=" + remoteSink.getSeid() +
                ", local SEID=" + localSource.getSeid() + ", bitpool=" + bitpool);

        setDefaultSbcConfiguration(handle, remoteSink.getSeid(), localSource.getSeid(), bitpool);
        return true;
    }

    /**
     * Automatically configures with default SBC settings (bitpool 53).
     *
     * @param handle connection handle
     * @return true if configuration was initiated
     */
    public boolean autoConfigureSbc(int handle) {
        return autoConfigureSbc(handle, 53);
    }

    /**
     * Opens a stream.
     *
     * @param handle connection handle
     */
    public void open(int handle) {
        AvdtpSession session = mSessions.get(handle);
        if (session == null || session.getActiveEndpoint() == null) return;

        byte[] pdu = AvdtpSignal.buildOpen(nextLabel(), session.getRemoteEndpoint().getSeid());
        sendSignaling(session, pdu);
        mListener.onMessage("Sent Open");
    }

    /**
     * Starts streaming.
     *
     * @param handle connection handle
     */
    public void start(int handle) {
        AvdtpSession session = mSessions.get(handle);
        if (session == null || session.getRemoteEndpoint() == null) return;

        byte[] pdu = AvdtpSignal.buildStart(nextLabel(), session.getRemoteEndpoint().getSeid());
        sendSignaling(session, pdu);
        mListener.onMessage("Sent Start");
    }

    /**
     * Suspends streaming.
     *
     * @param handle connection handle
     */
    public void suspend(int handle) {
        AvdtpSession session = mSessions.get(handle);
        if (session == null || session.getRemoteEndpoint() == null) return;

        byte[] pdu = AvdtpSignal.buildSuspend(nextLabel(), session.getRemoteEndpoint().getSeid());
        sendSignaling(session, pdu);
        mListener.onMessage("Sent Suspend");
    }

    /**
     * Closes a stream.
     *
     * @param handle connection handle
     */
    public void close(int handle) {
        AvdtpSession session = mSessions.get(handle);
        if (session == null || session.getRemoteEndpoint() == null) return;

        byte[] pdu = AvdtpSignal.buildClose(nextLabel(), session.getRemoteEndpoint().getSeid());
        sendSignaling(session, pdu);
        mListener.onMessage("Sent Close");
    }

    /**
     * Aborts a stream.
     *
     * @param handle connection handle
     */
    public void abort(int handle) {
        AvdtpSession session = mSessions.get(handle);
        if (session == null || session.getRemoteEndpoint() == null) return;

        byte[] pdu = AvdtpSignal.buildAbort(nextLabel(), session.getRemoteEndpoint().getSeid());
        sendSignaling(session, pdu);
        mListener.onMessage("Sent Abort");
    }

    /**
     * Sends a delay report.
     *
     * @param handle  connection handle
     * @param delayMs delay in milliseconds
     */
    public void sendDelayReport(int handle, int delayMs) {
        AvdtpSession session = mSessions.get(handle);
        if (session == null || session.getRemoteEndpoint() == null) return;

        byte[] pdu = AvdtpSignal.buildDelayReport(nextLabel(),
                session.getRemoteEndpoint().getSeid(), delayMs);
        sendSignaling(session, pdu);
    }

    // ==================== Media Transport ====================

    /**
     * Sends media data to a stream.
     *
     * @param handle connection handle
     * @param data   encoded media data (e.g., SBC frames)
     */
    public void sendMedia(int handle, byte[] data) {
        sendMedia(handle, data, 1);
    }

    /**
     * Sends media data with frame count.
     *
     * @param handle     connection handle
     * @param data       encoded media data
     * @param frameCount number of codec frames
     */
    public void sendMedia(int handle, byte[] data, int frameCount) {
        AvdtpSession session = mSessions.get(handle);
        if (session == null || !session.isStreaming() || session.getMediaChannel() == null) {
            return;
        }

        MediaPacket packet = session.getMediaPacket();
        byte[] rtpPacket = packet.buildNextSbc(data, frameCount, session.getSamplesPerFrame());
        mL2capManager.sendData(session.getMediaChannel(), rtpPacket);
    }

    /**
     * Returns whether currently streaming.
     */
    public boolean isStreaming() {
        return mStreaming.get();
    }

    /**
     * Stops any active streaming.
     */
    public void stopStreaming() {
        mStreaming.set(false);
    }

    // ==================== Signaling Helpers ====================

    private int nextLabel() {
        return mTransactionLabel.getAndIncrement() & 0x0F;
    }

    private void sendSignaling(AvdtpSession session, byte[] pdu) {
        if (session.getSignalingChannel() != null) {
            mL2capManager.sendData(session.getSignalingChannel(), pdu);
        }
    }

    private void sendResponse(AvdtpSession session, int label, int signalId, byte[] params) {
        byte[] pdu = AvdtpSignal.buildAcceptResponse(label, signalId, params);
        sendSignaling(session, pdu);
    }

    private void sendReject(AvdtpSession session, int label, int signalId, int errorCode) {
        byte[] pdu = AvdtpSignal.buildRejectResponse(label, signalId, errorCode);
        sendSignaling(session, pdu);
    }

    // ==================== Data Reception ====================

    @Override
    public void onDataReceived(L2capChannel channel, byte[] data) {
        AvdtpSession session = mSessionsByCid.get(channel.localCid);
        if (session == null) return;

        if (channel == session.getSignalingChannel()) {
            processSignaling(session, data);
        } else if (channel == session.getMediaChannel()) {
            processMedia(session, data);
        }
    }

    private void processSignaling(AvdtpSession session, byte[] data) {
        if (data == null || data.length < 2) return;

        AvdtpParser.PduHeader header = AvdtpParser.parsePduHeader(data);
        if (header == null) return;

        mListener.onMessage("AVDTP signal: " + AvdtpConstants.getSignalName(header.signalId) +
                " " + AvdtpConstants.getMessageTypeName(header.messageType));

        if (header.isCommand()) {
            handleCommand(session, header, data);
        } else if (header.isAccept()) {
            handleAccept(session, header, data);
        } else if (header.isReject()) {
            handleReject(session, header, data);
        }
    }

    private void processMedia(AvdtpSession session, byte[] data) {
        MediaPacket packet = new MediaPacket();
        if (!packet.parseSbc(data)) return;

        byte[] payload = packet.getPayload();
        int timestamp = packet.getTimestamp();

        AvdtpSession.IAvdtpSessionCallback callback = session.getCallback();
        if (callback != null) {
            callback.onMediaDataReceived(payload, timestamp);
        }

        notifyMediaReceived(session.getConnectionHandle(), payload, timestamp);
    }

    // ==================== Command Handling ====================

    private void handleCommand(AvdtpSession session, AvdtpParser.PduHeader header, byte[] data) {
        switch (header.signalId) {
            case AvdtpConstants.AVDTP_DISCOVER:
                handleDiscoverCommand(session, header.label);
                break;

            case AvdtpConstants.AVDTP_GET_CAPABILITIES:
            case AvdtpConstants.AVDTP_GET_ALL_CAPABILITIES:
                handleGetCapabilitiesCommand(session, header.label, data);
                break;

            case AvdtpConstants.AVDTP_SET_CONFIGURATION:
                handleSetConfigurationCommand(session, header.label, data);
                break;

            case AvdtpConstants.AVDTP_OPEN:
                handleOpenCommand(session, header.label, data);
                break;

            case AvdtpConstants.AVDTP_START:
                handleStartCommand(session, header.label, data);
                break;

            case AvdtpConstants.AVDTP_SUSPEND:
                handleSuspendCommand(session, header.label, data);
                break;

            case AvdtpConstants.AVDTP_CLOSE:
                handleCloseCommand(session, header.label, data);
                break;

            case AvdtpConstants.AVDTP_ABORT:
                handleAbortCommand(session, header.label, data);
                break;

            case AvdtpConstants.AVDTP_DELAY_REPORT:
                handleDelayReportCommand(session, header.label, data);
                break;

            default:
                // Unknown command - send general reject
                byte[] reject = AvdtpSignal.buildGeneralReject(header.label);
                sendSignaling(session, reject);
        }
    }

    private void handleDiscoverCommand(AvdtpSession session, int label) {
        StreamEndpoint[] endpoints = session.getLocalEndpoints().toArray(new StreamEndpoint[0]);
        byte[] response = AvdtpSignal.buildDiscoverResponse(label, endpoints);
        sendSignaling(session, response);
        mListener.onMessage("Sent Discover response");
    }

    private void handleGetCapabilitiesCommand(AvdtpSession session, int label, byte[] data) {
        if (data.length < 3) {
            sendReject(session, label, AvdtpConstants.AVDTP_GET_CAPABILITIES,
                    AvdtpConstants.ERR_BAD_LENGTH);
            return;
        }

        int seid = (data[2] & 0xFF) >> 2;
        StreamEndpoint endpoint = session.findLocalEndpoint(seid);

        if (endpoint == null) {
            sendReject(session, label, AvdtpConstants.AVDTP_GET_CAPABILITIES,
                    AvdtpConstants.ERR_BAD_ACP_SEID);
            return;
        }

        byte[] capabilities = endpoint.getCapabilities();
        byte[] response = AvdtpSignal.buildGetCapabilitiesResponse(label, capabilities);
        sendSignaling(session, response);
        mListener.onMessage("Sent capabilities for SEID " + seid);
    }

    private void handleSetConfigurationCommand(AvdtpSession session, int label, byte[] data) {
        AvdtpParser.SetConfigResult config = AvdtpParser.parseSetConfiguration(data);
        if (config == null) {
            sendReject(session, label, AvdtpConstants.AVDTP_SET_CONFIGURATION,
                    AvdtpConstants.ERR_BAD_LENGTH);
            return;
        }

        StreamEndpoint localEp = session.findLocalEndpoint(config.acpSeid);
        if (localEp == null) {
            sendReject(session, label, AvdtpConstants.AVDTP_SET_CONFIGURATION,
                    AvdtpConstants.ERR_BAD_ACP_SEID);
            return;
        }

        if (localEp.isInUse()) {
            sendReject(session, label, AvdtpConstants.AVDTP_SET_CONFIGURATION,
                    AvdtpConstants.ERR_SEP_IN_USE);
            return;
        }

        // Parse and apply configuration
        localEp.setConfiguration(config.configuration);
        localEp.setInUse(true);
        session.setActiveEndpoint(localEp);

        // Parse SBC config
        if (config.capabilities != null) {
            AvdtpParser.ServiceCapability codecCap = AvdtpParser.findCapability(
                    config.capabilities, AvdtpConstants.SC_MEDIA_CODEC);
            if (codecCap != null) {
                AvdtpParser.SbcConfig sbcConfig = AvdtpParser.parseSbcFromCapability(codecCap);
                if (sbcConfig != null) {
                    session.applySbcConfig(sbcConfig);
                }
            }
        }

        session.setState(StreamState.CONFIGURED);
        sendResponse(session, label, AvdtpConstants.AVDTP_SET_CONFIGURATION, null);

        mListener.onMessage("Stream configured");
        notifyStreamConfigured(localEp.getSeid(), config.intSeid);

        AvdtpSession.IAvdtpSessionCallback callback = session.getCallback();
        if (callback != null) {
            callback.onStreamConfigured(localEp);
        }
    }

    private void handleOpenCommand(AvdtpSession session, int label, byte[] data) {
        sendResponse(session, label, AvdtpConstants.AVDTP_OPEN, null);
        session.setState(StreamState.OPEN);
        openMediaChannel(session);
        mListener.onMessage("Stream opened");
        notifyStreamOpened(session.getActiveEndpoint() != null ?
                session.getActiveEndpoint().getSeid() : 0);
    }

    private void handleStartCommand(AvdtpSession session, int label, byte[] data) {
        sendResponse(session, label, AvdtpConstants.AVDTP_START, null);
        session.setState(StreamState.STREAMING);
        session.resetMediaSequence();

        mListener.onMessage("Stream started");
        notifyStreamStarted(session.getConnectionHandle());

        AvdtpSession.IAvdtpSessionCallback callback = session.getCallback();
        if (callback != null) {
            callback.onStreamStarted();
        }
    }

    private void handleSuspendCommand(AvdtpSession session, int label, byte[] data) {
        sendResponse(session, label, AvdtpConstants.AVDTP_SUSPEND, null);
        session.setState(StreamState.OPEN);

        mListener.onMessage("Stream suspended");
        notifyStreamSuspended(session.getConnectionHandle());

        AvdtpSession.IAvdtpSessionCallback callback = session.getCallback();
        if (callback != null) {
            callback.onStreamSuspended();
        }
    }

    private void handleCloseCommand(AvdtpSession session, int label, byte[] data) {
        sendResponse(session, label, AvdtpConstants.AVDTP_CLOSE, null);

        StreamEndpoint activeEp = session.getActiveEndpoint();
        if (activeEp != null) {
            activeEp.setInUse(false);
        }

        session.setState(StreamState.IDLE);
        session.setActiveEndpoint(null);
        session.setRemoteEndpoint(null);

        mListener.onMessage("Stream closed");
        notifyStreamClosed(session.getConnectionHandle());

        AvdtpSession.IAvdtpSessionCallback callback = session.getCallback();
        if (callback != null) {
            callback.onStreamClosed();
        }
    }

    private void handleAbortCommand(AvdtpSession session, int label, byte[] data) {
        sendResponse(session, label, AvdtpConstants.AVDTP_ABORT, null);

        StreamEndpoint activeEp = session.getActiveEndpoint();
        if (activeEp != null) {
            activeEp.setInUse(false);
        }

        session.setState(StreamState.IDLE);
        session.setActiveEndpoint(null);
        session.setRemoteEndpoint(null);

        mListener.onMessage("Stream aborted");
    }

    private void handleDelayReportCommand(AvdtpSession session, int label, byte[] data) {
        int delayMs = AvdtpParser.parseDelayReport(data);
        if (delayMs >= 0) {
            session.setDelayMs(delayMs);
            sendResponse(session, label, AvdtpConstants.AVDTP_DELAY_REPORT, null);
            mListener.onMessage("Delay report: " + delayMs + "ms");
        }
    }

    // ==================== Accept Response Handling ====================

    private void handleAccept(AvdtpSession session, AvdtpParser.PduHeader header, byte[] data) {
        switch (header.signalId) {
            case AvdtpConstants.AVDTP_DISCOVER:
                handleDiscoverAccept(session, data);
                break;

            case AvdtpConstants.AVDTP_GET_CAPABILITIES:
            case AvdtpConstants.AVDTP_GET_ALL_CAPABILITIES:
                handleGetCapabilitiesAccept(session, data);
                break;

            case AvdtpConstants.AVDTP_SET_CONFIGURATION:
                session.setState(StreamState.CONFIGURED);
                mListener.onMessage("Configuration accepted");
                AvdtpSession.IAvdtpSessionCallback callback = session.getCallback();
                if (callback != null && session.getActiveEndpoint() != null) {
                    callback.onStreamConfigured(session.getActiveEndpoint());
                }
                break;

            case AvdtpConstants.AVDTP_OPEN:
                session.setState(StreamState.OPEN);
                mListener.onMessage("Open accepted");
                openMediaChannel(session);
                notifyStreamOpened(session.getActiveEndpoint() != null ?
                        session.getActiveEndpoint().getSeid() : 0);
                break;

            case AvdtpConstants.AVDTP_START:
                session.setState(StreamState.STREAMING);
                session.resetMediaSequence();
                mListener.onMessage("Start accepted");
                notifyStreamStarted(session.getConnectionHandle());
                if (session.getCallback() != null) {
                    session.getCallback().onStreamStarted();
                }
                break;

            case AvdtpConstants.AVDTP_SUSPEND:
                session.setState(StreamState.OPEN);
                mListener.onMessage("Suspend accepted");
                notifyStreamSuspended(session.getConnectionHandle());
                if (session.getCallback() != null) {
                    session.getCallback().onStreamSuspended();
                }
                break;

            case AvdtpConstants.AVDTP_CLOSE:
                if (session.getActiveEndpoint() != null) {
                    session.getActiveEndpoint().setInUse(false);
                }
                session.setState(StreamState.IDLE);
                mListener.onMessage("Close accepted");
                notifyStreamClosed(session.getConnectionHandle());
                if (session.getCallback() != null) {
                    session.getCallback().onStreamClosed();
                }
                break;
        }
    }

    private void handleDiscoverAccept(AvdtpSession session, byte[] data) {
        List<StreamEndpoint> endpoints = AvdtpParser.parseDiscoverResponse(data);
        session.setRemoteEndpoints(endpoints);

        mListener.onMessage("Discovered " + endpoints.size() + " endpoints");
        notifyEndpointsDiscovered(session.getConnectionHandle(), endpoints);
    }

    private void handleGetCapabilitiesAccept(AvdtpSession session, byte[] data) {
        List<StreamEndpoint> remoteEndpoints = session.getRemoteEndpoints();
        if (remoteEndpoints.isEmpty()) return;

        // Get the SEID we actually queried
        Integer queriedSeid = mPendingCapabilitiesSeid.remove(session.getConnectionHandle());

        // Find the endpoint we queried
        StreamEndpoint targetEp = null;
        if (queriedSeid != null) {
            targetEp = session.findRemoteEndpoint(queriedSeid);
        }

        // Fall back to last endpoint if not found (shouldn't happen)
        if (targetEp == null) {
            targetEp = remoteEndpoints.get(remoteEndpoints.size() - 1);
            mListener.onMessage("Warning: Using fallback endpoint selection");
        }

        // Parse capabilities (skip 2-byte header)
        if (data.length > 2) {
            byte[] capData = new byte[data.length - 2];
            System.arraycopy(data, 2, capData, 0, capData.length);
            AvdtpParser.updateEndpointCapabilities(targetEp, capData);
        }

        mListener.onMessage("Got capabilities for SEID " + targetEp.getSeid() +
                ", codec=" + AvdtpConstants.getCodecName(targetEp.getCodecType()));
    }

    // ==================== Reject Response Handling ====================

    private void handleReject(AvdtpSession session, AvdtpParser.PduHeader header, byte[] data) {
        AvdtpParser.RejectInfo reject = AvdtpParser.parseReject(data);
        if (reject != null) {
            mListener.onError("AVDTP rejected: " + reject);
        } else {
            mListener.onError("AVDTP rejected: signal=" +
                    AvdtpConstants.getSignalName(header.signalId));
        }
    }

    // ==================== Media Channel ====================

    private void openMediaChannel(AvdtpSession session) {
        AclConnection conn = mL2capManager.getConnections().get(session.getConnectionHandle());
        if (conn == null) return;

        mL2capManager.connectChannel(conn, AvdtpConstants.PSM_AVDTP,
                new IL2capConnectionCallback() {
                    @Override
                    public void onSuccess(L2capChannel channel) {
                        session.setMediaChannel(channel);
                        mSessionsByCid.put(channel.localCid, session);
                        mListener.onMessage("Media channel opened, MTU=" + channel.getMtu());
                    }

                    @Override
                    public void onFailure(String reason) {
                        mListener.onError("Media channel failed: " + reason);
                    }
                });
    }

    // ==================== L2CAP Listener ====================

    @Override
    public void onConnectionComplete(AclConnection connection) {
        // Not used
    }

    @Override
    public void onDisconnectionComplete(int handle, int reason) {
        AvdtpSession session = mSessions.remove(handle);
        if (session != null) {
            if (session.getSignalingChannel() != null) {
                mSessionsByCid.remove(session.getSignalingChannel().localCid);
            }
            if (session.getMediaChannel() != null) {
                mSessionsByCid.remove(session.getMediaChannel().localCid);
            }
            session.close();
        }
    }

    @Override
    public void onChannelOpened(L2capChannel channel) {
        // Not used
    }

    @Override
    public void onChannelClosed(L2capChannel channel) {
        mSessionsByCid.remove(channel.localCid);
    }

    @Override
    public void onConnectionRequest(int handle, int psm, int sourceCid) {
        // Not used - handled by server adapter
    }

    @Override
    public void onError(String message) {
        mListener.onError(message);
    }

    @Override
    public void onMessage(String message) {
        // Not used
    }

    // ==================== Server Adapter ====================

    private class AvdtpServerAdapter implements IL2capServerListener {
        @Override
        public boolean onConnectionRequest(L2capChannel channel) {
            mListener.onMessage("Incoming AVDTP connection from " +
                    channel.connection.getFormattedAddress());

            // Create or get session for this connection
            int handle = channel.connection.handle;
            AvdtpSession session = mSessions.get(handle);

            if (session == null) {
                session = new AvdtpSession(handle, channel.connection.getPeerAddress());
                for (StreamEndpoint ep : mLocalEndpoints) {
                    session.addLocalEndpoint(new StreamEndpoint(ep));
                }
                mSessions.put(handle, session);
            }

            // Determine if this is signaling or media channel
            if (session.getSignalingChannel() == null) {
                session.setSignalingChannel(channel);
                mSessionsByCid.put(channel.localCid, session);
                mListener.onMessage("Signaling channel accepted");
            } else {
                session.setMediaChannel(channel);
                mSessionsByCid.put(channel.localCid, session);
                mListener.onMessage("Media channel accepted");
            }

            return true;
        }

        @Override
        public void onChannelOpened(L2capChannel channel) {
            // Already handled in onConnectionRequest
        }

        @Override
        public void onDataReceived(L2capChannel channel, byte[] data) {
            AvdtpManager.this.onDataReceived(channel, data);
        }

        @Override
        public void onChannelClosed(L2capChannel channel) {
            mSessionsByCid.remove(channel.localCid);
        }

        @Override
        public void onError(L2capChannel channel, String message) {
            mListener.onError("AVDTP server error: " + message);
        }
    }

    // ==================== Notifications ====================

    private void notifyConnected(int handle, byte[] address) {
        mListener.onConnected(handle, address);
        for (IAvdtpListener l : mAdditionalListeners) {
            try { l.onConnected(handle, address); } catch (Exception e) { /* ignore */ }
        }
    }

    private void notifyEndpointsDiscovered(int handle, List<StreamEndpoint> endpoints) {
        mListener.onEndpointsDiscovered(handle, endpoints);
        for (IAvdtpListener l : mAdditionalListeners) {
            try { l.onEndpointsDiscovered(handle, endpoints); } catch (Exception e) { /* ignore */ }
        }
    }

    private void notifyStreamConfigured(int localSeid, int remoteSeid) {
        mListener.onStreamConfigured(localSeid, remoteSeid);
        for (IAvdtpListener l : mAdditionalListeners) {
            try { l.onStreamConfigured(localSeid, remoteSeid); } catch (Exception e) { /* ignore */ }
        }
    }

    private void notifyStreamOpened(int localSeid) {
        mListener.onStreamOpened(localSeid);
        for (IAvdtpListener l : mAdditionalListeners) {
            try { l.onStreamOpened(localSeid); } catch (Exception e) { /* ignore */ }
        }
    }

    private void notifyStreamStarted(int handle) {
        mListener.onStreamStarted(handle);
        for (IAvdtpListener l : mAdditionalListeners) {
            try { l.onStreamStarted(handle); } catch (Exception e) { /* ignore */ }
        }
    }

    private void notifyStreamSuspended(int handle) {
        mListener.onStreamSuspended(handle);
        for (IAvdtpListener l : mAdditionalListeners) {
            try { l.onStreamSuspended(handle); } catch (Exception e) { /* ignore */ }
        }
    }

    private void notifyStreamClosed(int handle) {
        mListener.onStreamClosed(handle);
        for (IAvdtpListener l : mAdditionalListeners) {
            try { l.onStreamClosed(handle); } catch (Exception e) { /* ignore */ }
        }
    }

    private void notifyMediaReceived(int handle, byte[] data, int timestamp) {
        mListener.onMediaReceived(handle, data, timestamp);
        for (IAvdtpListener l : mAdditionalListeners) {
            try { l.onMediaReceived(handle, data, timestamp); } catch (Exception e) { /* ignore */ }
        }
    }

    // ==================== Utility Methods ====================

    private static String formatAddress(byte[] addr) {
        if (addr == null || addr.length != 6) return "??:??:??:??:??:??";
        return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                addr[5] & 0xFF, addr[4] & 0xFF, addr[3] & 0xFF,
                addr[2] & 0xFF, addr[1] & 0xFF, addr[0] & 0xFF);
    }
}