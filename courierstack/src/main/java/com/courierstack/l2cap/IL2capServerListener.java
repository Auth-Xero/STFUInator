package com.courierstack.l2cap;

/**
 * Listener interface for L2CAP server (incoming connections).
 *
 * <p>Implementations can accept or reject incoming L2CAP channel
 * connection requests on registered PSMs.
 *
 * <p>Thread safety: Callbacks may be invoked from any thread.
 * Implementations should be thread-safe.
 */
public interface IL2capServerListener {

    /**
     * Called when an incoming connection request is received.
     *
     * <p>The channel is in WAIT_CONNECT state when this is called.
     * Return true to accept the connection (channel will move to CONFIG),
     * or false to reject it.
     *
     * @param channel the requesting channel
     * @return true to accept, false to reject
     */
    boolean onConnectionRequest(L2capChannel channel);

    /**
     * Called when the channel configuration is complete and ready.
     *
     * <p>Default implementation does nothing.
     *
     * @param channel the opened channel
     */
    default void onChannelOpened(L2capChannel channel) {}

    /**
     * Called when data is received on a server channel.
     *
     * @param channel the channel
     * @param data    received L2CAP payload
     */
    void onDataReceived(L2capChannel channel, byte[] data);

    /**
     * Called when a server channel is closed.
     *
     * @param channel the closed channel
     */
    void onChannelClosed(L2capChannel channel);

    /**
     * Called when an error occurs on a server channel.
     *
     * <p>Default implementation does nothing.
     *
     * @param channel the channel (may be null for general errors)
     * @param message error description
     */
    default void onError(L2capChannel channel, String message) {}
}