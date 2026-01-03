package com.courierstack.a2dp.avdtp;

import java.util.List;

/**
 * Listener interface for AVDTP events.
 *
 * <p>Implement this interface to receive notifications about AVDTP
 * connection state changes, stream events, and media data.
 *
 * <p>Usage example:
 * <pre>{@code
 * IAvdtpListener listener = new IAvdtpListener() {
 *     @Override
 *     public void onConnected(int handle, byte[] address) {
 *         Log.d(TAG, "Connected to " + formatAddress(address));
 *     }
 *
 *     @Override
 *     public void onStreamStarted(int handle) {
 *         Log.d(TAG, "Streaming started on handle " + handle);
 *     }
 *
 *     // ... implement other methods
 * };
 *
 * AvdtpManager avdtp = new AvdtpManager(l2capManager, listener);
 * }</pre>
 *
 * <p>Thread safety: Callbacks may be invoked from different threads.
 * Implementations should handle synchronization as needed.
 */
public interface IAvdtpListener {

    // ==================== Connection Events ====================

    /**
     * Called when AVDTP connection is established.
     *
     * @param handle  ACL connection handle
     * @param address peer device Bluetooth address (6 bytes)
     */
    void onConnected(int handle, byte[] address);

    /**
     * Called when AVDTP connection is disconnected.
     *
     * @param handle ACL connection handle
     * @param reason disconnection reason code
     */
    default void onDisconnected(int handle, int reason) {
        // Default empty implementation
    }

    // ==================== Discovery Events ====================

    /**
     * Called when stream endpoints are discovered on the remote device.
     *
     * @param handle    ACL connection handle
     * @param endpoints list of discovered endpoints
     */
    void onEndpointsDiscovered(int handle, List<StreamEndpoint> endpoints);

    /**
     * Called when stream endpoints are discovered.
     *
     * @param endpoints discovered endpoints array
     * @deprecated Use {@link #onEndpointsDiscovered(int, List)} instead
     */
    @Deprecated
    default void onEndpointsDiscovered(StreamEndpoint[] endpoints) {
        // Default empty implementation for backward compatibility
    }

    /**
     * Called when capabilities are received for an endpoint.
     *
     * @param handle   connection handle
     * @param seid     endpoint SEID
     * @param endpoint endpoint with updated capabilities
     */
    default void onCapabilitiesReceived(int handle, int seid, StreamEndpoint endpoint) {
        // Default empty implementation
    }

    // ==================== Configuration Events ====================

    /**
     * Called when stream is configured.
     *
     * @param localSeid  local endpoint ID
     * @param remoteSeid remote endpoint ID
     */
    void onStreamConfigured(int localSeid, int remoteSeid);

    /**
     * Called when stream configuration is rejected.
     *
     * @param seid      endpoint SEID
     * @param errorCode AVDTP error code
     */
    default void onConfigurationRejected(int seid, int errorCode) {
        // Default empty implementation
    }

    // ==================== Stream State Events ====================

    /**
     * Called when stream is opened (media channel established).
     *
     * @param localSeid local endpoint ID
     */
    void onStreamOpened(int localSeid);

    /**
     * Called when streaming starts.
     *
     * @param handle ACL connection handle
     */
    void onStreamStarted(int handle);

    /**
     * Called when streaming starts.
     *
     * @deprecated Use {@link #onStreamStarted(int)} instead
     */
    @Deprecated
    default void onStreamStarted() {
        // Default empty implementation for backward compatibility
    }

    /**
     * Called when streaming is suspended.
     *
     * @param handle ACL connection handle
     */
    void onStreamSuspended(int handle);

    /**
     * Called when streaming is suspended.
     *
     * @deprecated Use {@link #onStreamSuspended(int)} instead
     */
    @Deprecated
    default void onStreamSuspended() {
        // Default empty implementation for backward compatibility
    }

    /**
     * Called when stream is closed.
     *
     * @param handle ACL connection handle
     */
    void onStreamClosed(int handle);

    /**
     * Called when stream is closed.
     *
     * @deprecated Use {@link #onStreamClosed(int)} instead
     */
    @Deprecated
    default void onStreamClosed() {
        // Default empty implementation for backward compatibility
    }

    /**
     * Called when stream is aborted.
     *
     * @param handle ACL connection handle
     */
    default void onStreamAborted(int handle) {
        // Default empty implementation
    }

    // ==================== Media Events ====================

    /**
     * Called when media data is received (sink role).
     *
     * @param handle    ACL connection handle
     * @param data      media payload data (e.g., SBC frames)
     * @param timestamp RTP timestamp
     */
    void onMediaReceived(int handle, byte[] data, int timestamp);

    /**
     * Called when media data is sent (source role).
     *
     * @param handle      connection handle
     * @param bytesSent   bytes sent
     * @param packetCount packets sent
     */
    default void onMediaSent(int handle, int bytesSent, int packetCount) {
        // Default empty implementation
    }

    /**
     * Called when delay report is received.
     *
     * @param handle  connection handle
     * @param delayMs reported delay in milliseconds
     */
    default void onDelayReport(int handle, int delayMs) {
        // Default empty implementation
    }

    // ==================== Status Events ====================

    /**
     * Called when an error occurs.
     *
     * @param message error description
     */
    void onError(String message);

    /**
     * Called for informational/debug messages.
     *
     * @param message info message
     */
    void onMessage(String message);

    // ==================== Adapter Class ====================

    /**
     * Adapter class with empty implementations of all methods.
     * Extend this class and override only the methods you need.
     */
    abstract class Adapter implements IAvdtpListener {
        @Override
        public void onConnected(int handle, byte[] address) {}

        @Override
        public void onEndpointsDiscovered(int handle, List<StreamEndpoint> endpoints) {}

        @Override
        public void onStreamConfigured(int localSeid, int remoteSeid) {}

        @Override
        public void onStreamOpened(int localSeid) {}

        @Override
        public void onStreamStarted(int handle) {}

        @Override
        public void onStreamSuspended(int handle) {}

        @Override
        public void onStreamClosed(int handle) {}

        @Override
        public void onMediaReceived(int handle, byte[] data, int timestamp) {}

        @Override
        public void onError(String message) {}

        @Override
        public void onMessage(String message) {}
    }
}