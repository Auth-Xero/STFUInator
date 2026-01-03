package com.courierstack.util;

import android.content.Context;
import android.util.Log;

import java.io.Closeable;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unified logging for CourierStack with console, file, and memory support.
 *
 * <p>This logger provides:
 * <ul>
 *   <li>Android logcat integration</li>
 *   <li>In-memory log buffer for UI display</li>
 *   <li>File logging for packet capture and debugging</li>
 *   <li>Thread-safe operation</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * // Initialize (once, typically in Application.onCreate)
 * CourierLogger.init(context);
 *
 * // Static logging methods
 * CourierLogger.d("MyTag", "Debug message");
 * CourierLogger.i("MyTag", "Info message");
 *
 * // Packet logging
 * CourierLogger.getInstance().logCommand(commandBytes);
 * CourierLogger.getInstance().logEvent(eventBytes);
 * }</pre>
 */
public final class CourierLogger implements Closeable {

    private static final String TAG = "CourierLogger";
    private static final String LOG_PREFIX = "Courier/";
    private static final String LOG_DIR = "CourierStack";
    private static final String DEBUG_LOG_FILE = "courier_debug.log";
    private static final int MAX_MEMORY_ENTRIES = 1000;
    private static final long MAX_DEBUG_LOG_SIZE = 10 * 1024 * 1024; // 10MB
    private static final long EXECUTOR_SHUTDOWN_TIMEOUT_MS = 2000;

    private static volatile CourierLogger sInstance;
    private static final Object sLock = new Object();
    private static volatile boolean sDebugEnabled = true;

    private final Context mContext;
    private final ExecutorService mWriterExecutor;
    private final ConcurrentLinkedQueue<PacketLogEntry> mMemoryLog;
    private final AtomicBoolean mFileLoggingEnabled;
    private final AtomicBoolean mDebugFileLoggingEnabled;
    private final AtomicBoolean mClosed;

    private volatile Listener mListener;
    private volatile PrintWriter mFileWriter;
    private volatile PrintWriter mDebugFileWriter;
    private volatile File mCurrentLogFile;
    private volatile String mSessionId;

    // ==================== Static logging methods ====================

    /**
     * Enables or disables debug logging globally.
     *
     * @param enabled true to enable debug logs
     */
    public static void setDebugEnabled(boolean enabled) {
        sDebugEnabled = enabled;
    }

    /**
     * Returns whether debug logging is enabled.
     *
     * @return true if debug logs are enabled
     */
    public static boolean isDebugEnabled() {
        return sDebugEnabled;
    }

    /** Logs a verbose message. */
    public static void v(String tag, String message) {
        if (sDebugEnabled) {
            Log.v(LOG_PREFIX + tag, message);
            writeToDebugFile(LogLevel.VERBOSE, tag, message);
        }
    }

    /** Logs a debug message. */
    public static void d(String tag, String message) {
        if (sDebugEnabled) {
            Log.d(LOG_PREFIX + tag, message);
            writeToDebugFile(LogLevel.DEBUG, tag, message);
        }
    }

    /** Logs an info message. */
    public static void i(String tag, String message) {
        Log.i(LOG_PREFIX + tag, message);
        writeToDebugFile(LogLevel.INFO, tag, message);
    }

    /** Logs a warning message. */
    public static void w(String tag, String message) {
        Log.w(LOG_PREFIX + tag, message);
        writeToDebugFile(LogLevel.WARNING, tag, message);
    }

    /** Logs a warning message with exception. */
    public static void w(String tag, String message, Throwable throwable) {
        Log.w(LOG_PREFIX + tag, message, throwable);
        writeToDebugFile(LogLevel.WARNING, tag, message + ": " + throwable.getMessage());
    }

    /** Logs an error message. */
    public static void e(String tag, String message) {
        Log.e(LOG_PREFIX + tag, message);
        writeToDebugFile(LogLevel.ERROR, tag, message);
    }

    /** Logs an error message with exception. */
    public static void e(String tag, String message, Throwable throwable) {
        Log.e(LOG_PREFIX + tag, message, throwable);
        writeToDebugFile(LogLevel.ERROR, tag, message + ": " + throwable.getMessage());
    }

    /** Logs a WTF message. */
    public static void wtf(String tag, String message) {
        Log.wtf(LOG_PREFIX + tag, message);
        writeToDebugFile(LogLevel.ERROR, tag, "WTF: " + message);
    }

    /** Logs a WTF message with exception. */
    public static void wtf(String tag, String message, Throwable throwable) {
        Log.wtf(LOG_PREFIX + tag, message, throwable);
        writeToDebugFile(LogLevel.ERROR, tag, "WTF: " + message + ": " + throwable.getMessage());
    }

    private static void writeToDebugFile(LogLevel level, String tag, String message) {
        CourierLogger instance = sInstance;
        if (instance != null && instance.mDebugFileLoggingEnabled.get()) {
            instance.writeDebugLog(level, tag, message);
        }
    }

    // ==================== Enums ====================

    /** Packet direction for logging. */
    public enum PacketDirection {
        TX("→", "TX"),
        RX("←", "RX"),
        INFO("·", "INFO"),
        ERROR("!", "ERROR");

        public final String symbol;
        public final String label;

        PacketDirection(String symbol, String label) {
            this.symbol = symbol;
            this.label = label;
        }
    }

    /** Packet type for logging. */
    public enum PacketType {
        COMMAND("CMD"),
        EVENT("EVT"),
        ACL("ACL"),
        SCO("SCO"),
        ISO("ISO"),
        INFO("INF"),
        ERROR("ERR");

        public final String label;

        PacketType(String label) {
            this.label = label;
        }
    }

    /** Log level. */
    public enum LogLevel {
        VERBOSE("V"),
        DEBUG("D"),
        INFO("I"),
        WARNING("W"),
        ERROR("E");

        public final String label;

        LogLevel(String label) {
            this.label = label;
        }
    }

    // ==================== PacketLogEntry ====================

    /**
     * Immutable packet log entry with hex data support.
     */
    public static final class PacketLogEntry {
        public final long timestamp;
        public final PacketDirection direction;
        public final PacketType type;
        public final byte[] data;
        public final String message;
        public final String formattedTime;

        private static final SimpleDateFormat TIME_FORMAT =
                new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

        public PacketLogEntry(PacketDirection direction, PacketType type,
                              byte[] data, String message) {
            this.timestamp = System.currentTimeMillis();
            this.direction = Objects.requireNonNull(direction);
            this.type = Objects.requireNonNull(type);
            this.data = data != null ? data.clone() : null;
            this.message = message;

            synchronized (TIME_FORMAT) {
                this.formattedTime = TIME_FORMAT.format(new Date(timestamp));
            }
        }

        /**
         * Returns hex representation of the data.
         */
        public String getHexData() {
            if (data == null || data.length == 0) {
                return "";
            }
            StringBuilder sb = new StringBuilder(data.length * 3);
            for (int i = 0; i < data.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append(String.format("%02X", data[i] & 0xFF));
            }
            return sb.toString();
        }

        /**
         * Returns compact hex without spaces.
         */
        public String getHexDataCompact() {
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
         * Returns a formatted log line.
         */
        public String toLogString() {
            StringBuilder sb = new StringBuilder();
            sb.append(formattedTime).append(' ')
                    .append(direction.symbol).append(" [").append(type.label).append("] ");
            if (message != null && !message.isEmpty()) {
                sb.append(message);
            }
            if (data != null && data.length > 0) {
                if (message != null && !message.isEmpty()) {
                    sb.append(" | ");
                }
                sb.append(getHexData());
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return toLogString();
        }
    }

    // ==================== Listener ====================

    /**
     * Listener for packet log events.
     */
    public interface Listener {
        /**
         * Called when a packet is logged.
         *
         * @param entry the log entry
         */
        void onLogEntry(PacketLogEntry entry);
    }

    // ==================== Initialization ====================

    private CourierLogger(Context context) {
        mContext = context.getApplicationContext();
        mWriterExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CourierLogger-Writer");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        mMemoryLog = new ConcurrentLinkedQueue<>();
        mFileLoggingEnabled = new AtomicBoolean(false);
        mDebugFileLoggingEnabled = new AtomicBoolean(false);
        mClosed = new AtomicBoolean(false);

        startDebugFileLogging();
    }

    /**
     * Initializes the singleton logger.
     *
     * @param context application context
     */
    public static void init(Context context) {
        Objects.requireNonNull(context, "context must not be null");
        synchronized (sLock) {
            if (sInstance == null) {
                sInstance = new CourierLogger(context);
            }
        }
    }

    /**
     * Returns the singleton instance.
     *
     * @return logger instance or null if not initialized
     */
    public static CourierLogger getInstance() {
        return sInstance;
    }

    /**
     * Returns whether the logger is initialized.
     *
     * @return true if initialized
     */
    public static boolean isInitialized() {
        return sInstance != null;
    }

    // ==================== Packet Logging ====================

    /**
     * Logs an HCI command.
     *
     * @param command command bytes
     */
    public void logCommand(byte[] command) {
        logPacket(new PacketLogEntry(
                PacketDirection.TX, PacketType.COMMAND, command, parseCommand(command)));
    }

    /**
     * Logs an HCI event.
     *
     * @param event event bytes
     */
    public void logEvent(byte[] event) {
        logPacket(new PacketLogEntry(
                PacketDirection.RX, PacketType.EVENT, event, parseEvent(event)));
    }

    /**
     * Logs ACL data.
     *
     * @param direction TX or RX
     * @param data      ACL packet bytes
     */
    public void logAcl(PacketDirection direction, byte[] data) {
        logPacket(new PacketLogEntry(direction, PacketType.ACL, data, parseAclData(data)));
    }

    /**
     * Logs SCO data.
     *
     * @param direction TX or RX
     * @param data      SCO packet bytes
     */
    public void logSco(PacketDirection direction, byte[] data) {
        logPacket(new PacketLogEntry(direction, PacketType.SCO, data, null));
    }

    /**
     * Logs ISO data.
     *
     * @param direction TX or RX
     * @param data      ISO packet bytes
     */
    public void logIso(PacketDirection direction, byte[] data) {
        logPacket(new PacketLogEntry(direction, PacketType.ISO, data, null));
    }

    /**
     * Logs an informational message.
     *
     * @param message the message
     */
    public void logInfo(String message) {
        logPacket(new PacketLogEntry(PacketDirection.INFO, PacketType.INFO, null, message));
    }

    /**
     * Logs an error message.
     *
     * @param message the error message
     */
    public void logError(String message) {
        logPacket(new PacketLogEntry(PacketDirection.ERROR, PacketType.ERROR, null, message));
    }

    private void logPacket(PacketLogEntry entry) {
        if (mClosed.get()) {
            return;
        }

        // Add to memory log
        mMemoryLog.offer(entry);
        while (mMemoryLog.size() > MAX_MEMORY_ENTRIES) {
            mMemoryLog.poll();
        }

        // Notify listener
        Listener listener = mListener;
        if (listener != null) {
            try {
                listener.onLogEntry(entry);
            } catch (Exception e) {
                Log.e(TAG, "Listener exception", e);
            }
        }

        // Write to file
        if (mFileLoggingEnabled.get()) {
            writePacketToFile(entry);
        }
    }

    // ==================== File Logging ====================

    /**
     * Starts packet file logging.
     *
     * @return true if successful
     */
    public boolean startFileLogging() {
        if (mClosed.get()) {
            return false;
        }

        try {
            File logDir = new File(mContext.getExternalFilesDir(null), LOG_DIR);
            if (!logDir.exists() && !logDir.mkdirs()) {
                Log.e(TAG, "Failed to create log directory");
                return false;
            }

            mSessionId = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date());
            mCurrentLogFile = new File(logDir, "courier_" + mSessionId + ".csv");
            mFileWriter = new PrintWriter(new FileWriter(mCurrentLogFile), true);
            mFileWriter.println("Timestamp,Direction,Type,Data,Message");
            mFileLoggingEnabled.set(true);

            Log.i(TAG, "Started packet logging to: " + mCurrentLogFile.getAbsolutePath());
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to start file logging", e);
            return false;
        }
    }

    /**
     * Stops packet file logging.
     */
    public void stopFileLogging() {
        mFileLoggingEnabled.set(false);
        PrintWriter writer = mFileWriter;
        mFileWriter = null;
        if (writer != null) {
            writer.close();
        }
    }

    /**
     * Returns whether file logging is enabled.
     *
     * @return true if enabled
     */
    public boolean isFileLoggingEnabled() {
        return mFileLoggingEnabled.get();
    }

    /**
     * Returns the current log file.
     *
     * @return log file or null
     */
    public File getCurrentLogFile() {
        return mCurrentLogFile;
    }

    private void writePacketToFile(PacketLogEntry entry) {
        mWriterExecutor.execute(() -> {
            PrintWriter writer = mFileWriter;
            if (writer != null) {
                StringBuilder sb = new StringBuilder();
                sb.append(entry.formattedTime).append(',')
                        .append(entry.direction.label).append(',')
                        .append(entry.type.label).append(',');

                if (entry.data != null && entry.data.length > 0) {
                    sb.append(entry.getHexDataCompact());
                }
                sb.append(',');

                if (entry.message != null) {
                    sb.append('"').append(entry.message.replace("\"", "\"\"")).append('"');
                }

                writer.println(sb.toString());
            }
        });
    }

    private void startDebugFileLogging() {
        try {
            File logDir = new File(mContext.getExternalFilesDir(null), LOG_DIR);
            if (!logDir.exists() && !logDir.mkdirs()) {
                Log.e(TAG, "Failed to create log directory");
                return;
            }

            File debugLogFile = new File(logDir, DEBUG_LOG_FILE);
            if (debugLogFile.exists() && debugLogFile.length() > MAX_DEBUG_LOG_SIZE) {
                if (!debugLogFile.delete()) {
                    Log.w(TAG, "Failed to delete oversized debug log");
                }
            }

            mDebugFileWriter = new PrintWriter(new FileWriter(debugLogFile, true), true);
            mDebugFileLoggingEnabled.set(true);
        } catch (IOException e) {
            Log.e(TAG, "Failed to start debug logging", e);
        }
    }

    private void writeDebugLog(LogLevel level, String tag, String message) {
        mWriterExecutor.execute(() -> {
            PrintWriter writer = mDebugFileWriter;
            if (writer != null) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
                writer.println(String.format("%s %s/%s: %s",
                        sdf.format(new Date()), level.label, tag, message));
            }
        });
    }

    // ==================== Memory Log ====================

    /**
     * Sets the packet log listener.
     *
     * @param listener listener or null to remove
     */
    public void setListener(Listener listener) {
        mListener = listener;
    }

    /**
     * Returns all entries in the memory log.
     *
     * @return array of log entries
     */
    public PacketLogEntry[] getMemoryLog() {
        return mMemoryLog.toArray(new PacketLogEntry[0]);
    }

    /**
     * Clears the memory log.
     */
    public void clearMemoryLog() {
        mMemoryLog.clear();
    }

    /**
     * Returns the number of entries in the memory log.
     *
     * @return entry count
     */
    public int getMemoryLogSize() {
        return mMemoryLog.size();
    }

    // ==================== Log Files ====================

    /**
     * Returns available log files, sorted by date (newest first).
     *
     * @return array of log files
     */
    public File[] getLogFiles() {
        File logDir = new File(mContext.getExternalFilesDir(null), LOG_DIR);
        if (!logDir.exists()) {
            return new File[0];
        }

        File[] files = logDir.listFiles((dir, name) ->
                name.startsWith("courier_") &&
                        (name.endsWith(".csv") || name.endsWith(".log")));

        if (files == null) {
            return new File[0];
        }

        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return files;
    }

    // ==================== Parsing Helpers ====================

    private String parseCommand(byte[] cmd) {
        if (cmd == null || cmd.length < 3) {
            return "Invalid";
        }
        int opcode = ((cmd[1] & 0xFF) << 8) | (cmd[0] & 0xFF);
        int ogf = (opcode >> 10) & 0x3F;
        int ocf = opcode & 0x3FF;
        return String.format("0x%04X (OGF=0x%02X, OCF=0x%03X) [%d bytes]",
                opcode, ogf, ocf, cmd[2] & 0xFF);
    }

    private String parseEvent(byte[] evt) {
        if (evt == null || evt.length < 2) {
            return "Invalid";
        }
        return String.format("0x%02X [%d bytes]", evt[0] & 0xFF, evt[1] & 0xFF);
    }

    private String parseAclData(byte[] data) {
        if (data == null || data.length < 4) {
            return "Invalid";
        }
        int handle = ((data[1] & 0x0F) << 8) | (data[0] & 0xFF);
        int length = ((data[3] & 0xFF) << 8) | (data[2] & 0xFF);
        return String.format("Handle=0x%03X Len=%d", handle, length);
    }

    // ==================== Shutdown ====================

    /**
     * Shuts down the logger and releases resources.
     *
     * @deprecated Use {@link #close()} instead
     */
    @Deprecated
    public void shutdown() {
        close();
    }

    @Override
    public void close() {
        if (!mClosed.compareAndSet(false, true)) {
            return;
        }

        stopFileLogging();

        mDebugFileLoggingEnabled.set(false);
        PrintWriter debugWriter = mDebugFileWriter;
        mDebugFileWriter = null;
        if (debugWriter != null) {
            debugWriter.close();
        }

        mWriterExecutor.shutdown();
        try {
            if (!mWriterExecutor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                mWriterExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            mWriterExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        Log.i(TAG, "CourierLogger closed");
    }
}