package com.courierstack.rfcomm;

/**
 * Listener interface for RFCOMM server (incoming connections).
 *
 * <p>Implementations can accept or reject incoming RFCOMM channel
 * connection requests on registered server channels.
 *
 * <p>Thread Safety: Callbacks may be invoked from any thread.
 * Implementations should be thread-safe.
 */
public interface IRfcommServerListener {

    /**
     * Called when an incoming connection request is received.
     *
     * <p>Return true to accept the connection (UA will be sent),
     * or false to reject it (DM will be sent).
     *
     * @param serverChannel server channel number (1-30)
     * @param remoteAddress remote device BD_ADDR (6 bytes)
     * @return true to accept, false to reject
     */
    boolean onConnectionRequest(int serverChannel, byte[] remoteAddress);

    /**
     * Called when a server channel is opened (UA sent, MSC exchanged).
     *
     * @param channel the opened channel
     */
    void onChannelOpened(RfcommChannel channel);

    /**
     * Called when data is received on a server channel.
     *
     * @param channel the channel
     * @param data    received data
     */
    void onDataReceived(RfcommChannel channel, byte[] data);

    /**
     * Called when a server channel is closed.
     *
     * @param channel the closed channel
     */
    void onChannelClosed(RfcommChannel channel);

    /**
     * Called when modem status changes on a server channel.
     *
     * <p>Default implementation does nothing.
     *
     * @param channel the channel
     * @param status  new modem status bits
     */
    default void onModemStatusChanged(RfcommChannel channel, int status) {
        // Default: no-op
    }

    /**
     * Called when an error occurs on a server channel.
     *
     * <p>Default implementation does nothing.
     *
     * @param channel the channel (may be null for general errors)
     * @param message error description
     */
    default void onError(RfcommChannel channel, String message) {
        // Default: no-op
    }
}