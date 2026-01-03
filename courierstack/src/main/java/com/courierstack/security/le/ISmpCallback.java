package com.courierstack.security.le;


/**
 * Callback interface for asynchronous pairing operations.
 */
public interface ISmpCallback {
    /**
     * Called when pairing completes.
     *
     * @param handle connection handle
     * @param success true if successful
     * @param bondingInfo bonding info if successful
     */
    void onPairingComplete(int handle, boolean success, BondingInfo bondingInfo);

    /**
     * Called for progress updates.
     *
     * @param handle connection handle
     * @param state current state
     * @param message status message
     */
    default void onPairingProgress(int handle, SmpState state, String message) {}

    /**
     * Called when passkey is required.
     *
     * @param handle connection handle
     * @param display true if should display, false if should input
     * @param passkey passkey to display (if display is true)
     */
    default void onPasskeyRequired(int handle, boolean display, int passkey) {}

    /**
     * Called when numeric comparison is required.
     *
     * @param handle connection handle
     * @param numericValue 6-digit value to confirm
     */
    default void onNumericComparisonRequired(int handle, int numericValue) {}

    /**
     * Called when OOB data is required.
     *
     * @param handle connection handle
     */
    default void onOobDataRequired(int handle) {}
}