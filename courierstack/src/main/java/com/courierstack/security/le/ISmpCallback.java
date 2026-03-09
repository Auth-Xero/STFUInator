package com.courierstack.security.le;

/**
 * Callback interface for asynchronous SMP pairing operations.
 *
 * <p>This callback provides detailed information about pairing progress,
 * success, and failure conditions. Implementations receive both error
 * details (via onPairingFailed) and completion status (via onPairingComplete).
 *
 * <p>Call order on failure:
 * <ol>
 *   <li>{@link #onPairingFailed(int, int, String)} - with error details</li>
 *   <li>{@link #onPairingComplete(int, boolean, BondingInfo)} - with success=false</li>
 * </ol>
 *
 * <p>Call order on success:
 * <ol>
 *   <li>{@link #onPairingComplete(int, boolean, BondingInfo)} - with success=true and bondingInfo</li>
 * </ol>
 *
 * <p>Thread Safety: Callbacks may be invoked from any thread.
 * Implementations should be thread-safe.
 */
public interface ISmpCallback {

    /**
     * Called when pairing completes (either success or failure).
     *
     * <p>For failures, this is called AFTER onPairingFailed().
     *
     * @param connectionHandle ACL connection handle
     * @param success          true if pairing succeeded
     * @param bondingInfo      bonding information (null if success is false)
     */
    void onPairingComplete(int connectionHandle, boolean success, BondingInfo bondingInfo);

    /**
     * Called when pairing fails, providing error details.
     *
     * <p>This is called BEFORE onPairingComplete(success=false) to allow
     * capturing error details before session cleanup.
     *
     * @param connectionHandle ACL connection handle
     * @param errorCode        SMP error code (see SmpConstants.ERR_*)
     * @param errorMessage     human-readable error description
     */
    default void onPairingFailed(int connectionHandle, int errorCode, String errorMessage) {
        // Default no-op - implementations can override to capture error details
    }

    /**
     * Called when passkey input or display is required.
     *
     * <p>For display=true, show the passkey to the user.
     * For display=false, prompt user to enter the passkey shown on peer device.
     *
     * @param connectionHandle ACL connection handle
     * @param display          true if passkey should be displayed to user
     * @param passkey          passkey value (only valid if display=true)
     */
    default void onPasskeyRequired(int connectionHandle, boolean display, int passkey) {
        // Default no-op - pairing may fail if not handled
    }

    /**
     * Called when numeric comparison confirmation is required (Secure Connections only).
     *
     * <p>Display the numeric value to the user and confirm it matches the peer device.
     * Call SmpManager.confirmNumericComparison() with the user's response.
     *
     * @param connectionHandle ACL connection handle
     * @param numericValue     6-digit numeric value to compare
     */
    default void onNumericComparisonRequired(int connectionHandle, int numericValue) {
        // Default no-op - pairing may fail if not handled
    }

    /**
     * Creates a simple callback that captures success/failure status.
     *
     * @param onComplete consumer for completion (success, bondingInfo)
     * @return callback instance
     */
    static ISmpCallback simple(java.util.function.BiConsumer<Boolean, BondingInfo> onComplete) {
        return (handle, success, info) -> {
            if (onComplete != null) {
                onComplete.accept(success, info);
            }
        };
    }

    /**
     * Creates a callback with error handling.
     *
     * @param onComplete consumer for completion (success, bondingInfo)
     * @param onFailed   consumer for failure (errorCode, errorMessage)
     * @return callback instance
     */
    static ISmpCallback withErrorHandling(
            java.util.function.BiConsumer<Boolean, BondingInfo> onComplete,
            java.util.function.BiConsumer<Integer, String> onFailed) {
        return new ISmpCallback() {
            @Override
            public void onPairingComplete(int handle, boolean success, BondingInfo info) {
                if (onComplete != null) {
                    onComplete.accept(success, info);
                }
            }

            @Override
            public void onPairingFailed(int handle, int errorCode, String errorMessage) {
                if (onFailed != null) {
                    onFailed.accept(errorCode, errorMessage);
                }
            }
        };
    }

    /**
     * Creates a full callback with all event handlers.
     *
     * @param onComplete         completion handler
     * @param onFailed           failure handler
     * @param onPasskey          passkey handler
     * @param onNumericComparison numeric comparison handler
     * @return callback instance
     */
    static ISmpCallback full(
            java.util.function.BiConsumer<Boolean, BondingInfo> onComplete,
            java.util.function.BiConsumer<Integer, String> onFailed,
            java.util.function.BiConsumer<Boolean, Integer> onPasskey,
            java.util.function.Consumer<Integer> onNumericComparison) {
        return new ISmpCallback() {
            @Override
            public void onPairingComplete(int handle, boolean success, BondingInfo info) {
                if (onComplete != null) {
                    onComplete.accept(success, info);
                }
            }

            @Override
            public void onPairingFailed(int handle, int errorCode, String errorMessage) {
                if (onFailed != null) {
                    onFailed.accept(errorCode, errorMessage);
                }
            }

            @Override
            public void onPasskeyRequired(int handle, boolean display, int passkey) {
                if (onPasskey != null) {
                    onPasskey.accept(display, passkey);
                }
            }

            @Override
            public void onNumericComparisonRequired(int handle, int numericValue) {
                if (onNumericComparison != null) {
                    onNumericComparison.accept(numericValue);
                }
            }
        };
    }
}