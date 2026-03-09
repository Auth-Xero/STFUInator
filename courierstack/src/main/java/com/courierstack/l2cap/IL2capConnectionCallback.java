package com.courierstack.l2cap;

/**
 * Callback for asynchronous L2CAP connection operations.
 *
 * <p>Either {@link #onSuccess} or {@link #onFailure} will be called
 * exactly once per operation.
 *
 * <p>Thread Safety: Callbacks may be invoked from any thread.
 */
public interface IL2capConnectionCallback {

    /**
     * Called when the connection or channel is successfully established.
     *
     * @param channel the resulting L2CAP channel
     */
    void onSuccess(L2capChannel channel);

    /**
     * Called when the connection or channel operation fails.
     *
     * @param reason failure description
     */
    void onFailure(String reason);

    /**
     * Creates a callback from lambda expressions.
     *
     * @param onSuccess success handler
     * @param onFailure failure handler
     * @return callback instance
     */
    static IL2capConnectionCallback create(
            java.util.function.Consumer<L2capChannel> onSuccess,
            java.util.function.Consumer<String> onFailure) {
        return new IL2capConnectionCallback() {
            @Override
            public void onSuccess(L2capChannel channel) {
                if (onSuccess != null) onSuccess.accept(channel);
            }

            @Override
            public void onFailure(String reason) {
                if (onFailure != null) onFailure.accept(reason);
            }
        };
    }
}