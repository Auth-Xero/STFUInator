package com.courierstack.scan;

/**
 * Listener interface for device discovery events.
 *
 * <p>Implementations receive notifications about discovered devices,
 * scan state changes, and scan completion.
 *
 * <p>Thread safety: Callbacks may be invoked from any thread.
 * Implementations should be thread-safe.
 */
public interface IScanListener {

    /**
     * Called when a device is discovered or updated.
     *
     * <p>This may be called multiple times for the same device as
     * new information (name, RSSI, etc.) becomes available.
     *
     * @param device the discovered or updated device
     */
    void onDeviceFound(ScannedDevice device);

    /**
     * Called when scan state changes.
     *
     * @param scanning true if scanning is now active
     */
    default void onScanStateChanged(boolean scanning) {}

    /**
     * Called when a scan completes (inquiry or LE scan finished).
     *
     * <p>Note: For continuous LE scanning, this is only called when
     * scanning is explicitly stopped.
     */
    default void onScanComplete() {}

    /**
     * Called when an error occurs during scanning.
     *
     * @param message error description
     */
    default void onError(String message) {}

    /**
     * Creates a simple listener from a device found callback.
     *
     * @param onDeviceFound callback for device discovery
     * @return listener instance
     */
    static IScanListener create(java.util.function.Consumer<ScannedDevice> onDeviceFound) {
        return device -> {
            if (onDeviceFound != null) {
                onDeviceFound.accept(device);
            }
        };
    }
}