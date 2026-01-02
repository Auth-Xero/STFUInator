package com.courierstack.gatt;

import java.util.List;
import java.util.function.Consumer;

/**
 * Callback interfaces for GATT operations.
 *
 * <p>These interfaces provide asynchronous completion handling for
 * various GATT client and server operations.
 */
public final class GattCallback {

    private GattCallback() {
        // Utility class - prevent instantiation
    }

    // ==================== Operation Callback ====================

    /**
     * Callback for single GATT operations (read, write, etc.).
     */
    public interface Operation {
        /**
         * Called when the operation succeeds.
         *
         * @param data result data (may be null for write operations)
         */
        void onSuccess(byte[] data);

        /**
         * Called when the operation fails.
         *
         * @param errorCode ATT error code
         * @param message   human-readable error description
         */
        void onError(int errorCode, String message);

        /**
         * Creates an operation callback from lambda expressions.
         *
         * @param onSuccess success handler
         * @param onError   error handler
         * @return callback instance
         */
        static Operation create(Consumer<byte[]> onSuccess,
                                java.util.function.BiConsumer<Integer, String> onError) {
            return new Operation() {
                @Override
                public void onSuccess(byte[] data) {
                    if (onSuccess != null) onSuccess.accept(data);
                }

                @Override
                public void onError(int errorCode, String message) {
                    if (onError != null) onError.accept(errorCode, message);
                }
            };
        }

        /**
         * Creates a simple callback that only handles success.
         *
         * @param handler success handler
         * @return callback instance
         */
        static Operation successOnly(Consumer<byte[]> handler) {
            return create(handler, null);
        }

        /**
         * Creates a simple callback that only handles errors.
         *
         * @param handler error handler
         * @return callback instance
         */
        static Operation errorOnly(java.util.function.BiConsumer<Integer, String> handler) {
            return create(null, handler);
        }
    }

    // ==================== Discovery Callback ====================

    /**
     * Callback for service discovery operations.
     */
    public interface Discovery {
        /**
         * Called when service discovery completes successfully.
         *
         * @param services list of discovered services
         */
        void onServicesDiscovered(List<GattService> services);

        /**
         * Called when service discovery fails.
         *
         * @param errorCode ATT error code
         * @param message   human-readable error description
         */
        void onError(int errorCode, String message);

        /**
         * Creates a discovery callback from lambda expressions.
         *
         * @param onSuccess success handler
         * @param onError   error handler
         * @return callback instance
         */
        static Discovery create(Consumer<List<GattService>> onSuccess,
                                java.util.function.BiConsumer<Integer, String> onError) {
            return new Discovery() {
                @Override
                public void onServicesDiscovered(List<GattService> services) {
                    if (onSuccess != null) onSuccess.accept(services);
                }

                @Override
                public void onError(int errorCode, String message) {
                    if (onError != null) onError.accept(errorCode, message);
                }
            };
        }
    }

    // ==================== Notification Callback ====================

    /**
     * Callback for receiving notifications and indications.
     */
    public interface Notification {
        /**
         * Called when a notification is received.
         *
         * @param connectionHandle connection handle
         * @param charHandle       characteristic value handle
         * @param data             notification data
         */
        void onNotification(int connectionHandle, int charHandle, byte[] data);

        /**
         * Called when an indication is received.
         *
         * @param connectionHandle connection handle
         * @param charHandle       characteristic value handle
         * @param data             indication data
         */
        void onIndication(int connectionHandle, int charHandle, byte[] data);

        /**
         * Creates a notification callback that handles both notifications and indications.
         *
         * @param handler handler for both notification types
         * @return callback instance
         */
        static Notification create(NotificationHandler handler) {
            return new Notification() {
                @Override
                public void onNotification(int connectionHandle, int charHandle, byte[] data) {
                    if (handler != null) handler.handle(connectionHandle, charHandle, data, false);
                }

                @Override
                public void onIndication(int connectionHandle, int charHandle, byte[] data) {
                    if (handler != null) handler.handle(connectionHandle, charHandle, data, true);
                }
            };
        }

        /**
         * Creates a notification-only callback.
         *
         * @param handler notification handler
         * @return callback instance
         */
        static Notification notificationsOnly(NotificationHandler handler) {
            return new Notification() {
                @Override
                public void onNotification(int connectionHandle, int charHandle, byte[] data) {
                    if (handler != null) handler.handle(connectionHandle, charHandle, data, false);
                }

                @Override
                public void onIndication(int connectionHandle, int charHandle, byte[] data) {
                    // Ignored
                }
            };
        }
    }

    /**
     * Functional interface for handling notifications/indications.
     */
    @FunctionalInterface
    public interface NotificationHandler {
        /**
         * Handles a notification or indication.
         *
         * @param connectionHandle connection handle
         * @param charHandle       characteristic value handle
         * @param data             notification/indication data
         * @param isIndication     true if indication, false if notification
         */
        void handle(int connectionHandle, int charHandle, byte[] data, boolean isIndication);
    }

    // ==================== Server Callback ====================

    /**
     * Callback interface for GATT server operations.
     *
     * <p>Implementations handle read and write requests from connected clients.
     */
    public interface Server {
        /**
         * Called when a client reads an attribute.
         *
         * @param connectionHandle connection handle
         * @param handle           attribute handle
         * @return attribute value, or null to use default value
         */
        byte[] onRead(int connectionHandle, int handle);

        /**
         * Called when a client writes an attribute.
         *
         * @param connectionHandle connection handle
         * @param handle           attribute handle
         * @param data             write data
         * @param needResponse     true if write request (requires response)
         * @return ATT error code (0 = success)
         */
        int onWrite(int connectionHandle, int handle, byte[] data, boolean needResponse);

        /**
         * Called when a notification/indication is sent.
         *
         * @param connectionHandle connection handle
         * @param handle           characteristic value handle
         * @param success          true if sent successfully
         */
        default void onNotificationSent(int connectionHandle, int handle, boolean success) {
            // Default no-op
        }

        /**
         * Called when a client subscribes to notifications/indications.
         *
         * @param connectionHandle connection handle
         * @param charHandle       characteristic value handle
         * @param notifications    true if notifications enabled
         * @param indications      true if indications enabled
         */
        default void onSubscriptionChanged(int connectionHandle, int charHandle,
                                           boolean notifications, boolean indications) {
            // Default no-op
        }
    }

    // ==================== Server Adapter ====================

    /**
     * Adapter class for Server callback with sensible defaults.
     */
    public static class ServerAdapter implements Server {
        @Override
        public byte[] onRead(int connectionHandle, int handle) {
            return null; // Use default value
        }

        @Override
        public int onWrite(int connectionHandle, int handle, byte[] data, boolean needResponse) {
            return 0; // Success
        }
    }

    // ==================== MTU Callback ====================

    /**
     * Callback for MTU exchange operations.
     */
    public interface MtuExchange {
        /**
         * Called when MTU exchange completes.
         *
         * @param mtu negotiated MTU value
         */
        void onMtuChanged(int mtu);

        /**
         * Called when MTU exchange fails.
         *
         * @param errorCode ATT error code
         * @param message   error description
         */
        void onError(int errorCode, String message);

        /**
         * Creates an MTU callback from lambda expressions.
         *
         * @param onSuccess success handler
         * @param onError   error handler
         * @return callback instance
         */
        static MtuExchange create(Consumer<Integer> onSuccess,
                                  java.util.function.BiConsumer<Integer, String> onError) {
            return new MtuExchange() {
                @Override
                public void onMtuChanged(int mtu) {
                    if (onSuccess != null) onSuccess.accept(mtu);
                }

                @Override
                public void onError(int errorCode, String message) {
                    if (onError != null) onError.accept(errorCode, message);
                }
            };
        }
    }
}