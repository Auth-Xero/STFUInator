package com.courierstack.l2cap;

/**
 * Listener interface for L2CAP events.
 *
 * <p>Implementations receive notifications about ACL connection events,
 * L2CAP channel lifecycle events, and data reception.
 *
 * <p>Thread safety: Callbacks may be invoked from any thread.
 * Implementations should be thread-safe.
 */
public interface IL2capListener {

    /**
     * Called when an ACL connection is established.
     *
     * @param connection the new connection
     */
    void onConnectionComplete(AclConnection connection);

    /**
     * Called when an ACL connection is terminated.
     *
     * @param handle connection handle
     * @param reason HCI disconnection reason code
     */
    void onDisconnectionComplete(int handle, int reason);

    /**
     * Called when an L2CAP channel is opened (configuration complete).
     *
     * @param channel the opened channel
     */
    void onChannelOpened(L2capChannel channel);

    /**
     * Called when an L2CAP channel is closed.
     *
     * @param channel the closed channel
     */
    void onChannelClosed(L2capChannel channel);

    /**
     * Called when data is received on a dynamic channel.
     *
     * @param channel the channel
     * @param data    received L2CAP payload
     */
    void onDataReceived(L2capChannel channel, byte[] data);

    /**
     * Called when an incoming connection request is received.
     *
     * <p>This is called before the channel is fully established.
     * Use {@link IL2capServerListener} for more control over accepting connections.
     *
     * @param handle    ACL connection handle
     * @param psm       requested PSM
     * @param sourceCid remote source CID
     */
    default void onConnectionRequest(int handle, int psm, int sourceCid) {}

    /**
     * Called when data is received on a fixed channel (ATT, SMP, etc).
     *
     * @param handle          ACL connection handle
     * @param cid             fixed channel ID
     * @param peerAddress     peer device address
     * @param peerAddressType peer address type
     * @param data            received data
     */
    default void onFixedChannelData(int handle, int cid, byte[] peerAddress,
                                    int peerAddressType, byte[] data) {}

    /**
     * Called when an error occurs.
     *
     * @param message error description
     */
    void onError(String message);

    /**
     * Called for informational messages.
     *
     * @param message info message
     */
    default void onMessage(String message) {}

    /**
     * Called when connection parameter update is requested (LE).
     *
     * @param handle      connection handle
     * @param minInterval minimum connection interval
     * @param maxInterval maximum connection interval
     * @param latency     slave latency
     * @param timeout     supervision timeout
     * @return true to accept the parameters
     */
    default boolean onConnectionParameterUpdateRequest(int handle, int minInterval,
                                                       int maxInterval, int latency,
                                                       int timeout) {
        return true; // Accept by default
    }
}