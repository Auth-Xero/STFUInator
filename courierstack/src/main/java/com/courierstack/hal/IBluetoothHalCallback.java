package com.courierstack.hal;

import com.courierstack.hci.HciPacketType;

/**
 * Callback interface for receiving HCI packets from the HAL.
 *
 * <p>Implementations of this interface receive raw HCI packets from the
 * Bluetooth controller via the HAL. Callbacks are invoked on the binder
 * thread and should not block for extended periods.
 *
 * <p>Thread safety note: Implementations must be thread-safe as callbacks
 * may arrive concurrently from different binder threads.
 */
public interface IBluetoothHalCallback {

    /**
     * Called when an HCI packet is received from the controller.
     *
     * <p>This method is invoked on a binder thread and should return quickly.
     * Long-running processing should be delegated to a worker thread.
     *
     * @param type   packet type (EVENT, ACL_DATA, SCO_DATA, or ISO_DATA)
     * @param packet packet data (excluding the HCI packet indicator byte)
     */
    void onPacket(HciPacketType type, byte[] packet);
}