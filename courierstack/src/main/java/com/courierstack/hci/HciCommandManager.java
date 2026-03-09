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
 * <p>Uses synchronous ACL sends to ensure proper ordering and adds
 * inter-PDU delay for timing-sensitive protocols like SMP.
 */
public class HciCommandManager implements IBluetoothHalCallback, Closeable {

    private static final String TAG = "HciCommandManager";
    private static final long DEFAULT_TIMEOUT_MS = 5000;
    private static final long EXECUTOR_SHUTDOWN_TIMEOUT_MS = 2000;

    /** Inter-PDU delay for timing-sensitive protocols (ms). */
    private static final int ACL_SEND_DELAY_MS = 5;

    // HCI Event codes
    private static final int EVT_COMMAND_COMPLETE = 0x0E;
    private static final int EVT_COMMAND_STATUS = 0x0F;

    private final IBluetoothHal mHal;
    private final IHciCommandListener mPrimaryListener;
    private final List<IHciCommandListener> mAdditionalListeners;
    private final ExecutorService mExecutor;
    private final ConcurrentHashMap<Integer, PendingCommand> mPendingCommands;
    private final ReentrantLock mSyncCommandLock;

    /** Lock for serializing ACL data sends. */
    private final ReentrantLock mAclSendLock = new ReentrantLock(true);

    /** Timestamp of last ACL send for inter-PDU pacing. */
    private volatile long mLastAclSendTime = 0;

    private volatile boolean mClosed = false;

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

    public void addListener(IHciCommandListener listener) {
        if (listener != null && listener != mPrimaryListener
                && !mAdditionalListeners.contains(listener)) {
            mAdditionalListeners.add(listener);
        }
    }

    public void removeListener(IHciCommandListener listener) {
        mAdditionalListeners.remove(listener);
    }

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

    public boolean isInitialized() {
        return mHal != null && mHal.isInitialized() && !mClosed;
    }

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

    public byte[] sendCommandSync(byte[] command) {
        return sendCommandSync(command, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Sends ACL data to the controller.
     *
     * <p>Uses synchronous sending with proper serialization to ensure PDU
     * ordering, and adds a small delay between sends for timing-sensitive
     * protocols like SMP.
     *
     * @param data ACL packet data
     */
    public void sendAclData(byte[] data) {
        Objects.requireNonNull(data, "data must not be null");
        if (!checkInitialized()) {
            return;
        }

        mAclSendLock.lock();
        try {
            // Enforce minimum inter-PDU spacing for timing-sensitive protocols
            long now = System.currentTimeMillis();
            long elapsed = now - mLastAclSendTime;
            if (elapsed < ACL_SEND_DELAY_MS && mLastAclSendTime > 0) {
                try {
                    Thread.sleep(ACL_SEND_DELAY_MS - elapsed);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            mHal.sendPacket(HciPacketType.ACL_DATA, data);
            mLastAclSendTime = System.currentTimeMillis();

        } catch (Exception e) {
            notifyError("Failed to send ACL data: " + e.getMessage());
        } finally {
            mAclSendLock.unlock();
        }
    }

    /**
     * Sends ACL data asynchronously.
     *
     * <p>Use for non-timing-sensitive data where ordering is not required.
     * For ordered/paced sends, use {@link #sendAclData(byte[])} instead.
     *
     * @param data ACL packet data
     */
    public void sendAclDataAsync(byte[] data) {
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

    @Override
    public void close() {
        if (mClosed) {
            return;
        }
        mClosed = true;

        for (PendingCommand pending : mPendingCommands.values()) {
            pending.latch.countDown();
        }
        mPendingCommands.clear();

        mExecutor.shutdown();
        try {
            if (!mExecutor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                mExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            mExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        if (mHal != null) {
            mHal.close();
        }

        CourierLogger.i(TAG, "HciCommandManager closed");
    }

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
        dispatchEvent(event);

        if (eventCode == EVT_COMMAND_COMPLETE && event.length >= 6) {
            handleCommandComplete(event);
        } else if (eventCode == EVT_COMMAND_STATUS && event.length >= 6) {
            handleCommandStatus(event);
        }
    }

    private void handleCommandComplete(byte[] event) {
        int opcode = ((event[4] & 0xFF) << 8) | (event[3] & 0xFF);
        int status = event[5] & 0xFF;
        byte[] returnParams = event.length > 6 ? Arrays.copyOfRange(event, 6, event.length) : new byte[0];

        completePendingCommand(opcode, event);

        mPrimaryListener.onCommandComplete(opcode, status, returnParams);
        for (IHciCommandListener listener : mAdditionalListeners) {
            listener.onCommandComplete(opcode, status, returnParams);
        }
    }

    private void handleCommandStatus(byte[] event) {
        int status = event[2] & 0xFF;
        int opcode = ((event[5] & 0xFF) << 8) | (event[4] & 0xFF);

        completePendingCommand(opcode, event);

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