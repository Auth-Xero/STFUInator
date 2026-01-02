package com.courierstack.sdp;

import java.util.List;

/**
 * Callback interface for SDP service discovery queries.
 *
 * <p>Implementations receive notifications about:
 * <ul>
 *   <li>Individual service records as they are discovered</li>
 *   <li>Query completion with all results</li>
 *   <li>Query errors</li>
 * </ul>
 *
 * <p>Thread safety: Callbacks may be invoked from any thread.
 * Implementations should be thread-safe or dispatch to an appropriate
 * thread/handler.
 *
 * <p>Usage example:
 * <pre>{@code
 * sdpManager.queryService(address, uuid, new ISdpQueryCallback() {
 *     @Override
 *     public void onServiceFound(ServiceRecord record) {
 *         Log.d(TAG, "Found: " + record.getServiceName() +
 *               " channel=" + record.getRfcommChannel());
 *     }
 *
 *     @Override
 *     public void onQueryComplete(List<ServiceRecord> records) {
 *         Log.d(TAG, "Query complete: " + records.size() + " services");
 *     }
 *
 *     @Override
 *     public void onError(String message) {
 *         Log.e(TAG, "Query failed: " + message);
 *     }
 * });
 * }</pre>
 */
public interface ISdpQueryCallback {

    /**
     * Called when a matching service record is found.
     *
     * <p>This is called for each service record as it is discovered.
     * The same record will also be included in the list passed to
     * {@link #onQueryComplete}.
     *
     * @param record the discovered service record (never null)
     */
    void onServiceFound(ServiceRecord record);

    /**
     * Called when the query completes successfully.
     *
     * <p>This is called exactly once at the end of a successful query,
     * with all discovered service records. The list may be empty if no
     * matching services were found.
     *
     * <p>After this callback, no more callbacks will be invoked for
     * this query.
     *
     * @param records all discovered service records (never null, may be empty)
     */
    void onQueryComplete(List<ServiceRecord> records);

    /**
     * Called when an error occurs during the query.
     *
     * <p>This is called exactly once if the query fails for any reason.
     * Possible reasons include:
     * <ul>
     *   <li>Connection failure</li>
     *   <li>SDP protocol error</li>
     *   <li>Timeout</li>
     *   <li>Channel closed unexpectedly</li>
     * </ul>
     *
     * <p>After this callback, no more callbacks will be invoked for
     * this query (including {@link #onQueryComplete}).
     *
     * @param message human-readable error description (never null)
     */
    void onError(String message);

    /**
     * Creates a simple callback from lambda expressions.
     *
     * <p>Example:
     * <pre>{@code
     * ISdpQueryCallback callback = ISdpQueryCallback.create(
     *     record -> handleFoundService(record),
     *     records -> handleQueryComplete(records),
     *     error -> handleError(error)
     * );
     * }</pre>
     *
     * @param onServiceFound handler for found services (may be null)
     * @param onComplete     handler for query completion (may be null)
     * @param onError        handler for errors (may be null)
     * @return new callback instance
     */
    static ISdpQueryCallback create(
            java.util.function.Consumer<ServiceRecord> onServiceFound,
            java.util.function.Consumer<List<ServiceRecord>> onComplete,
            java.util.function.Consumer<String> onError) {

        return new ISdpQueryCallback() {
            @Override
            public void onServiceFound(ServiceRecord record) {
                if (onServiceFound != null) {
                    onServiceFound.accept(record);
                }
            }

            @Override
            public void onQueryComplete(List<ServiceRecord> records) {
                if (onComplete != null) {
                    onComplete.accept(records);
                }
            }

            @Override
            public void onError(String message) {
                if (onError != null) {
                    onError.accept(message);
                }
            }
        };
    }

    /**
     * Creates a minimal callback that only handles completion.
     *
     * <p>The onServiceFound callback will be a no-op, and errors will
     * result in an empty list being passed to onComplete.
     *
     * @param onComplete handler for query completion
     * @return new callback instance
     */
    static ISdpQueryCallback onComplete(java.util.function.Consumer<List<ServiceRecord>> onComplete) {
        return new ISdpQueryCallback() {
            @Override
            public void onServiceFound(ServiceRecord record) {
                // No-op
            }

            @Override
            public void onQueryComplete(List<ServiceRecord> records) {
                if (onComplete != null) {
                    onComplete.accept(records);
                }
            }

            @Override
            public void onError(String message) {
                if (onComplete != null) {
                    onComplete.accept(java.util.Collections.emptyList());
                }
            }
        };
    }
}