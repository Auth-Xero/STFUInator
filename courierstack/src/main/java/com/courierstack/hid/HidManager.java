package com.courierstack.hid;

import com.courierstack.l2cap.AclConnection;
import com.courierstack.l2cap.ChannelState;
import com.courierstack.l2cap.ConnectionType;
import com.courierstack.l2cap.IL2capConnectionCallback;
import com.courierstack.l2cap.IL2capListener;
import com.courierstack.l2cap.IL2capServerListener;
import com.courierstack.l2cap.L2capChannel;
import com.courierstack.l2cap.L2capConstants;
import com.courierstack.l2cap.L2capManager;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HID (Human Interface Device) Profile Manager.
 *
 * <p>Provides complete HID host functionality for connecting to and
 * communicating with Bluetooth HID devices (keyboards, mice, gamepads, etc.)
 * per Bluetooth HID Profile Specification v1.1.1.
 *
 * <p>Features:
 * <ul>
 *   <li>HID device connection management</li>
 *   <li>Boot and Report protocol support</li>
 *   <li>Input, Output, and Feature report handling</li>
 *   <li>GET_REPORT, SET_REPORT transactions</li>
 *   <li>Protocol and idle rate management</li>
 *   <li>Virtual cable and suspend/resume</li>
 *   <li>Automatic reconnection support</li>
 *   <li>SDP record parsing for HID attributes</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * HidManager hid = new HidManager(l2capManager, new IHidListener.Adapter() {
 *     @Override
 *     public void onDeviceConnected(HidDevice device) {
 *         Log.i("HID", "Connected: " + device.getName());
 *     }
 *
 *     @Override
 *     public void onInputReport(HidDevice device, HidReport report) {
 *         // Handle input reports (keyboard, mouse, etc.)
 *     }
 * });
 *
 * if (hid.initialize()) {
 *     // Connect to a device
 *     hid.connect(deviceAddress);
 *
 *     // Send keyboard LED output report
 *     hid.sendOutputReport(device, HidReport.createBootKeyboardOutput(
 *         HidConstants.LED_NUM_LOCK | HidConstants.LED_CAPS_LOCK));
 * }
 * hid.close();
 * }</pre>
 *
 * <p>Thread safety: This class is thread-safe. All public methods may be
 * called from any thread. Callbacks are invoked on an internal executor.
 *
 * @see IHidListener
 * @see HidDevice
 * @see HidConstants
 */
public class HidManager implements Closeable {

    private static final String TAG = "HidManager";

    // Timeouts
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_MS = 5000;
    private static final int TRANSACTION_TIMEOUT_MS = 5000;
    private static final int CONNECT_TIMEOUT_MS = 30000;
    private static final int RECONNECT_DELAY_MS = 2000;
    private static final int MAX_RECONNECT_ATTEMPTS = 3;

    // Dependencies
    private final L2capManager mL2capManager;
    private final IHidListener mListener;
    private final ExecutorService mExecutor;
    private final ScheduledExecutorService mScheduler;

    // Additional listeners
    private final List<IHidListener> mAdditionalListeners;

    // Device management
    private final Map<String, HidDevice> mDevices;
    private final Map<Integer, HidDevice> mDevicesByHandle;
    private final Map<Integer, HidDevice> mDevicesByControlCid;
    private final Map<Integer, HidDevice> mDevicesByInterruptCid;

    // Pending connections
    private final Map<String, PendingConnection> mPendingConnections;

    // Transaction tracking
    private final Map<String, PendingTransaction> mPendingTransactions;
    private final AtomicInteger mTransactionIdGenerator;

    // DATA continuation reassembly buffers (deviceId -> buffer)
    private final Map<String, java.io.ByteArrayOutputStream> mReassemblyBuffers;

    // Reconnection state
    private final Map<String, ReconnectInfo> mReconnectInfo;

    // State
    private final AtomicBoolean mInitialized;
    private final AtomicBoolean mClosed;
    private final AtomicBoolean mAcceptingConnections;

    // Configuration
    private volatile boolean mAutoReconnect = true;
    private volatile boolean mUseBootProtocol = false;
    private volatile int mDefaultIdleRate = HidConstants.IDLE_RATE_INDEFINITE;

    // L2CAP listener for HID channels
    private final HidL2capListener mL2capListener;

    // ==================== Pending Connection ====================

    /**
     * Tracks state for a pending connection.
     */
    private static class PendingConnection {
        final String deviceId;
        final byte[] address;
        final HidDevice device;
        final long startTime;
        volatile ScheduledFuture<?> timeoutFuture;
        volatile boolean cancelled;

        PendingConnection(String deviceId, byte[] address, HidDevice device) {
            this.deviceId = deviceId;
            this.address = address.clone();
            this.device = device;
            this.startTime = System.currentTimeMillis();
        }

        void cancel() {
            cancelled = true;
            if (timeoutFuture != null) {
                timeoutFuture.cancel(false);
            }
        }
    }

    // ==================== Pending Transaction ====================

    /**
     * Tracks state for a pending HID transaction.
     */
    private static class PendingTransaction {
        final int id;
        final String deviceId;
        final int transactionType;
        final int reportType;
        final int reportId;
        final long startTime;
        volatile ScheduledFuture<?> timeoutFuture;
        volatile boolean completed;

        // Callback for transaction completion
        volatile TransactionCallback callback;

        PendingTransaction(int id, String deviceId, int transactionType,
                           int reportType, int reportId) {
            this.id = id;
            this.deviceId = deviceId;
            this.transactionType = transactionType;
            this.reportType = reportType;
            this.reportId = reportId;
            this.startTime = System.currentTimeMillis();
        }

        void complete() {
            completed = true;
            if (timeoutFuture != null) {
                timeoutFuture.cancel(false);
            }
        }
    }

    /**
     * Callback interface for transaction completion.
     */
    public interface TransactionCallback {
        void onComplete(boolean success, int result, byte[] data);
    }

    // ==================== Reconnect Info ====================

    /**
     * Tracks reconnection state for a device.
     */
    private static class ReconnectInfo {
        final byte[] address;
        int attempts;
        long lastAttempt;
        ScheduledFuture<?> scheduledReconnect;

        ReconnectInfo(byte[] address) {
            this.address = address.clone();
        }
    }

    // ==================== Constructors ====================

    /**
     * Creates a new HID manager.
     *
     * @param l2capManager L2CAP manager (must not be null)
     * @param listener     primary event listener (must not be null)
     * @throws NullPointerException if l2capManager or listener is null
     */
    public HidManager(L2capManager l2capManager, IHidListener listener) {
        mL2capManager = Objects.requireNonNull(l2capManager, "l2capManager must not be null");
        mListener = Objects.requireNonNull(listener, "listener must not be null");

        mExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "HID-Worker");
            t.setDaemon(true);
            return t;
        });

        mScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HID-Scheduler");
            t.setDaemon(true);
            return t;
        });

        mAdditionalListeners = new CopyOnWriteArrayList<>();
        mDevices = new ConcurrentHashMap<>();
        mDevicesByHandle = new ConcurrentHashMap<>();
        mDevicesByControlCid = new ConcurrentHashMap<>();
        mDevicesByInterruptCid = new ConcurrentHashMap<>();
        mPendingConnections = new ConcurrentHashMap<>();
        mPendingTransactions = new ConcurrentHashMap<>();
        mReassemblyBuffers = new ConcurrentHashMap<>();
        mReconnectInfo = new ConcurrentHashMap<>();
        mTransactionIdGenerator = new AtomicInteger(1);

        mInitialized = new AtomicBoolean(false);
        mClosed = new AtomicBoolean(false);
        mAcceptingConnections = new AtomicBoolean(false);

        mL2capListener = new HidL2capListener();
    }

    // ==================== Initialization ====================

    /**
     * Initializes the HID manager.
     *
     * <p>Registers L2CAP listeners for HID PSMs and prepares for connections.
     *
     * @return true if successful
     */
    public boolean initialize() {
        if (mClosed.get()) {
            notifyError(null, "HID manager is closed");
            return false;
        }

        if (mInitialized.get()) {
            return true;
        }

        // Verify L2CAP is initialized
        if (!mL2capManager.isInitialized()) {
            notifyError(null, "L2CAP manager is not initialized");
            return false;
        }

        // Register as L2CAP listener
        mL2capManager.addListener(mL2capListener);

        mInitialized.set(true);
        notifyMessage("HID manager initialized");

        return true;
    }

    /**
     * Returns whether the HID manager is initialized.
     *
     * @return true if initialized and not closed
     */
    public boolean isInitialized() {
        return mInitialized.get() && !mClosed.get();
    }

    /**
     * Starts accepting incoming HID connections.
     *
     * <p>Registers server listeners on HID Control and Interrupt PSMs.
     *
     * @return true if successful
     */
    public boolean startAcceptingConnections() {
        if (!checkInitialized()) return false;

        if (mAcceptingConnections.get()) {
            return true;
        }

        // Register server for HID Control channel
        mL2capManager.registerServer(HidConstants.PSM_HID_CONTROL, new HidServerListener(true));

        // Register server for HID Interrupt channel
        mL2capManager.registerServer(HidConstants.PSM_HID_INTERRUPT, new HidServerListener(false));

        mAcceptingConnections.set(true);
        notifyMessage("Accepting HID connections");

        return true;
    }

    /**
     * Stops accepting incoming HID connections.
     */
    public void stopAcceptingConnections() {
        if (!mAcceptingConnections.compareAndSet(true, false)) {
            return;
        }

        mL2capManager.unregisterServer(HidConstants.PSM_HID_CONTROL);
        mL2capManager.unregisterServer(HidConstants.PSM_HID_INTERRUPT);

        notifyMessage("Stopped accepting HID connections");
    }

    // ==================== Connection Management ====================

    /**
     * Connects to an HID device.
     *
     * <p>Creates an ACL connection (if needed) and establishes both
     * HID Control and Interrupt L2CAP channels.
     *
     * @param address device address (6 bytes)
     * @throws NullPointerException     if address is null
     * @throws IllegalArgumentException if address is not 6 bytes
     */
    public void connect(byte[] address) {
        Objects.requireNonNull(address, "address must not be null");
        if (address.length != 6) {
            throw new IllegalArgumentException("address must be 6 bytes");
        }

        if (!checkInitialized()) return;

        String deviceId = formatAddress(address);

        // Check if already connected
        HidDevice existing = mDevices.get(deviceId);
        if (existing != null && existing.isConnected()) {
            notifyMessage("Device already connected: " + deviceId);
            return;
        }

        // Check if connection already pending
        if (mPendingConnections.containsKey(deviceId)) {
            notifyMessage("Connection already pending: " + deviceId);
            return;
        }

        // Create device
        HidDevice device = new HidDevice(address);
        device.setState(HidDevice.State.CONNECTING_CONTROL);

        // Clear any reconnect info
        cancelReconnect(deviceId);

        // Track pending connection
        PendingConnection pending = new PendingConnection(deviceId, address, device);
        mPendingConnections.put(deviceId, pending);

        // Set connection timeout
        pending.timeoutFuture = mScheduler.schedule(() -> {
            handleConnectionTimeout(deviceId);
        }, CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        notifyMessage("Connecting to HID device: " + deviceId);

        // Create ACL connection first
        mL2capManager.createConnection(address, IL2capConnectionCallback.create(
                channel -> {
                    // ACL connected, now connect control channel
                    handleAclConnected(deviceId, channel.connection);
                },
                reason -> {
                    // ACL connection failed
                    handleConnectionFailed(deviceId, "ACL connection failed: " + reason);
                }
        ));
    }

    /**
     * Disconnects from an HID device.
     *
     * @param device device to disconnect
     */
    public void disconnect(HidDevice device) {
        if (device == null) return;

        String deviceId = device.getDeviceId();
        HidDevice registered = mDevices.get(deviceId);
        if (registered == null) {
            return;
        }

        if (!device.compareAndSetState(HidDevice.State.CONNECTED, HidDevice.State.DISCONNECTING)) {
            // Not in connected state
            if (device.getState() == HidDevice.State.DISCONNECTING) {
                return; // Already disconnecting
            }
        }

        // Cancel any pending reconnect
        cancelReconnect(deviceId);

        notifyMessage("Disconnecting from: " + deviceId);

        // Close channels
        mExecutor.execute(() -> {
            disconnectChannels(device);
        });
    }

    /**
     * Disconnects from an HID device by address.
     *
     * @param address device address (6 bytes)
     */
    public void disconnect(byte[] address) {
        if (address == null || address.length != 6) return;
        String deviceId = formatAddress(address);
        HidDevice device = mDevices.get(deviceId);
        if (device != null) {
            disconnect(device);
        }
    }

    /**
     * Returns a connected device by address.
     *
     * @param address device address (6 bytes)
     * @return device, or null if not connected
     */
    public HidDevice getDevice(byte[] address) {
        if (address == null || address.length != 6) return null;
        return mDevices.get(formatAddress(address));
    }

    /**
     * Returns a connected device by device ID.
     *
     * @param deviceId device ID (formatted address)
     * @return device, or null if not connected
     */
    public HidDevice getDevice(String deviceId) {
        if (deviceId == null) return null;
        return mDevices.get(deviceId);
    }

    /**
     * Returns all connected devices.
     *
     * @return unmodifiable list of connected devices
     */
    public List<HidDevice> getConnectedDevices() {
        List<HidDevice> connected = new ArrayList<>();
        for (HidDevice device : mDevices.values()) {
            if (device.isConnected()) {
                connected.add(device);
            }
        }
        return Collections.unmodifiableList(connected);
    }

    /**
     * Returns the number of connected devices.
     *
     * @return connected device count
     */
    public int getConnectedDeviceCount() {
        int count = 0;
        for (HidDevice device : mDevices.values()) {
            if (device.isConnected()) count++;
        }
        return count;
    }

    // ==================== Report Operations ====================

    /**
     * Sends an output report to a device.
     *
     * <p>Output reports are sent on the interrupt channel using DATA transaction.
     *
     * @param device device to send to
     * @param report output report
     * @return true if sent successfully
     */
    public boolean sendOutputReport(HidDevice device, HidReport report) {
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(report, "report must not be null");

        if (!report.isOutput()) {
            notifyError(device, "Not an output report");
            return false;
        }

        if (!device.isConnected()) {
            notifyError(device, "Device not connected");
            return false;
        }

        L2capChannel interruptChannel = device.getInterruptChannel();
        if (interruptChannel == null || !interruptChannel.isOpen()) {
            notifyError(device, "Interrupt channel not available");
            return false;
        }

        // Create DATA transaction
        byte[] data = report.toTransactionFormat(HidConstants.TRANS_DATA);

        mL2capManager.sendData(interruptChannel, data);
        return true;
    }

    /**
     * Sends a feature report to a device using SET_REPORT.
     *
     * <p>Feature reports are sent on the control channel.
     *
     * @param device   device to send to
     * @param report   feature report
     * @param callback completion callback (may be null)
     * @return true if request was sent
     */
    public boolean sendFeatureReport(HidDevice device, HidReport report, TransactionCallback callback) {
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(report, "report must not be null");

        if (!report.isFeature()) {
            notifyError(device, "Not a feature report");
            if (callback != null) {
                callback.onComplete(false, HidConstants.HANDSHAKE_ERR_INVALID_PARAMETER, null);
            }
            return false;
        }

        return setReport(device, report, callback);
    }

    /**
     * Requests a report from a device using GET_REPORT.
     *
     * @param device     device to request from
     * @param reportType report type (INPUT, OUTPUT, or FEATURE)
     * @param reportId   report ID (0 if not using report IDs)
     * @param callback   completion callback
     * @return true if request was sent
     */
    public boolean getReport(HidDevice device, int reportType, int reportId, TransactionCallback callback) {
        Objects.requireNonNull(device, "device must not be null");

        if (!device.isConnected()) {
            notifyError(device, "Device not connected");
            if (callback != null) {
                callback.onComplete(false, HidConstants.HANDSHAKE_ERR_NOT_READY, null);
            }
            return false;
        }

        L2capChannel controlChannel = device.getControlChannel();
        if (controlChannel == null || !controlChannel.isOpen()) {
            notifyError(device, "Control channel not available");
            if (callback != null) {
                callback.onComplete(false, HidConstants.HANDSHAKE_ERR_NOT_READY, null);
            }
            return false;
        }

        // Create GET_REPORT transaction
        // Header: [Transaction Type (4 bits)][Size (1 bit)][Reserved (1 bit)][Report Type (2 bits)]
        // The "Size" bit indicates if the buffer size field is present
        byte header = HidConstants.createHeader(HidConstants.TRANS_GET_REPORT, reportType);

        byte[] data;
        if (reportId != 0) {
            // Include report ID
            data = new byte[]{header, (byte) reportId};
        } else {
            data = new byte[]{header};
        }

        // Create pending transaction
        int transId = mTransactionIdGenerator.getAndIncrement();
        PendingTransaction pending = new PendingTransaction(
                transId, device.getDeviceId(),
                HidConstants.TRANS_GET_REPORT, reportType, reportId);
        pending.callback = callback;

        String key = device.getDeviceId() + ":" + HidConstants.TRANS_GET_REPORT;
        mPendingTransactions.put(key, pending);

        // Set timeout
        pending.timeoutFuture = mScheduler.schedule(() -> {
            handleTransactionTimeout(key);
        }, TRANSACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        // Send request
        mL2capManager.sendData(controlChannel, data);
        return true;
    }

    /**
     * Sends a report to a device using SET_REPORT.
     *
     * @param device   device to send to
     * @param report   report to send
     * @param callback completion callback (may be null)
     * @return true if request was sent
     */
    public boolean setReport(HidDevice device, HidReport report, TransactionCallback callback) {
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(report, "report must not be null");

        if (!device.isConnected()) {
            notifyError(device, "Device not connected");
            if (callback != null) {
                callback.onComplete(false, HidConstants.HANDSHAKE_ERR_NOT_READY, null);
            }
            return false;
        }

        L2capChannel controlChannel = device.getControlChannel();
        if (controlChannel == null || !controlChannel.isOpen()) {
            notifyError(device, "Control channel not available");
            if (callback != null) {
                callback.onComplete(false, HidConstants.HANDSHAKE_ERR_NOT_READY, null);
            }
            return false;
        }

        // Create SET_REPORT transaction
        byte[] data = report.toTransactionFormat(HidConstants.TRANS_SET_REPORT);

        // Create pending transaction
        int transId = mTransactionIdGenerator.getAndIncrement();
        PendingTransaction pending = new PendingTransaction(
                transId, device.getDeviceId(),
                HidConstants.TRANS_SET_REPORT, report.getType(), report.getReportId());
        pending.callback = callback;

        String key = device.getDeviceId() + ":" + HidConstants.TRANS_SET_REPORT;
        mPendingTransactions.put(key, pending);

        // Set timeout
        pending.timeoutFuture = mScheduler.schedule(() -> {
            handleTransactionTimeout(key);
        }, TRANSACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        // Send request
        mL2capManager.sendData(controlChannel, data);
        return true;
    }

    /**
     * Sends keyboard LED output report.
     *
     * <p>Convenience method for updating keyboard LEDs.
     *
     * @param device device to update
     * @param leds   LED flags (NUM_LOCK, CAPS_LOCK, etc.)
     * @return true if sent successfully
     */
    public boolean setKeyboardLeds(HidDevice device, int leds) {
        return sendOutputReport(device, HidReport.createBootKeyboardOutput(leds));
    }

    // ==================== Protocol Operations ====================

    /**
     * Gets the current protocol mode from a device.
     *
     * @param device   device to query
     * @param callback completion callback
     * @return true if request was sent
     */
    public boolean getProtocol(HidDevice device, TransactionCallback callback) {
        Objects.requireNonNull(device, "device must not be null");

        if (!device.isConnected()) {
            if (callback != null) {
                callback.onComplete(false, HidConstants.HANDSHAKE_ERR_NOT_READY, null);
            }
            return false;
        }

        L2capChannel controlChannel = device.getControlChannel();
        if (controlChannel == null || !controlChannel.isOpen()) {
            if (callback != null) {
                callback.onComplete(false, HidConstants.HANDSHAKE_ERR_NOT_READY, null);
            }
            return false;
        }

        byte header = HidConstants.createHeader(HidConstants.TRANS_GET_PROTOCOL, 0);
        byte[] data = new byte[]{header};

        // Create pending transaction
        int transId = mTransactionIdGenerator.getAndIncrement();
        PendingTransaction pending = new PendingTransaction(
                transId, device.getDeviceId(),
                HidConstants.TRANS_GET_PROTOCOL, 0, 0);
        pending.callback = callback;

        String key = device.getDeviceId() + ":" + HidConstants.TRANS_GET_PROTOCOL;
        mPendingTransactions.put(key, pending);

        pending.timeoutFuture = mScheduler.schedule(() -> {
            handleTransactionTimeout(key);
        }, TRANSACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        mL2capManager.sendData(controlChannel, data);
        return true;
    }

    /**
     * Sets the protocol mode on a device.
     *
     * @param device   device to configure
     * @param protocol protocol mode (BOOT or REPORT)
     * @param callback completion callback (may be null)
     * @return true if request was sent
     */
    public boolean setProtocol(HidDevice device, int protocol, TransactionCallback callback) {
        Objects.requireNonNull(device, "device must not be null");

        if (!HidConstants.isValidProtocol(protocol)) {
            if (callback != null) {
                callback.onComplete(false, HidConstants.HANDSHAKE_ERR_INVALID_PARAMETER, null);
            }
            return false;
        }

        if (!device.isConnected()) {
            if (callback != null) {
                callback.onComplete(false, HidConstants.HANDSHAKE_ERR_NOT_READY, null);
            }
            return false;
        }

        L2capChannel controlChannel = device.getControlChannel();
        if (controlChannel == null || !controlChannel.isOpen()) {
            if (callback != null) {
                callback.onComplete(false, HidConstants.HANDSHAKE_ERR_NOT_READY, null);
            }
            return false;
        }

        byte header = HidConstants.createHeader(HidConstants.TRANS_SET_PROTOCOL, protocol);
        byte[] data = new byte[]{header};

        // Create pending transaction
        int transId = mTransactionIdGenerator.getAndIncrement();
        PendingTransaction pending = new PendingTransaction(
                transId, device.getDeviceId(),
                HidConstants.TRANS_SET_PROTOCOL, protocol, 0);
        pending.callback = callback;

        String key = device.getDeviceId() + ":" + HidConstants.TRANS_SET_PROTOCOL;
        mPendingTransactions.put(key, pending);

        pending.timeoutFuture = mScheduler.schedule(() -> {
            handleTransactionTimeout(key);
        }, TRANSACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        mL2capManager.sendData(controlChannel, data);
        return true;
    }

    /**
     * Switches device to boot protocol mode.
     *
     * @param device   device to configure
     * @param callback completion callback (may be null)
     * @return true if request was sent
     */
    public boolean setBootProtocol(HidDevice device, TransactionCallback callback) {
        return setProtocol(device, HidConstants.PROTOCOL_BOOT, callback);
    }

    /**
     * Switches device to report protocol mode.
     *
     * @param device   device to configure
     * @param callback completion callback (may be null)
     * @return true if request was sent
     */
    public boolean setReportProtocol(HidDevice device, TransactionCallback callback) {
        return setProtocol(device, HidConstants.PROTOCOL_REPORT, callback);
    }

    // ==================== Idle Rate Operations ====================

    /**
     * Gets the idle rate from a device.
     *
     * @param device   device to query
     * @param callback completion callback
     * @return true if request was sent
     */
    public boolean getIdle(HidDevice device, TransactionCallback callback) {
        Objects.requireNonNull(device, "device must not be null");

        if (!device.isConnected()) {
            if (callback != null) {
                callback.onComplete(false, HidConstants.HANDSHAKE_ERR_NOT_READY, null);
            }
            return false;
        }

        L2capChannel controlChannel = device.getControlChannel();
        if (controlChannel == null || !controlChannel.isOpen()) {
            if (callback != null) {
                callback.onComplete(false, HidConstants.HANDSHAKE_ERR_NOT_READY, null);
            }
            return false;
        }

        byte header = HidConstants.createHeader(HidConstants.TRANS_GET_IDLE, 0);
        byte[] data = new byte[]{header};

        int transId = mTransactionIdGenerator.getAndIncrement();
        PendingTransaction pending = new PendingTransaction(
                transId, device.getDeviceId(),
                HidConstants.TRANS_GET_IDLE, 0, 0);
        pending.callback = callback;

        String key = device.getDeviceId() + ":" + HidConstants.TRANS_GET_IDLE;
        mPendingTransactions.put(key, pending);

        pending.timeoutFuture = mScheduler.schedule(() -> {
            handleTransactionTimeout(key);
        }, TRANSACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        mL2capManager.sendData(controlChannel, data);
        return true;
    }

    /**
     * Sets the idle rate on a device.
     *
     * @param device   device to configure
     * @param idleRate idle rate in 4ms units (0 = indefinite)
     * @param callback completion callback (may be null)
     * @return true if request was sent
     */
    public boolean setIdle(HidDevice device, int idleRate, TransactionCallback callback) {
        Objects.requireNonNull(device, "device must not be null");

        if (!device.isConnected()) {
            if (callback != null) {
                callback.onComplete(false, HidConstants.HANDSHAKE_ERR_NOT_READY, null);
            }
            return false;
        }

        L2capChannel controlChannel = device.getControlChannel();
        if (controlChannel == null || !controlChannel.isOpen()) {
            if (callback != null) {
                callback.onComplete(false, HidConstants.HANDSHAKE_ERR_NOT_READY, null);
            }
            return false;
        }

        byte header = HidConstants.createHeader(HidConstants.TRANS_SET_IDLE, 0);
        byte[] data = new byte[]{header, (byte) (idleRate & 0xFF)};

        int transId = mTransactionIdGenerator.getAndIncrement();
        PendingTransaction pending = new PendingTransaction(
                transId, device.getDeviceId(),
                HidConstants.TRANS_SET_IDLE, idleRate, 0);
        pending.callback = callback;

        String key = device.getDeviceId() + ":" + HidConstants.TRANS_SET_IDLE;
        mPendingTransactions.put(key, pending);

        pending.timeoutFuture = mScheduler.schedule(() -> {
            handleTransactionTimeout(key);
        }, TRANSACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        mL2capManager.sendData(controlChannel, data);
        return true;
    }

    // ==================== Control Operations ====================

    /**
     * Sends a HID_CONTROL operation to a device.
     *
     * @param device    device to control
     * @param operation control operation (SUSPEND, EXIT_SUSPEND, etc.)
     * @return true if sent successfully
     */
    public boolean sendControl(HidDevice device, int operation) {
        Objects.requireNonNull(device, "device must not be null");

        if (!device.isConnected()) {
            return false;
        }

        L2capChannel controlChannel = device.getControlChannel();
        if (controlChannel == null || !controlChannel.isOpen()) {
            return false;
        }

        byte header = HidConstants.createHeader(HidConstants.TRANS_HID_CONTROL, operation);
        byte[] data = new byte[]{header};

        mL2capManager.sendData(controlChannel, data);

        // Handle local state changes
        switch (operation) {
            case HidConstants.CTRL_SUSPEND:
                device.setSuspended(true);
                mExecutor.execute(() -> notifyDeviceSuspended(device));
                break;
            case HidConstants.CTRL_EXIT_SUSPEND:
                device.setSuspended(false);
                mExecutor.execute(() -> notifyDeviceResumed(device));
                break;
            case HidConstants.CTRL_VIRTUAL_CABLE_UNPLUG:
                break;
        }

        return true;
    }

    /**
     * Suspends a device (enters low power mode).
     *
     * @param device device to suspend
     * @return true if sent successfully
     */
    public boolean suspend(HidDevice device) {
        return sendControl(device, HidConstants.CTRL_SUSPEND);
    }

    /**
     * Resumes a suspended device.
     *
     * @param device device to resume
     * @return true if sent successfully
     */
    public boolean resume(HidDevice device) {
        return sendControl(device, HidConstants.CTRL_EXIT_SUSPEND);
    }

    /**
     * Sends virtual cable unplug to a device.
     *
     * <p>This terminates the connection and indicates the user has
     * "unplugged" the device.
     *
     * @param device device to unplug
     * @return true if sent successfully
     */
    public boolean virtualCableUnplug(HidDevice device) {
        boolean sent = sendControl(device, HidConstants.CTRL_VIRTUAL_CABLE_UNPLUG);
        if (sent) {
            // Disable auto-reconnect for this device
            cancelReconnect(device.getDeviceId());
        }
        return sent;
    }

    // ==================== Configuration ====================

    /**
     * Sets whether to automatically reconnect to disconnected devices.
     *
     * @param autoReconnect true to enable auto-reconnect
     */
    public void setAutoReconnect(boolean autoReconnect) {
        mAutoReconnect = autoReconnect;
        if (!autoReconnect) {
            // Cancel all pending reconnects
            for (String deviceId : mReconnectInfo.keySet()) {
                cancelReconnect(deviceId);
            }
        }
    }

    /**
     * Returns whether auto-reconnect is enabled.
     *
     * @return true if enabled
     */
    public boolean isAutoReconnect() {
        return mAutoReconnect;
    }

    /**
     * Sets whether to use boot protocol by default.
     *
     * @param useBootProtocol true to use boot protocol
     */
    public void setUseBootProtocol(boolean useBootProtocol) {
        mUseBootProtocol = useBootProtocol;
    }

    /**
     * Returns whether boot protocol is used by default.
     *
     * @return true if using boot protocol
     */
    public boolean isUseBootProtocol() {
        return mUseBootProtocol;
    }

    /**
     * Sets the default idle rate for connected devices.
     *
     * @param idleRate idle rate in 4ms units (0 = indefinite)
     */
    public void setDefaultIdleRate(int idleRate) {
        mDefaultIdleRate = idleRate & 0xFF;
    }

    /**
     * Returns the default idle rate.
     *
     * @return idle rate in 4ms units
     */
    public int getDefaultIdleRate() {
        return mDefaultIdleRate;
    }

    // ==================== Listener Management ====================

    /**
     * Adds an HID event listener.
     *
     * @param listener listener to add
     */
    public void addListener(IHidListener listener) {
        if (listener != null && !mAdditionalListeners.contains(listener)) {
            mAdditionalListeners.add(listener);
        }
    }

    /**
     * Removes an HID event listener.
     *
     * @param listener listener to remove
     */
    public void removeListener(IHidListener listener) {
        mAdditionalListeners.remove(listener);
    }

    // ==================== Cleanup ====================

    /**
     * Closes the HID manager and releases all resources.
     */
    @Override
    public void close() {
        if (!mClosed.compareAndSet(false, true)) {
            return;
        }

        notifyMessage("Closing HID manager");

        // Stop accepting connections
        stopAcceptingConnections();

        // Cancel all pending connections
        for (PendingConnection pending : mPendingConnections.values()) {
            pending.cancel();
        }
        mPendingConnections.clear();

        // Cancel all pending transactions
        for (PendingTransaction pending : mPendingTransactions.values()) {
            pending.complete();
        }
        mPendingTransactions.clear();
        mReassemblyBuffers.clear();

        // Cancel all reconnects
        for (String deviceId : new ArrayList<>(mReconnectInfo.keySet())) {
            cancelReconnect(deviceId);
        }

        // Disconnect all devices
        for (HidDevice device : new ArrayList<>(mDevices.values())) {
            disconnectChannels(device);
        }
        mDevices.clear();
        mDevicesByHandle.clear();
        mDevicesByControlCid.clear();
        mDevicesByInterruptCid.clear();

        // Remove L2CAP listener
        mL2capManager.removeListener(mL2capListener);

        // Shutdown executors
        mScheduler.shutdown();
        mExecutor.shutdown();
        try {
            if (!mScheduler.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                mScheduler.shutdownNow();
            }
            if (!mExecutor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                mExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            mScheduler.shutdownNow();
            mExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        mInitialized.set(false);
    }

    // ==================== Internal Connection Handling ====================

    private void handleAclConnected(String deviceId, AclConnection connection) {
        PendingConnection pending = mPendingConnections.get(deviceId);
        if (pending == null || pending.cancelled) {
            return;
        }

        HidDevice device = pending.device;
        device.setAclConnection(connection);

        notifyMessage("ACL connected, opening control channel: " + deviceId);

        // Now connect the HID Control channel
        mL2capManager.connectChannel(connection, HidConstants.PSM_HID_CONTROL,
                IL2capConnectionCallback.create(
                        controlChannel -> {
                            handleControlChannelConnected(deviceId, controlChannel);
                        },
                        reason -> {
                            handleConnectionFailed(deviceId, "Control channel failed: " + reason);
                        }
                ));
    }

    private void handleControlChannelConnected(String deviceId, L2capChannel channel) {
        PendingConnection pending = mPendingConnections.get(deviceId);
        if (pending == null || pending.cancelled) {
            return;
        }

        HidDevice device = pending.device;
        device.setControlChannel(channel);
        device.setState(HidDevice.State.CONNECTING_INTERRUPT);

        // Track channel
        mDevicesByControlCid.put(channel.localCid, device);

        notifyMessage("Control channel connected, opening interrupt channel: " + deviceId);

        // Now connect the HID Interrupt channel
        AclConnection connection = device.getAclConnection();
        if (connection == null) {
            handleConnectionFailed(deviceId, "ACL connection lost");
            return;
        }

        mL2capManager.connectChannel(connection, HidConstants.PSM_HID_INTERRUPT,
                IL2capConnectionCallback.create(
                        interruptChannel -> {
                            handleInterruptChannelConnected(deviceId, interruptChannel);
                        },
                        reason -> {
                            handleConnectionFailed(deviceId, "Interrupt channel failed: " + reason);
                        }
                ));
    }

    private void handleInterruptChannelConnected(String deviceId, L2capChannel channel) {
        PendingConnection pending = mPendingConnections.remove(deviceId);
        if (pending == null || pending.cancelled) {
            return;
        }

        // Cancel timeout
        pending.cancel();

        HidDevice device = pending.device;
        device.setInterruptChannel(channel);
        device.setState(HidDevice.State.CONFIGURING);

        // Track channel
        mDevicesByInterruptCid.put(channel.localCid, device);

        notifyMessage("Interrupt channel connected: " + deviceId);

        // Complete connection setup
        completeConnection(device);
    }

    private void completeConnection(HidDevice device) {
        String deviceId = device.getDeviceId();

        // Register device
        mDevices.put(deviceId, device);

        AclConnection acl = device.getAclConnection();
        if (acl != null) {
            mDevicesByHandle.put(acl.handle, device);
        }

        // Set connected state
        device.setState(HidDevice.State.CONNECTED);

        notifyMessage("HID device connected: " + deviceId);

        // Apply default configuration
        mExecutor.execute(() -> {
            configureDevice(device);
        });

        // Notify listeners
        mExecutor.execute(() -> {
            notifyDeviceConnected(device);
        });
    }

    private void configureDevice(HidDevice device) {
        // Set protocol mode if needed
        if (mUseBootProtocol && device.supportsBootProtocol()) {
            setBootProtocol(device, (success, result, data) -> {
                if (success) {
                    device.setProtocolMode(HidConstants.PROTOCOL_BOOT);
                    notifyProtocolChanged(device, HidConstants.PROTOCOL_BOOT);
                }
            });
        }

        // Set idle rate if configured
        if (mDefaultIdleRate != HidConstants.IDLE_RATE_INDEFINITE) {
            setIdle(device, mDefaultIdleRate, null);
        }
    }

    private void handleConnectionFailed(String deviceId, String reason) {
        PendingConnection pending = mPendingConnections.remove(deviceId);
        if (pending == null) {
            return;
        }

        pending.cancel();

        notifyError(null, "Connection failed to " + deviceId + ": " + reason);

        // Cleanup any partial state
        HidDevice device = pending.device;
        if (device != null) {
            cleanupDevice(device);
        }

        // Notify listeners
        mExecutor.execute(() -> {
            notifyConnectionFailed(pending.address, reason);
        });
    }

    private void handleConnectionTimeout(String deviceId) {
        handleConnectionFailed(deviceId, "Connection timed out");
    }

    private void handleTransactionTimeout(String key) {
        PendingTransaction pending = mPendingTransactions.remove(key);
        if (pending == null || pending.completed) {
            return;
        }

        pending.complete();

        HidDevice device = mDevices.get(pending.deviceId);
        notifyError(device, "Transaction timed out: " +
                HidConstants.getTransactionName(pending.transactionType));

        if (pending.callback != null) {
            pending.callback.onComplete(false, HidConstants.HANDSHAKE_ERR_UNKNOWN, null);
        }
    }

    private void disconnectChannels(HidDevice device) {
        L2capChannel controlChannel = device.getControlChannel();
        L2capChannel interruptChannel = device.getInterruptChannel();

        // Send L2CAP disconnect requests for each channel
        if (interruptChannel != null && interruptChannel.isOpen()) {
            sendL2capDisconnect(interruptChannel);
        }

        if (controlChannel != null && controlChannel.isOpen()) {
            sendL2capDisconnect(controlChannel);
        }
    }

    /**
     * Sends L2CAP disconnection request for a channel.
     */
    private void sendL2capDisconnect(L2capChannel channel) {
        if (channel == null || channel.connection == null) return;

        // Build Disconnection Request
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) L2capConstants.CMD_DISCONNECTION_REQUEST);
        buf.put((byte) mTransactionIdGenerator.getAndIncrement()); // Identifier
        buf.putShort((short) 4);  // Length
        buf.putShort((short) channel.getRemoteCid());
        buf.putShort((short) channel.localCid);

        // Send on signaling channel (fixed channel CID 0x0001)
        mL2capManager.sendFixedChannelData(channel.connection.handle,
                L2capConstants.CID_SIGNALING, buf.array());
    }

    private void cleanupDevice(HidDevice device) {
        String deviceId = device.getDeviceId();

        // Read channels before clearing them
        L2capChannel control = device.getControlChannel();
        L2capChannel interrupt = device.getInterruptChannel();
        AclConnection acl = device.getAclConnection();

        device.setState(HidDevice.State.DISCONNECTED);
        device.setControlChannel(null);
        device.setInterruptChannel(null);

        mDevices.remove(deviceId);
        mReassemblyBuffers.remove(deviceId);

        if (acl != null) {
            mDevicesByHandle.remove(acl.handle);
        }

        if (control != null) {
            mDevicesByControlCid.remove(control.localCid);
        }

        if (interrupt != null) {
            mDevicesByInterruptCid.remove(interrupt.localCid);
        }
    }

    // ==================== Reconnection ====================

    private void scheduleReconnect(HidDevice device, int reason) {
        if (!mAutoReconnect) {
            return;
        }

        String deviceId = device.getDeviceId();
        byte[] address = device.getAddress();

        // Don't reconnect for user-initiated disconnects
        if (reason == HidConstants.CTRL_VIRTUAL_CABLE_UNPLUG) {
            return;
        }

        ReconnectInfo info = mReconnectInfo.get(deviceId);
        if (info == null) {
            info = new ReconnectInfo(address);
            mReconnectInfo.put(deviceId, info);
        }

        if (info.attempts >= MAX_RECONNECT_ATTEMPTS) {
            notifyMessage("Max reconnect attempts reached for: " + deviceId);
            mReconnectInfo.remove(deviceId);
            return;
        }

        info.attempts++;
        info.lastAttempt = System.currentTimeMillis();

        int delay = RECONNECT_DELAY_MS * info.attempts;
        final ReconnectInfo finalInfo = info;

        notifyMessage("Scheduling reconnect attempt " + info.attempts + " for: " + deviceId);

        info.scheduledReconnect = mScheduler.schedule(() -> {
            if (!mClosed.get() && mReconnectInfo.containsKey(deviceId)) {
                notifyMessage("Attempting reconnect to: " + deviceId);
                connect(finalInfo.address);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private void cancelReconnect(String deviceId) {
        ReconnectInfo info = mReconnectInfo.remove(deviceId);
        if (info != null && info.scheduledReconnect != null) {
            info.scheduledReconnect.cancel(false);
        }
    }

    // ==================== Data Handling ====================

    private void handleControlData(HidDevice device, byte[] data) {
        if (data == null || data.length < 1) {
            return;
        }

        device.updateActivityTime();

        int header = data[0] & 0xFF;
        int transactionType = HidConstants.getTransactionType(header);
        int param = HidConstants.getParameter(header);

        switch (transactionType) {
            case HidConstants.TRANS_HANDSHAKE:
                handleHandshake(device, param);
                break;

            case HidConstants.TRANS_HID_CONTROL:
                handleHidControl(device, param);
                break;

            case HidConstants.TRANS_DATA:
                handleDataOnControl(device, param, data);
                break;

            case HidConstants.TRANS_DATC:
                handleDataContinuation(device, data);
                break;

            default:
                notifyError(device, "Unexpected control transaction: " +
                        HidConstants.getTransactionTypeName(transactionType));
        }
    }

    private void handleHandshake(HidDevice device, int result) {
        notifyHandshake(device, result);

        // Complete any pending transaction
        String getReportKey = device.getDeviceId() + ":" + HidConstants.TRANS_GET_REPORT;
        String setReportKey = device.getDeviceId() + ":" + HidConstants.TRANS_SET_REPORT;
        String setProtocolKey = device.getDeviceId() + ":" + HidConstants.TRANS_SET_PROTOCOL;
        String setIdleKey = device.getDeviceId() + ":" + HidConstants.TRANS_SET_IDLE;

        PendingTransaction pending = mPendingTransactions.remove(setReportKey);
        if (pending == null) pending = mPendingTransactions.remove(setProtocolKey);
        if (pending == null) pending = mPendingTransactions.remove(setIdleKey);
        if (pending == null) pending = mPendingTransactions.remove(getReportKey);

        if (pending != null) {
            pending.complete();
            boolean success = (result == HidConstants.HANDSHAKE_SUCCESSFUL);

            // Notify based on transaction type
            switch (pending.transactionType) {
                case HidConstants.TRANS_SET_REPORT:
                    notifySetReportComplete(device, success, result);
                    break;
                case HidConstants.TRANS_SET_PROTOCOL:
                    if (success) {
                        device.setProtocolMode(pending.reportType);
                        notifyProtocolChanged(device, pending.reportType);
                    }
                    notifySetProtocolComplete(device, success);
                    break;
                case HidConstants.TRANS_SET_IDLE:
                    if (success) {
                        device.setIdleRate(pending.reportType);
                    }
                    notifySetIdleComplete(device, success);
                    break;
            }

            if (pending.callback != null) {
                pending.callback.onComplete(success, result, null);
            }
        }
    }

    private void handleHidControl(HidDevice device, int operation) {
        switch (operation) {
            case HidConstants.CTRL_SUSPEND:
                device.setSuspended(true);
                notifyDeviceSuspended(device);
                break;

            case HidConstants.CTRL_EXIT_SUSPEND:
                device.setSuspended(false);
                notifyDeviceResumed(device);
                break;

            case HidConstants.CTRL_VIRTUAL_CABLE_UNPLUG:
                notifyVirtualCableUnplug(device);
                // Cancel auto-reconnect
                cancelReconnect(device.getDeviceId());
                // Device will disconnect
                break;

            default:
                notifyMessage("Unhandled HID_CONTROL: " +
                        HidConstants.getControlName(operation));
        }
    }

    private void handleDataOnControl(HidDevice device, int reportType, byte[] data) {
        if (data.length < 2) {
            return;
        }

        // Flush any previous incomplete reassembly
        mReassemblyBuffers.remove(device.getDeviceId());

        // Check for pending GET_REPORT
        String key = device.getDeviceId() + ":" + HidConstants.TRANS_GET_REPORT;
        PendingTransaction pending = mPendingTransactions.remove(key);

        byte[] reportData = new byte[data.length - 1];
        System.arraycopy(data, 1, reportData, 0, reportData.length);

        boolean usesReportId = device.usesReportIds();
        HidReport report = HidReport.fromWireFormat(reportType, reportData, usesReportId);

        if (pending != null) {
            pending.complete();
            notifyGetReportComplete(device, report, true);
            if (pending.callback != null) {
                pending.callback.onComplete(true, HidConstants.HANDSHAKE_SUCCESSFUL, reportData);
            }
        } else {
            // Unsolicited data on control channel
            if (reportType == HidConstants.REPORT_TYPE_FEATURE) {
                notifyFeatureReport(device, report);
            }
        }
    }

    private void handleDataContinuation(HidDevice device, byte[] data) {
        if (data.length < 2) {
            return;
        }

        String deviceId = device.getDeviceId();

        // Append continuation data to reassembly buffer
        java.io.ByteArrayOutputStream buffer = mReassemblyBuffers.computeIfAbsent(
                deviceId, k -> new java.io.ByteArrayOutputStream());
        buffer.write(data, 1, data.length - 1);

        // Check if there's a pending GET_REPORT expecting more data
        String key = deviceId + ":" + HidConstants.TRANS_GET_REPORT;
        PendingTransaction pending = mPendingTransactions.get(key);
        if (pending == null) {
            // No pending transaction; the next DATA will flush the buffer
            return;
        }

        // Determine if this is the last fragment by checking if the payload
        // is smaller than the L2CAP MTU (indicating it's the final segment)
        L2capChannel controlChannel = device.getControlChannel();
        int mtu = (controlChannel != null) ? controlChannel.getEffectiveMtu() : 672;

        if (data.length < mtu) {
            // Last fragment — reassembly complete
            mPendingTransactions.remove(key);
            pending.complete();

            byte[] reassembled = buffer.toByteArray();
            mReassemblyBuffers.remove(deviceId);

            int reportType = HidConstants.getParameter(data[0]) & 0x03;
            boolean usesReportId = device.usesReportIds();
            HidReport report = HidReport.fromWireFormat(reportType, reassembled, usesReportId);

            notifyGetReportComplete(device, report, true);
            if (pending.callback != null) {
                pending.callback.onComplete(true, HidConstants.HANDSHAKE_SUCCESSFUL, reassembled);
            }
        }
    }

    private void handleInterruptData(HidDevice device, byte[] data) {
        if (data == null || data.length < 1) {
            return;
        }

        device.updateActivityTime();

        int header = data[0] & 0xFF;
        int transactionType = HidConstants.getTransactionType(header);
        int reportType = HidConstants.getParameter(header);

        if (transactionType != HidConstants.TRANS_DATA) {
            notifyError(device, "Unexpected interrupt transaction: " +
                    HidConstants.getTransactionTypeName(transactionType));
            return;
        }

        // Parse report
        if (data.length < 2) {
            return;
        }

        byte[] reportData = new byte[data.length - 1];
        System.arraycopy(data, 1, reportData, 0, reportData.length);

        boolean usesReportId = device.usesReportIds();
        HidReport report = HidReport.fromWireFormat(reportType, reportData, usesReportId);

        // Notify based on report type
        switch (reportType) {
            case HidConstants.REPORT_TYPE_INPUT:
                notifyInputReport(device, report);
                break;
            case HidConstants.REPORT_TYPE_OUTPUT:
                // Output reports on interrupt channel are unusual
                notifyMessage("Received OUTPUT report on interrupt channel");
                break;
            case HidConstants.REPORT_TYPE_FEATURE:
                notifyFeatureReport(device, report);
                break;
        }
    }

    // ==================== Incoming Connection Handling ====================

    private void handleIncomingControlChannel(L2capChannel channel) {
        AclConnection acl = channel.connection;
        String deviceId = formatAddress(acl.getPeerAddress());

        notifyMessage("Incoming HID control connection: " + deviceId);

        // Look for existing device or create new one
        HidDevice device = mDevices.get(deviceId);
        if (device == null) {
            device = new HidDevice(acl);
            device.setState(HidDevice.State.CONNECTING_INTERRUPT);
            mDevices.put(deviceId, device);
        }

        device.setAclConnection(acl);
        device.setControlChannel(channel);
        mDevicesByControlCid.put(channel.localCid, device);
        mDevicesByHandle.put(acl.handle, device);
    }

    private void handleIncomingInterruptChannel(L2capChannel channel) {
        AclConnection acl = channel.connection;
        String deviceId = formatAddress(acl.getPeerAddress());

        notifyMessage("Incoming HID interrupt connection: " + deviceId);

        HidDevice device = mDevices.get(deviceId);
        if (device == null) {
            notifyError(null, "Interrupt channel without control channel: " + deviceId);
            sendL2capDisconnect(channel);
            return;
        }

        device.setInterruptChannel(channel);
        mDevicesByInterruptCid.put(channel.localCid, device);

        // Complete connection
        completeConnection(device);
    }

    // ==================== Notification Methods ====================

    private void notifyDeviceConnected(HidDevice device) {
        mListener.onDeviceConnected(device);
        for (IHidListener listener : mAdditionalListeners) {
            listener.onDeviceConnected(device);
        }
    }

    private void notifyDeviceDisconnected(HidDevice device, int reason) {
        mListener.onDeviceDisconnected(device, reason);
        for (IHidListener listener : mAdditionalListeners) {
            listener.onDeviceDisconnected(device, reason);
        }
    }

    private void notifyConnectionFailed(byte[] address, String reason) {
        mListener.onConnectionFailed(address, reason);
        for (IHidListener listener : mAdditionalListeners) {
            listener.onConnectionFailed(address, reason);
        }
    }

    private void notifyInputReport(HidDevice device, HidReport report) {
        mListener.onInputReport(device, report);
        for (IHidListener listener : mAdditionalListeners) {
            listener.onInputReport(device, report);
        }
    }

    private void notifyFeatureReport(HidDevice device, HidReport report) {
        mListener.onFeatureReport(device, report);
        for (IHidListener listener : mAdditionalListeners) {
            listener.onFeatureReport(device, report);
        }
    }

    private void notifyGetReportComplete(HidDevice device, HidReport report, boolean success) {
        mListener.onGetReportComplete(device, report, success);
        for (IHidListener listener : mAdditionalListeners) {
            listener.onGetReportComplete(device, report, success);
        }
    }

    private void notifySetReportComplete(HidDevice device, boolean success, int result) {
        mListener.onSetReportComplete(device, success, result);
        for (IHidListener listener : mAdditionalListeners) {
            listener.onSetReportComplete(device, success, result);
        }
    }

    private void notifyGetProtocolComplete(HidDevice device, int protocol, boolean success) {
        mListener.onGetProtocolComplete(device, protocol, success);
        for (IHidListener listener : mAdditionalListeners) {
            listener.onGetProtocolComplete(device, protocol, success);
        }
    }

    private void notifySetProtocolComplete(HidDevice device, boolean success) {
        mListener.onSetProtocolComplete(device, success);
        for (IHidListener listener : mAdditionalListeners) {
            listener.onSetProtocolComplete(device, success);
        }
    }

    private void notifyProtocolChanged(HidDevice device, int protocol) {
        mListener.onProtocolChanged(device, protocol);
        for (IHidListener listener : mAdditionalListeners) {
            listener.onProtocolChanged(device, protocol);
        }
    }

    private void notifyGetIdleComplete(HidDevice device, int idleRate, boolean success) {
        mListener.onGetIdleComplete(device, idleRate, success);
        for (IHidListener listener : mAdditionalListeners) {
            listener.onGetIdleComplete(device, idleRate, success);
        }
    }

    private void notifySetIdleComplete(HidDevice device, boolean success) {
        mListener.onSetIdleComplete(device, success);
        for (IHidListener listener : mAdditionalListeners) {
            listener.onSetIdleComplete(device, success);
        }
    }

    private void notifyControlComplete(HidDevice device, int operation, boolean success) {
        mListener.onControlComplete(device, operation, success);
        for (IHidListener listener : mAdditionalListeners) {
            listener.onControlComplete(device, operation, success);
        }
    }

    private void notifyVirtualCableUnplug(HidDevice device) {
        mListener.onVirtualCableUnplug(device);
        for (IHidListener listener : mAdditionalListeners) {
            listener.onVirtualCableUnplug(device);
        }
    }

    private void notifyDeviceSuspended(HidDevice device) {
        mListener.onDeviceSuspended(device);
        for (IHidListener listener : mAdditionalListeners) {
            listener.onDeviceSuspended(device);
        }
    }

    private void notifyDeviceResumed(HidDevice device) {
        mListener.onDeviceResumed(device);
        for (IHidListener listener : mAdditionalListeners) {
            listener.onDeviceResumed(device);
        }
    }

    private void notifyHandshake(HidDevice device, int result) {
        mListener.onHandshake(device, result);
        for (IHidListener listener : mAdditionalListeners) {
            listener.onHandshake(device, result);
        }
    }

    private void notifyDescriptorReceived(HidDevice device, HidReportDescriptor descriptor) {
        mListener.onDescriptorReceived(device, descriptor);
        for (IHidListener listener : mAdditionalListeners) {
            listener.onDescriptorReceived(device, descriptor);
        }
    }

    private void notifyError(HidDevice device, String message) {
        mListener.onError(device, message);
        for (IHidListener listener : mAdditionalListeners) {
            listener.onError(device, message);
        }
    }

    private void notifyMessage(String message) {
        mListener.onMessage(message);
        for (IHidListener listener : mAdditionalListeners) {
            listener.onMessage(message);
        }
    }

    // ==================== Utility Methods ====================

    private boolean checkInitialized() {
        if (!mInitialized.get() || mClosed.get()) {
            notifyError(null, "HID manager not initialized");
            return false;
        }
        return true;
    }

    private static String formatAddress(byte[] address) {
        if (address == null || address.length != 6) {
            return "??:??:??:??:??:??";
        }
        return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                address[5] & 0xFF, address[4] & 0xFF,
                address[3] & 0xFF, address[2] & 0xFF,
                address[1] & 0xFF, address[0] & 0xFF);
    }

    // ==================== L2CAP Listener ====================

    /**
     * Internal L2CAP listener for HID events.
     */
    private class HidL2capListener implements IL2capListener {

        @Override
        public void onConnectionComplete(AclConnection connection) {
            // Handled via connection callbacks
        }

        @Override
        public void onDisconnectionComplete(int handle, int reason) {
            HidDevice device = mDevicesByHandle.get(handle);
            if (device != null) {
                mExecutor.execute(() -> {
                    handleDeviceDisconnected(device, reason);
                });
            }
        }

        @Override
        public void onChannelOpened(L2capChannel channel) {
            // Channels opened via our connection flow are handled directly
        }

        @Override
        public void onChannelClosed(L2capChannel channel) {
            HidDevice device = mDevicesByControlCid.get(channel.localCid);
            if (device == null) {
                device = mDevicesByInterruptCid.get(channel.localCid);
            }

            if (device != null) {
                final HidDevice finalDevice = device;
                mExecutor.execute(() -> {
                    handleChannelClosed(finalDevice, channel);
                });
            }
        }

        @Override
        public void onDataReceived(L2capChannel channel, byte[] data) {
            HidDevice controlDevice = mDevicesByControlCid.get(channel.localCid);
            if (controlDevice != null) {
                final HidDevice finalDevice = controlDevice;
                mExecutor.execute(() -> {
                    handleControlData(finalDevice, data);
                });
                return;
            }

            HidDevice interruptDevice = mDevicesByInterruptCid.get(channel.localCid);
            if (interruptDevice != null) {
                final HidDevice finalDevice = interruptDevice;
                mExecutor.execute(() -> {
                    handleInterruptData(finalDevice, data);
                });
            }
        }

        @Override
        public void onError(String message) {
            notifyError(null, "L2CAP error: " + message);
        }

        @Override
        public void onMessage(String message) {
            // Forward L2CAP messages if needed
        }

        private void handleDeviceDisconnected(HidDevice device, int reason) {
            String deviceId = device.getDeviceId();

            // Cleanup mappings
            cleanupDevice(device);

            // Clear any pending transactions
            mPendingTransactions.entrySet().removeIf(entry -> {
                if (entry.getValue().deviceId.equals(deviceId)) {
                    entry.getValue().complete();
                    return true;
                }
                return false;
            });

            // Notify listeners
            notifyDeviceDisconnected(device, reason);

            // Schedule reconnect if applicable
            scheduleReconnect(device, reason);
        }

        private void handleChannelClosed(HidDevice device, L2capChannel channel) {
            if (channel.psm == HidConstants.PSM_HID_CONTROL) {
                device.setControlChannel(null);
                mDevicesByControlCid.remove(channel.localCid);
            } else if (channel.psm == HidConstants.PSM_HID_INTERRUPT) {
                device.setInterruptChannel(null);
                mDevicesByInterruptCid.remove(channel.localCid);
            }

            // If both channels are gone, device is disconnected
            if (!device.hasControlChannel() && !device.hasInterruptChannel()) {
                if (device.getState() != HidDevice.State.DISCONNECTED) {
                    handleDeviceDisconnected(device, 0);
                }
            }
        }
    }

    // ==================== Server Listener ====================

    /**
     * Internal L2CAP server listener for incoming HID connections.
     */
    private class HidServerListener implements IL2capServerListener {
        private final boolean isControl;

        HidServerListener(boolean isControl) {
            this.isControl = isControl;
        }

        @Override
        public boolean onConnectionRequest(L2capChannel channel) {
            // Accept HID connections
            return true;
        }

        @Override
        public void onChannelOpened(L2capChannel channel) {
            mExecutor.execute(() -> {
                if (isControl) {
                    handleIncomingControlChannel(channel);
                } else {
                    handleIncomingInterruptChannel(channel);
                }
            });
        }

        @Override
        public void onDataReceived(L2capChannel channel, byte[] data) {
            // Data is handled by the main L2CAP listener
        }

        @Override
        public void onChannelClosed(L2capChannel channel) {
            // Channel closed is handled by the main L2CAP listener
        }

        @Override
        public void onError(L2capChannel channel, String message) {
            notifyError(null, "HID server error: " + message);
        }
    }

    // ==================== SDP Parsing ====================

    /**
     * Parses HID-specific attributes from SDP service record.
     *
     * <p>This can be used to extract HID information when doing SDP queries.
     *
     * @param device device to populate
     * @param sdpData raw SDP service record data
     */
    public void parseSdpRecord(HidDevice device, byte[] sdpData) {
        if (sdpData == null || sdpData.length < 10) {
            return;
        }

        try {
            parseSdpAttributes(device, sdpData);
        } catch (Exception e) {
            notifyError(device, "Failed to parse SDP record: " + e.getMessage());
        }
    }

    /**
     * Parses HID SDP attributes from raw data.
     */
    private void parseSdpAttributes(HidDevice device, byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        int pos = 0;
        while (pos < data.length - 4) {
            // Look for attribute IDs
            if (pos + 3 <= data.length) {
                int attrId = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);

                switch (attrId) {
                    case HidConstants.SDP_ATTR_HID_DEVICE_RELEASE:
                        if (pos + 4 < data.length) {
                            int version = ((data[pos + 3] & 0xFF) << 8) | (data[pos + 4] & 0xFF);
                            device.setDeviceReleaseVersion(version);
                        }
                        break;

                    case HidConstants.SDP_ATTR_HID_PARSER_VERSION:
                        if (pos + 4 < data.length) {
                            int pVersion = ((data[pos + 3] & 0xFF) << 8) | (data[pos + 4] & 0xFF);
                            device.setParserVersion(pVersion);
                        }
                        break;

                    case HidConstants.SDP_ATTR_HID_DEVICE_SUBCLASS:
                        if (pos + 3 < data.length) {
                            device.setSubclass(data[pos + 3] & 0xFF);
                        }
                        break;

                    case HidConstants.SDP_ATTR_HID_COUNTRY_CODE:
                        if (pos + 3 < data.length) {
                            device.setCountryCode(data[pos + 3] & 0xFF);
                        }
                        break;

                    case HidConstants.SDP_ATTR_HID_VIRTUAL_CABLE:
                        if (pos + 3 < data.length) {
                            device.setSupportsVirtualCable(data[pos + 3] != 0);
                        }
                        break;

                    case HidConstants.SDP_ATTR_HID_RECONNECT_INITIATE:
                        if (pos + 3 < data.length) {
                            device.setSupportsReconnectInitiate(data[pos + 3] != 0);
                        }
                        break;

                    case HidConstants.SDP_ATTR_HID_DESCRIPTOR_LIST:
                        // This contains the HID report descriptor
                        parseDescriptorList(device, data, pos);
                        break;

                    case HidConstants.SDP_ATTR_HID_BOOT_DEVICE:
                        if (pos + 3 < data.length) {
                            device.setSupportsBootProtocol(data[pos + 3] != 0);
                        }
                        break;

                    case HidConstants.SDP_ATTR_HID_PROFILE_VERSION:
                        if (pos + 4 < data.length) {
                            int version = ((data[pos + 3] & 0xFF) << 8) | (data[pos + 4] & 0xFF);
                            device.setProfileVersion(version);
                        }
                        break;

                    case HidConstants.SDP_ATTR_HID_SUPERVISION_TIMEOUT:
                        if (pos + 4 < data.length) {
                            int timeout = ((data[pos + 3] & 0xFF) << 8) | (data[pos + 4] & 0xFF);
                            device.setSupervisionTimeout(timeout);
                        }
                        break;

                    case HidConstants.SDP_ATTR_HID_NORMALLY_CONNECTABLE:
                        if (pos + 3 < data.length) {
                            device.setNormallyConnectable(data[pos + 3] != 0);
                        }
                        break;

                    case HidConstants.SDP_ATTR_HID_BATTERY_POWER:
                        if (pos + 3 < data.length) {
                            device.setBatteryPowered(data[pos + 3] != 0);
                        }
                        break;

                    case HidConstants.SDP_ATTR_HID_REMOTE_WAKE:
                        if (pos + 3 < data.length) {
                            device.setSupportsRemoteWake(data[pos + 3] != 0);
                        }
                        break;
                }
            }
            pos++;
        }
    }

    /**
     * Parses the HID descriptor list from SDP data.
     */
    private void parseDescriptorList(HidDevice device, byte[] data, int offset) {
        // Search for report descriptor type (0x22) within the descriptor list
        int pos = offset;
        while (pos < data.length - 4) {
            // Look for the descriptor type indicator (0x22 = Report descriptor)
            if ((data[pos] & 0xFF) == 0x22) {
                // Next bytes should contain length and then the descriptor
                pos++;
                if (pos < data.length) {
                    int lenType = data[pos] & 0xFF;
                    int len = 0;
                    int dataStart = pos + 1;

                    // Parse length based on SDP type
                    if ((lenType & 0xF8) == 0x25) {
                        // 8-bit length
                        if (pos + 1 < data.length) {
                            len = data[pos + 1] & 0xFF;
                            dataStart = pos + 2;
                        }
                    } else if ((lenType & 0xF8) == 0x26) {
                        // 16-bit length
                        if (pos + 2 < data.length) {
                            len = ((data[pos + 1] & 0xFF) << 8) | (data[pos + 2] & 0xFF);
                            dataStart = pos + 3;
                        }
                    }

                    if (len > 0 && dataStart + len <= data.length) {
                        byte[] descriptor = new byte[len];
                        System.arraycopy(data, dataStart, descriptor, 0, len);
                        device.setReportDescriptor(descriptor);

                        // Notify about descriptor
                        HidReportDescriptor parsed = device.getParsedDescriptor();
                        if (parsed != null) {
                            notifyDescriptorReceived(device, parsed);
                        }
                        return;
                    }
                }
            }
            pos++;
        }
    }

    // ==================== Debug/Status ====================

    /**
     * Returns a status summary of the HID manager.
     *
     * @return status string
     */
    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("HidManager{");
        sb.append("initialized=").append(mInitialized.get());
        sb.append(", closed=").append(mClosed.get());
        sb.append(", accepting=").append(mAcceptingConnections.get());
        sb.append(", devices=").append(mDevices.size());
        sb.append(", connected=").append(getConnectedDeviceCount());
        sb.append(", pending=").append(mPendingConnections.size());
        sb.append(", transactions=").append(mPendingTransactions.size());
        sb.append(", autoReconnect=").append(mAutoReconnect);
        sb.append(", bootProtocol=").append(mUseBootProtocol);
        sb.append("}");
        return sb.toString();
    }

    /**
     * Returns detailed status of all devices.
     *
     * @return detailed status string
     */
    public String getDetailedStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append(getStatus()).append("\n");

        sb.append("Connected devices:\n");
        for (HidDevice device : mDevices.values()) {
            sb.append("  ").append(device.toDetailedString()).append("\n");
        }

        if (!mPendingConnections.isEmpty()) {
            sb.append("Pending connections:\n");
            for (PendingConnection pending : mPendingConnections.values()) {
                sb.append("  ").append(pending.deviceId);
                sb.append(" (").append(System.currentTimeMillis() - pending.startTime).append("ms)\n");
            }
        }

        if (!mReconnectInfo.isEmpty()) {
            sb.append("Reconnect info:\n");
            for (Map.Entry<String, ReconnectInfo> entry : mReconnectInfo.entrySet()) {
                sb.append("  ").append(entry.getKey());
                sb.append(" (attempts=").append(entry.getValue().attempts).append(")\n");
            }
        }

        return sb.toString();
    }
}