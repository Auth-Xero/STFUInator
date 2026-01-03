package com.courierstack.util;

/**
 * Log severity and category types.
 *
 * <p>Log types are ordered by priority, where lower ordinal values
 * indicate higher priority (more critical).
 */
public enum LogType {
    /** Critical error condition. */
    ERROR(0, "E"),
    /** Warning condition that may indicate a problem. */
    WARNING(1, "W"),
    /** Informational message. */
    INFO(2, "I"),
    /** Debug-level message for development. */
    DEBUG(3, "D"),
    /** Transmitted data packet. */
    TX(4, "→"),
    /** Received data packet. */
    RX(4, "←"),
    /** Event notification. */
    EVENT(3, "◆");

    /** Numeric priority (lower = more critical). */
    public final int priority;

    /** Single-character label for display. */
    public final String label;

    LogType(int priority, String label) {
        this.priority = priority;
        this.label = label;
    }

    /**
     * Returns whether this log type is at least as severe as the given type.
     *
     * @param other the type to compare against
     * @return true if this type is at least as severe
     */
    public boolean isAtLeast(LogType other) {
        return this.priority <= other.priority;
    }

    /**
     * Returns whether this log type represents packet data.
     *
     * @return true if this is TX or RX
     */
    public boolean isPacketData() {
        return this == TX || this == RX;
    }
}