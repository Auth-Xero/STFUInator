package com.courierstack.security.bredr;

/**
 * Callback interface for asynchronous pairing operations.
 *
 * <p>Implementations receive callbacks when pairing completes and for
 * user interaction requirements during pairing.
 *
 * <p>Thread Safety: Callbacks may be invoked from different threads.
 * Implementations should be thread-safe or dispatch to a specific thread.
 */
public interface IBrEdrPairingCallback {

    /**
     * Called when pairing completes.
     *
     * @param success     true if pairing succeeded
     * @param bondingInfo bonding information if successful, null if failed
     */
    void onPairingComplete(boolean success, BondingInfo bondingInfo);

    /**
     * Called for pairing progress updates.
     *
     * @param address peer device address
     * @param state   current pairing state
     * @param message progress message
     */
    default void onPairingProgress(byte[] address, BrEdrPairingState state, String message) {
        // Default: no-op
    }

    /**
     * Called when passkey input or display is required.
     *
     * @param address peer device address
     * @param display true if we should display the passkey, false if we should enter it
     * @param passkey passkey to display (only valid if display is true)
     */
    default void onPasskeyRequired(byte[] address, boolean display, int passkey) {
        // Default: no-op (pairing will likely fail if not handled)
    }

    /**
     * Called when numeric comparison confirmation is required.
     *
     * @param address      peer device address
     * @param numericValue the 6-digit value to compare
     */
    default void onConfirmationRequired(byte[] address, int numericValue) {
        // Default: no-op (pairing will likely fail if not handled)
    }
}