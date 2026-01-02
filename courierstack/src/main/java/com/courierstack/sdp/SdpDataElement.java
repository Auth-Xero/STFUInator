package com.courierstack.sdp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * SDP Data Element encoder/decoder per Bluetooth Core Spec v5.3, Vol 3, Part B, Section 3.
 *
 * <p>Data elements are the basic building blocks of SDP. Each element consists of:
 * <ul>
 *   <li>Type descriptor (3 bits)</li>
 *   <li>Size descriptor (5 bits or extended)</li>
 *   <li>Data (variable length)</li>
 * </ul>
 *
 * <p>This class provides static methods for encoding and decoding all SDP data element types
 * including integers, UUIDs, strings, sequences, and alternatives.
 *
 * <p>Thread safety: This class is stateless and thread-safe.
 */
public final class SdpDataElement {

    private SdpDataElement() {
        // Utility class - prevent instantiation
    }

    // ==================== UUID Encoding ====================

    /**
     * Encodes a UUID as an SDP data element.
     *
     * <p>Uses the most compact representation:
     * <ul>
     *   <li>16-bit for Bluetooth Base UUIDs (0000xxxx-0000-1000-8000-00805F9B34FB)</li>
     *   <li>32-bit for extended Base UUIDs</li>
     *   <li>128-bit for all others</li>
     * </ul>
     *
     * @param uuid UUID to encode (must not be null)
     * @return encoded data element
     * @throws NullPointerException if uuid is null
     */
    public static byte[] encodeUuid(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid must not be null");

        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();

        // Check if this is a Bluetooth Base UUID
        if (isBluetoothBaseUuid(msb, lsb)) {
            int shortUuid = (int) ((msb >> 32) & 0xFFFFFFFFL);

            if ((shortUuid & 0xFFFF0000) == 0) {
                // 16-bit UUID
                ByteBuffer buf = ByteBuffer.allocate(3).order(ByteOrder.BIG_ENDIAN);
                buf.put((byte) ((SdpConstants.DE_UUID << 3) | 1)); // Type=UUID, Size=2
                buf.putShort((short) shortUuid);
                return buf.array();
            } else {
                // 32-bit UUID
                ByteBuffer buf = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN);
                buf.put((byte) ((SdpConstants.DE_UUID << 3) | 2)); // Type=UUID, Size=4
                buf.putInt(shortUuid);
                return buf.array();
            }
        }

        // Full 128-bit UUID
        ByteBuffer buf = ByteBuffer.allocate(17).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) ((SdpConstants.DE_UUID << 3) | 4)); // Type=UUID, Size=16
        buf.putLong(msb);
        buf.putLong(lsb);
        return buf.array();
    }

    /**
     * Decodes a UUID from a data element.
     *
     * @param buf buffer positioned at the UUID data element
     * @return decoded UUID, or null if invalid
     */
    public static UUID decodeUuid(ByteBuffer buf) {
        if (!buf.hasRemaining()) return null;

        int header = buf.get() & 0xFF;
        int type = header >> 3;
        int sizeIndex = header & 0x07;

        if (type != SdpConstants.DE_UUID) return null;

        int size = getFixedSize(sizeIndex);
        if (size < 0 || buf.remaining() < size) return null;

        switch (size) {
            case 2: {
                int uuid16 = buf.getShort() & 0xFFFF;
                return uuidFromShort(uuid16);
            }
            case 4: {
                int uuid32 = buf.getInt();
                return uuidFromInt(uuid32);
            }
            case 16: {
                long msb = buf.getLong();
                long lsb = buf.getLong();
                return new UUID(msb, lsb);
            }
            default:
                return null;
        }
    }

    /**
     * Creates a full UUID from a 16-bit Bluetooth UUID.
     *
     * @param uuid16 16-bit UUID
     * @return full 128-bit UUID
     */
    public static UUID uuidFromShort(int uuid16) {
        return new UUID(((long) uuid16 << 32) | 0x00001000L, 0x800000805F9B34FBL);
    }

    /**
     * Creates a full UUID from a 32-bit Bluetooth UUID.
     *
     * @param uuid32 32-bit UUID
     * @return full 128-bit UUID
     */
    public static UUID uuidFromInt(int uuid32) {
        return new UUID(((long) uuid32 << 32) | 0x00001000L, 0x800000805F9B34FBL);
    }

    // ==================== Integer Encoding ====================

    /**
     * Encodes an unsigned integer as an SDP data element.
     *
     * @param value unsigned integer value
     * @param bytes number of bytes (1, 2, 4, 8, or 16)
     * @return encoded data element
     * @throws IllegalArgumentException if bytes is not 1, 2, 4, 8, or 16
     */
    public static byte[] encodeUint(long value, int bytes) {
        int sizeIndex = bytesToSizeIndex(bytes);
        ByteBuffer buf = ByteBuffer.allocate(1 + bytes).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) ((SdpConstants.DE_UINT << 3) | sizeIndex));

        switch (bytes) {
            case 1:
                buf.put((byte) value);
                break;
            case 2:
                buf.putShort((short) value);
                break;
            case 4:
                buf.putInt((int) value);
                break;
            case 8:
                buf.putLong(value);
                break;
            case 16:
                buf.putLong(0);
                buf.putLong(value);
                break;
            default:
                throw new IllegalArgumentException("Invalid byte count: " + bytes);
        }
        return buf.array();
    }

    /**
     * Encodes an 8-bit unsigned integer.
     */
    public static byte[] encodeUint8(int value) {
        return encodeUint(value & 0xFF, 1);
    }

    /**
     * Encodes a 16-bit unsigned integer.
     */
    public static byte[] encodeUint16(int value) {
        return encodeUint(value & 0xFFFF, 2);
    }

    /**
     * Encodes a 32-bit unsigned integer.
     */
    public static byte[] encodeUint32(long value) {
        return encodeUint(value & 0xFFFFFFFFL, 4);
    }

    /**
     * Decodes an unsigned integer from a data element.
     *
     * @param buf buffer positioned at the integer data element
     * @return decoded value, or -1 if invalid
     */
    public static long decodeUint(ByteBuffer buf) {
        if (!buf.hasRemaining()) return -1;

        int header = buf.get() & 0xFF;
        int type = header >> 3;
        int sizeIndex = header & 0x07;

        if (type != SdpConstants.DE_UINT && type != SdpConstants.DE_INT) return -1;

        int size = getFixedSize(sizeIndex);
        if (size < 0 || buf.remaining() < size) return -1;

        switch (size) {
            case 1:
                return buf.get() & 0xFFL;
            case 2:
                return buf.getShort() & 0xFFFFL;
            case 4:
                return buf.getInt() & 0xFFFFFFFFL;
            case 8:
                return buf.getLong();
            case 16:
                buf.getLong(); // High 64 bits
                return buf.getLong();
            default:
                return -1;
        }
    }

    // ==================== String Encoding ====================

    /**
     * Encodes a string as an SDP text string data element.
     *
     * @param text string to encode (must not be null)
     * @return encoded data element
     * @throws NullPointerException if text is null
     */
    public static byte[] encodeString(String text) {
        Objects.requireNonNull(text, "text must not be null");
        byte[] strBytes = text.getBytes(StandardCharsets.UTF_8);
        return encodeVariableData(SdpConstants.DE_STRING, strBytes);
    }

    /**
     * Encodes a URL as an SDP URL data element.
     *
     * @param url URL to encode (must not be null)
     * @return encoded data element
     */
    public static byte[] encodeUrl(String url) {
        Objects.requireNonNull(url, "url must not be null");
        byte[] urlBytes = url.getBytes(StandardCharsets.UTF_8);
        return encodeVariableData(SdpConstants.DE_URL, urlBytes);
    }

    /**
     * Decodes a string from a data element.
     *
     * @param buf buffer positioned at the string data element
     * @return decoded string, or null if invalid
     */
    public static String decodeString(ByteBuffer buf) {
        if (!buf.hasRemaining()) return null;

        int header = buf.get() & 0xFF;
        int type = header >> 3;
        int sizeIndex = header & 0x07;

        if (type != SdpConstants.DE_STRING && type != SdpConstants.DE_URL) return null;

        int length = getVariableLength(buf, sizeIndex);
        if (length < 0 || buf.remaining() < length) return null;

        byte[] strBytes = new byte[length];
        buf.get(strBytes);
        return new String(strBytes, StandardCharsets.UTF_8).trim();
    }

    // ==================== Sequence/Alternative Encoding ====================

    /**
     * Encodes a sequence of data elements.
     *
     * @param elements list of already-encoded data elements
     * @return encoded sequence data element
     */
    public static byte[] encodeSequence(List<byte[]> elements) {
        return encodeContainer(SdpConstants.DE_SEQ, elements);
    }

    /**
     * Encodes a sequence of data elements.
     *
     * @param elements already-encoded data elements
     * @return encoded sequence data element
     */
    public static byte[] encodeSequence(byte[]... elements) {
        int totalLen = 0;
        for (byte[] e : elements) {
            totalLen += e.length;
        }
        byte[] combined = new byte[totalLen];
        int pos = 0;
        for (byte[] e : elements) {
            System.arraycopy(e, 0, combined, pos, e.length);
            pos += e.length;
        }
        return encodeVariableData(SdpConstants.DE_SEQ, combined);
    }

    /**
     * Encodes an alternative of data elements.
     *
     * @param elements list of already-encoded data elements
     * @return encoded alternative data element
     */
    public static byte[] encodeAlternative(List<byte[]> elements) {
        return encodeContainer(SdpConstants.DE_ALT, elements);
    }

    /**
     * Encodes a boolean as an SDP data element.
     *
     * @param value boolean value
     * @return encoded data element
     */
    public static byte[] encodeBoolean(boolean value) {
        return new byte[]{
                (byte) ((SdpConstants.DE_BOOL << 3) | 0),
                (byte) (value ? 1 : 0)
        };
    }

    /**
     * Encodes nil (null) data element.
     *
     * @return encoded nil element
     */
    public static byte[] encodeNil() {
        return new byte[]{(byte) (SdpConstants.DE_NIL << 3)};
    }

    // ==================== Decoding Utilities ====================

    /**
     * Reads and returns the data element at the current buffer position.
     *
     * <p>After this call, the buffer will be positioned after the element.
     *
     * @param buf buffer positioned at a data element
     * @return the raw bytes of the data element (header + data), or null if invalid
     */
    public static byte[] readElement(ByteBuffer buf) {
        if (!buf.hasRemaining()) return null;

        int startPos = buf.position();
        int header = buf.get() & 0xFF;
        int type = header >> 3;
        int sizeIndex = header & 0x07;

        int dataLength;
        if (type == SdpConstants.DE_NIL) {
            dataLength = 0;
        } else if (sizeIndex <= 4) {
            dataLength = getFixedSize(sizeIndex);
        } else {
            dataLength = getVariableLength(buf, sizeIndex);
        }

        if (dataLength < 0 || buf.remaining() < dataLength) {
            buf.position(startPos);
            return null;
        }

        int totalLength = buf.position() - startPos + dataLength;
        buf.position(startPos);

        byte[] element = new byte[totalLength];
        buf.get(element);
        return element;
    }

    /**
     * Skips over a data element.
     *
     * @param buf buffer positioned at a data element
     * @return true if successfully skipped, false if invalid
     */
    public static boolean skipElement(ByteBuffer buf) {
        if (!buf.hasRemaining()) return false;

        int header = buf.get() & 0xFF;
        int type = header >> 3;
        int sizeIndex = header & 0x07;

        int dataLength;
        if (type == SdpConstants.DE_NIL) {
            dataLength = 0;
        } else if (sizeIndex <= 4) {
            dataLength = getFixedSize(sizeIndex);
        } else {
            dataLength = getVariableLength(buf, sizeIndex);
        }

        if (dataLength < 0 || buf.remaining() < dataLength) return false;

        buf.position(buf.position() + dataLength);
        return true;
    }

    /**
     * Returns the type of the data element at the current position.
     *
     * @param buf buffer positioned at a data element
     * @return element type (DE_*), or -1 if buffer is empty
     */
    public static int peekType(ByteBuffer buf) {
        if (!buf.hasRemaining()) return -1;
        return (buf.get(buf.position()) & 0xFF) >> 3;
    }

    /**
     * Returns the total length of the data element at the current position.
     *
     * @param buf buffer positioned at a data element
     * @return total length including header, or -1 if invalid
     */
    public static int getElementLength(ByteBuffer buf) {
        if (!buf.hasRemaining()) return -1;

        int startPos = buf.position();
        int header = buf.get() & 0xFF;
        int type = header >> 3;
        int sizeIndex = header & 0x07;

        int dataLength;
        if (type == SdpConstants.DE_NIL) {
            dataLength = 0;
        } else if (sizeIndex <= 4) {
            dataLength = getFixedSize(sizeIndex);
        } else {
            dataLength = getVariableLength(buf, sizeIndex);
        }

        int headerLength = buf.position() - startPos;
        buf.position(startPos);

        return dataLength >= 0 ? headerLength + dataLength : -1;
    }

    // ==================== Attribute ID List Encoding ====================

    /**
     * Encodes an attribute ID range for ServiceAttribute/ServiceSearchAttribute requests.
     *
     * @param startId start of range (inclusive)
     * @param endId   end of range (inclusive)
     * @return encoded attribute ID range
     */
    public static byte[] encodeAttributeIdRange(int startId, int endId) {
        ByteBuffer buf = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) ((SdpConstants.DE_UINT << 3) | 2)); // 32-bit uint
        buf.putShort((short) startId);
        buf.putShort((short) endId);
        return buf.array();
    }

    /**
     * Encodes a single attribute ID.
     *
     * @param attrId attribute ID
     * @return encoded attribute ID
     */
    public static byte[] encodeAttributeId(int attrId) {
        return encodeUint16(attrId);
    }

    /**
     * Encodes an attribute ID list containing all attributes (0x0000-0xFFFF).
     *
     * @return encoded attribute ID list
     */
    public static byte[] encodeAllAttributesRange() {
        return encodeSequence(encodeAttributeIdRange(0x0000, 0xFFFF));
    }

    // ==================== Private Helper Methods ====================

    private static boolean isBluetoothBaseUuid(long msb, long lsb) {
        return (msb & 0x0000FFFFFFFFFFFFL) == 0x00001000L
                && lsb == 0x800000805F9B34FBL;
    }

    private static int bytesToSizeIndex(int bytes) {
        switch (bytes) {
            case 1:
                return 0;
            case 2:
                return 1;
            case 4:
                return 2;
            case 8:
                return 3;
            case 16:
                return 4;
            default:
                throw new IllegalArgumentException("Invalid byte count: " + bytes);
        }
    }

    private static int getFixedSize(int sizeIndex) {
        switch (sizeIndex) {
            case 0:
                return 1;
            case 1:
                return 2;
            case 2:
                return 4;
            case 3:
                return 8;
            case 4:
                return 16;
            default:
                return -1;
        }
    }

    private static int getVariableLength(ByteBuffer buf, int sizeIndex) {
        switch (sizeIndex) {
            case 5:
                return buf.hasRemaining() ? buf.get() & 0xFF : -1;
            case 6:
                return buf.remaining() >= 2 ? buf.getShort() & 0xFFFF : -1;
            case 7:
                return buf.remaining() >= 4 ? buf.getInt() : -1;
            default:
                return -1;
        }
    }

    private static byte[] encodeVariableData(int type, byte[] data) {
        int length = data.length;
        ByteBuffer buf;

        if (length <= 0xFF) {
            buf = ByteBuffer.allocate(2 + length).order(ByteOrder.BIG_ENDIAN);
            buf.put((byte) ((type << 3) | 5)); // 8-bit length
            buf.put((byte) length);
        } else if (length <= 0xFFFF) {
            buf = ByteBuffer.allocate(3 + length).order(ByteOrder.BIG_ENDIAN);
            buf.put((byte) ((type << 3) | 6)); // 16-bit length
            buf.putShort((short) length);
        } else {
            buf = ByteBuffer.allocate(5 + length).order(ByteOrder.BIG_ENDIAN);
            buf.put((byte) ((type << 3) | 7)); // 32-bit length
            buf.putInt(length);
        }

        buf.put(data);
        return buf.array();
    }

    private static byte[] encodeContainer(int type, List<byte[]> elements) {
        int totalLen = 0;
        for (byte[] e : elements) {
            totalLen += e.length;
        }
        byte[] combined = new byte[totalLen];
        int pos = 0;
        for (byte[] e : elements) {
            System.arraycopy(e, 0, combined, pos, e.length);
            pos += e.length;
        }
        return encodeVariableData(type, combined);
    }
}