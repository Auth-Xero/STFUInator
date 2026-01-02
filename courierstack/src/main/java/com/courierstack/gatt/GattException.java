package com.courierstack.gatt;

/**
 * Exception thrown for GATT operation failures.
 *
 * <p>This exception encapsulates ATT error codes and provides
 * human-readable error messages.
 */
public class GattException extends Exception {

    private static final long serialVersionUID = 1L;

    /** The ATT error code. */
    private final int errorCode;

    /** The attribute handle involved (if applicable). */
    private final int handle;

    /**
     * Creates a GATT exception with an error code.
     *
     * @param errorCode ATT error code
     */
    public GattException(int errorCode) {
        super(GattConstants.getAttErrorString(errorCode));
        this.errorCode = errorCode;
        this.handle = 0;
    }

    /**
     * Creates a GATT exception with an error code and handle.
     *
     * @param errorCode ATT error code
     * @param handle    attribute handle
     */
    public GattException(int errorCode, int handle) {
        super(String.format("%s (handle=0x%04X)",
                GattConstants.getAttErrorString(errorCode), handle));
        this.errorCode = errorCode;
        this.handle = handle;
    }

    /**
     * Creates a GATT exception with a custom message.
     *
     * @param errorCode ATT error code
     * @param message   custom error message
     */
    public GattException(int errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.handle = 0;
    }

    /**
     * Creates a GATT exception with a message and cause.
     *
     * @param message error message
     * @param cause   underlying cause
     */
    public GattException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = GattConstants.ATT_ERR_UNLIKELY_ERROR;
        this.handle = 0;
    }

    /**
     * Creates a GATT exception with a message only.
     *
     * @param message error message
     */
    public GattException(String message) {
        super(message);
        this.errorCode = GattConstants.ATT_ERR_UNLIKELY_ERROR;
        this.handle = 0;
    }

    /**
     * Returns the ATT error code.
     *
     * @return error code
     */
    public int getErrorCode() {
        return errorCode;
    }

    /**
     * Returns the attribute handle (if applicable).
     *
     * @return handle, or 0 if not applicable
     */
    public int getHandle() {
        return handle;
    }

    /**
     * Returns the standard error description for this error code.
     *
     * @return error description
     */
    public String getErrorDescription() {
        return GattConstants.getAttErrorString(errorCode);
    }

    /**
     * Checks if this is an "Attribute Not Found" error.
     *
     * @return true if attribute not found
     */
    public boolean isAttributeNotFound() {
        return errorCode == GattConstants.ATT_ERR_ATTRIBUTE_NOT_FOUND;
    }

    /**
     * Checks if this is an authentication error.
     *
     * @return true if authentication/authorization/encryption error
     */
    public boolean isSecurityError() {
        return errorCode == GattConstants.ATT_ERR_INSUFFICIENT_AUTHENTICATION ||
                errorCode == GattConstants.ATT_ERR_INSUFFICIENT_AUTHORIZATION ||
                errorCode == GattConstants.ATT_ERR_INSUFFICIENT_ENCRYPTION ||
                errorCode == GattConstants.ATT_ERR_INSUFFICIENT_ENCRYPTION_KEY_SIZE;
    }

    /**
     * Checks if this is a read permission error.
     *
     * @return true if read not permitted
     */
    public boolean isReadNotPermitted() {
        return errorCode == GattConstants.ATT_ERR_READ_NOT_PERMITTED;
    }

    /**
     * Checks if this is a write permission error.
     *
     * @return true if write not permitted
     */
    public boolean isWriteNotPermitted() {
        return errorCode == GattConstants.ATT_ERR_WRITE_NOT_PERMITTED;
    }

    /**
     * Checks if this is an application-defined error.
     *
     * @return true if application error
     */
    public boolean isApplicationError() {
        return errorCode >= GattConstants.ATT_ERR_APP_ERROR_START &&
                errorCode <= GattConstants.ATT_ERR_APP_ERROR_END;
    }

    // ==================== Factory Methods ====================

    /**
     * Creates an exception for "Invalid Handle" error.
     *
     * @param handle the invalid handle
     * @return exception
     */
    public static GattException invalidHandle(int handle) {
        return new GattException(GattConstants.ATT_ERR_INVALID_HANDLE, handle);
    }

    /**
     * Creates an exception for "Attribute Not Found" error.
     *
     * @param handle the handle that was not found
     * @return exception
     */
    public static GattException attributeNotFound(int handle) {
        return new GattException(GattConstants.ATT_ERR_ATTRIBUTE_NOT_FOUND, handle);
    }

    /**
     * Creates an exception for "Read Not Permitted" error.
     *
     * @return exception
     */
    public static GattException readNotPermitted() {
        return new GattException(GattConstants.ATT_ERR_READ_NOT_PERMITTED);
    }

    /**
     * Creates an exception for "Write Not Permitted" error.
     *
     * @return exception
     */
    public static GattException writeNotPermitted() {
        return new GattException(GattConstants.ATT_ERR_WRITE_NOT_PERMITTED);
    }

    /**
     * Creates an exception for "Request Not Supported" error.
     *
     * @return exception
     */
    public static GattException requestNotSupported() {
        return new GattException(GattConstants.ATT_ERR_REQUEST_NOT_SUPPORTED);
    }

    /**
     * Creates an exception for "Insufficient Authentication" error.
     *
     * @return exception
     */
    public static GattException insufficientAuthentication() {
        return new GattException(GattConstants.ATT_ERR_INSUFFICIENT_AUTHENTICATION);
    }

    /**
     * Creates an exception for "Insufficient Encryption" error.
     *
     * @return exception
     */
    public static GattException insufficientEncryption() {
        return new GattException(GattConstants.ATT_ERR_INSUFFICIENT_ENCRYPTION);
    }

    /**
     * Creates an exception for connection-related errors.
     *
     * @param message error description
     * @return exception
     */
    public static GattException connectionError(String message) {
        return new GattException(GattConstants.ATT_ERR_UNLIKELY_ERROR, message);
    }

    /**
     * Creates an exception for timeout errors.
     *
     * @return exception
     */
    public static GattException timeout() {
        return new GattException(GattConstants.ATT_ERR_UNLIKELY_ERROR, "Operation timed out");
    }

    @Override
    public String toString() {
        if (handle != 0) {
            return String.format("GattException{errorCode=0x%02X, handle=0x%04X, message='%s'}",
                    errorCode, handle, getMessage());
        }
        return String.format("GattException{errorCode=0x%02X, message='%s'}",
                errorCode, getMessage());
    }
}