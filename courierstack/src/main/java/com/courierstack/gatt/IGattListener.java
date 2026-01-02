package com.courierstack.gatt;

import java.util.List;

/**
 * Listener interface for GATT events.
 *
 * <p>Implementations receive notifications about GATT operations including
 * connection state changes, service discovery, characteristic read/write,
 * and notifications/indications.
 *
 * <p>Thread safety: Callbacks may be invoked from any thread.
 * Implementations should be thread-safe.
 *
 * @see GattListenerAdapter
 */
public interface IGattListener {

    // ==================== Connection Events ====================

    /**
     * Called when the GATT connection state changes.
     *
     * @param connectionHandle HCI connection handle
     * @param connected        true if connected, false if disconnected
     */
    void onConnectionStateChanged(int connectionHandle, boolean connected);

    // ==================== Service Discovery ====================

    /**
     * Called when service discovery completes successfully.
     *
     * @param connectionHandle connection handle
     * @param services         array of discovered services
     */
    void onServicesDiscovered(int connectionHandle, GattService[] services);

    // ==================== Characteristic Operations ====================

    /**
     * Called when a characteristic read operation completes.
     *
     * @param connectionHandle connection handle
     * @param characteristic   the characteristic that was read
     * @param value            the read value (may be null on error)
     * @param status           ATT status code (0 = success)
     */
    void onCharacteristicRead(int connectionHandle, GattCharacteristic characteristic,
                              byte[] value, int status);

    /**
     * Called when a characteristic write operation completes.
     *
     * @param connectionHandle connection handle
     * @param characteristic   the characteristic that was written
     * @param status           ATT status code (0 = success)
     */
    void onCharacteristicWrite(int connectionHandle, GattCharacteristic characteristic,
                               int status);

    // ==================== Notifications and Indications ====================

    /**
     * Called when a notification is received from a characteristic.
     *
     * <p>Notifications are unreliable and do not require confirmation.
     *
     * @param connectionHandle connection handle
     * @param characteristic   the characteristic that sent the notification
     * @param value            the notification value
     */
    void onNotification(int connectionHandle, GattCharacteristic characteristic, byte[] value);

    /**
     * Called when an indication is received from a characteristic.
     *
     * <p>Indications are reliable and automatically confirmed by the GATT layer.
     *
     * @param connectionHandle connection handle
     * @param characteristic   the characteristic that sent the indication
     * @param value            the indication value
     */
    void onIndication(int connectionHandle, GattCharacteristic characteristic, byte[] value);

    // ==================== Descriptor Operations ====================

    /**
     * Called when a descriptor read operation completes.
     *
     * @param connectionHandle connection handle
     * @param descriptor       the descriptor that was read
     * @param value            the read value (may be null on error)
     * @param status           ATT status code (0 = success)
     */
    void onDescriptorRead(int connectionHandle, GattDescriptor descriptor,
                          byte[] value, int status);

    /**
     * Called when a descriptor write operation completes.
     *
     * @param connectionHandle connection handle
     * @param descriptor       the descriptor that was written
     * @param status           ATT status code (0 = success)
     */
    void onDescriptorWrite(int connectionHandle, GattDescriptor descriptor, int status);

    // ==================== MTU ====================

    /**
     * Called when the MTU is changed via MTU exchange.
     *
     * @param connectionHandle connection handle
     * @param mtu              the new negotiated MTU value
     */
    void onMtuChanged(int connectionHandle, int mtu);

    // ==================== Errors and Messages ====================

    /**
     * Called when an error occurs in the GATT layer.
     *
     * @param message human-readable error description
     */
    void onError(String message);

    /**
     * Called for informational messages from the GATT layer.
     *
     * <p>Default implementation does nothing.
     *
     * @param message informational message
     */
    default void onMessage(String message) {
        // Default no-op
    }

    // ==================== Adapter Class ====================

    /**
     * Adapter class providing empty implementations of all listener methods.
     *
     * <p>Extend this class and override only the methods you need.
     *
     * <pre>{@code
     * GattManager gattManager = new GattManager(l2cap, new GattListenerAdapter() {
     *     @Override
     *     public void onServicesDiscovered(int handle, GattService[] services) {
     *         // Handle service discovery
     *     }
     *
     *     @Override
     *     public void onNotification(int handle, GattCharacteristic c, byte[] value) {
     *         // Handle notifications
     *     }
     * });
     * }</pre>
     */
    class Adapter implements IGattListener {

        @Override
        public void onConnectionStateChanged(int connectionHandle, boolean connected) {
        }

        @Override
        public void onServicesDiscovered(int connectionHandle, GattService[] services) {
        }

        @Override
        public void onCharacteristicRead(int connectionHandle, GattCharacteristic characteristic,
                                         byte[] value, int status) {
        }

        @Override
        public void onCharacteristicWrite(int connectionHandle, GattCharacteristic characteristic,
                                          int status) {
        }

        @Override
        public void onNotification(int connectionHandle, GattCharacteristic characteristic,
                                   byte[] value) {
        }

        @Override
        public void onIndication(int connectionHandle, GattCharacteristic characteristic,
                                 byte[] value) {
        }

        @Override
        public void onDescriptorRead(int connectionHandle, GattDescriptor descriptor,
                                     byte[] value, int status) {
        }

        @Override
        public void onDescriptorWrite(int connectionHandle, GattDescriptor descriptor,
                                      int status) {
        }

        @Override
        public void onMtuChanged(int connectionHandle, int mtu) {
        }

        @Override
        public void onError(String message) {
        }

        @Override
        public void onMessage(String message) {
        }
    }

    /**
     * Convenience alias for the adapter class.
     *
     * @deprecated Use {@link Adapter} instead
     */
    @Deprecated
    class GattListenerAdapter extends Adapter {
    }
}