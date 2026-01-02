package com.courierstack.core;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bluetooth HAL Manager - handles killing and restoring the Android Bluetooth stack.
 *
 * <p>This manager provides root-level control over the Bluetooth HAL service,
 * allowing CourierStack to take exclusive control of the Bluetooth hardware.
 *
 * <p><b>Requirements:</b> Root access (su) is required for HAL control.
 *
 * <p>Usage:
 * <pre>{@code
 * HalManager hal = HalManager.getInstance();
 * hal.addListener(listener);
 *
 * // Kill Android's Bluetooth stack
 * hal.killBluetoothStackAsync(() -> {
 *     // Stack killed, now initialize CourierStack
 * });
 *
 * // Later, restore Android's stack
 * hal.restoreBluetoothStackAsync(null);
 * }</pre>
 */
public final class HalManager implements Closeable {

    private static final String TAG = "HalManager";

    // Singleton instance
    private static volatile HalManager sInstance;
    private static final Object sInstanceLock = new Object();

    /**
     * HAL state enumeration.
     */
    public enum HalState {
        /** State is unknown (initial state). */
        UNKNOWN,
        /** Android Bluetooth stack is active. */
        RUNNING,
        /** HAL killed, ready for CourierStack. */
        STOPPED,
        /** An error occurred. */
        ERROR
    }

    /**
     * Listener for HAL events.
     */
    public interface HalListener {
        /**
         * Called when HAL state changes.
         *
         * @param state new state
         */
        void onStateChanged(HalState state);

        /**
         * Called for informational messages.
         *
         * @param message the message
         */
        void onMessage(String message);

        /**
         * Called when an error occurs.
         *
         * @param error error description
         */
        void onError(String error);
    }

    // Configuration
    private static final boolean SKIP_ROOT = false; // Set true for non-root testing
    private static final int COMMAND_DELAY_MS = 500;
    private static final int HAL_RESTART_DELAY_MS = 2000;
    private static final int PROCESS_TIMEOUT_MS = 30000;
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_MS = 5000;

    // State
    private final AtomicReference<HalState> mState = new AtomicReference<>(HalState.UNKNOWN);
    private final AtomicBoolean mStackKilled = new AtomicBoolean(false);
    private final AtomicBoolean mClosed = new AtomicBoolean(false);
    private final List<HalListener> mListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService mExecutor;

    // Private constructor for singleton
    private HalManager() {
        mExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "HalManager-Executor");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Returns the singleton instance.
     *
     * @return HalManager instance
     */
    public static HalManager getInstance() {
        if (sInstance == null) {
            synchronized (sInstanceLock) {
                if (sInstance == null) {
                    sInstance = new HalManager();
                }
            }
        }
        return sInstance;
    }

    // ==================== Listener Management ====================

    /**
     * Adds a listener for HAL events.
     *
     * @param listener listener to add
     */
    public void addListener(HalListener listener) {
        if (listener != null && !mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    /**
     * Removes a listener.
     *
     * @param listener listener to remove
     */
    public void removeListener(HalListener listener) {
        mListeners.remove(listener);
    }

    // ==================== State Access ====================

    /**
     * Returns the current HAL state.
     *
     * @return current state
     */
    public HalState getState() {
        return mState.get();
    }

    /**
     * Returns whether the Android Bluetooth stack has been killed.
     *
     * @return true if stack is killed
     */
    public boolean isStackKilled() {
        return mStackKilled.get();
    }

    // ==================== HAL Control Methods ====================

    /**
     * Kills the Android Bluetooth stack asynchronously.
     *
     * <p>This stops the Bluetooth service, force-stops Bluetooth apps,
     * and kills the HAL service.
     *
     * @param callback called when operation completes (may be null)
     */
    public void killBluetoothStackAsync(Runnable callback) {
        if (mClosed.get()) {
            notifyError("HalManager is closed");
            return;
        }

        mExecutor.execute(() -> {
            killBluetoothStackSync();
            if (callback != null) {
                callback.run();
            }
        });
    }

    /**
     * Kills the Android Bluetooth stack synchronously.
     *
     * <p>Must be called from a background thread.
     *
     * @return true if successful
     */
    public boolean killBluetoothStackSync() {
        notifyMessage("Killing Android Bluetooth stack...");

        if (SKIP_ROOT) {
            notifyMessage("ROOT DISABLED - skipping HAL kill");
            mStackKilled.set(true);
            setState(HalState.STOPPED);
            return true;
        }

        try {
            List<String> commands = new ArrayList<>();

            // Set SELinux to permissive
            commands.add("setenforce 0");

            // Disable Bluetooth via settings
            commands.add("svc bluetooth disable");
            commands.add("cmd bluetooth_manager disable");

            // Stop Bluetooth packages
            commands.add("am force-stop com.android.bluetooth");
            commands.add("am force-stop com.google.android.bluetooth");

            // Kill any remaining Bluetooth processes
            commands.add("pkill -9 -f bluetooth");

            // Kill the Bluetooth HAL service
            commands.add("pkill -9 -f android.hardware.bluetooth");

            boolean success = runSuCommands(commands);

            if (success) {
                mStackKilled.set(true);
                setState(HalState.STOPPED);
                notifyMessage("Bluetooth stack killed successfully");

                // Wait for HAL to fully stop
                Thread.sleep(HAL_RESTART_DELAY_MS);
                return true;
            } else {
                setState(HalState.ERROR);
                notifyError("Failed to kill Bluetooth stack");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notifyError("Interrupted while killing stack");
            return false;
        } catch (Exception e) {
            CourierLogger.e(TAG, "Exception killing BT stack", e);
            setState(HalState.ERROR);
            notifyError("Exception: " + e.getMessage());
            return false;
        }
    }

    /**
     * Restores the Android Bluetooth stack asynchronously.
     *
     * <p>This restarts the HAL service and re-enables Bluetooth.
     *
     * @param callback called when operation completes (may be null)
     */
    public void restoreBluetoothStackAsync(Runnable callback) {
        if (mClosed.get()) {
            notifyError("HalManager is closed");
            return;
        }

        mExecutor.execute(() -> {
            restoreBluetoothStackSync();
            if (callback != null) {
                callback.run();
            }
        });
    }

    /**
     * Restores the Android Bluetooth stack synchronously.
     *
     * <p>Must be called from a background thread.
     *
     * @return true if successful
     */
    public boolean restoreBluetoothStackSync() {
        notifyMessage("Restoring Android Bluetooth stack...");

        if (SKIP_ROOT) {
            notifyMessage("ROOT DISABLED - skipping HAL restore");
            mStackKilled.set(false);
            setState(HalState.RUNNING);
            return true;
        }

        try {
            List<String> commands = new ArrayList<>();

            // Start the Bluetooth HAL service (varies by device)
            commands.add("start vendor.bluetooth-1-0");
            commands.add("start android.hardware.bluetooth@1.0-service");
            commands.add("start android.hardware.bluetooth@1.1-service");

            // Also try AIDL service names for Android 13+
            commands.add("start vendor.bluetooth.default");
            commands.add("start android.hardware.bluetooth-service.default");

            // Re-enable Bluetooth
            commands.add("svc bluetooth enable");
            commands.add("cmd bluetooth_manager enable");

            boolean success = runSuCommands(commands);

            // Wait for HAL to start
            Thread.sleep(HAL_RESTART_DELAY_MS);

            if (success) {
                mStackKilled.set(false);
                setState(HalState.RUNNING);
                notifyMessage("Bluetooth stack restored successfully");
                return true;
            } else {
                setState(HalState.ERROR);
                notifyError("Failed to restore Bluetooth stack");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notifyError("Interrupted while restoring stack");
            return false;
        } catch (Exception e) {
            CourierLogger.e(TAG, "Exception restoring BT stack", e);
            setState(HalState.ERROR);
            notifyError("Exception: " + e.getMessage());
            return false;
        }
    }

    /**
     * Force restarts the Bluetooth HAL (kill then restore).
     *
     * @return true if successful
     */
    public boolean restartBluetoothHal() {
        notifyMessage("Restarting Bluetooth HAL...");

        if (!killBluetoothStackSync()) {
            return false;
        }

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        return restoreBluetoothStackSync();
    }

    /**
     * Checks if the HAL service is running.
     *
     * @return true if HAL is running
     */
    public boolean isHalRunning() {
        if (SKIP_ROOT) {
            return !mStackKilled.get();
        }

        Process process = null;
        BufferedReader reader = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{
                    "su", "-c",
                    "pidof android.hardware.bluetooth@1.0-service || " +
                            "pidof android.hardware.bluetooth@1.1-service || " +
                            "pidof android.hardware.bluetooth-service.default"
            });
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            boolean completed = process.waitFor(5, TimeUnit.SECONDS);
            return completed && line != null && !line.isEmpty();
        } catch (Exception e) {
            CourierLogger.d(TAG, "isHalRunning check failed: " + e.getMessage());
            return false;
        } finally {
            closeQuietly(reader);
            if (process != null) {
                process.destroy();
            }
        }
    }

    // ==================== Specific Kill Commands ====================

    /**
     * Kills only the HAL service (leaves Android BT app running but disconnected).
     *
     * @return true if successful
     */
    public boolean killHalOnly() {
        notifyMessage("Killing HAL service only...");

        if (SKIP_ROOT) {
            notifyMessage("ROOT DISABLED - skipping");
            return true;
        }

        List<String> commands = new ArrayList<>();
        commands.add("pkill -9 -f android.hardware.bluetooth");
        return runSuCommands(commands);
    }

    /**
     * Kills Android Bluetooth app processes.
     *
     * @return true if successful
     */
    public boolean killBluetoothApps() {
        notifyMessage("Killing Bluetooth apps...");

        if (SKIP_ROOT) {
            notifyMessage("ROOT DISABLED - skipping");
            return true;
        }

        List<String> commands = new ArrayList<>();
        commands.add("am force-stop com.android.bluetooth");
        commands.add("am force-stop com.google.android.bluetooth");
        commands.add("pkill -9 -f bluetooth");
        return runSuCommands(commands);
    }

    /**
     * Sets SELinux to permissive mode.
     *
     * @return true if successful
     */
    public boolean setSelinuxPermissive() {
        if (SKIP_ROOT) return true;
        List<String> commands = new ArrayList<>();
        commands.add("setenforce 0");
        return runSuCommands(commands);
    }

    // ==================== Internal Methods ====================

    private boolean runSuCommands(List<String> commands) {
        if (SKIP_ROOT) {
            for (String cmd : commands) {
                notifyMessage("$ " + cmd + " [SKIPPED]");
            }
            return true;
        }

        Process process = null;
        DataOutputStream os = null;
        BufferedReader reader = null;
        BufferedReader errorReader = null;

        try {
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));

            for (String cmd : commands) {
                CourierLogger.d(TAG, "Running: " + cmd);
                notifyMessage("$ " + cmd);
                os.writeBytes(cmd + "\n");
                os.flush();
                Thread.sleep(COMMAND_DELAY_MS);
            }

            os.writeBytes("exit\n");
            os.flush();

            // Read output
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                CourierLogger.d(TAG, "stdout: " + line);
            }

            // Read errors
            StringBuilder errors = new StringBuilder();
            while ((line = errorReader.readLine()) != null) {
                errors.append(line).append("\n");
                CourierLogger.w(TAG, "stderr: " + line);
            }

            // Wait for process with timeout
            boolean completed = process.waitFor(PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                notifyError("Command execution timed out");
                return false;
            }

            int exitCode = process.exitValue();
            CourierLogger.d(TAG, "su exit code: " + exitCode);

            if (errors.length() > 0) {
                notifyMessage("Warnings: " + errors.toString().trim());
            }

            return true; // Consider it successful even with warnings

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            notifyError("Command execution interrupted");
            return false;
        } catch (Exception e) {
            CourierLogger.e(TAG, "Failed to run su commands", e);
            notifyError("Root access failed: " + e.getMessage());
            return false;
        } finally {
            closeQuietly(os);
            closeQuietly(reader);
            closeQuietly(errorReader);
            if (process != null) {
                process.destroy();
            }
        }
    }

    private void setState(HalState state) {
        HalState oldState = mState.getAndSet(state);
        if (oldState != state) {
            for (HalListener listener : mListeners) {
                try {
                    listener.onStateChanged(state);
                } catch (Exception e) {
                    CourierLogger.e(TAG, "Listener exception in onStateChanged", e);
                }
            }
        }
    }

    private void notifyMessage(String message) {
        CourierLogger.i(TAG, message);
        for (HalListener listener : mListeners) {
            try {
                listener.onMessage(message);
            } catch (Exception e) {
                CourierLogger.e(TAG, "Listener exception in onMessage", e);
            }
        }
    }

    private void notifyError(String error) {
        CourierLogger.e(TAG, error);
        for (HalListener listener : mListeners) {
            try {
                listener.onError(error);
            } catch (Exception e) {
                CourierLogger.e(TAG, "Listener exception in onError", e);
            }
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ==================== Shutdown ====================

    /**
     * Shuts down the executor.
     *
     * @deprecated Use {@link #close()} instead
     */
    @Deprecated
    public void shutdown() {
        close();
    }

    @Override
    public void close() {
        if (!mClosed.compareAndSet(false, true)) {
            return;
        }

        mExecutor.shutdown();
        try {
            if (!mExecutor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                mExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            mExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        mListeners.clear();
        CourierLogger.i(TAG, "HalManager closed");
    }
}