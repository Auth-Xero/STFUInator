package com.courierstack.hci;

import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Trace;

import com.courierstack.core.CourierLogger;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AIDL-based HCI HAL implementation for Android 13+.
 *
 * <p>This implementation communicates with the Bluetooth HAL via the AIDL
 * interface introduced in Android 13 (API 33).
 */
final class HciAidlHal extends android.hardware.bluetooth.IBluetoothHciCallbacks.Stub
        implements IHciHal {

    private static final String TAG = "HciAidlHal";
    private static final String SERVICE_NAME = "android.hardware.bluetooth.IBluetoothHci/default";
    private static final long INIT_TIMEOUT_MS = 10_000;

    /** Initialization states */
    private static final int STATE_UNINITIALIZED = 0;
    private static final int STATE_INITIALIZING = 1;
    private static final int STATE_INITIALIZED = 2;
    private static final int STATE_CLOSED = 3;

    private final android.hardware.bluetooth.IBluetoothHci mHciService;
    private final IHciHalCallback mCallbacks;
    private final AtomicInteger mState = new AtomicInteger(STATE_UNINITIALIZED);
    private final AtomicReference<HciStatus> mInitStatus = new AtomicReference<>();
    private final CountDownLatch mInitLatch = new CountDownLatch(1);

    /**
     * Creates an AIDL HAL instance if available.
     *
     * @param callbacks callback handler (must not be null)
     * @return HciAidlHal instance or null if AIDL HAL is not available
     */
    static HciAidlHal create(IHciHalCallback callbacks) {
        Objects.requireNonNull(callbacks, "callbacks must not be null");

        IBinder binder = ServiceManager.getService(SERVICE_NAME);
        if (binder == null) {
            CourierLogger.d(TAG, "AIDL HAL service not found");
            return null;
        }

        android.hardware.bluetooth.IBluetoothHci service =
                android.hardware.bluetooth.IBluetoothHci.Stub.asInterface(binder);
        if (service == null) {
            CourierLogger.w(TAG, "Failed to get AIDL HAL interface");
            return null;
        }

        CourierLogger.d(TAG, "AIDL HAL service found");
        return new HciAidlHal(service, callbacks);
    }

    private HciAidlHal(android.hardware.bluetooth.IBluetoothHci service,
                       IHciHalCallback callbacks) {
        mHciService = service;
        mCallbacks = callbacks;
    }

    @Override
    public HciStatus initialize() throws RemoteException, InterruptedException {
        if (!mState.compareAndSet(STATE_UNINITIALIZED, STATE_INITIALIZING)) {
            int currentState = mState.get();
            if (currentState == STATE_INITIALIZED) {
                return HciStatus.ALREADY_INITIALIZED;
            } else if (currentState == STATE_CLOSED) {
                throw new IllegalStateException("HAL has been closed");
            } else {
                throw new IllegalStateException("Initialization already in progress");
            }
        }

        CourierLogger.d(TAG, "Initializing AIDL HAL...");
        mHciService.initialize(this);

        if (!mInitLatch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            mState.set(STATE_UNINITIALIZED);
            CourierLogger.e(TAG, "Initialization timed out");
            return HciStatus.INITIALIZATION_ERROR;
        }

        HciStatus status = mInitStatus.get();
        if (status != null && status.isSuccess()) {
            mState.set(STATE_INITIALIZED);
            CourierLogger.i(TAG, "AIDL HAL initialized successfully");
        } else {
            mState.set(STATE_UNINITIALIZED);
            CourierLogger.e(TAG, "AIDL HAL initialization failed: " + status);
        }

        return status != null ? status : HciStatus.UNKNOWN;
    }

    @Override
    public void sendPacket(HciPacketType type, byte[] packet) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(packet, "packet must not be null");

        if (mState.get() != STATE_INITIALIZED) {
            CourierLogger.w(TAG, "Cannot send packet: HAL not initialized");
            return;
        }

        boolean tracing = Trace.isEnabled();
        if (tracing) {
            Trace.beginAsyncSection("HCI_SEND_" + type.name(), packet.hashCode());
        }

        try {
            switch (type) {
                case COMMAND:
                    mHciService.sendHciCommand(packet);
                    break;
                case ACL_DATA:
                    mHciService.sendAclData(packet);
                    break;
                case SCO_DATA:
                    mHciService.sendScoData(packet);
                    break;
                case ISO_DATA:
                    mHciService.sendIsoData(packet);
                    break;
                default:
                    CourierLogger.w(TAG, "Unsupported packet type: " + type);
                    break;
            }
        } catch (RemoteException e) {
            CourierLogger.e(TAG, "Failed to send packet: " + e.getMessage());
        } finally {
            if (tracing) {
                Trace.endAsyncSection("HCI_SEND_" + type.name(), packet.hashCode());
            }
        }
    }

    @Override
    public boolean isInitialized() {
        return mState.get() == STATE_INITIALIZED;
    }

    @Override
    public void close() {
        int previousState = mState.getAndSet(STATE_CLOSED);
        if (previousState != STATE_CLOSED) {
            CourierLogger.d(TAG, "AIDL HAL closed");
            // Release the init latch in case we're still waiting
            mInitLatch.countDown();
        }
    }

    // ========== IBluetoothHciCallbacks implementation ==========

    @Override
    public void initializationComplete(int status) throws RemoteException {
        HciStatus hciStatus = mapAidlStatus(status);
        CourierLogger.d(TAG, "Initialization complete: " + hciStatus);
        mInitStatus.set(hciStatus);
        mInitLatch.countDown();
    }

    @Override
    public void hciEventReceived(byte[] event) throws RemoteException {
        if (mState.get() == STATE_INITIALIZED) {
            mCallbacks.onPacket(HciPacketType.EVENT, event);
        }
    }

    @Override
    public void aclDataReceived(byte[] data) throws RemoteException {
        if (mState.get() == STATE_INITIALIZED) {
            mCallbacks.onPacket(HciPacketType.ACL_DATA, data);
        }
    }

    @Override
    public void scoDataReceived(byte[] data) throws RemoteException {
        if (mState.get() == STATE_INITIALIZED) {
            mCallbacks.onPacket(HciPacketType.SCO_DATA, data);
        }
    }

    @Override
    public void isoDataReceived(byte[] data) throws RemoteException {
        if (mState.get() == STATE_INITIALIZED) {
            mCallbacks.onPacket(HciPacketType.ISO_DATA, data);
        }
    }

    // ========== Private helpers ==========

    private static HciStatus mapAidlStatus(int status) {
        switch (status) {
            case android.hardware.bluetooth.Status.SUCCESS:
                return HciStatus.SUCCESS;
            case android.hardware.bluetooth.Status.ALREADY_INITIALIZED:
                return HciStatus.ALREADY_INITIALIZED;
            case android.hardware.bluetooth.Status.UNABLE_TO_OPEN_INTERFACE:
                return HciStatus.UNABLE_TO_OPEN_INTERFACE;
            case android.hardware.bluetooth.Status.HARDWARE_INITIALIZATION_ERROR:
                return HciStatus.INITIALIZATION_ERROR;
            default:
                CourierLogger.w(TAG, "Unknown AIDL status code: " + status);
                return HciStatus.UNKNOWN;
        }
    }
}