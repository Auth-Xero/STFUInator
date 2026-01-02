package com.courierstack.hci;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Utility methods for HCI packet manipulation.
 *
 * <p>This class provides conversion utilities for bridging between the byte array
 * format used internally and the ArrayList format required by the HIDL HAL interface.
 */
public final class HciPacket {

    private HciPacket() {
        // Utility class - prevent instantiation
    }

    /**
     * Converts a byte array to an ArrayList for HIDL HAL compatibility.
     *
     * @param byteArray source array (must not be null)
     * @return ArrayList of Bytes
     * @throws NullPointerException if byteArray is null
     */
    public static ArrayList<Byte> byteArrayToList(byte[] byteArray) {
        Objects.requireNonNull(byteArray, "byteArray must not be null");
        ArrayList<Byte> list = new ArrayList<>(byteArray.length);
        for (byte b : byteArray) {
            list.add(b);
        }
        return list;
    }

    /**
     * Converts an ArrayList to a byte array.
     *
     * @param byteList source list (must not be null)
     * @return byte array
     * @throws NullPointerException if byteList is null
     */
    public static byte[] listToByteArray(ArrayList<Byte> byteList) {
        Objects.requireNonNull(byteList, "byteList must not be null");
        byte[] byteArray = new byte[byteList.size()];
        for (int i = 0; i < byteList.size(); i++) {
            byteArray[i] = byteList.get(i);
        }
        return byteArray;
    }

    /**
     * Formats a byte array as a hexadecimal string for debugging.
     *
     * @param data byte array to format
     * @return hex string representation
     */
    public static String toHexString(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }

    /**
     * Formats a byte array as a spaced hexadecimal string for debugging.
     *
     * @param data byte array to format
     * @return hex string with spaces between bytes
     */
    public static String toHexStringSpaced(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }
}