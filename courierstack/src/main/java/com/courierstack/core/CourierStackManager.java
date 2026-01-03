package com.courierstack.core;

import android.content.Context;

import com.courierstack.hci.HciCommandManager;
import com.courierstack.hci.HciCommands;
import com.courierstack.hci.HciErrorCode;
import com.courierstack.util.CourierLogger;
import com.courierstack.util.LogEntry;
import com.courierstack.util.LogType;
import com.courierstack.hal.HalManager;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Main entry point for CourierStack Bluetooth protocol stack.
 *
 * <p>Provides unified access to all Bluetooth layers: HCI, L2CAP, SDP, RFCOMM,
 * GATT, AVDTP, SMP, and device scanning.
 *
 * <p>Also provides HAL management for taking exclusive control of Bluetooth hardware.
 *
 * <p>Usage:
 * <pre>{@code
 * CourierStackManager stack = CourierStackManager.getInstance();
 *
 * // Option 1: Initialize with HAL kill (recommended for rooted devices)
 * stack.initializeWithHalKill(context, (success, error) -> {
 *     if (success) {
 *         // Stack ready - start scanning, connecting, etc.
 *     }
 * });
 *
 * // Option 2: Initialize directly (if HAL already managed externally)
 * if (stack.initialize(context)) {
 *     // Stack ready
 * }
 *
 * // When done
 * stack.shutdownAndRestore(null);
 * }</pre>
 */
public final class CourierStackManager implements Closeable {

    private static final String TAG = "CourierStack";
    private static final int MAX_LOG_ENTRIES = 1000;
    private static final int INIT_EXECUTOR_SHUTDOWN_TIMEOUT_MS = 5000;
    private static final int HAL_RESTART_DELAY_MS = 2000;

    /**
     * Stack initialization state.
     */
    public enum State {
        /** Not yet initialized. */
        UNINITIALIZED,
        /** Initialization in progress. */
        INITIALIZING,
        /** Ready for use. */
        READY,
        /** An error occurred during initialization. */
        ERROR,
        /** Stack has been shut down. */
        SHUTDOWN
    }

    /**
     * Callback for async initialization.
     */
    public interface InitCallback {
        /**
         * Called when initialization completes.
         *
         * @param success true if successful
         * @param error   error message if failed, null otherwise
         */
        void onComplete(boolean success, String error);
    }

    // Singleton
    private static volatile CourierStackManager sInstance;
    private static final Object sInstanceLock = new Object();

    // Context
    private Context mContext;

    // State
    private final AtomicReference<State> mState = new AtomicReference<>(State.UNINITIALIZED);

    // Core managers (always initialized)
    private HciCommandManager mHciManager;
    // Note: L2capManager, ScannerManager, etc. would be added here
    // Omitted for brevity as they depend on other classes not provided

    // Listeners
    private final List<IStackListener> mStackListeners = new CopyOnWriteArrayList<>();

    // Log history (using ConcurrentLinkedDeque for efficient head removal)
    private final ConcurrentLinkedDeque<LogEntry> mLogHistory = new ConcurrentLinkedDeque<>();

    // Local device info
    private volatile byte[] mLocalAddress;
    private volatile String mLocalName;
    private volatile byte[] mSupportedCommands;
    private volatile int mHciVersion;
    private volatile int mLmpVersion;

    // Executor for async init
    private final ExecutorService mInitExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CourierStack-Init");
        t.setDaemon(true);
        return t;
    });

    private CourierStackManager() {
    }

    /**
     * Returns the singleton instance.
     *
     * @return CourierStackManager instance
     */
    public static CourierStackManager getInstance() {
        if (sInstance == null) {
            synchronized (sInstanceLock) {
                if (sInstance == null) {
                    sInstance = new CourierStackManager();
                }
            }
        }
        return sInstance;
    }

    // ==================== State ====================

    /**
     * Returns the current state.
     *
     * @return current state
     */
    public State getState() {
        return mState.get();
    }

    /**
     * Returns whether the stack is ready for use.
     *
     * @return true if ready
     */
    public boolean isReady() {
        return mState.get() == State.READY;
    }

    /**
     * Returns whether the stack is initialized (alias for {@link #isReady()}).
     *
     * @return true if initialized and ready
     */
    public boolean isInitialized() {
        return isReady();
    }

    // ==================== HAL Management ====================

    /**
     * Returns the HAL manager for controlling the Android Bluetooth stack.
     *
     * @return HalManager instance
     */
    public HalManager getHalManager() {
        return HalManager.getInstance();
    }

    /**
     * Kills the Android Bluetooth stack to take exclusive control.
     *
     * <p>This is required before initializing CourierStack on most devices.
     *
     * @param callback called when kill completes (on background thread)
     */
    public void killAndroidBluetoothStack(Runnable callback) {
        log(TAG, "Killing Android Bluetooth stack...", LogType.INFO);
        HalManager.getInstance().killBluetoothStackAsync(() -> {
            log(TAG, "Android Bluetooth stack killed", LogType.INFO);
            if (callback != null) {
                callback.run();
            }
        });
    }

    /**
     * Restores the Android Bluetooth stack.
     *
     * <p>Call this when done using CourierStack.
     *
     * @param callback called when restore completes (on background thread)
     */
    public void restoreAndroidBluetoothStack(Runnable callback) {
        log(TAG, "Restoring Android Bluetooth stack...", LogType.INFO);
        HalManager.getInstance().restoreBluetoothStackAsync(() -> {
            log(TAG, "Android Bluetooth stack restored", LogType.INFO);
            if (callback != null) {
                callback.run();
            }
        });
    }

    /**
     * Kills the Android Bluetooth stack synchronously.
     *
     * <p>Must be called from a background thread.
     *
     * @return true if successful
     */
    public boolean killAndroidBluetoothStackSync() {
        log(TAG, "Killing Android Bluetooth stack (sync)...", LogType.INFO);
        boolean success = HalManager.getInstance().killBluetoothStackSync();
        if (success) {
            log(TAG, "Android Bluetooth stack killed", LogType.INFO);
        } else {
            log(TAG, "Failed to kill Android Bluetooth stack", LogType.ERROR);
        }
        return success;
    }

    /**
     * Restores the Android Bluetooth stack synchronously.
     *
     * <p>Must be called from a background thread.
     *
     * @return true if successful
     */
    public boolean restoreAndroidBluetoothStackSync() {
        log(TAG, "Restoring Android Bluetooth stack (sync)...", LogType.INFO);
        boolean success = HalManager.getInstance().restoreBluetoothStackSync();
        if (success) {
            log(TAG, "Android Bluetooth stack restored", LogType.INFO);
        } else {
            log(TAG, "Failed to restore Android Bluetooth stack", LogType.ERROR);
        }
        return success;
    }

    /**
     * Checks if the Android Bluetooth stack has been killed.
     *
     * @return true if stack is killed
     */
    public boolean isAndroidStackKilled() {
        return HalManager.getInstance().isStackKilled();
    }

    /**
     * Initializes with HAL kill - kills Android BT stack then initializes.
     *
     * <p>This is the recommended way to initialize on rooted devices.
     *
     * @param context  application context
     * @param callback called with result (on background thread)
     */
    public void initializeWithHalKill(Context context, InitCallback callback) {
        Objects.requireNonNull(context, "context must not be null");

        mInitExecutor.execute(() -> {
            // Kill Android stack first
            if (!killAndroidBluetoothStackSync()) {
                if (callback != null) {
                    callback.onComplete(false, "Failed to kill Android BT stack");
                }
                return;
            }

            // Wait for HAL to restart
            try {
                Thread.sleep(HAL_RESTART_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (callback != null) {
                    callback.onComplete(false, "Interrupted during initialization");
                }
                return;
            }

            // Initialize CourierStack
            boolean success = initialize(context);
            if (callback != null) {
                callback.onComplete(success, success ? null : "Stack initialization failed");
            }
        });
    }

    // ==================== Initialization ====================

    /**
     * Initializes the Bluetooth stack.
     *
     * @param context application context
     * @return true if successful
     */
    public synchronized boolean initialize(Context context) {
        Objects.requireNonNull(context, "context must not be null");

        State currentState = mState.get();
        if (currentState == State.READY) {
            log(TAG, "Already initialized", LogType.INFO);
            return true;
        }

        if (currentState == State.SHUTDOWN) {
            log(TAG, "Cannot initialize - stack has been shut down", LogType.ERROR);
            return false;
        }

        if (!mState.compareAndSet(currentState, State.INITIALIZING)) {
            log(TAG, "Initialization already in progress", LogType.WARNING);
            return false;
        }

        mContext = context.getApplicationContext();
        log(TAG, "Initializing Bluetooth stack...", LogType.INFO);

        try {
            // Initialize logging
            CourierLogger.init(mContext);

            // Initialize HCI layer
            mHciManager = new HciCommandManager(new HciListenerImpl());
            if (!mHciManager.initialize()) {
                return fail("HCI initialization failed");
            }

            // Read local device info
            readLocalDeviceInfo();

            // Configure event masks for scanning support
            configureEventMasks();

            // Enable Simple Secure Pairing
            enableSimpleSecurePairing();

            mState.set(State.READY);
            log(TAG, "Stack initialized successfully", LogType.INFO);
            notifyInitialized(true);
            return true;

        } catch (Exception e) {
            CourierLogger.e(TAG, "Initialization exception", e);
            return fail("Init exception: " + e.getMessage());
        }
    }

    private boolean fail(String message) {
        log(TAG, message, LogType.ERROR);
        mState.set(State.ERROR);
        notifyInitialized(false);
        return false;
    }

    private void readLocalDeviceInfo() {
        if (mHciManager == null) return;

        // Read BD_ADDR
        byte[] resp = mHciManager.sendCommandSync(HciCommands.readBdAddr());
        if (resp != null && resp.length >= 12) {
            mLocalAddress = new byte[6];
            System.arraycopy(resp, 6, mLocalAddress, 0, 6);
            log(TAG, "Local address: " + formatAddress(mLocalAddress), LogType.INFO);
        }

        // Read local name
        resp = mHciManager.sendCommandSync(HciCommands.readLocalName());
        if (resp != null && resp.length >= 7) {
            int end = 6;
            while (end < resp.length && resp[end] != 0) end++;
            mLocalName = new String(resp, 6, end - 6).trim();
            log(TAG, "Local name: " + mLocalName, LogType.DEBUG);
        }

        // Read version info
        resp = mHciManager.sendCommandSync(HciCommands.readLocalVersionInfo());
        if (resp != null && resp.length >= 13) {
            mHciVersion = resp[6] & 0xFF;
            mLmpVersion = resp[9] & 0xFF;
            log(TAG, "HCI version: " + mHciVersion + ", LMP version: " + mLmpVersion, LogType.DEBUG);
        }

        // Read supported commands
        resp = mHciManager.sendCommandSync(HciCommands.readLocalSupportedCommands());
        if (resp != null && resp.length >= 70) {
            mSupportedCommands = new byte[64];
            System.arraycopy(resp, 6, mSupportedCommands, 0, 64);
        }
    }

    /**
     * Configures HCI event masks for scan and connection support.
     */
    private void configureEventMasks() {
        if (mHciManager == null) return;

        log(TAG, "Configuring event masks...", LogType.DEBUG);

        // Set Event Mask to include all standard events plus:
        // - LE Meta Event (bit 61)
        // - Simple Pairing Complete (bit 60)
        // - User Passkey Notification (bit 59)
        // - User Passkey Request (bit 58)
        // - User Confirmation Request (bit 57)
        // - IO Capability Response (bit 56)
        // - IO Capability Request (bit 55)
        long eventMask = 0x3FBFF807FFFFFFFFL;
        byte[] resp = mHciManager.sendCommandSync(HciCommands.setEventMask(eventMask));
        if (resp != null && resp.length >= 6 && resp[5] == 0x00) {
            log(TAG, "Event mask configured successfully", LogType.DEBUG);
        } else {
            log(TAG, "Warning: Event mask configuration may have failed", LogType.WARNING);
        }

        // Set LE Event Mask
        long leEventMask = 0x000000000000719FL;
        resp = mHciManager.sendCommandSync(HciCommands.leSetEventMask(leEventMask));
        if (resp != null && resp.length >= 6 && resp[5] == 0x00) {
            log(TAG, "LE event mask configured successfully", LogType.DEBUG);
        } else {
            log(TAG, "Warning: LE event mask configuration may have failed", LogType.WARNING);
        }
    }

    /**
     * Enables Simple Secure Pairing (SSP) on the controller.
     */
    private void enableSimpleSecurePairing() {
        if (mHciManager == null) return;

        log(TAG, "Enabling Simple Secure Pairing...", LogType.DEBUG);

        byte[] resp = mHciManager.sendCommandSync(HciCommands.writeSimplePairingMode(true));
        if (resp != null && resp.length >= 6 && resp[5] == 0x00) {
            log(TAG, "Simple Secure Pairing enabled", LogType.INFO);
        } else {
            log(TAG, "Warning: Failed to enable SSP", LogType.WARNING);
        }
    }

    // ==================== Manager Access ====================

    /**
     * Returns the HCI manager.
     *
     * @return HciCommandManager or null if not initialized
     */
    public HciCommandManager getHciManager() {
        return mHciManager;
    }

    // ==================== Local Device Info ====================

    /**
     * Returns the local Bluetooth address.
     *
     * @return 6-byte address or null
     */
    public byte[] getLocalAddress() {
        return mLocalAddress != null ? mLocalAddress.clone() : null;
    }

    /**
     * Returns the local Bluetooth address as a formatted string.
     *
     * @return formatted address (XX:XX:XX:XX:XX:XX) or null
     */
    public String getLocalAddressString() {
        return mLocalAddress != null ? formatAddress(mLocalAddress) : null;
    }

    /**
     * Returns the local device name.
     *
     * @return device name or null
     */
    public String getLocalName() {
        return mLocalName;
    }

    /**
     * Sets the local device name.
     *
     * @param name new device name
     */
    public void setLocalName(String name) {
        if (!isReady() || mHciManager == null) return;
        byte[] cmd = HciCommands.writeLocalName(name);
        mHciManager.sendCommandSync(cmd);
        mLocalName = name;
    }

    /**
     * Returns the HCI version.
     *
     * @return HCI version number
     */
    public int getHciVersion() {
        return mHciVersion;
    }

    /**
     * Returns the LMP version.
     *
     * @return LMP version number
     */
    public int getLmpVersion() {
        return mLmpVersion;
    }

    /**
     * Checks if a specific HCI command is supported.
     *
     * @param octet octet index in supported commands array
     * @param bit   bit index within the octet
     * @return true if command is supported
     */
    public boolean isCommandSupported(int octet, int bit) {
        if (mSupportedCommands == null || octet >= mSupportedCommands.length) {
            return false;
        }
        return (mSupportedCommands[octet] & (1 << bit)) != 0;
    }

    // ==================== Listeners ====================

    /**
     * Adds a stack listener.
     *
     * @param listener listener to add
     */
    public void addStackListener(IStackListener listener) {
        if (listener != null && !mStackListeners.contains(listener)) {
            mStackListeners.add(listener);
        }
    }

    /**
     * Removes a stack listener.
     *
     * @param listener listener to remove
     */
    public void removeStackListener(IStackListener listener) {
        mStackListeners.remove(listener);
    }

    // ==================== Logging ====================

    /**
     * Returns the log history.
     *
     * @return unmodifiable list of log entries
     */
    public List<LogEntry> getLogHistory() {
        return Collections.unmodifiableList(new ArrayList<>(mLogHistory));
    }

    /**
     * Clears the log history.
     */
    public void clearLogHistory() {
        mLogHistory.clear();
    }

    private void log(String tag, String message, LogType type) {
        LogEntry entry = new LogEntry(tag, message, type);

        // Add to history with size limit
        mLogHistory.addLast(entry);
        while (mLogHistory.size() > MAX_LOG_ENTRIES) {
            mLogHistory.pollFirst();
        }

        // Log to Android logcat
        switch (type) {
            case ERROR:
                CourierLogger.e(tag, message);
                break;
            case WARNING:
                CourierLogger.w(tag, message);
                break;
            case DEBUG:
                CourierLogger.d(tag, message);
                break;
            default:
                CourierLogger.i(tag, message);
                break;
        }

        // Notify listeners
        for (IStackListener listener : mStackListeners) {
            try {
                listener.onLog(entry);
            } catch (Exception ignored) {
            }
        }
    }

    // ==================== Notifications ====================

    private void notifyInitialized(boolean success) {
        for (IStackListener listener : mStackListeners) {
            try {
                listener.onInitialized(success);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyError(String message) {
        for (IStackListener listener : mStackListeners) {
            try {
                listener.onError(message);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyStateChanged(State state) {
        for (IStackListener listener : mStackListeners) {
            try {
                listener.onStateChanged(state);
            } catch (Exception ignored) {
            }
        }
    }

    // ==================== Shutdown ====================

    /**
     * Shuts down the stack and all managers.
     */
    public synchronized void shutdown() {
        State currentState = mState.get();
        if (currentState == State.SHUTDOWN) {
            return;
        }

        log(TAG, "Shutting down stack...", LogType.INFO);
        mState.set(State.SHUTDOWN);

        // Close HCI manager
        if (mHciManager != null) {
            mHciManager.close();
            mHciManager = null;
        }

        // Notify listeners
        for (IStackListener listener : mStackListeners) {
            try {
                listener.onShutdown();
            } catch (Exception ignored) {
            }
        }

        mStackListeners.clear();

        // Shutdown init executor
        mInitExecutor.shutdown();
        try {
            if (!mInitExecutor.awaitTermination(INIT_EXECUTOR_SHUTDOWN_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS)) {
                mInitExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            mInitExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log(TAG, "Stack shutdown complete", LogType.INFO);
    }

    /**
     * Shuts down the stack and restores the Android Bluetooth stack.
     *
     * @param callback called when complete
     */
    public void shutdownAndRestore(Runnable callback) {
        shutdown();
        restoreAndroidBluetoothStack(callback);
    }

    @Override
    public void close() {
        shutdown();
    }

    // ==================== Utilities ====================

    private String formatAddress(byte[] addr) {
        if (addr == null || addr.length != 6) {
            return "??:??:??:??:??:??";
        }
        return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                addr[5] & 0xFF, addr[4] & 0xFF, addr[3] & 0xFF,
                addr[2] & 0xFF, addr[1] & 0xFF, addr[0] & 0xFF);
    }

    /**
     * Parses a BD_ADDR string to byte array (little-endian).
     *
     * @param address address string (XX:XX:XX:XX:XX:XX)
     * @return 6-byte address array
     * @throws IllegalArgumentException if format is invalid
     */
    public static byte[] parseAddress(String address) {
        Objects.requireNonNull(address, "address must not be null");
        String[] parts = address.split(":");
        if (parts.length != 6) {
            throw new IllegalArgumentException("Invalid address format: " + address);
        }
        byte[] addr = new byte[6];
        for (int i = 0; i < 6; i++) {
            addr[5 - i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return addr;
    }

    // ==================== HCI Listener Implementation ====================

    private class HciListenerImpl implements com.courierstack.hci.IHciCommandListener {
        @Override
        public void onEvent(byte[] event) {
            // Log event
            if (CourierLogger.isInitialized()) {
                CourierLogger.getInstance().logEvent(event);
            }
        }

        @Override
        public void onAclData(byte[] data) {
            if (CourierLogger.isInitialized()) {
                CourierLogger.getInstance().logAcl(CourierLogger.PacketDirection.RX, data);
            }
        }

        @Override
        public void onScoData(byte[] data) {
            if (CourierLogger.isInitialized()) {
                CourierLogger.getInstance().logSco(CourierLogger.PacketDirection.RX, data);
            }
        }

        @Override
        public void onIsoData(byte[] data) {
            if (CourierLogger.isInitialized()) {
                CourierLogger.getInstance().logIso(CourierLogger.PacketDirection.RX, data);
            }
        }

        @Override
        public void onCommandComplete(int opcode, int status, byte[] returnParams) {
            if (status != HciErrorCode.SUCCESS) {
                log("HCI", String.format("Command 0x%04X failed: %s",
                        opcode, HciErrorCode.getDescription(status)), LogType.WARNING);
            }
        }

        @Override
        public void onCommandStatus(int opcode, int status) {
            if (status != HciErrorCode.SUCCESS) {
                log("HCI", String.format("Command 0x%04X status: %s",
                        opcode, HciErrorCode.getDescription(status)), LogType.WARNING);
            }
        }

        @Override
        public void onError(String message) {
            log("HCI", message, LogType.ERROR);
            notifyError(message);
        }

        @Override
        public void onMessage(String message) {
            log("HCI", message, LogType.DEBUG);
        }
    }
}