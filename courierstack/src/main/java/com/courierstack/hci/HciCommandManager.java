package com.courierstack.hci;

import com.courierstack.hal.IBluetoothHal;
import com.courierstack.hal.IBluetoothHalCallback;
import com.courierstack.util.CourierLogger;

import java.io.Closeable;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manager for sending raw HCI commands and receiving responses.
 *
 * <p>This class provides a high-level interface for communicating with the
 * Bluetooth controller via HCI commands. It supports both synchronous and
 * asynchronous command execution.
 *
 * <p>Usage example:
 * <pre>{@code
 * HciCommandManager manager = new HciCommandManager(listener);
 * if (manager.initialize()) {
 *     // Send async command
 *     manager.sendCommand(HciCommandManager.cmdReset());
 *
 *     // Send sync command with timeout
 *     byte[] response = manager.sendCommandSync(HciCommandManager.cmdReadBdAddr());
 *     if (response != null) {
 *         // Process response
 *     }
 * }
 * manager.close();
 * }</pre>
 *
 * <p>Thread safety: This class is thread-safe. Commands may be sent from any thread.
 */
public class HciCommandManager implements IBluetoothHalCallback, Closeable {

    private static final String TAG = "HciCommandManager";
    private static final long DEFAULT_TIMEOUT_MS = 5000;
    private static final long EXECUTOR_SHUTDOWN_TIMEOUT_MS = 2000;

    // HCI Event codes
    private static final int EVT_COMMAND_COMPLETE = 0x0E;
    private static final int EVT_COMMAND_STATUS = 0x0F;

    private final IBluetoothHal mHal;
    private final IHciCommandListener mPrimaryListener;
    private final List<IHciCommandListener> mAdditionalListeners;
    private final ExecutorService mExecutor;
    private final ConcurrentHashMap<Integer, PendingCommand> mPendingCommands;
    private final ReentrantLock mSyncCommandLock;

    private volatile boolean mClosed = false;

    /**
     * Creates a new HciCommandManager.
     *
     * @param listener primary listener for HCI events (must not be null)
     * @throws NullPointerException if listener is null
     * @throws IllegalStateException if no HAL is available
     */
    public HciCommandManager(IHciCommandListener listener) {
        mPrimaryListener = Objects.requireNonNull(listener, "listener must not be null");
        mAdditionalListeners = new CopyOnWriteArrayList<>();
        mExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "HciCommandManager-Worker");
            t.setDaemon(true);
            return t;
        });
        mPendingCommands = new ConcurrentHashMap<>();
        mSyncCommandLock = new ReentrantLock(true);
        mHal = IBluetoothHal.create(this);
    }

    /**
     * Adds an additional listener for HCI events.
     *
     * <p>Multiple listeners can be registered to receive callbacks.
     * The primary listener is always called first.
     *
     * @param listener listener to add
     */
    public void addListener(IHciCommandListener listener) {
        if (listener != null && listener != mPrimaryListener
                && !mAdditionalListeners.contains(listener)) {
            mAdditionalListeners.add(listener);
        }
    }

    /**
     * Removes a previously added listener.
     *
     * @param listener listener to remove
     */
    public void removeListener(IHciCommandListener listener) {
        mAdditionalListeners.remove(listener);
    }

    /**
     * Initializes the HAL and Bluetooth controller.
     *
     * <p>This method must be called before sending any commands.
     *
     * @return true if initialization succeeded
     */
    public boolean initialize() {
        if (mHal == null) {
            notifyError("No Bluetooth HAL available");
            return false;
        }

        if (mClosed) {
            notifyError("Manager has been closed");
            return false;
        }

        try {
            CourierLogger.i(TAG, "Initializing HAL...");
            HciStatus status = mHal.initialize();

            if (status.isSuccess()) {
                notifyMessage("HAL initialized successfully");
                return true;
            } else {
                notifyError("HAL initialization failed: " + status.label);
                return false;
            }
        } catch (Exception e) {
            notifyError("HAL initialization exception: " + e.getMessage());
            CourierLogger.e(TAG, "Initialization failed", e);
            return false;
        }
    }

    /**
     * Returns whether the HAL has been successfully initialized.
     *
     * @return true if ready to send commands
     */
    public boolean isInitialized() {
        return mHal != null && mHal.isInitialized() && !mClosed;
    }

    /**
     * Sends an HCI command asynchronously.
     *
     * <p>The command is queued for transmission. Responses are delivered
     * via the listener callbacks.
     *
     * @param command HCI command packet
     */
    public void sendCommand(byte[] command) {
        Objects.requireNonNull(command, "command must not be null");

        if (!checkInitialized()) {
            return;
        }

        mExecutor.execute(() -> {
            try {
                int opcode = extractOpcode(command);
                CourierLogger.d(TAG, String.format("Sending HCI command: 0x%04X (%d bytes)",
                        opcode, command.length));
                mHal.sendPacket(HciPacketType.COMMAND, command);
            } catch (Exception e) {
                notifyError("Failed to send command: " + e.getMessage());
            }
        });
    }

    /**
     * Sends an HCI command synchronously and waits for the response.
     *
     * <p>This method blocks until a Command Complete or Command Status event
     * is received for the command, or the timeout expires.
     *
     * @param command   HCI command packet
     * @param timeoutMs maximum time to wait in milliseconds
     * @return response event data, or null if timeout or error
     */
    public byte[] sendCommandSync(byte[] command, long timeoutMs) {
        Objects.requireNonNull(command, "command must not be null");

        if (!checkInitialized()) {
            return null;
        }

        boolean lockAcquired = false;
        int opcode = extractOpcode(command);
        PendingCommand pending = new PendingCommand(opcode);

        try {
            lockAcquired = mSyncCommandLock.tryLock(timeoutMs, TimeUnit.MILLISECONDS);
            if (!lockAcquired) {
                notifyError("Command lock timeout");
                return null;
            }

            mPendingCommands.put(opcode, pending);
            CourierLogger.d(TAG, String.format("Sending HCI command (sync): 0x%04X", opcode));
            mHal.sendPacket(HciPacketType.COMMAND, command);

            if (pending.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return pending.response.get();
            } else {
                notifyError(String.format("Command 0x%04X timeout", opcode));
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notifyError("Command interrupted");
            return null;
        } catch (Exception e) {
            notifyError("Command failed: " + e.getMessage());
            return null;
        } finally {
            mPendingCommands.remove(opcode);
            if (lockAcquired) {
                mSyncCommandLock.unlock();
            }
        }
    }

    /**
     * Sends an HCI command synchronously with the default timeout.
     *
     * @param command HCI command packet
     * @return response event data, or null if timeout or error
     */
    public byte[] sendCommandSync(byte[] command) {
        return sendCommandSync(command, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Sends ACL data to the controller.
     *
     * @param data ACL packet data
     */
    public void sendAclData(byte[] data) {
        Objects.requireNonNull(data, "data must not be null");
        if (!checkInitialized()) {
            return;
        }
        mExecutor.execute(() -> {
            try {
                mHal.sendPacket(HciPacketType.ACL_DATA, data);
            } catch (Exception e) {
                notifyError("Failed to send ACL data: " + e.getMessage());
            }
        });
    }

    /**
     * Sends SCO data to the controller.
     *
     * @param data SCO packet data
     */
    public void sendScoData(byte[] data) {
        Objects.requireNonNull(data, "data must not be null");
        if (!checkInitialized()) {
            return;
        }
        mExecutor.execute(() -> {
            try {
                mHal.sendPacket(HciPacketType.SCO_DATA, data);
            } catch (Exception e) {
                notifyError("Failed to send SCO data: " + e.getMessage());
            }
        });
    }

    /**
     * Sends ISO data to the controller (Bluetooth 5.2+).
     *
     * @param data ISO packet data
     */
    public void sendIsoData(byte[] data) {
        Objects.requireNonNull(data, "data must not be null");
        if (!checkInitialized()) {
            return;
        }
        mExecutor.execute(() -> {
            try {
                mHal.sendPacket(HciPacketType.ISO_DATA, data);
            } catch (Exception e) {
                notifyError("Failed to send ISO data: " + e.getMessage());
            }
        });
    }

    /**
     * Closes the manager and releases all resources.
     *
     * <p>After calling this method, the manager cannot be used.
     */
    @Override
    public void close() {
        if (mClosed) {
            return;
        }
        mClosed = true;

        // Cancel pending commands
        for (PendingCommand pending : mPendingCommands.values()) {
            pending.latch.countDown();
        }
        mPendingCommands.clear();

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

        // Close HAL
        if (mHal != null) {
            mHal.close();
        }

        CourierLogger.i(TAG, "HciCommandManager closed");
    }

    /**
     * @deprecated Use {@link #close()} instead
     */
    @Deprecated
    public void shutdown() {
        close();
    }

    // ========== IHciHalCallback implementation ==========

    @Override
    public void onPacket(HciPacketType type, byte[] packet) {
        if (mClosed) {
            return;
        }

        switch (type) {
            case EVENT:
                handleEvent(packet);
                break;
            case ACL_DATA:
                dispatchAclData(packet);
                break;
            case SCO_DATA:
                dispatchScoData(packet);
                break;
            case ISO_DATA:
                dispatchIsoData(packet);
                break;
            default:
                CourierLogger.w(TAG, "Unknown packet type: " + type);
                break;
        }
    }

    // ========== Event handling ==========

    private void handleEvent(byte[] event) {
        if (event == null || event.length < 1) {
            return;
        }

        int eventCode = event[0] & 0xFF;

        // Dispatch raw event to all listeners first
        dispatchEvent(event);

        // Parse and dispatch structured events
        if (eventCode == EVT_COMMAND_COMPLETE && event.length >= 6) {
            handleCommandComplete(event);
        } else if (eventCode == EVT_COMMAND_STATUS && event.length >= 6) {
            handleCommandStatus(event);
        }
    }

    private void handleCommandComplete(byte[] event) {
        // Event format: [0x0E, length, numCommands, opcodeLow, opcodeHigh, status, params...]
        int opcode = ((event[4] & 0xFF) << 8) | (event[3] & 0xFF);
        int status = event[5] & 0xFF;
        byte[] returnParams = event.length > 6 ? Arrays.copyOfRange(event, 6, event.length) : new byte[0];

        // Complete pending sync command
        completePendingCommand(opcode, event);

        // Notify listeners
        mPrimaryListener.onCommandComplete(opcode, status, returnParams);
        for (IHciCommandListener listener : mAdditionalListeners) {
            listener.onCommandComplete(opcode, status, returnParams);
        }
    }

    private void handleCommandStatus(byte[] event) {
        // Event format: [0x0F, length, status, numCommands, opcodeLow, opcodeHigh]
        int status = event[2] & 0xFF;
        int opcode = ((event[5] & 0xFF) << 8) | (event[4] & 0xFF);

        // Complete pending sync command
        completePendingCommand(opcode, event);

        // Notify listeners
        mPrimaryListener.onCommandStatus(opcode, status);
        for (IHciCommandListener listener : mAdditionalListeners) {
            listener.onCommandStatus(opcode, status);
        }
    }

    private void completePendingCommand(int opcode, byte[] event) {
        PendingCommand pending = mPendingCommands.get(opcode);
        if (pending != null) {
            pending.response.set(event);
            pending.latch.countDown();
        }
    }

    // ========== Dispatch helpers ==========

    private void dispatchEvent(byte[] event) {
        mPrimaryListener.onEvent(event);
        for (IHciCommandListener listener : mAdditionalListeners) {
            listener.onEvent(event);
        }
    }

    private void dispatchAclData(byte[] data) {
        mPrimaryListener.onAclData(data);
        for (IHciCommandListener listener : mAdditionalListeners) {
            listener.onAclData(data);
        }
    }

    private void dispatchScoData(byte[] data) {
        mPrimaryListener.onScoData(data);
        for (IHciCommandListener listener : mAdditionalListeners) {
            listener.onScoData(data);
        }
    }

    private void dispatchIsoData(byte[] data) {
        mPrimaryListener.onIsoData(data);
        for (IHciCommandListener listener : mAdditionalListeners) {
            listener.onIsoData(data);
        }
    }

    private void notifyError(String message) {
        CourierLogger.e(TAG, message);
        mPrimaryListener.onError(message);
        for (IHciCommandListener listener : mAdditionalListeners) {
            listener.onError(message);
        }
    }

    private void notifyMessage(String message) {
        CourierLogger.i(TAG, message);
        mPrimaryListener.onMessage(message);
        for (IHciCommandListener listener : mAdditionalListeners) {
            listener.onMessage(message);
        }
    }

    // ========== Utility methods ==========

    private boolean checkInitialized() {
        if (!isInitialized()) {
            notifyError("HAL not initialized");
            return false;
        }
        return true;
    }

    private static int extractOpcode(byte[] command) {
        if (command.length >= 2) {
            return ((command[1] & 0xFF) << 8) | (command[0] & 0xFF);
        }
        return 0;
    }

    // ========== Pending command holder ==========

    private static final class PendingCommand {
        final int opcode;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<byte[]> response = new AtomicReference<>();

        PendingCommand(int opcode) {
            this.opcode = opcode;
        }
    }
}