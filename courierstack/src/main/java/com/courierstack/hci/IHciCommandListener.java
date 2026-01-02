package com.courierstack.hci;

/**
 * Listener interface for HCI events and data.
 *
 * <p>This interface provides callbacks for receiving HCI packets and events
 * from the Bluetooth controller. Implementations should be thread-safe as
 * callbacks may be invoked from different threads.
 *
 * <p>For convenience, default no-op implementations are provided for optional
 * callbacks such as {@link #onCommandComplete} and {@link #onCommandStatus}.
 */
public interface IHciCommandListener {

    /**
     * Called when an HCI event is received.
     *
     * @param event raw event data including event code and parameters
     */
    void onEvent(byte[] event);

    /**
     * Called when ACL data is received.
     *
     * @param data ACL packet including connection handle and data
     */
    void onAclData(byte[] data);

    /**
     * Called when SCO data is received.
     *
     * @param data SCO packet including connection handle and data
     */
    void onScoData(byte[] data);

    /**
     * Called when ISO data is received (Bluetooth 5.2+).
     *
     * @param data ISO packet including connection handle and data
     */
    void onIsoData(byte[] data);

    /**
     * Called when a Command Complete event (0x0E) is received.
     *
     * <p>This is a parsed notification extracted from the raw event.
     * The raw event is also delivered via {@link #onEvent}.
     *
     * @param opcode command opcode that completed
     * @param status HCI status code (0 = success)
     * @param returnParams return parameters (excluding status byte), may be empty
     */
    default void onCommandComplete(int opcode, int status, byte[] returnParams) {}

    /**
     * Called when a Command Status event (0x0F) is received.
     *
     * <p>This is a parsed notification extracted from the raw event.
     * The raw event is also delivered via {@link #onEvent}.
     *
     * @param opcode command opcode
     * @param status HCI status code (0 = pending, non-zero = error)
     */
    default void onCommandStatus(int opcode, int status) {}

    /**
     * Called when an error occurs in the HCI layer.
     *
     * @param message human-readable error description
     */
    void onError(String message);

    /**
     * Called for informational messages from the HCI layer.
     *
     * @param message informational message
     */
    void onMessage(String message);
}