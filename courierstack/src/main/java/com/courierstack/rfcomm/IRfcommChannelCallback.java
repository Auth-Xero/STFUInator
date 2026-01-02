package com.courierstack.rfcomm;

import java.util.function.Consumer;

/**
 * Callback interface for RFCOMM channel connection results.
 *
 * <p>This callback is used for asynchronous channel operations.
 * Either {@link #onSuccess} or {@link #onFailure} will be called
 * exactly once.
 *
 * <p>Thread Safety: Callbacks may be invoked from any thread.
 * Implementations should be thread-safe or dispatch to a specific thread.
 */
public interface IRfcommChannelCallback {

    /**
     * Called when channel is successfully opened.
     *
     * @param channel the opened channel
     */
    void onSuccess(RfcommChannel channel);

    /**
     * Called when channel connection fails.
     *
     * @param reason failure description
     */
    void onFailure(String reason);

    /**
     * Creates a simple callback from lambda expressions.
     *
     * @param onSuccess success handler
     * @param onFailure failure handler
     * @return callback instance
     */
    static IRfcommChannelCallback create(
            Consumer<RfcommChannel> onSuccess,
            Consumer<String> onFailure) {
        return new IRfcommChannelCallback() {
            @Override
            public void onSuccess(RfcommChannel channel) {
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