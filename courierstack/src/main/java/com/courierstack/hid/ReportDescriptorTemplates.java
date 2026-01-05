package com.courierstack.hid;

import java.io.ByteArrayOutputStream;

/**
 * Standard HID Report Descriptor templates for common device types.
 *
 * <p>These descriptors define the format and meaning of HID reports for
 * various device types. They are used when configuring {@link HidDeviceProfile}
 * to emulate keyboards, mice, gamepads, etc.
 *
 * <p>Report Descriptor Format:
 * <ul>
 *   <li>Boot Keyboard: 8-byte input (modifiers + reserved + 6 keys), 1-byte output (LEDs)</li>
 *   <li>Boot Mouse: 3/4-byte input (buttons + X + Y + optional wheel)</li>
 *   <li>Combo: Combines keyboard and mouse with report IDs</li>
 *   <li>Gamepad: Buttons, sticks, triggers with report ID</li>
 * </ul>
 *
 * @see HidDeviceProfile
 * @see HidConstants
 */
public final class ReportDescriptorTemplates {

    private ReportDescriptorTemplates() {}

    /**
     * Standard Boot Protocol Keyboard descriptor.
     *
     * <p>Input Report (8 bytes):
     * <pre>
     * Byte 0: Modifier keys (Ctrl, Shift, Alt, GUI)
     * Byte 1: Reserved (0x00)
     * Bytes 2-7: Key codes (up to 6 simultaneous keys)
     * </pre>
     *
     * <p>Output Report (1 byte):
     * <pre>
     * Bits 0-4: LEDs (NumLock, CapsLock, ScrollLock, Compose, Kana)
     * Bits 5-7: Padding
     * </pre>
     */
    public static final byte[] BOOT_KEYBOARD = new byte[] {
            0x05, 0x01,        // Usage Page (Generic Desktop)
            0x09, 0x06,        // Usage (Keyboard)
            (byte)0xA1, 0x01,  // Collection (Application)

            // Modifier keys (input)
            0x05, 0x07,        //   Usage Page (Keyboard/Keypad)
            0x19, (byte)0xE0,  //   Usage Minimum (Left Control)
            0x29, (byte)0xE7,  //   Usage Maximum (Right GUI)
            0x15, 0x00,        //   Logical Minimum (0)
            0x25, 0x01,        //   Logical Maximum (1)
            0x75, 0x01,        //   Report Size (1 bit)
            (byte)0x95, 0x08,  //   Report Count (8)
            (byte)0x81, 0x02,  //   Input (Data, Variable, Absolute)

            // Reserved byte
            (byte)0x95, 0x01,  //   Report Count (1)
            0x75, 0x08,        //   Report Size (8 bits)
            (byte)0x81, 0x01,  //   Input (Constant)

            // Key codes (up to 6)
            (byte)0x95, 0x06,  //   Report Count (6)
            0x75, 0x08,        //   Report Size (8 bits)
            0x15, 0x00,        //   Logical Minimum (0)
            0x25, 0x65,        //   Logical Maximum (101)
            0x05, 0x07,        //   Usage Page (Keyboard/Keypad)
            0x19, 0x00,        //   Usage Minimum (0)
            0x29, 0x65,        //   Usage Maximum (101)
            (byte)0x81, 0x00,  //   Input (Data, Array)

            // LED output report
            (byte)0x95, 0x05,  //   Report Count (5)
            0x75, 0x01,        //   Report Size (1 bit)
            0x05, 0x08,        //   Usage Page (LEDs)
            0x19, 0x01,        //   Usage Minimum (Num Lock)
            0x29, 0x05,        //   Usage Maximum (Kana)
            (byte)0x91, 0x02,  //   Output (Data, Variable, Absolute)

            // LED padding
            (byte)0x95, 0x01,  //   Report Count (1)
            0x75, 0x03,        //   Report Size (3 bits)
            (byte)0x91, 0x01,  //   Output (Constant)

            (byte)0xC0         // End Collection
    };

    /**
     * Standard Boot Protocol Mouse descriptor with wheel.
     *
     * <p>Input Report (4 bytes):
     * <pre>
     * Byte 0: Buttons (Left, Right, Middle)
     * Byte 1: X movement (signed, -127 to 127)
     * Byte 2: Y movement (signed, -127 to 127)
     * Byte 3: Wheel movement (signed, -127 to 127)
     * </pre>
     */
    public static final byte[] BOOT_MOUSE = new byte[] {
            0x05, 0x01,        // Usage Page (Generic Desktop)
            0x09, 0x02,        // Usage (Mouse)
            (byte)0xA1, 0x01,  // Collection (Application)
            0x09, 0x01,        //   Usage (Pointer)
            (byte)0xA1, 0x00,  //   Collection (Physical)

            // Buttons
            0x05, 0x09,        //     Usage Page (Button)
            0x19, 0x01,        //     Usage Minimum (Button 1)
            0x29, 0x03,        //     Usage Maximum (Button 3)
            0x15, 0x00,        //     Logical Minimum (0)
            0x25, 0x01,        //     Logical Maximum (1)
            0x75, 0x01,        //     Report Size (1 bit)
            (byte)0x95, 0x03,  //     Report Count (3)
            (byte)0x81, 0x02,  //     Input (Data, Variable, Absolute)

            // Button padding
            0x75, 0x05,        //     Report Size (5 bits)
            (byte)0x95, 0x01,  //     Report Count (1)
            (byte)0x81, 0x01,  //     Input (Constant)

            // X, Y movement
            0x05, 0x01,        //     Usage Page (Generic Desktop)
            0x09, 0x30,        //     Usage (X)
            0x09, 0x31,        //     Usage (Y)
            0x15, (byte)0x81,  //     Logical Minimum (-127)
            0x25, 0x7F,        //     Logical Maximum (127)
            0x75, 0x08,        //     Report Size (8 bits)
            (byte)0x95, 0x02,  //     Report Count (2)
            (byte)0x81, 0x06,  //     Input (Data, Variable, Relative)

            // Wheel
            0x09, 0x38,        //     Usage (Wheel)
            0x15, (byte)0x81,  //     Logical Minimum (-127)
            0x25, 0x7F,        //     Logical Maximum (127)
            0x75, 0x08,        //     Report Size (8 bits)
            (byte)0x95, 0x01,  //     Report Count (1)
            (byte)0x81, 0x06,  //     Input (Data, Variable, Relative)

            (byte)0xC0,        //   End Collection (Physical)
            (byte)0xC0         // End Collection (Application)
    };

    /**
     * Keyboard + Mouse combo descriptor using report IDs.
     *
     * <p>Report ID 1: Keyboard (same format as BOOT_KEYBOARD)
     * <p>Report ID 2: Mouse (same format as BOOT_MOUSE)
     */
    public static final byte[] KEYBOARD_MOUSE_COMBO = new byte[] {
            0x05, 0x01,        // Usage Page (Generic Desktop)

            // Keyboard collection (Report ID 1)
            0x09, 0x06,        // Usage (Keyboard)
            (byte)0xA1, 0x01,  // Collection (Application)
            (byte)0x85, 0x01,  //   Report ID (1)

            // Modifier keys
            0x05, 0x07,        //   Usage Page (Keyboard)
            0x19, (byte)0xE0,  //   Usage Minimum (Left Control)
            0x29, (byte)0xE7,  //   Usage Maximum (Right GUI)
            0x15, 0x00,        //   Logical Minimum (0)
            0x25, 0x01,        //   Logical Maximum (1)
            0x75, 0x01,        //   Report Size (1)
            (byte)0x95, 0x08,  //   Report Count (8)
            (byte)0x81, 0x02,  //   Input (Data, Variable, Absolute)

            // Reserved byte
            (byte)0x95, 0x01,  //   Report Count (1)
            0x75, 0x08,        //   Report Size (8)
            (byte)0x81, 0x01,  //   Input (Constant)

            // Key codes
            (byte)0x95, 0x06,  //   Report Count (6)
            0x75, 0x08,        //   Report Size (8)
            0x15, 0x00,        //   Logical Minimum (0)
            0x25, 0x65,        //   Logical Maximum (101)
            0x05, 0x07,        //   Usage Page (Keyboard)
            0x19, 0x00,        //   Usage Minimum (0)
            0x29, 0x65,        //   Usage Maximum (101)
            (byte)0x81, 0x00,  //   Input (Data, Array)

            // LEDs
            (byte)0x95, 0x05,  //   Report Count (5)
            0x75, 0x01,        //   Report Size (1)
            0x05, 0x08,        //   Usage Page (LEDs)
            0x19, 0x01,        //   Usage Minimum (Num Lock)
            0x29, 0x05,        //   Usage Maximum (Kana)
            (byte)0x91, 0x02,  //   Output (Data, Variable, Absolute)
            (byte)0x95, 0x01,  //   Report Count (1)
            0x75, 0x03,        //   Report Size (3)
            (byte)0x91, 0x01,  //   Output (Constant)

            (byte)0xC0,        // End Collection (Keyboard)

            // Mouse collection (Report ID 2)
            0x05, 0x01,        // Usage Page (Generic Desktop)
            0x09, 0x02,        // Usage (Mouse)
            (byte)0xA1, 0x01,  // Collection (Application)
            (byte)0x85, 0x02,  //   Report ID (2)
            0x09, 0x01,        //   Usage (Pointer)
            (byte)0xA1, 0x00,  //   Collection (Physical)

            // Buttons
            0x05, 0x09,        //     Usage Page (Button)
            0x19, 0x01,        //     Usage Minimum (1)
            0x29, 0x03,        //     Usage Maximum (3)
            0x15, 0x00,        //     Logical Minimum (0)
            0x25, 0x01,        //     Logical Maximum (1)
            0x75, 0x01,        //     Report Size (1)
            (byte)0x95, 0x03,  //     Report Count (3)
            (byte)0x81, 0x02,  //     Input (Data, Variable, Absolute)
            0x75, 0x05,        //     Report Size (5)
            (byte)0x95, 0x01,  //     Report Count (1)
            (byte)0x81, 0x01,  //     Input (Constant)

            // X, Y, Wheel
            0x05, 0x01,        //     Usage Page (Generic Desktop)
            0x09, 0x30,        //     Usage (X)
            0x09, 0x31,        //     Usage (Y)
            0x09, 0x38,        //     Usage (Wheel)
            0x15, (byte)0x81,  //     Logical Minimum (-127)
            0x25, 0x7F,        //     Logical Maximum (127)
            0x75, 0x08,        //     Report Size (8)
            (byte)0x95, 0x03,  //     Report Count (3)
            (byte)0x81, 0x06,  //     Input (Data, Variable, Relative)

            (byte)0xC0,        //   End Collection (Physical)
            (byte)0xC0         // End Collection (Mouse)
    };

    /**
     * Generic Gamepad descriptor.
     *
     * <p>Input Report (Report ID 3):
     * <pre>
     * Bytes 0-1: Buttons (16 buttons)
     * Byte 2: Left stick X (0-255, center 128)
     * Byte 3: Left stick Y (0-255, center 128)
     * Byte 4: Right stick X (0-255, center 128)
     * Byte 5: Right stick Y (0-255, center 128)
     * Byte 6: Left trigger (0-255)
     * Byte 7: Right trigger (0-255)
     * Byte 8: D-pad (hat switch, 0-8)
     * </pre>
     */
    public static final byte[] GAMEPAD = new byte[] {
            0x05, 0x01,        // Usage Page (Generic Desktop)
            0x09, 0x05,        // Usage (Gamepad)
            (byte)0xA1, 0x01,  // Collection (Application)
            (byte)0x85, 0x03,  //   Report ID (3)

            // 16 Buttons
            0x05, 0x09,        //   Usage Page (Button)
            0x19, 0x01,        //   Usage Minimum (1)
            0x29, 0x10,        //   Usage Maximum (16)
            0x15, 0x00,        //   Logical Minimum (0)
            0x25, 0x01,        //   Logical Maximum (1)
            0x75, 0x01,        //   Report Size (1)
            (byte)0x95, 0x10,  //   Report Count (16)
            (byte)0x81, 0x02,  //   Input (Data, Variable, Absolute)

            // Left stick X, Y
            0x05, 0x01,        //   Usage Page (Generic Desktop)
            0x09, 0x30,        //   Usage (X)
            0x09, 0x31,        //   Usage (Y)
            0x15, 0x00,        //   Logical Minimum (0)
            0x26, (byte)0xFF, 0x00, // Logical Maximum (255)
            0x75, 0x08,        //   Report Size (8)
            (byte)0x95, 0x02,  //   Report Count (2)
            (byte)0x81, 0x02,  //   Input (Data, Variable, Absolute)

            // Right stick X, Y  
            0x09, 0x32,        //   Usage (Z) - Right X
            0x09, 0x35,        //   Usage (Rz) - Right Y
            (byte)0x95, 0x02,  //   Report Count (2)
            (byte)0x81, 0x02,  //   Input (Data, Variable, Absolute)

            // Triggers
            0x09, 0x33,        //   Usage (Rx) - Left trigger
            0x09, 0x34,        //   Usage (Ry) - Right trigger
            (byte)0x95, 0x02,  //   Report Count (2)
            (byte)0x81, 0x02,  //   Input (Data, Variable, Absolute)

            // D-pad (Hat switch)
            0x09, 0x39,        //   Usage (Hat Switch)
            0x15, 0x00,        //   Logical Minimum (0)
            0x25, 0x07,        //   Logical Maximum (7)
            0x35, 0x00,        //   Physical Minimum (0)
            0x46, 0x3B, 0x01,  //   Physical Maximum (315)
            0x65, 0x14,        //   Unit (Degrees)
            0x75, 0x04,        //   Report Size (4)
            (byte)0x95, 0x01,  //   Report Count (1)
            (byte)0x81, 0x42,  //   Input (Data, Variable, Absolute, Null State)

            // D-pad padding
            0x75, 0x04,        //   Report Size (4)
            (byte)0x95, 0x01,  //   Report Count (1)
            (byte)0x81, 0x01,  //   Input (Constant)

            (byte)0xC0         // End Collection
    };

    /**
     * Consumer Control descriptor for media keys.
     *
     * <p>Input Report (Report ID 4):
     * <pre>
     * Bytes 0-1: Consumer control usage (16-bit)
     * </pre>
     */
    public static final byte[] CONSUMER_CONTROL = new byte[] {
            0x05, 0x0C,        // Usage Page (Consumer)
            0x09, 0x01,        // Usage (Consumer Control)
            (byte)0xA1, 0x01,  // Collection (Application)
            (byte)0x85, 0x04,  //   Report ID (4)

            0x15, 0x00,        //   Logical Minimum (0)
            0x26, (byte)0xFF, 0x0F, // Logical Maximum (4095)
            0x19, 0x00,        //   Usage Minimum (0)
            0x2A, (byte)0xFF, 0x0F, // Usage Maximum (4095)
            0x75, 0x10,        //   Report Size (16)
            (byte)0x95, 0x01,  //   Report Count (1)
            (byte)0x81, 0x00,  //   Input (Data, Array)

            (byte)0xC0         // End Collection
    };

    // ==================== Consumer Control Usage IDs ====================

    /** Volume Up consumer usage. */
    public static final int CONSUMER_VOLUME_UP = 0x00E9;
    /** Volume Down consumer usage. */
    public static final int CONSUMER_VOLUME_DOWN = 0x00EA;
    /** Mute consumer usage. */
    public static final int CONSUMER_MUTE = 0x00E2;
    /** Play/Pause consumer usage. */
    public static final int CONSUMER_PLAY_PAUSE = 0x00CD;
    /** Next Track consumer usage. */
    public static final int CONSUMER_NEXT_TRACK = 0x00B5;
    /** Previous Track consumer usage. */
    public static final int CONSUMER_PREV_TRACK = 0x00B6;
    /** Stop consumer usage. */
    public static final int CONSUMER_STOP = 0x00B7;
    /** Eject consumer usage. */
    public static final int CONSUMER_EJECT = 0x00B8;

    // ==================== Descriptor Builder ====================

    /**
     * Builder for creating custom HID report descriptors.
     *
     * <p>Example:
     * <pre>{@code
     * byte[] descriptor = new ReportDescriptorBuilder()
     *     .beginCollection(HidConstants.COLLECTION_APPLICATION,
     *                      HidConstants.USAGE_PAGE_GENERIC_DESKTOP,
     *                      HidConstants.USAGE_KEYBOARD)
     *     .setReportId(1)
     *     .addInputField(...)
     *     .addOutputField(...)
     *     .endCollection()
     *     .build();
     * }</pre>
     */
    public static class ReportDescriptorBuilder {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        public ReportDescriptorBuilder usagePage(int page) {
            if (page <= 0xFF) {
                write(0x05, page);
            } else {
                write(0x06, page & 0xFF, (page >> 8) & 0xFF);
            }
            return this;
        }

        public ReportDescriptorBuilder usage(int usage) {
            if (usage <= 0xFF) {
                write(0x09, usage);
            } else {
                write(0x0A, usage & 0xFF, (usage >> 8) & 0xFF);
            }
            return this;
        }

        public ReportDescriptorBuilder usageMinimum(int min) {
            if (min <= 0xFF) {
                write(0x19, min);
            } else {
                write(0x1A, min & 0xFF, (min >> 8) & 0xFF);
            }
            return this;
        }

        public ReportDescriptorBuilder usageMaximum(int max) {
            if (max <= 0xFF) {
                write(0x29, max);
            } else {
                write(0x2A, max & 0xFF, (max >> 8) & 0xFF);
            }
            return this;
        }

        public ReportDescriptorBuilder logicalMinimum(int min) {
            if (min >= -128 && min <= 127) {
                write(0x15, min & 0xFF);
            } else {
                write(0x16, min & 0xFF, (min >> 8) & 0xFF);
            }
            return this;
        }

        public ReportDescriptorBuilder logicalMaximum(int max) {
            if (max >= 0 && max <= 255) {
                write(0x25, max);
            } else {
                write(0x26, max & 0xFF, (max >> 8) & 0xFF);
            }
            return this;
        }

        public ReportDescriptorBuilder reportSize(int bits) {
            write(0x75, bits);
            return this;
        }

        public ReportDescriptorBuilder reportCount(int count) {
            write(0x95, count);
            return this;
        }

        public ReportDescriptorBuilder reportId(int id) {
            write(0x85, id);
            return this;
        }

        public ReportDescriptorBuilder input(int flags) {
            write(0x81, flags);
            return this;
        }

        public ReportDescriptorBuilder output(int flags) {
            write(0x91, flags);
            return this;
        }

        public ReportDescriptorBuilder feature(int flags) {
            write(0xB1, flags);
            return this;
        }

        public ReportDescriptorBuilder beginCollection(int type) {
            write(0xA1, type);
            return this;
        }

        public ReportDescriptorBuilder endCollection() {
            write(0xC0);
            return this;
        }

        public ReportDescriptorBuilder beginApplicationCollection(int usagePage, int usage) {
            usagePage(usagePage);
            usage(usage);
            beginCollection(HidConstants.COLLECTION_APPLICATION);
            return this;
        }

        public ReportDescriptorBuilder beginPhysicalCollection() {
            beginCollection(HidConstants.COLLECTION_PHYSICAL);
            return this;
        }

        public ReportDescriptorBuilder raw(byte... bytes) {
            for (byte b : bytes) {
                buffer.write(b & 0xFF);
            }
            return this;
        }

        private void write(int... bytes) {
            for (int b : bytes) {
                buffer.write(b & 0xFF);
            }
        }

        public byte[] build() {
            return buffer.toByteArray();
        }
    }

    /**
     * Creates a new report descriptor builder.
     */
    public static ReportDescriptorBuilder builder() {
        return new ReportDescriptorBuilder();
    }
}
