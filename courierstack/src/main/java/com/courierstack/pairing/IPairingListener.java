package com.courierstack.pairing;

/**
 * Listener interface for BR/EDR pairing events.
 *
 * <p>Implementations receive callbacks for pairing lifecycle events,
 * user interaction requirements, and error conditions.
 *
 * <p>Thread Safety: Callbacks may be invoked from different threads.
 * Implementations should be thread-safe or dispatch to a specific thread.
 */
public interface IPairingListener {

    /**
     * Called when pairing procedure starts.
     *
     * @param connectionHandle ACL connection handle
     * @param address          peer device address
     */
    void onPairingStarted(int connectionHandle, byte[] address);

    /**
     * Called when IO capability exchange is requested.
     *
     * <p>The application should call {@link PairingManager#respondToIoCapability(int, boolean)}
     * to respond to this request.
     *
     * @param connectionHandle ACL connection handle
     * @param address          peer device address
     */
    void onIoCapabilityRequest(int connectionHandle, byte[] address);

    /**
     * Called when numeric comparison confirmation is required.
     *
     * <p>Both devices display the same 6-digit value. The user should verify
     * they match and call {@link PairingManager#confirmNumericComparison(int, boolean)}.
     *
     * @param connectionHandle ACL connection handle
     * @param address          peer device address
     * @param numericValue     the 6-digit value to compare (0-999999)
     */
    void onNumericComparison(int connectionHandle, byte[] address, int numericValue);

    /**
     * Called when passkey input or display is required.
     *
     * <p>If {@code display} is true, the passkey should be displayed to the user
     * for entry on the peer device. If false, the user should enter the passkey
     * displayed on the peer device and call {@link PairingManager#enterPasskey(int, int)}.
     *
     * @param connectionHandle ACL connection handle
     * @param address          peer device address
     * @param display          true if we should display the passkey, false if we should enter it
     * @param passkey          passkey to display (only valid if display is true)
     */
    void onPasskeyRequest(int connectionHandle, byte[] address, boolean display, int passkey);

    /**
     * Called when pairing completes successfully.
     *
     * @param connectionHandle ACL connection handle
     * @param address          peer device address
     * @param success          true if pairing succeeded
     * @param bondingInfo      bonding information if successful, null if failed
     */
    void onPairingComplete(int connectionHandle, byte[] address, boolean success, BondingInfo bondingInfo);

    /**
     * Called when pairing fails.
     *
     * @param connectionHandle ACL connection handle
     * @param address          peer device address
     * @param errorCode        HCI error code
     * @param reason           human-readable failure reason
     */
    void onPairingFailed(int connectionHandle, byte[] address, int errorCode, String reason);

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