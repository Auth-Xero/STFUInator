package com.courierstack.hid;

/**
 * Listener interface for HID events.
 *
 * <p>Implementations receive notifications about HID device connections,
 * report data, protocol changes, and errors.
 *
 * <p>Thread safety: Callbacks may be invoked from any thread.
 * Implementations should be thread-safe.
 *
 * @see HidManager
 */
public interface IHidListener {

    // ==================== Connection Events ====================

    /**
     * Called when an HID device is fully connected.
     *
     * <p>Both control and interrupt channels are established.
     *
     * @param device the connected device
     */
    void onDeviceConnected(HidDevice device);

    /**
     * Called when an HID device is disconnected.
     *
     * @param device the disconnected device
     * @param reason disconnection reason (HCI error code or HID-specific)
     */
    void onDeviceDisconnected(HidDevice device, int reason);

    /**
     * Called when connection to an HID device fails.
     *
     * @param address device address (6 bytes)
     * @param reason  failure reason description
     */
    void onConnectionFailed(byte[] address, String reason);

    // ==================== Report Events ====================

    /**
     * Called when an input report is received from a device.
     *
     * <p>Input reports are sent on the interrupt channel and contain
     * device state (key presses, mouse movements, etc.).
     *
     * @param device the device
     * @param report the input report
     */
    void onInputReport(HidDevice device, HidReport report);

    /**
     * Called when a feature report is received.
     *
     * <p>Feature reports are received in response to GET_REPORT requests.
     *
     * @param device the device
     * @param report the feature report
     */
    void onFeatureReport(HidDevice device, HidReport report);

    /**
     * Called when GET_REPORT request completes.
     *
     * @param device   the device
     * @param report   the report data (null if failed)
     * @param success  true if successful
     */
    default void onGetReportComplete(HidDevice device, HidReport report, boolean success) {
        if (success && report != null) {
            if (report.isFeature()) {
                onFeatureReport(device, report);
            }
        }
    }

    /**
     * Called when SET_REPORT request completes.
     *
     * @param device  the device
     * @param success true if successful
     * @param result  handshake result code
     */
    default void onSetReportComplete(HidDevice device, boolean success, int result) {}

    // ==================== Protocol Events ====================

    /**
     * Called when GET_PROTOCOL request completes.
     *
     * @param device   the device
     * @param protocol protocol mode (BOOT or REPORT)
     * @param success  true if successful
     */
    default void onGetProtocolComplete(HidDevice device, int protocol, boolean success) {}

    /**
     * Called when SET_PROTOCOL request completes.
     *
     * @param device  the device
     * @param success true if successful
     */
    default void onSetProtocolComplete(HidDevice device, boolean success) {}

    /**
     * Called when the device's protocol mode changes.
     *
     * @param device   the device
     * @param protocol new protocol mode (BOOT or REPORT)
     */
    default void onProtocolChanged(HidDevice device, int protocol) {}

    // ==================== Idle Events ====================

    /**
     * Called when GET_IDLE request completes.
     *
     * @param device   the device
     * @param idleRate idle rate (4ms units, 0 = indefinite)
     * @param success  true if successful
     */
    default void onGetIdleComplete(HidDevice device, int idleRate, boolean success) {}

    /**
     * Called when SET_IDLE request completes.
     *
     * @param device  the device
     * @param success true if successful
     */
    default void onSetIdleComplete(HidDevice device, boolean success) {}

    // ==================== Control Events ====================

    /**
     * Called when a HID_CONTROL operation completes.
     *
     * @param device    the device
     * @param operation control operation (SUSPEND, EXIT_SUSPEND, etc.)
     * @param success   true if successful
     */
    default void onControlComplete(HidDevice device, int operation, boolean success) {}

    /**
     * Called when the device requests virtual cable unplug.
     *
     * @param device the device
     */
    default void onVirtualCableUnplug(HidDevice device) {}

    /**
     * Called when the device enters suspended state.
     *
     * @param device the device
     */
    default void onDeviceSuspended(HidDevice device) {}

    /**
     * Called when the device exits suspended state.
     *
     * @param device the device
     */
    default void onDeviceResumed(HidDevice device) {}

    // ==================== Keyboard Events ====================

    /**
     * Called when a boot keyboard input report is received.
     *
     * <p>Default implementation parses the report and delegates.
     *
     * @param device the device
     * @param report the keyboard report
     */
    default void onBootKeyboardInput(HidDevice device, HidReport report) {
        HidReport.BootKeyboardData data = report.parseBootKeyboard();
        if (data != null) {
            onKeyboardData(device, data);
        }
    }

    /**
     * Called with parsed boot keyboard data.
     *
     * @param device the device
     * @param data   parsed keyboard data
     */
    default void onKeyboardData(HidDevice device, HidReport.BootKeyboardData data) {}

    /**
     * Called when keyboard LEDs should be updated.
     *
     * <p>This is called when the host receives information about
     * the desired LED state (e.g., from the OS keyboard subsystem).
     *
     * @param device the device
     * @param leds   LED flags (NUM_LOCK, CAPS_LOCK, etc.)
     */
    default void onKeyboardLedsChanged(HidDevice device, int leds) {}

    // ==================== Mouse Events ====================

    /**
     * Called when a boot mouse input report is received.
     *
     * <p>Default implementation parses the report and delegates.
     *
     * @param device the device
     * @param report the mouse report
     */
    default void onBootMouseInput(HidDevice device, HidReport report) {
        HidReport.BootMouseData data = report.parseBootMouse();
        if (data != null) {
            onMouseData(device, data);
        }
    }

    /**
     * Called with parsed boot mouse data.
     *
     * @param device the device
     * @param data   parsed mouse data
     */
    default void onMouseData(HidDevice device, HidReport.BootMouseData data) {}

    // ==================== Error and Info Events ====================

    /**
     * Called when an error occurs.
     *
     * @param device  the device (may be null for general errors)
     * @param message error description
     */
    void onError(HidDevice device, String message);

    /**
     * Called for informational messages.
     *
     * @param message info message
     */
    default void onMessage(String message) {}

    /**
     * Called when a handshake is received.
     *
     * @param device the device
     * @param result handshake result code
     */
    default void onHandshake(HidDevice device, int result) {}

    // ==================== Descriptor Events ====================

    /**
     * Called when the HID report descriptor is received.
     *
     * @param device     the device
     * @param descriptor parsed descriptor (null if parsing failed)
     */
    default void onDescriptorReceived(HidDevice device, HidReportDescriptor descriptor) {}

    // ==================== Adapter Class ====================

    /**
     * Adapter class providing empty implementations of all listener methods.
     *
     * <p>Extend this class and override only the methods you need.
     *
     * <pre>{@code
     * HidManager hidManager = new HidManager(l2cap, new IHidListener.Adapter() {
     *     @Override
     *     public void onDeviceConnected(HidDevice device) {
     *         Log.i("HID", "Connected: " + device.getName());
     *     }
     *
     *     @Override
     *     public void onInputReport(HidDevice device, HidReport report) {
     *         // Handle input
     *     }
     * });
     * }</pre>
     */
    class Adapter implements IHidListener {
        @Override
        public void onDeviceConnected(HidDevice device) {}

        @Override
        public void onDeviceDisconnected(HidDevice device, int reason) {}

        @Override
        public void onConnectionFailed(byte[] address, String reason) {}

        @Override
        public void onInputReport(HidDevice device, HidReport report) {}

        @Override
        public void onFeatureReport(HidDevice device, HidReport report) {}

        @Override
        public void onError(HidDevice device, String message) {}
    }

    /**
     * Simplified listener for common use cases.
     *
     * <p>Provides high-level callbacks for keyboard and mouse events.
     */
    abstract class SimpleListener extends Adapter {
        @Override
        public void onInputReport(HidDevice device, HidReport report) {
            // Auto-detect device type and dispatch
            if (device.isKeyboard() && report.getLength() >= HidConstants.BOOT_KEYBOARD_INPUT_SIZE) {
                onBootKeyboardInput(device, report);
            } else if (device.isMouse() && report.getLength() >= HidConstants.BOOT_MOUSE_INPUT_SIZE) {
                onBootMouseInput(device, report);
            } else {
                onGenericInput(device, report);
            }
        }

        /**
         * Called for non-keyboard/mouse input reports.
         *
         * @param device the device
         * @param report the report
         */
        protected void onGenericInput(HidDevice device, HidReport report) {}
    }

    /**
     * Detailed keyboard listener with key event tracking.
     */
    abstract class KeyboardListener extends SimpleListener {
        private int lastModifiers = 0;
        private final int[] lastKeys = new int[6];

        @Override
        public void onKeyboardData(HidDevice device, HidReport.BootKeyboardData data) {
            // Detect modifier changes
            int modifierChanges = lastModifiers ^ data.modifiers;
            if (modifierChanges != 0) {
                for (int i = 0; i < 8; i++) {
                    int mask = 1 << i;
                    if ((modifierChanges & mask) != 0) {
                        boolean pressed = (data.modifiers & mask) != 0;
                        onModifierKey(device, mask, pressed);
                    }
                }
                lastModifiers = data.modifiers;
            }

            // Detect key presses and releases
            for (int key : data.keyCodes) {
                if (key != 0 && !wasPressed(key)) {
                    onKeyPressed(device, key, data.modifiers);
                }
            }
            for (int key : lastKeys) {
                if (key != 0 && !isPressed(data.keyCodes, key)) {
                    onKeyReleased(device, key, data.modifiers);
                }
            }

            // Update last keys
            System.arraycopy(data.keyCodes, 0, lastKeys, 0, 6);
        }

        private boolean wasPressed(int key) {
            for (int k : lastKeys) {
                if (k == key) return true;
            }
            return false;
        }

        private boolean isPressed(int[] keys, int key) {
            for (int k : keys) {
                if (k == key) return true;
            }
            return false;
        }

        /**
         * Called when a key is pressed.
         *
         * @param device    the device
         * @param keyCode   HID key code
         * @param modifiers current modifier state
         */
        protected abstract void onKeyPressed(HidDevice device, int keyCode, int modifiers);

        /**
         * Called when a key is released.
         *
         * @param device    the device
         * @param keyCode   HID key code
         * @param modifiers current modifier state
         */
        protected abstract void onKeyReleased(HidDevice device, int keyCode, int modifiers);

        /**
         * Called when a modifier key state changes.
         *
         * @param device   the device
         * @param modifier modifier bit (MOD_LEFT_CTRL, etc.)
         * @param pressed  true if pressed, false if released
         */
        protected void onModifierKey(HidDevice device, int modifier, boolean pressed) {}
    }

    /**
     * Detailed mouse listener with button tracking.
     */
    abstract class MouseListener extends SimpleListener {
        private int lastButtons = 0;

        @Override
        public void onMouseData(HidDevice device, HidReport.BootMouseData data) {
            // Detect button changes
            int buttonChanges = lastButtons ^ data.buttons;
            if (buttonChanges != 0) {
                for (int i = 0; i < 8; i++) {
                    int mask = 1 << i;
                    if ((buttonChanges & mask) != 0) {
                        boolean pressed = (data.buttons & mask) != 0;
                        onMouseButton(device, i + 1, pressed);
                    }
                }
                lastButtons = data.buttons;
            }

            // Report movement
            if (data.hasMoved()) {
                onMouseMove(device, data.x, data.y);
            }

            // Report wheel
            if (data.hasWheelMoved()) {
                onMouseWheel(device, data.wheel);
            }
        }

        /**
         * Called when a mouse button is pressed or released.
         *
         * @param device       the device
         * @param buttonNumber button number (1=left, 2=right, 3=middle, etc.)
         * @param pressed      true if pressed, false if released
         */
        protected abstract void onMouseButton(HidDevice device, int buttonNumber, boolean pressed);

        /**
         * Called when the mouse moves.
         *
         * @param device the device
         * @param dx     X displacement (relative)
         * @param dy     Y displacement (relative)
         */
        protected abstract void onMouseMove(HidDevice device, int dx, int dy);

        /**
         * Called when the scroll wheel moves.
         *
         * @param device the device
         * @param delta  wheel displacement (positive = up/away)
         */
        protected void onMouseWheel(HidDevice device, int delta) {}
    }
}
