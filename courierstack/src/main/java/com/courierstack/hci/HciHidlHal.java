package com.courierstack.hci;

import android.os.RemoteException;
import android.os.Trace;

import com.courierstack.core.CourierLogger;

import java.util.ArrayList;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HIDL-based HCI HAL implementation for Android 8.0-12.
 */
class HciHidlHal extends android.hardware.bluetooth.V1_0.IBluetoothHciCallbacks.Stub
        implements IHciHal {

    private static final String TAG = "HciHidlHal";

    private final android.hardware.bluetooth.V1_0.IBluetoothHci mHciService;
    private final IHciHalCallback mCallbacks;
    private int mInitializationStatus = -1;
    private final boolean mTracingEnabled = Trace.isEnabled();
    private final AtomicBoolean mInitialized = new AtomicBoolean(false);
    private final AtomicBoolean mClosed = new AtomicBoolean(false);

    /**
     * Creates a HIDL HAL instance if available.
     *
     * @param callbacks callback handler
     * @return HciHidlHal instance or null
     */
    static HciHidlHal create(IHciHalCallback callbacks) {
        android.hardware.bluetooth.V1_0.IBluetoothHci service;

        // Try V1.1 first
        try {
            service = android.hardware.bluetooth.V1_1.IBluetoothHci.getService(true);
            if (service != null) {
                CourierLogger.d(TAG, "Found HIDL HAL V1.1");
                return new HciHidlHal(service, callbacks);
            }
        } catch (NoSuchElementException e) {
            CourierLogger.d(TAG, "HIDL HAL V1.1 not found");
        } catch (RemoteException e) {
            CourierLogger.w(TAG, "Exception from getService V1.1: " + e);
        }

        // Fall back to V1.0
        try {
            service = android.hardware.bluetooth.V1_0.IBluetoothHci.getService(true);
        } catch (NoSuchElementException e) {
            CourierLogger.d(TAG, "HIDL HAL V1.0 not found");
            return null;
        } catch (RemoteException e) {
            CourierLogger.w(TAG, "Exception from getService V1.0: " + e);
            return null;
        }
        CourierLogger.d(TAG, "Found HIDL HAL V1.0");
        return new HciHidlHal(service, callbacks);
    }

    private HciHidlHal(android.hardware.bluetooth.V1_0.IBluetoothHci service,
                       IHciHalCallback callbacks) {
        mHciService = service;
        mCallbacks = callbacks;
    }

    @Override
    public HciStatus initialize() throws RemoteException, InterruptedException {
        if (mClosed.get()) {
            throw new IllegalStateException("HAL has been closed");
        }

        mHciService.initialize(this);
        CourierLogger.d(TAG, "Waiting for initialization status...");
        synchronized (this) {
            while (mInitializationStatus == -1) {
                wait();
            }
        }
        CourierLogger.d(TAG, "Initialization status = " + mInitializationStatus);

        HciStatus status;
        switch (mInitializationStatus) {
            case android.hardware.bluetooth.V1_0.Status.SUCCESS:
                status = HciStatus.SUCCESS;
                mInitialized.set(true);
                break;
            case android.hardware.bluetooth.V1_0.Status.TRANSPORT_ERROR:
                status = HciStatus.TRANSPORT_ERROR;
                break;
            case android.hardware.bluetooth.V1_0.Status.INITIALIZATION_ERROR:
                status = HciStatus.INITIALIZATION_ERROR;
                break;
            default:
                status = HciStatus.UNKNOWN;
                break;
        }
        return status;
    }

    @Override
    public void sendPacket(HciPacketType type, byte[] packet) {
        if (!isInitialized()) {
            CourierLogger.w(TAG, "Cannot send packet: HAL not initialized");
            return;
        }

        ArrayList<Byte> data = HciPacket.byteArrayToList(packet);
        if (mTracingEnabled) {
            Trace.beginAsyncSection("SEND_PACKET_TO_HAL", 1);
        }
        try {
            switch (type) {
                case COMMAND:
                    mHciService.sendHciCommand(data);
                    break;
                case ACL_DATA:
                    mHciService.sendAclData(data);
                    break;
                case SCO_DATA:
                    mHciService.sendScoData(data);
                    break;
                default:
                    break;
            }
        } catch (RemoteException e) {
            CourierLogger.w(TAG, "Failed to forward packet: " + e);
        }
        if (mTracingEnabled) {
            Trace.endAsyncSection("SEND_PACKET_TO_HAL", 1);
        }
    }

    @Override
    public boolean isInitialized() {
        return mInitialized.get() && !mClosed.get();
    }

    @Override
    public void close() {
        if (mClosed.compareAndSet(false, true)) {
            mInitialized.set(false);
            CourierLogger.d(TAG, "HIDL HAL closed");
        }
    }

    @Override
    public synchronized void initializationComplete(int status) throws RemoteException {
        mInitializationStatus = status;
        notifyAll();
    }

    @Override
    public void hciEventReceived(ArrayList<Byte> event) throws RemoteException {
        if (isInitialized()) {
            byte[] packet = HciPacket.listToByteArray(event);
            mCallbacks.onPacket(HciPacketType.EVENT, packet);
        }
    }

    @Override
    public void aclDataReceived(ArrayList<Byte> data) throws RemoteException {
        if (isInitialized()) {
            byte[] packet = HciPacket.listToByteArray(data);
            mCallbacks.onPacket(HciPacketType.ACL_DATA, packet);
        }
    }

    @Override
    public void scoDataReceived(ArrayList<Byte> data) throws RemoteException {
        if (isInitialized()) {
            byte[] packet = HciPacket.listToByteArray(data);
            mCallbacks.onPacket(HciPacketType.SCO_DATA, packet);
        }
    }
}