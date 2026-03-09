package com.courierstack.security.le;

import com.courierstack.util.CourierLogger;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.courierstack.security.le.SmpConstants.*;

/**
 * Auto-retry SMP pairing with progressive security escalation.
 *
 * <p>This class wraps SmpManager to provide automatic retry functionality
 * when pairing fails due to authentication requirement mismatches. It tries
 * different security profiles from least secure to most secure until one
 * succeeds or all options are exhausted.
 *
 * <p>Usage example:
 * <pre>{@code
 * SmpAutoRetryPairing autoRetry = new SmpAutoRetryPairing(smpManager);
 * SmpAutoRetryPairing.Result result = autoRetry.pairWithAutoRetry(
 *     connectionHandle, peerAddress, peerAddressType, 30000L);
 *
 * if (result.isSuccess()) {
 *     BondingInfo bonding = result.getBondingInfo();
 *     SmpAuthReqProfile usedProfile = result.getSuccessfulProfile();
 * }
 * }</pre>
 *
 * <p>Thread Safety: This class is thread-safe. However, only one pairing
 * operation should be active per connection handle at a time.
 */
public class SmpAutoRetryPairing {

    private static final String TAG = "SmpAutoRetryPairing";

    /** Default delay between retry attempts (ms) - needs to be long enough for device to reset state. */
    private static final long DEFAULT_RETRY_DELAY_MS = 1000;

    /** Minimum timeout per attempt (ms). */
    private static final long MIN_ATTEMPT_TIMEOUT_MS = 15000;

    private final SmpManager mSmpManager;
    private volatile long mRetryDelayMs = DEFAULT_RETRY_DELAY_MS;
    private volatile SmpAuthReqProfile[] mRetrySequence = SmpAuthReqProfile.getDefaultRetrySequence();
    private volatile ISmpAutoRetryListener mListener;

    // Saved original settings to restore after pairing
    private int mOriginalAuthReq;
    private int mOriginalIoCap;
    private boolean mOriginalScEnabled;
    private boolean mSettingsSaved = false;

    /**
     * Creates a new auto-retry pairing helper.
     *
     * @param smpManager SMP manager to use for pairing
     * @throws NullPointerException if smpManager is null
     */
    public SmpAutoRetryPairing(SmpManager smpManager) {
        mSmpManager = Objects.requireNonNull(smpManager, "smpManager must not be null");
    }

    /**
     * Sets the retry sequence to use.
     *
     * @param sequence array of profiles to try in order
     */
    public void setRetrySequence(SmpAuthReqProfile[] sequence) {
        mRetrySequence = sequence != null ? sequence.clone() : SmpAuthReqProfile.getDefaultRetrySequence();
    }

    /**
     * Uses the SC-preferred retry sequence.
     */
    public void useScPreferredSequence() {
        mRetrySequence = SmpAuthReqProfile.getScPreferredSequence();
    }

    /**
     * Uses the identity resolution optimized sequence.
     */
    public void useIdentityResolutionSequence() {
        mRetrySequence = SmpAuthReqProfile.getIdentityResolutionSequence();
    }

    /**
     * Sets the delay between retry attempts.
     *
     * @param delayMs delay in milliseconds
     */
    public void setRetryDelay(long delayMs) {
        mRetryDelayMs = Math.max(0, delayMs);
    }

    /**
     * Sets a listener for retry events.
     *
     * @param listener listener to receive events
     */
    public void setListener(ISmpAutoRetryListener listener) {
        mListener = listener;
    }

    /**
     * Attempts pairing with automatic retry on authentication requirement failures.
     *
     * <p>This method will try each profile in the retry sequence until one
     * succeeds or all profiles have been tried. Only retryable errors
     * trigger a retry; other errors cause immediate failure.
     *
     * @param connectionHandle ACL connection handle
     * @param peerAddress peer Bluetooth address (6 bytes)
     * @param peerAddressType peer address type
     * @param totalTimeoutMs total timeout across all attempts
     * @return result containing success status, bonding info, and profile used
     */
    public Result pairWithAutoRetry(int connectionHandle, byte[] peerAddress,
                                    int peerAddressType, long totalTimeoutMs) {
        Objects.requireNonNull(peerAddress, "peerAddress must not be null");

        String addrStr = SmpConstants.formatAddress(peerAddress);
        CourierLogger.i(TAG, "Starting auto-retry pairing with " + addrStr);
        notifyStarted(addrStr, mRetrySequence.length);

        // Save original settings before modifying
        saveOriginalSettings();

        long startTime = System.currentTimeMillis();
        long remainingTime = totalTimeoutMs;

        // Calculate timeout per attempt
        long timeoutPerAttempt = Math.max(MIN_ATTEMPT_TIMEOUT_MS,
                totalTimeoutMs / mRetrySequence.length);

        Result lastResult = null;
        int attemptNumber = 0;

        try {
            for (SmpAuthReqProfile profile : mRetrySequence) {
                attemptNumber++;

                // Check if we've exceeded total timeout
                long elapsed = System.currentTimeMillis() - startTime;
                remainingTime = totalTimeoutMs - elapsed;

                if (remainingTime <= 0) {
                    CourierLogger.w(TAG, "Total timeout exceeded after " + (attemptNumber - 1) + " attempts");
                    break;
                }

                // Use either the per-attempt timeout or remaining time, whichever is smaller
                long attemptTimeout = Math.min(timeoutPerAttempt, remainingTime);

                CourierLogger.i(TAG, String.format("Attempt %d/%d: %s",
                        attemptNumber, mRetrySequence.length, profile.getDisplayName()));
                notifyAttempt(attemptNumber, profile);

                // Configure SMP manager for this attempt
                configureForProfile(profile);

                // Try pairing with this profile using callback-based approach
                PairingAttemptResult attemptResult = tryPairing(
                        connectionHandle, peerAddress, peerAddressType, attemptTimeout, profile);

                if (attemptResult.success) {
                    CourierLogger.i(TAG, "Pairing succeeded with profile: " + profile.getDisplayName());

                    // Get bonding info from SmpManager
                    BondingInfo bondingInfo = mSmpManager.getBondingInfo(peerAddress);

                    lastResult = new Result(true, bondingInfo, profile,
                            attemptNumber, 0, null);

                    notifySuccess(profile, attemptNumber);
                    return lastResult;
                }

                // Check if we should retry
                if (isRetryableError(attemptResult.errorCode)) {
                    CourierLogger.d(TAG, "Retryable error (0x" + Integer.toHexString(attemptResult.errorCode) +
                            ": " + SmpConstants.getErrorString(attemptResult.errorCode) +
                            "), will try next profile");
                    notifyRetry(attemptNumber, profile, attemptResult.errorCode, attemptResult.errorMessage);

                    // Clear ECDH cache to force fresh key generation.
                    // This also signals any pending latches so orphaned async
                    // threads from the failed attempt can unblock and exit.
                    mSmpManager.clearEcdhCache();

                    // Wait before next attempt. This delay serves two purposes:
                    // 1. Give the peer device time to reset its SMP state machine
                    // 2. Allow orphaned executor threads from the previous attempt
                    //    to fully terminate after their latches were signaled above
                    long delayMs = Math.max(mRetryDelayMs, 500); // minimum 500ms to drain async ops
                    if (attemptNumber < mRetrySequence.length) {
                        try {
                            Thread.sleep(delayMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }

                    lastResult = new Result(false, null, profile,
                            attemptNumber, attemptResult.errorCode, attemptResult.errorMessage);
                    continue;
                }

                // Non-retryable error
                CourierLogger.w(TAG, "Non-retryable error: " + attemptResult.errorMessage);
                lastResult = new Result(false, null, profile,
                        attemptNumber, attemptResult.errorCode, attemptResult.errorMessage);

                notifyFailed(attemptResult.errorCode, attemptResult.errorMessage, attemptNumber);
                return lastResult;
            }

            // All attempts exhausted
            CourierLogger.w(TAG, "All " + mRetrySequence.length + " pairing profiles exhausted");
            notifyExhausted(attemptNumber);

            if (lastResult == null) {
                lastResult = new Result(false, null, null, attemptNumber,
                        ERR_AUTHENTICATION_REQUIREMENTS, "All profiles exhausted");
            }

            return lastResult;

        } finally {
            // Always restore original settings
            restoreOriginalSettings();
        }
    }

    /**
     * Attempts pairing asynchronously with auto-retry.
     *
     * @param connectionHandle ACL connection handle
     * @param peerAddress peer Bluetooth address
     * @param peerAddressType peer address type
     * @param totalTimeoutMs total timeout
     * @param callback callback for result
     */
    public void pairWithAutoRetryAsync(int connectionHandle, byte[] peerAddress,
                                       int peerAddressType, long totalTimeoutMs,
                                       IAutoRetryCallback callback) {
        new Thread(() -> {
            Result result = pairWithAutoRetry(connectionHandle, peerAddress,
                    peerAddressType, totalTimeoutMs);
            if (callback != null) {
                callback.onResult(result);
            }
        }, "SmpAutoRetry-" + Integer.toHexString(connectionHandle)).start();
    }

    private void saveOriginalSettings() {
        if (!mSettingsSaved) {
            mOriginalAuthReq = mSmpManager.getDefaultAuthReq();
            mOriginalIoCap = mSmpManager.getDefaultIoCapability();
            mOriginalScEnabled = mSmpManager.isSecureConnectionsPreferred();
            mSettingsSaved = true;
            CourierLogger.d(TAG, "Saved original settings: AuthReq=0x" + Integer.toHexString(mOriginalAuthReq) +
                    ", IoCap=" + SmpConstants.getIoCapabilityString(mOriginalIoCap) +
                    ", SC=" + mOriginalScEnabled);
        }
    }

    private void restoreOriginalSettings() {
        if (mSettingsSaved) {
            mSmpManager.setDefaultAuthReq(mOriginalAuthReq);
            mSmpManager.setDefaultIoCapability(mOriginalIoCap);
            mSmpManager.setSecureConnectionsEnabled(mOriginalScEnabled);
            mSettingsSaved = false;
            CourierLogger.d(TAG, "Restored original settings: AuthReq=0x" + Integer.toHexString(mOriginalAuthReq) +
                    ", IoCap=" + SmpConstants.getIoCapabilityString(mOriginalIoCap) +
                    ", SC=" + mOriginalScEnabled);
        }
    }

    private void configureForProfile(SmpAuthReqProfile profile) {
        mSmpManager.setDefaultAuthReq(profile.getAuthReq());
        mSmpManager.setDefaultIoCapability(profile.getIoCap());
        mSmpManager.setSecureConnectionsEnabled(profile.usesSecureConnections());

        CourierLogger.d(TAG, "Configured: AuthReq=" + profile.getAuthReqString() +
                ", IoCap=" + SmpConstants.getIoCapabilityString(profile.getIoCap()) +
                ", SC=" + profile.usesSecureConnections());
    }

    /**
     * Determines if an SMP error code should trigger a retry.
     *
     * <p>Some devices don't properly report AUTH_REQUIREMENTS errors and instead
     * return UNSPECIFIED_REASON or CONFIRM_VALUE_FAILED when the authentication
     * parameters don't match. We retry on these errors as well.
     *
     * @param errorCode SMP error code
     * @return true if we should retry with a different profile
     */
    private boolean isRetryableError(int errorCode) {
        switch (errorCode) {
            case ERR_AUTHENTICATION_REQUIREMENTS:
                // Standard auth requirements mismatch
                return true;
            case ERR_UNSPECIFIED_REASON:
                // Many devices return this instead of proper AUTH_REQ error
                return true;
            case ERR_CONFIRM_VALUE_FAILED:
                // Can occur with parameter mismatches on some devices
                return true;
            case ERR_PAIRING_NOT_SUPPORTED:
                // Device doesn't support this pairing mode, try another
                return true;
            default:
                return false;
        }
    }

    /**
     * Tries pairing with callback-based approach to capture error details
     * BEFORE session is removed from SmpManager.
     */
    private PairingAttemptResult tryPairing(int connectionHandle, byte[] peerAddress,
                                            int peerAddressType, long timeoutMs,
                                            SmpAuthReqProfile profile) {
        final AtomicBoolean success = new AtomicBoolean(false);
        final AtomicInteger errorCode = new AtomicInteger(ERR_UNSPECIFIED_REASON);
        final AtomicReference<String> errorMessage = new AtomicReference<>("Unknown error");
        final AtomicReference<BondingInfo> bondingInfoRef = new AtomicReference<>(null);
        final CountDownLatch completionLatch = new CountDownLatch(1);

        // Create callback to capture error details BEFORE session removal
        ISmpCallback callback = new ISmpCallback() {
            @Override
            public void onPairingFailed(int handle, int code, String message) {
                // This is called BEFORE session is removed, capture error details
                errorCode.set(code);
                errorMessage.set(message != null ? message : SmpConstants.getErrorString(code));
                CourierLogger.d(TAG, "Callback received error: " + errorCode.get() + " - " + errorMessage.get());
            }

            @Override
            public void onPairingComplete(int handle, boolean pairingSuccess, BondingInfo info) {
                success.set(pairingSuccess);
                if (pairingSuccess && info != null) {
                    bondingInfoRef.set(info);
                }
                completionLatch.countDown();
            }

            @Override
            public void onPasskeyRequired(int handle, boolean display, int passkey) {
                // For Just Works profiles, we shouldn't receive this
                // For profiles that might trigger passkey, auto-accept with 0
                // (This is for identity resolution where user interaction isn't expected)
                if (profile.getIoCap() == IO_CAP_NO_INPUT_NO_OUTPUT) {
                    // Just Works profile shouldn't need passkey, but some devices misbehave
                    CourierLogger.w(TAG, "Unexpected passkey request in Just Works mode, entering 0");
                    mSmpManager.enterPasskey(handle, 0);
                }
            }

            @Override
            public void onNumericComparisonRequired(int handle, int numericValue) {
                // For Just Works profiles, auto-confirm
                if (profile.getIoCap() == IO_CAP_NO_INPUT_NO_OUTPUT) {
                    CourierLogger.d(TAG, "Auto-confirming numeric comparison for Just Works mode");
                    mSmpManager.confirmNumericComparison(handle, true);
                }
            }
        };

        // Initiate pairing with callback
        mSmpManager.initiatePairing(connectionHandle, peerAddress, peerAddressType, callback);

        try {
            // Wait for completion
            boolean completed = completionLatch.await(timeoutMs, TimeUnit.MILLISECONDS);

            if (!completed) {
                // Timeout occurred
                CourierLogger.w(TAG, "Pairing attempt timed out after " + timeoutMs + "ms");
                return new PairingAttemptResult(false, ERR_UNSPECIFIED_REASON, "Timeout");
            }

            if (success.get()) {
                return new PairingAttemptResult(true, 0, null);
            } else {
                return new PairingAttemptResult(false, errorCode.get(), errorMessage.get());
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new PairingAttemptResult(false, ERR_UNSPECIFIED_REASON, "Interrupted");
        }
    }

    // Notification methods
    private void notifyStarted(String address, int totalProfiles) {
        if (mListener != null) {
            mListener.onAutoRetryStarted(address, totalProfiles);
        }
    }

    private void notifyAttempt(int attemptNumber, SmpAuthReqProfile profile) {
        if (mListener != null) {
            mListener.onAttemptStarted(attemptNumber, profile);
        }
    }

    private void notifyRetry(int attemptNumber, SmpAuthReqProfile failedProfile,
                             int errorCode, String errorMessage) {
        if (mListener != null) {
            mListener.onRetrying(attemptNumber, failedProfile, errorCode, errorMessage);
        }
    }

    private void notifySuccess(SmpAuthReqProfile profile, int attempts) {
        if (mListener != null) {
            mListener.onAutoRetrySuccess(profile, attempts);
        }
    }

    private void notifyFailed(int errorCode, String errorMessage, int attempts) {
        if (mListener != null) {
            mListener.onAutoRetryFailed(errorCode, errorMessage, attempts);
        }
    }

    private void notifyExhausted(int totalAttempts) {
        if (mListener != null) {
            mListener.onAllProfilesExhausted(totalAttempts);
        }
    }

    // ==================== Inner Classes ====================

    private static class PairingAttemptResult {
        final boolean success;
        final int errorCode;
        final String errorMessage;

        PairingAttemptResult(boolean success, int errorCode, String errorMessage) {
            this.success = success;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }
    }

    /**
     * Result of an auto-retry pairing operation.
     */
    public static class Result {
        private final boolean success;
        private final BondingInfo bondingInfo;
        private final SmpAuthReqProfile successfulProfile;
        private final int totalAttempts;
        private final int lastErrorCode;
        private final String lastErrorMessage;

        Result(boolean success, BondingInfo bondingInfo, SmpAuthReqProfile profile,
               int attempts, int errorCode, String errorMessage) {
            this.success = success;
            this.bondingInfo = bondingInfo;
            this.successfulProfile = profile;
            this.totalAttempts = attempts;
            this.lastErrorCode = errorCode;
            this.lastErrorMessage = errorMessage;
        }

        /** Returns whether pairing succeeded. */
        public boolean isSuccess() {
            return success;
        }

        /** Returns the bonding info (null if failed). */
        public BondingInfo getBondingInfo() {
            return bondingInfo;
        }

        /** Returns the profile that succeeded (null if failed). */
        public SmpAuthReqProfile getSuccessfulProfile() {
            return successfulProfile;
        }

        /** Returns the total number of attempts made. */
        public int getTotalAttempts() {
            return totalAttempts;
        }

        /** Returns the last error code (0 if succeeded). */
        public int getLastErrorCode() {
            return lastErrorCode;
        }

        /** Returns the last error message (null if succeeded). */
        public String getLastErrorMessage() {
            return lastErrorMessage;
        }

        @Override
        public String toString() {
            if (success) {
                return String.format("Result{success=true, profile=%s, attempts=%d}",
                        successfulProfile.getDisplayName(), totalAttempts);
            } else {
                return String.format("Result{success=false, error=%s, attempts=%d}",
                        SmpConstants.getErrorString(lastErrorCode), totalAttempts);
            }
        }
    }

    /**
     * Listener for auto-retry pairing events.
     */
    public interface ISmpAutoRetryListener {

        /** Called when auto-retry pairing starts. */
        void onAutoRetryStarted(String address, int totalProfiles);

        /** Called when a new attempt starts. */
        void onAttemptStarted(int attemptNumber, SmpAuthReqProfile profile);

        /** Called when retrying after a failure. */
        void onRetrying(int attemptNumber, SmpAuthReqProfile failedProfile,
                        int errorCode, String errorMessage);

        /** Called when pairing succeeds. */
        void onAutoRetrySuccess(SmpAuthReqProfile successfulProfile, int totalAttempts);

        /** Called when pairing fails with a non-retryable error. */
        void onAutoRetryFailed(int errorCode, String errorMessage, int totalAttempts);

        /** Called when all profiles have been exhausted. */
        void onAllProfilesExhausted(int totalAttempts);
    }

    /**
     * Callback for asynchronous auto-retry pairing.
     */
    public interface IAutoRetryCallback {
        void onResult(Result result);
    }

    /**
     * Simple listener adapter with default no-op implementations.
     */
    public static class SimpleAutoRetryListener implements ISmpAutoRetryListener {
        @Override public void onAutoRetryStarted(String address, int totalProfiles) {}
        @Override public void onAttemptStarted(int attemptNumber, SmpAuthReqProfile profile) {}
        @Override public void onRetrying(int attemptNumber, SmpAuthReqProfile failedProfile,
                                         int errorCode, String errorMessage) {}
        @Override public void onAutoRetrySuccess(SmpAuthReqProfile successfulProfile, int totalAttempts) {}
        @Override public void onAutoRetryFailed(int errorCode, String errorMessage, int totalAttempts) {}
        @Override public void onAllProfilesExhausted(int totalAttempts) {}
    }
}