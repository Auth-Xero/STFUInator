package com.courierstack.hal;

import android.os.RemoteException;

import com.courierstack.util.CourierLogger;
import com.courierstack.hci.HciPacketType;
import com.courierstack.hci.HciStatus;

import java.io.Closeable;

/**
 * Hardware Abstraction Layer interface for HCI transport.
 *
 * <p>This interface abstracts the underlying Bluetooth HAL implementation,
 * supporting both AIDL (Android 13+) and HIDL (Android 8.0-12) interfaces.
 *
 * <p>Usage example:
 * <pre>{@code
 * IHciHal hal = IHciHal.create(callback);
 * if (hal != null) {
 *     try {
 *         HciStatus status = hal.initialize();
 *         if (status.isSuccess()) {
 *             hal.sendPacket(HciPacketType.COMMAND, commandData);
 *         }
 *     } finally {
 *         hal.close();
 *     }
 * }
 * }</pre>
 *
 * <p>Thread safety: Implementations must be thread-safe. The {@link #sendPacket}
 * method may be called from any thread.
 */
public interface IBluetoothHal extends Closeable {

    String TAG = "IHciHal";

    /**
     * Creates an IHciHal instance, preferring AIDL (newer) over HIDL (legacy).
     *
     * <p>The factory method attempts to connect to available HAL implementations
     * in the following order:
     * <ol>
     *   <li>AIDL HAL (Android 13+) - preferred for better performance and features</li>
     *   <li>HIDL HAL (Android 8.0-12) - fallback for older devices</li>
     * </ol>
     *
     * @param callbacks callback handler for received packets (must not be null)
     * @return IHciHal instance or null if no HAL is available
     * @throws NullPointerException if callbacks is null
     */
    static IBluetoothHal create(IBluetoothHalCallback callbacks) {
        if (callbacks == null) {
            throw new NullPointerException("callbacks must not be null");
        }

        // Prefer AIDL (newer, better performance) over HIDL (legacy)
        IBluetoothHal hal = AidlHalAdapter.create(callbacks);
        if (hal != null) {
            CourierLogger.d(TAG, "Using AIDL HAL");
            return hal;
        }

        hal = HidlHalAdapter.create(callbacks);
        if (hal != null) {
            CourierLogger.d(TAG, "Using HIDL HAL (legacy)");
            return hal;
        }

        CourierLogger.w(TAG, "No Bluetooth HAL available");
        return null;
    }

    /**
     * Initializes the HAL and Bluetooth controller.
     *
     * <p>This method blocks until initialization completes or fails.
     * It should only be called once per HAL instance.
     *
     * @return initialization status
     * @throws RemoteException      on IPC failure
     * @throws InterruptedException if the calling thread is interrupted
     * @throws IllegalStateException if already initialized
     */
    HciStatus initialize() throws RemoteException, InterruptedException;

    /**
     * Sends an HCI packet to the controller.
     *
     * <p>This method is non-blocking and may be called from any thread.
     * Delivery failures are reported via the callback's error mechanism.
     *
     * @param type   packet type (COMMAND, ACL_DATA, SCO_DATA, or ISO_DATA)
     * @param packet packet data (must not be null)
     * @throws NullPointerException if type or packet is null
     * @throws IllegalStateException if not initialized
     */
    void sendPacket(HciPacketType type, byte[] packet);

    /**
     * Returns whether the HAL has been successfully initialized.
     *
     * @return true if initialized and ready to send/receive packets
     */
    boolean isInitialized();

    /**
     * Closes the HAL and releases resources.
     *
     * <p>After calling this method, the HAL instance should not be used.
     * This method is idempotent - calling it multiple times has no effect.
     */
    @Override
    void close();
}