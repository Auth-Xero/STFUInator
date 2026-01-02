package com.courierstack.rfcomm;

/**
 * Listener interface for RFCOMM events.
 */
public interface IRfcommListener {
    
    /**
     * Called when a channel is connected.
     *
     * @param channel the connected channel
     */
    void onConnected(RfcommChannel channel);
    
    /**
     * Called when a channel is disconnected.
     *
     * @param channel the disconnected channel
     */
    void onDisconnected(RfcommChannel channel);
    
    /**
     * Called when data is received.
     *
     * @param channel the channel
     * @param data    received data
     */
    void onDataReceived(RfcommChannel channel, byte[] data);
    
    /**
     * Called when modem status changes.
     *
     * @param channel the channel
     * @param status  new modem status
     */
    void onModemStatusChanged(RfcommChannel channel, int status);
    
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
    void onMessage(String message);
}
