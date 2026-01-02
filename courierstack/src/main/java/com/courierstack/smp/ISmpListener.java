package com.courierstack.smp;

/**
 * Listener for SMP pairing events.
 *
 * <p>Implementations receive callbacks for pairing lifecycle events,
 * user interaction requirements, and error conditions.
 *
 * <p>Thread Safety: Callbacks may be invoked from different threads.
 * Implementations should be thread-safe or dispatch to a specific thread.
 */
public interface ISmpListener {

    /**
     * Called when pairing procedure starts.
     *
     * @param handle connection handle
     * @param address peer Bluetooth address
     */
    void onPairingStarted(int handle, byte[] address);

    /**
     * Called when a pairing request is received (responder role).
     *
     * @param handle connection handle
     * @param address peer Bluetooth address
     * @param ioCap peer IO capability
     * @param authReq peer authentication requirements
     * @param secureConnections true if peer supports Secure Connections
     */
    void onPairingRequest(int handle, byte[] address, int ioCap, int authReq, boolean secureConnections);

    /**
     * Called when pairing completes successfully.
     *
     * @param handle connection handle
     * @param address peer Bluetooth address
     * @param success true if pairing succeeded
     * @param info bonding information (may be null if success is false)
     */
    void onPairingComplete(int handle, byte[] address, boolean success, BondingInfo info);

    /**
     * Called when pairing fails.
     *
     * @param handle connection handle
     * @param address peer Bluetooth address
     * @param errorCode SMP error code
     * @param reason human-readable failure reason
     */
    void onPairingFailed(int handle, byte[] address, int errorCode, String reason);

    /**
     * Called when link encryption status changes.
     *
     * @param handle connection handle
     * @param encrypted true if encryption is now enabled
     */
    default void onEncryptionChanged(int handle, boolean encrypted) {
        // Default: no-op
    }

    /**
     * Called when passkey input/display is required.
     *
     * @param handle connection handle
     * @param address peer Bluetooth address
     * @param display true if passkey should be displayed, false if user should input
     * @param passkey the passkey to display (only valid if display is true)
     */
    default void onPasskeyRequired(int handle, byte[] address, boolean display, int passkey) {
        // Default: no-op (will likely fail pairing if not handled)
    }

    /**
     * Called when numeric comparison confirmation is required (SC only).
     *
     * @param handle connection handle
     * @param address peer Bluetooth address
     * @param numericValue the 6-digit value to compare
     */
    default void onNumericComparisonRequired(int handle, byte[] address, int numericValue) {
        // Default: no-op (will likely fail pairing if not handled)
    }

    /**
     * Called when peer identity address is received during key distribution.
     *
     * @param handle connection handle
     * @param peerAddress current peer address
     * @param identityAddress peer's identity address
     * @param addressType identity address type
     */
    default void onIdentityAddressReceived(int handle, byte[] peerAddress,
                                           byte[] identityAddress, int addressType) {
        // Default: no-op
    }

    /**
     * Called when a Security Request is received from the peer.
     *
     * @param handle connection handle
     * @param authReq authentication requirements from peer
     */
    default void onSecurityRequest(int handle, int authReq) {
        // Default: no-op
    }

    /**
     * Called for debug/info messages.
     *
     * @param message informational message
     */
    default void onMessage(String message) {
        // Default: no-op
    }

    /**
     * Called on errors.
     *
     * @param message error message
     */
    default void onError(String message) {
        // Default: no-op
    }
}