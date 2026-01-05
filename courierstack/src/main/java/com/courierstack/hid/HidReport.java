package com.courierstack.hid;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

/**
 * Represents an HID report.
 *
 * <p>HID reports are the fundamental unit of data transfer in the HID protocol.
 * There are three types: Input (device to host), Output (host to device),
 * and Feature (bidirectional configuration).
 *
 * <p>This class provides utilities for parsing and creating HID reports,
 * including specialized support for boot protocol keyboard and mouse reports.
 *
 * <p>Thread safety: This class is immutable and thread-safe.
 *
 * @see HidConstants
 */
public class HidReport {

    /** Report type (INPUT, OUTPUT, or FEATURE). */
    private final int type;

    /** Report ID (0 if not using report IDs). */
    private final int reportId;

    /** Raw report data (excluding report ID if present). */
    private final byte[] data;

    /** Timestamp when report was created/received. */
    private final long timestamp;

    // ==================== Constructors ====================

    /**
     * Creates a new HID report.
     *
     * @param type     report type (INPUT, OUTPUT, or FEATURE)
     * @param reportId report ID (0 if not using report IDs)
     * @param data     report data (must not be null)
     * @throws NullPointerException     if data is null
     * @throws IllegalArgumentException if type is invalid
     */
    public HidReport(int type, int reportId, byte[] data) {
        if (!HidConstants.isValidReportType(type)) {
            throw new IllegalArgumentException("Invalid report type: " + type);
        }
        Objects.requireNonNull(data, "data must not be null");

        this.type = type;
        this.reportId = reportId & 0xFF;
        this.data = data.clone();
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * Creates a new HID report with no report ID.
     *
     * @param type report type
     * @param data report data
     */
    public HidReport(int type, byte[] data) {
        this(type, 0, data);
    }

    /**
     * Creates a new input report.
     *
     * @param reportId report ID
     * @param data     report data
     * @return input report
     */
    public static HidReport input(int reportId, byte[] data) {
        return new HidReport(HidConstants.REPORT_TYPE_INPUT, reportId, data);
    }

    /**
     * Creates a new input report with no report ID.
     *
     * @param data report data
     * @return input report
     */
    public static HidReport input(byte[] data) {
        return input(0, data);
    }

    /**
     * Creates a new output report.
     *
     * @param reportId report ID
     * @param data     report data
     * @return output report
     */
    public static HidReport output(int reportId, byte[] data) {
        return new HidReport(HidConstants.REPORT_TYPE_OUTPUT, reportId, data);
    }

    /**
     * Creates a new output report with no report ID.
     *
     * @param data report data
     * @return output report
     */
    public static HidReport output(byte[] data) {
        return output(0, data);
    }

    /**
     * Creates a new feature report.
     *
     * @param reportId report ID
     * @param data     report data
     * @return feature report
     */
    public static HidReport feature(int reportId, byte[] data) {
        return new HidReport(HidConstants.REPORT_TYPE_FEATURE, reportId, data);
    }

    /**
     * Creates a new feature report with no report ID.
     *
     * @param data report data
     * @return feature report
     */
    public static HidReport feature(byte[] data) {
        return feature(0, data);
    }

    // ==================== Basic Accessors ====================

    /**
     * Returns the report type.
     *
     * @return report type (INPUT, OUTPUT, or FEATURE)
     */
    public int getType() {
        return type;
    }

    /**
     * Returns the report type name.
     *
     * @return type name string
     */
    public String getTypeName() {
        return HidConstants.getReportTypeName(type);
    }

    /**
     * Returns whether this is an input report.
     *
     * @return true if input report
     */
    public boolean isInput() {
        return type == HidConstants.REPORT_TYPE_INPUT;
    }

    /**
     * Returns whether this is an output report.
     *
     * @return true if output report
     */
    public boolean isOutput() {
        return type == HidConstants.REPORT_TYPE_OUTPUT;
    }

    /**
     * Returns whether this is a feature report.
     *
     * @return true if feature report
     */
    public boolean isFeature() {
        return type == HidConstants.REPORT_TYPE_FEATURE;
    }

    /**
     * Returns the report ID.
     *
     * @return report ID (0 if not using report IDs)
     */
    public int getReportId() {
        return reportId;
    }

    /**
     * Returns whether this report has a report ID.
     *
     * @return true if report ID is non-zero
     */
    public boolean hasReportId() {
        return reportId != 0;
    }

    /**
     * Returns a copy of the report data.
     *
     * @return report data (excluding report ID)
     */
    public byte[] getData() {
        return data.clone();
    }

    /**
     * Returns the raw data at a specific offset.
     *
     * @param offset byte offset
     * @return byte value
     * @throws IndexOutOfBoundsException if offset is invalid
     */
    public int getByte(int offset) {
        return data[offset] & 0xFF;
    }

    /**
     * Returns the report data length.
     *
     * @return data length in bytes
     */
    public int getLength() {
        return data.length;
    }

    /**
     * Returns the total size including report ID if present.
     *
     * @return total size in bytes
     */
    public int getTotalSize() {
        return hasReportId() ? data.length + 1 : data.length;
    }

    /**
     * Returns the creation/reception timestamp.
     *
     * @return timestamp in milliseconds
     */
    public long getTimestamp() {
        return timestamp;
    }

    // ==================== Data Extraction ====================

    /**
     * Extracts an unsigned integer value from the report.
     *
     * @param bitOffset  bit offset from start of data
     * @param bitLength  number of bits (1-32)
     * @return unsigned value
     */
    public long getUnsigned(int bitOffset, int bitLength) {
        if (bitLength < 1 || bitLength > 32) {
            throw new IllegalArgumentException("bitLength must be 1-32");
        }

        long value = 0;
        for (int i = 0; i < bitLength; i++) {
            int byteIndex = (bitOffset + i) / 8;
            int bitIndex = (bitOffset + i) % 8;
            if (byteIndex < data.length) {
                if ((data[byteIndex] & (1 << bitIndex)) != 0) {
                    value |= (1L << i);
                }
            }
        }
        return value;
    }

    /**
     * Extracts a signed integer value from the report.
     *
     * @param bitOffset  bit offset from start of data
     * @param bitLength  number of bits (1-32)
     * @return signed value
     */
    public int getSigned(int bitOffset, int bitLength) {
        long unsigned = getUnsigned(bitOffset, bitLength);
        // Sign extend if negative
        if ((unsigned & (1L << (bitLength - 1))) != 0) {
            unsigned |= ~((1L << bitLength) - 1);
        }
        return (int) unsigned;
    }

    /**
     * Extracts an unsigned 8-bit value at byte offset.
     *
     * @param byteOffset byte offset
     * @return unsigned value (0-255)
     */
    public int getUint8(int byteOffset) {
        return data[byteOffset] & 0xFF;
    }

    /**
     * Extracts a signed 8-bit value at byte offset.
     *
     * @param byteOffset byte offset
     * @return signed value (-128 to 127)
     */
    public int getInt8(int byteOffset) {
        return data[byteOffset];
    }

    /**
     * Extracts an unsigned 16-bit little-endian value.
     *
     * @param byteOffset byte offset
     * @return unsigned value (0-65535)
     */
    public int getUint16LE(int byteOffset) {
        return (data[byteOffset] & 0xFF) |
               ((data[byteOffset + 1] & 0xFF) << 8);
    }

    /**
     * Extracts a signed 16-bit little-endian value.
     *
     * @param byteOffset byte offset
     * @return signed value
     */
    public int getInt16LE(int byteOffset) {
        return (short) getUint16LE(byteOffset);
    }

    /**
     * Extracts an unsigned 32-bit little-endian value.
     *
     * @param byteOffset byte offset
     * @return unsigned value
     */
    public long getUint32LE(int byteOffset) {
        return (data[byteOffset] & 0xFFL) |
               ((data[byteOffset + 1] & 0xFFL) << 8) |
               ((data[byteOffset + 2] & 0xFFL) << 16) |
               ((data[byteOffset + 3] & 0xFFL) << 24);
    }

    /**
     * Checks if a specific bit is set.
     *
     * @param bitOffset bit offset from start
     * @return true if bit is set
     */
    public boolean getBit(int bitOffset) {
        int byteIndex = bitOffset / 8;
        int bitIndex = bitOffset % 8;
        if (byteIndex >= data.length) return false;
        return (data[byteIndex] & (1 << bitIndex)) != 0;
    }

    // ==================== Wire Format ====================

    /**
     * Creates the wire format bytes for transmission.
     *
     * <p>Format: [Report ID (if present)][Data]
     *
     * @return wire format bytes
     */
    public byte[] toWireFormat() {
        if (hasReportId()) {
            byte[] wire = new byte[data.length + 1];
            wire[0] = (byte) reportId;
            System.arraycopy(data, 0, wire, 1, data.length);
            return wire;
        }
        return data.clone();
    }

    /**
     * Creates the HID transaction format for control channel.
     *
     * <p>Format: [Header][Report ID (if buffered size > MTU)][Data]
     *
     * @param transactionType transaction type (DATA or SET_REPORT)
     * @return transaction bytes
     */
    public byte[] toTransactionFormat(int transactionType) {
        byte header = HidConstants.createHeader(transactionType, type);
        byte[] wire = toWireFormat();
        byte[] result = new byte[1 + wire.length];
        result[0] = header;
        System.arraycopy(wire, 0, result, 1, wire.length);
        return result;
    }

    /**
     * Parses a report from wire format.
     *
     * @param type         report type
     * @param data         wire format data
     * @param hasReportId  whether first byte is report ID
     * @return parsed report
     */
    public static HidReport fromWireFormat(int type, byte[] data, boolean hasReportId) {
        Objects.requireNonNull(data, "data must not be null");
        if (hasReportId && data.length > 0) {
            int reportId = data[0] & 0xFF;
            byte[] reportData = new byte[data.length - 1];
            System.arraycopy(data, 1, reportData, 0, reportData.length);
            return new HidReport(type, reportId, reportData);
        }
        return new HidReport(type, 0, data);
    }

    // ==================== Boot Protocol Keyboards ====================

    /**
     * Parses a boot protocol keyboard input report.
     *
     * <p>Boot keyboard input format (8 bytes):
     * <pre>
     * [0]   Modifier keys (bit flags)
     * [1]   Reserved (OEM use)
     * [2-7] Key codes (up to 6 simultaneous keys)
     * </pre>
     *
     * @return keyboard data, or null if not valid keyboard report
     */
    public BootKeyboardData parseBootKeyboard() {
        if (data.length < HidConstants.BOOT_KEYBOARD_INPUT_SIZE) {
            return null;
        }
        return new BootKeyboardData(
                data[0] & 0xFF,
                new int[]{
                        data[2] & 0xFF, data[3] & 0xFF, data[4] & 0xFF,
                        data[5] & 0xFF, data[6] & 0xFF, data[7] & 0xFF
                }
        );
    }

    /**
     * Creates a boot protocol keyboard input report.
     *
     * @param modifiers modifier key flags
     * @param keyCodes  array of key codes (max 6)
     * @return keyboard input report
     */
    public static HidReport createBootKeyboardInput(int modifiers, int... keyCodes) {
        byte[] data = new byte[HidConstants.BOOT_KEYBOARD_INPUT_SIZE];
        data[0] = (byte) modifiers;
        data[1] = 0; // Reserved
        for (int i = 0; i < Math.min(6, keyCodes.length); i++) {
            data[2 + i] = (byte) keyCodes[i];
        }
        return input(data);
    }

    /**
     * Creates a boot protocol keyboard output report (LEDs).
     *
     * @param leds LED flags (NUM_LOCK, CAPS_LOCK, etc.)
     * @return keyboard output report
     */
    public static HidReport createBootKeyboardOutput(int leds) {
        return output(new byte[]{(byte) leds});
    }

    /**
     * Boot keyboard input data.
     */
    public static class BootKeyboardData {
        /** Modifier keys (bit flags). */
        public final int modifiers;
        /** Key codes currently pressed (up to 6). */
        public final int[] keyCodes;

        public BootKeyboardData(int modifiers, int[] keyCodes) {
            this.modifiers = modifiers;
            this.keyCodes = keyCodes.clone();
        }

        public boolean hasLeftCtrl() { return (modifiers & HidConstants.MOD_LEFT_CTRL) != 0; }
        public boolean hasLeftShift() { return (modifiers & HidConstants.MOD_LEFT_SHIFT) != 0; }
        public boolean hasLeftAlt() { return (modifiers & HidConstants.MOD_LEFT_ALT) != 0; }
        public boolean hasLeftGui() { return (modifiers & HidConstants.MOD_LEFT_GUI) != 0; }
        public boolean hasRightCtrl() { return (modifiers & HidConstants.MOD_RIGHT_CTRL) != 0; }
        public boolean hasRightShift() { return (modifiers & HidConstants.MOD_RIGHT_SHIFT) != 0; }
        public boolean hasRightAlt() { return (modifiers & HidConstants.MOD_RIGHT_ALT) != 0; }
        public boolean hasRightGui() { return (modifiers & HidConstants.MOD_RIGHT_GUI) != 0; }

        public boolean hasCtrl() { return hasLeftCtrl() || hasRightCtrl(); }
        public boolean hasShift() { return hasLeftShift() || hasRightShift(); }
        public boolean hasAlt() { return hasLeftAlt() || hasRightAlt(); }
        public boolean hasGui() { return hasLeftGui() || hasRightGui(); }

        /**
         * Returns the number of keys currently pressed.
         */
        public int getKeyCount() {
            int count = 0;
            for (int key : keyCodes) {
                if (key != 0) count++;
            }
            return count;
        }

        /**
         * Checks if a specific key code is pressed.
         */
        public boolean isKeyPressed(int keyCode) {
            for (int key : keyCodes) {
                if (key == keyCode) return true;
            }
            return false;
        }

        /**
         * Checks for keyboard rollover error (0x01 in all positions).
         */
        public boolean isRolloverError() {
            for (int key : keyCodes) {
                if (key != 0x01) return false;
            }
            return true;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("BootKeyboard{");
            String mods = HidConstants.formatModifiers(modifiers);
            if (!mods.isEmpty()) {
                sb.append("modifiers=[").append(mods).append("], ");
            }
            sb.append("keys=[");
            boolean first = true;
            for (int key : keyCodes) {
                if (key != 0) {
                    if (!first) sb.append(", ");
                    sb.append(String.format("0x%02X", key));
                    first = false;
                }
            }
            sb.append("]}");
            return sb.toString();
        }
    }

    // ==================== Boot Protocol Mouse ====================

    /**
     * Parses a boot protocol mouse input report.
     *
     * <p>Boot mouse input format (3+ bytes):
     * <pre>
     * [0]   Button states (bit flags)
     * [1]   X displacement (signed)
     * [2]   Y displacement (signed)
     * [3]   Wheel (optional, signed)
     * </pre>
     *
     * @return mouse data, or null if not valid mouse report
     */
    public BootMouseData parseBootMouse() {
        if (data.length < HidConstants.BOOT_MOUSE_INPUT_SIZE) {
            return null;
        }
        int buttons = data[0] & 0xFF;
        int x = data[1];
        int y = data[2];
        int wheel = data.length > 3 ? data[3] : 0;
        return new BootMouseData(buttons, x, y, wheel);
    }

    /**
     * Creates a boot protocol mouse input report.
     *
     * @param buttons button flags
     * @param x       X displacement (-127 to 127)
     * @param y       Y displacement (-127 to 127)
     * @return mouse input report
     */
    public static HidReport createBootMouseInput(int buttons, int x, int y) {
        return createBootMouseInput(buttons, x, y, 0);
    }

    /**
     * Creates a boot protocol mouse input report with wheel.
     *
     * @param buttons button flags
     * @param x       X displacement (-127 to 127)
     * @param y       Y displacement (-127 to 127)
     * @param wheel   wheel displacement (-127 to 127)
     * @return mouse input report
     */
    public static HidReport createBootMouseInput(int buttons, int x, int y, int wheel) {
        byte[] data = new byte[4];
        data[0] = (byte) buttons;
        data[1] = (byte) Math.max(-127, Math.min(127, x));
        data[2] = (byte) Math.max(-127, Math.min(127, y));
        data[3] = (byte) Math.max(-127, Math.min(127, wheel));
        return input(data);
    }

    /**
     * Boot mouse input data.
     */
    public static class BootMouseData {
        /** Button states (bit flags). */
        public final int buttons;
        /** X displacement (relative). */
        public final int x;
        /** Y displacement (relative). */
        public final int y;
        /** Wheel displacement (relative). */
        public final int wheel;

        public BootMouseData(int buttons, int x, int y, int wheel) {
            this.buttons = buttons;
            this.x = x;
            this.y = y;
            this.wheel = wheel;
        }

        public boolean hasLeftButton() { return (buttons & HidConstants.BUTTON_LEFT) != 0; }
        public boolean hasRightButton() { return (buttons & HidConstants.BUTTON_RIGHT) != 0; }
        public boolean hasMiddleButton() { return (buttons & HidConstants.BUTTON_MIDDLE) != 0; }

        public boolean isButton(int buttonNumber) {
            return (buttons & (1 << (buttonNumber - 1))) != 0;
        }

        public int getButtonCount() {
            return Integer.bitCount(buttons);
        }

        public boolean hasMoved() {
            return x != 0 || y != 0;
        }

        public boolean hasWheelMoved() {
            return wheel != 0;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("BootMouse{");
            String btns = HidConstants.formatButtons(buttons);
            if (!btns.isEmpty()) {
                sb.append("buttons=[").append(btns).append("], ");
            }
            sb.append("x=").append(x).append(", y=").append(y);
            if (wheel != 0) {
                sb.append(", wheel=").append(wheel);
            }
            sb.append("}");
            return sb.toString();
        }
    }

    // ==================== Gamepad Support ====================

    /**
     * Generic gamepad data extracted from a report.
     */
    public static class GamepadData {
        /** Button states bitmask. */
        public final int buttons;
        /** Left stick X (-32768 to 32767 or 0-255). */
        public final int leftX;
        /** Left stick Y. */
        public final int leftY;
        /** Right stick X. */
        public final int rightX;
        /** Right stick Y. */
        public final int rightY;
        /** Left trigger (0-255). */
        public final int leftTrigger;
        /** Right trigger (0-255). */
        public final int rightTrigger;
        /** D-pad value (0-8, 0=none, 1=N, 2=NE, ... 8=NW). */
        public final int dpad;

        public GamepadData(int buttons, int leftX, int leftY, int rightX, int rightY,
                           int leftTrigger, int rightTrigger, int dpad) {
            this.buttons = buttons;
            this.leftX = leftX;
            this.leftY = leftY;
            this.rightX = rightX;
            this.rightY = rightY;
            this.leftTrigger = leftTrigger;
            this.rightTrigger = rightTrigger;
            this.dpad = dpad;
        }

        public boolean isButton(int buttonNumber) {
            return (buttons & (1 << (buttonNumber - 1))) != 0;
        }

        @Override
        public String toString() {
            return String.format("Gamepad{buttons=0x%04X, L(%d,%d), R(%d,%d), LT=%d, RT=%d, dpad=%d}",
                    buttons, leftX, leftY, rightX, rightY, leftTrigger, rightTrigger, dpad);
        }
    }

    // ==================== Object Methods ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HidReport that = (HidReport) o;
        return type == that.type && reportId == that.reportId && Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(type, reportId);
        result = 31 * result + Arrays.hashCode(data);
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("HidReport{type=").append(getTypeName());
        if (hasReportId()) {
            sb.append(", id=").append(reportId);
        }
        sb.append(", len=").append(data.length);
        sb.append(", data=");
        for (int i = 0; i < Math.min(data.length, 16); i++) {
            sb.append(String.format("%02X", data[i] & 0xFF));
            if (i < data.length - 1 && i < 15) sb.append(" ");
        }
        if (data.length > 16) {
            sb.append("...");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Returns a detailed hex dump of the report.
     *
     * @return hex dump string
     */
    public String toHexDump() {
        StringBuilder sb = new StringBuilder();
        sb.append(getTypeName()).append(" Report");
        if (hasReportId()) {
            sb.append(" (ID=").append(reportId).append(")");
        }
        sb.append(":\n");

        for (int i = 0; i < data.length; i += 16) {
            sb.append(String.format("  %04X: ", i));
            for (int j = 0; j < 16 && i + j < data.length; j++) {
                sb.append(String.format("%02X ", data[i + j] & 0xFF));
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    // ==================== Builder ====================

    /**
     * Creates a new builder for HidReport.
     *
     * @return new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating HidReport instances.
     */
    public static class Builder {
        private int type = HidConstants.REPORT_TYPE_INPUT;
        private int reportId = 0;
        private ByteBuffer buffer;
        private int bitOffset = 0;

        public Builder() {
            buffer = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        }

        public Builder type(int type) {
            this.type = type;
            return this;
        }

        public Builder input() { return type(HidConstants.REPORT_TYPE_INPUT); }
        public Builder output() { return type(HidConstants.REPORT_TYPE_OUTPUT); }
        public Builder feature() { return type(HidConstants.REPORT_TYPE_FEATURE); }

        public Builder reportId(int reportId) {
            this.reportId = reportId;
            return this;
        }

        public Builder addByte(int value) {
            alignToByte();
            buffer.put((byte) value);
            bitOffset = buffer.position() * 8;
            return this;
        }

        public Builder addBytes(byte[] data) {
            alignToByte();
            buffer.put(data);
            bitOffset = buffer.position() * 8;
            return this;
        }

        public Builder addUint16(int value) {
            alignToByte();
            buffer.putShort((short) value);
            bitOffset = buffer.position() * 8;
            return this;
        }

        public Builder addInt16(int value) {
            return addUint16(value);
        }

        public Builder addUint32(long value) {
            alignToByte();
            buffer.putInt((int) value);
            bitOffset = buffer.position() * 8;
            return this;
        }

        public Builder addBits(int value, int bitCount) {
            for (int i = 0; i < bitCount; i++) {
                int byteIndex = bitOffset / 8;
                int bitIndex = bitOffset % 8;
                ensureCapacity(byteIndex + 1);
                if ((value & (1 << i)) != 0) {
                    buffer.put(byteIndex, (byte) (buffer.get(byteIndex) | (1 << bitIndex)));
                }
                bitOffset++;
            }
            return this;
        }

        public Builder addPadding(int bits) {
            bitOffset += bits;
            ensureCapacity((bitOffset + 7) / 8);
            return this;
        }

        private void alignToByte() {
            if (bitOffset % 8 != 0) {
                bitOffset = ((bitOffset / 8) + 1) * 8;
            }
            ensureCapacity(bitOffset / 8);
            buffer.position(bitOffset / 8);
        }

        private void ensureCapacity(int bytes) {
            if (buffer.capacity() < bytes) {
                ByteBuffer newBuffer = ByteBuffer.allocate(bytes * 2).order(ByteOrder.LITTLE_ENDIAN);
                buffer.flip();
                newBuffer.put(buffer);
                buffer = newBuffer;
            }
            // Ensure position allows writing to this byte
            while (buffer.position() < bytes) {
                buffer.put((byte) 0);
            }
        }

        public HidReport build() {
            alignToByte();
            byte[] data = new byte[buffer.position()];
            buffer.flip();
            buffer.get(data);
            return new HidReport(type, reportId, data);
        }
    }
}
