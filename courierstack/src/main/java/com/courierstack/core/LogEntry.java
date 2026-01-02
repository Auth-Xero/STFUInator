package com.courierstack.core;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

/**
 * Immutable representation of a single log entry with timestamp and metadata.
 *
 * <p>LogEntry objects are thread-safe and can be safely shared across threads.
 */
public final class LogEntry {

    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    /** Timestamp in milliseconds since epoch. */
    public final long timestamp;

    /** Source tag for the log entry. */
    public final String tag;

    /** Log message content. */
    public final String message;

    /** Severity/category type. */
    public final LogType type;

    /** Pre-formatted time string for display. */
    private final String formattedTime;

    /**
     * Creates a new log entry with the current timestamp.
     *
     * @param tag     source identifier (must not be null)
     * @param message log message (must not be null)
     * @param type    log type/severity (must not be null)
     * @throws NullPointerException if any parameter is null
     */
    public LogEntry(String tag, String message, LogType type) {
        this(System.currentTimeMillis(), tag, message, type);
    }

    /**
     * Creates a new log entry with a specific timestamp.
     *
     * @param timestamp time in milliseconds since epoch
     * @param tag       source identifier (must not be null)
     * @param message   log message (must not be null)
     * @param type      log type/severity (must not be null)
     * @throws NullPointerException if tag, message, or type is null
     */
    public LogEntry(long timestamp, String tag, String message, LogType type) {
        this.timestamp = timestamp;
        this.tag = Objects.requireNonNull(tag, "tag must not be null");
        this.message = Objects.requireNonNull(message, "message must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");

        synchronized (TIME_FORMAT) {
            this.formattedTime = TIME_FORMAT.format(new Date(timestamp));
        }
    }

    /**
     * Returns a formatted time string (HH:mm:ss.SSS).
     *
     * @return formatted time
     */
    public String getFormattedTime() {
        return formattedTime;
    }

    /**
     * Returns a formatted log line suitable for display.
     *
     * @return formatted log string
     */
    public String toLogString() {
        return String.format("%s [%s] %s: %s", formattedTime, type.label, tag, message);
    }

    /**
     * Returns a short format without timestamp.
     *
     * @return short format string
     */
    public String toShortString() {
        return String.format("[%s] %s: %s", type.label, tag, message);
    }

    @Override
    public String toString() {
        return toLogString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LogEntry logEntry = (LogEntry) o;
        return timestamp == logEntry.timestamp
                && tag.equals(logEntry.tag)
                && message.equals(logEntry.message)
                && type == logEntry.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(timestamp, tag, message, type);
    }
}