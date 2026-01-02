package com.courierstack.hci;

/**
 * HCI packet types per Bluetooth Core Spec v5.3, Vol 4, Part A, Section 2.
 *
 * <p>Each packet type defines its indicator byte value and the structure of
 * its length field for parsing purposes.
 */
public enum HciPacketType {
    /** HCI Command packet (Host to Controller). */
    COMMAND((byte) 0x01, 1, 2),
    /** ACL Data packet (bidirectional). */
    ACL_DATA((byte) 0x02, 2, 2),
    /** SCO Data packet (bidirectional). */
    SCO_DATA((byte) 0x03, 1, 2),
    /** HCI Event packet (Controller to Host). */
    EVENT((byte) 0x04, 1, 1),
    /** ISO Data packet (bidirectional, BT 5.2+). */
    ISO_DATA((byte) 0x05, 2, 2);

    /** HCI packet indicator byte. */
    public final byte value;

    /** Size of the length field in bytes. */
    public final int lengthSize;

    /** Offset to length field from packet start (after indicator). */
    public final int lengthOffset;

    HciPacketType(byte value, int lengthSize, int lengthOffset) {
        this.value = value;
        this.lengthSize = lengthSize;
        this.lengthOffset = lengthOffset;
    }

    /**
     * Resolves packet type from indicator byte.
     *
     * @param value indicator byte
     * @return packet type or null if invalid
     */
    public static HciPacketType fromValue(byte value) {
        for (HciPacketType type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        return null;
    }
}