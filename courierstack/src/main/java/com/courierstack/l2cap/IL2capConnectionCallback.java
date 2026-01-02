package com.courierstack.l2cap;

/**
 * Callback interface for L2CAP connection results.
 *
 * <p>This callback is used for asynchronous connection operations.
 * Either {@link #onSuccess} or {@link #onFailure} will be called
 * exactly once.
 *
 * <p>Thread safety: Callbacks may be invoked from any thread.
 */
public interface IL2capConnectionCallback {

    /**
     * Called when connection succeeds.
     *
     * <p>For ACL connection requests, the channel parameter may be
     * a placeholder channel with localCid=0. Use the connection
     * property to access the ACL connection.
     *
     * @param channel the connected channel (or placeholder for ACL)
     */
    void onSuccess(L2capChannel channel);

    /**
     * Called when connection fails.
     *
     * @param reason human-readable failure description
     */
    void onFailure(String reason);

    /**
     * Creates a simple callback from lambda expressions.
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
                if (onSuccess != null) {
                    onSuccess.accept(channel);
                }
            }

            @Override
            public void onFailure(String reason) {
                if (onFailure != null) {
                    onFailure.accept(reason);
                }
            }
        };
    }
}