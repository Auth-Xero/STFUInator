package com.courierstack.l2cap;

/**
 * Bluetooth connection transport type.
 *
 * <p>Indicates whether a connection uses BR/EDR (Classic Bluetooth)
 * or LE (Bluetooth Low Energy) transport.
 */
public enum ConnectionType {
    /** BR/EDR (Basic Rate / Enhanced Data Rate) - Classic Bluetooth. */
    BR_EDR("BR/EDR"),

    /** LE (Low Energy) - Bluetooth Low Energy. */
    LE("LE");

    private final String label;

    ConnectionType(String label) {
        this.label = label;
    }

    /**
     * Returns the human-readable label.
     *
     * @return transport type label
     */
    public String getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}