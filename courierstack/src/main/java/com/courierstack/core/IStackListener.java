package com.courierstack.core;

/**
 * Listener interface for stack-level events.
 *
 * <p>Implementations receive notifications about stack initialization,
 * log events, and errors. Default implementations are provided for
 * optional callbacks.
 *
 * <p>Thread safety: Callbacks may be invoked from any thread.
 * Implementations should be thread-safe.
 */
public interface IStackListener {

    /**
     * Called when stack initialization completes.
     *
     * @param success true if initialization succeeded
     */
    void onInitialized(boolean success);

    /**
     * Called when a log entry is added.
     *
     * <p>Default implementation does nothing.
     *
     * @param entry the log entry
     */
    default void onLog(LogEntry entry) {}

    /**
     * Called when an error occurs.
     *
     * @param message error description
     */
    void onError(String message);

    /**
     * Called when the stack state changes.
     *
     * <p>Default implementation does nothing.
     *
     * @param state the new state
     */
    default void onStateChanged(CourierStackManager.State state) {}

    /**
     * Called when the stack is shutting down.
     *
     * <p>Default implementation does nothing.
     */
    default void onShutdown() {}
}