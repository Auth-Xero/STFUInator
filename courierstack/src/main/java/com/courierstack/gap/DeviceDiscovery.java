package com.courierstack.gap;

import com.courierstack.util.CourierLogger;
import com.courierstack.hci.HciCommandManager;
import com.courierstack.hci.HciCommands;
import com.courierstack.hci.IHciCommandListener;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages Bluetooth device discovery via BR/EDR inquiry and LE scanning.
 *
 * <p>Implements HCI event handling per Bluetooth Core Spec v5.3, Vol 4, Part E.
 *
 * <p>Features:
 * <ul>
 *   <li>BR/EDR Inquiry with EIR (Extended Inquiry Response)</li>
 *   <li>LE Scanning (passive and active)</li>
 *   <li>Dual-mode scanning (simultaneous BR/EDR + LE)</li>
 *   <li>Configurable scan parameters</li>
 *   <li>Device discovery by name</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * ScannerManager scanner = new ScannerManager(hciManager);
 * scanner.addListener(device -> {
 *     Log.d("Scan", "Found: " + device);
 * });
 *
 * // Start LE scanning
 * scanner.startLeScan();
 *
 * // Or dual-mode scanning
 * scanner.startDualScan();
 *
 * // Get discovered devices
 * List<ScannedDevice> devices = scanner.getDevices();
 *
 * // Stop scanning
 * scanner.stopAllScans();
 * }</pre>
 *
 * <p>Thread safety: This class is thread-safe. All public methods may be
 * called from any thread.
 */
public class DeviceDiscovery implements IHciCommandListener, Closeable {

    private static final String TAG = "ScannerManager";
    private static final long EXECUTOR_SHUTDOWN_TIMEOUT_MS = 5000;

    // ==================== Constants ====================

    /** General Inquiry Access Code (GIAC) LAP. */
    public static final int LAP_GIAC = 0x9E8B33;

    /** Limited Inquiry Access Code (LIAC) LAP. */
    public static final int LAP_LIAC = 0x9E8B00;

    /** Default inquiry duration (10.24s = 8 * 1.28s). */
    public static final int DEFAULT_INQUIRY_LENGTH = 0x08;

    /** Default LE scan interval (100ms = 160 * 0.625ms). */
    public static final int DEFAULT_LE_SCAN_INTERVAL = 0x00A0;

    /** Default LE scan window (50ms = 80 * 0.625ms). */
    public static final int DEFAULT_LE_SCAN_WINDOW = 0x0050;

    // HCI Event Codes
    private static final int EVT_INQUIRY_COMPLETE = 0x01;
    private static final int EVT_INQUIRY_RESULT = 0x02;
    private static final int EVT_INQUIRY_RESULT_RSSI = 0x22;
    private static final int EVT_EXTENDED_INQUIRY_RESULT = 0x2F;
    private static final int EVT_LE_META = 0x3E;

    // LE Meta Subevent Codes
    private static final int LE_ADV_REPORT = 0x02;
    private static final int LE_EXT_ADV_REPORT = 0x0D;

    // EIR/AD Data Types
    private static final int AD_TYPE_FLAGS = 0x01;
    private static final int AD_TYPE_UUID16_INCOMPLETE = 0x02;
    private static final int AD_TYPE_UUID16_COMPLETE = 0x03;
    private static final int AD_TYPE_UUID32_INCOMPLETE = 0x04;
    private static final int AD_TYPE_UUID32_COMPLETE = 0x05;
    private static final int AD_TYPE_UUID128_INCOMPLETE = 0x06;
    private static final int AD_TYPE_UUID128_COMPLETE = 0x07;
    private static final int AD_TYPE_SHORT_NAME = 0x08;
    private static final int AD_TYPE_COMPLETE_NAME = 0x09;
    private static final int AD_TYPE_TX_POWER = 0x0A;
    private static final int AD_TYPE_MANUFACTURER = 0xFF;

    // ==================== Enums ====================

    /** Scan operating mode. */
    public enum ScanMode {
        IDLE,
        INQUIRY,
        LE_SCAN,
        DUAL
    }

    /** LE scan type per Core Spec. */
    public enum LeScanType {
        PASSIVE(0x00),
        ACTIVE(0x01);

        public final int value;

        LeScanType(int value) {
            this.value = value;
        }
    }

    /** LE address type. */
    public enum AddressType {
        PUBLIC(0x00),
        RANDOM(0x01),
        RPA_PUBLIC(0x02),
        RPA_RANDOM(0x03);

        public final int value;

        AddressType(int value) {
            this.value = value;
        }
    }

    // ==================== ScanParameters ====================

    /**
     * Scan configuration parameters.
     */
    public static class ScanParameters {
        /** LE scan type (active or passive). */
        public LeScanType leScanType = LeScanType.ACTIVE;

        /** LE scan interval in 0.625ms units. */
        public int leScanInterval = DEFAULT_LE_SCAN_INTERVAL;

        /** LE scan window in 0.625ms units. */
        public int leScanWindow = DEFAULT_LE_SCAN_WINDOW;

        /** Own address type for LE scanning. */
        public AddressType ownAddressType = AddressType.PUBLIC;

        /** Inquiry LAP (GIAC or LIAC). */
        public int inquiryLap = LAP_GIAC;

        /** Inquiry length in 1.28s units. */
        public int inquiryLength = DEFAULT_INQUIRY_LENGTH;

        /** Max inquiry responses (0 = unlimited). */
        public int inquiryMaxResponses = 0;

        /** Filter duplicates during LE scan. */
        public boolean filterDuplicates = true;

        /** Scan timeout in milliseconds (0 = no timeout). */
        public long timeoutMs = 0;

        /**
         * Creates default parameters.
         */
        public ScanParameters() {}

        /**
         * Creates parameters with custom LE scan settings.
         *
         * @param scanType LE scan type
         * @param interval scan interval
         * @param window   scan window
         * @return parameters instance
         */
        public static ScanParameters forLeScan(LeScanType scanType, int interval, int window) {
            ScanParameters params = new ScanParameters();
            params.leScanType = scanType;
            params.leScanInterval = interval;
            params.leScanWindow = window;
            return params;
        }

        /**
         * Creates parameters for inquiry.
         *
         * @param lap          inquiry LAP
         * @param length       inquiry length
         * @param maxResponses max responses
         * @return parameters instance
         */
        public static ScanParameters forInquiry(int lap, int length, int maxResponses) {
            ScanParameters params = new ScanParameters();
            params.inquiryLap = lap;
            params.inquiryLength = length;
            params.inquiryMaxResponses = maxResponses;
            return params;
        }
    }

    // ==================== Instance Fields ====================

    private final HciCommandManager mHci;
    private final ExecutorService mExecutor;
    private final ScheduledExecutorService mScheduler;
    private final List<IDiscoveryListener> mListeners;
    private final Map<String, DiscoveredDevice> mDevices;

    private final AtomicBoolean mInquiryActive = new AtomicBoolean(false);
    private final AtomicBoolean mLeScanActive = new AtomicBoolean(false);
    private final AtomicBoolean mClosed = new AtomicBoolean(false);

    private volatile ScheduledFuture<?> mScanTimeoutTask;
    private volatile ScanMode mCurrentMode = ScanMode.IDLE;
    private volatile ScanParameters mParams;

    // ==================== Constructor ====================

    /**
     * Creates a scanner manager.
     *
     * @param hci HCI command manager (must not be null)
     * @throws NullPointerException if hci is null
     */
    public DeviceDiscovery(HciCommandManager hci) {
        mHci = Objects.requireNonNull(hci, "hci must not be null");
        mExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Scanner-Worker");
            t.setDaemon(true);
            return t;
        });
        mScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Scanner-Scheduler");
            t.setDaemon(true);
            return t;
        });
        mListeners = new CopyOnWriteArrayList<>();
        mDevices = new ConcurrentHashMap<>();
        mParams = new ScanParameters();
        mHci.addListener(this);
    }

    // ==================== Configuration ====================

    /**
     * Sets scan parameters.
     *
     * @param params new parameters (null for defaults)
     */
    public void setParameters(ScanParameters params) {
        mParams = (params != null) ? params : new ScanParameters();
    }

    /**
     * Returns current scan parameters.
     *
     * @return scan parameters
     */
    public ScanParameters getParameters() {
        return mParams;
    }

    // ==================== Listener Management ====================

    /**
     * Adds a scan listener.
     *
     * @param listener listener to add
     */
    public void addListener(IDiscoveryListener listener) {
        if (listener != null && !mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    /**
     * Removes a scan listener.
     *
     * @param listener listener to remove
     */
    public void removeListener(IDiscoveryListener listener) {
        mListeners.remove(listener);
    }

    // ==================== State Queries ====================

    /**
     * Returns the current scan mode.
     *
     * @return scan mode
     */
    public ScanMode getMode() {
        return mCurrentMode;
    }

    /**
     * Returns whether any scan is active.
     *
     * @return true if scanning
     */
    public boolean isScanning() {
        return mInquiryActive.get() || mLeScanActive.get();
    }

    /**
     * Returns whether BR/EDR inquiry is active.
     *
     * @return true if inquiry active
     */
    public boolean isInquiryActive() {
        return mInquiryActive.get();
    }

    /**
     * Returns whether LE scan is active.
     *
     * @return true if LE scan active
     */
    public boolean isLeScanActive() {
        return mLeScanActive.get();
    }

    // ==================== Device Access ====================

    /**
     * Returns all discovered devices.
     *
     * @return list of devices
     */
    public List<DiscoveredDevice> getDevices() {
        return new ArrayList<>(mDevices.values());
    }

    /**
     * Returns device by address.
     *
     * @param address BD_ADDR string
     * @return device or null
     */
    public DiscoveredDevice getDevice(String address) {
        return mDevices.get(address);
    }

    /**
     * Clears discovered devices.
     */
    public void clearDevices() {
        mDevices.clear();
    }

    // ==================== BR/EDR Inquiry ====================

    /**
     * Starts BR/EDR inquiry.
     *
     * @return true if started successfully
     */
    public boolean startInquiry() {
        if (mClosed.get()) {
            return false;
        }

        if (mInquiryActive.get()) {
            CourierLogger.w(TAG, "Inquiry already active");
            return false;
        }

        mExecutor.execute(() -> {
            CourierLogger.d(TAG, "Starting BR/EDR inquiry");

            // Set extended inquiry mode for EIR data
            byte[] cmd = HciCommands.writeInquiryMode(0x02);
            byte[] resp = mHci.sendCommandSync(cmd);
            if (!isCommandSuccess(resp)) {
                // Fall back to inquiry with RSSI
                cmd = HciCommands.writeInquiryMode(0x01);
                mHci.sendCommandSync(cmd);
            }

            // Start inquiry
            cmd = HciCommands.inquiry(mParams.inquiryLap,
                    mParams.inquiryLength, mParams.inquiryMaxResponses);
            mHci.sendCommand(cmd);
            mInquiryActive.set(true);
            updateMode();
            notifyScanStateChanged(true);

            scheduleTimeout();
        });
        return true;
    }

    /**
     * Stops BR/EDR inquiry.
     */
    public void stopInquiry() {
        if (!mInquiryActive.get()) return;

        mExecutor.execute(() -> {
            CourierLogger.d(TAG, "Stopping BR/EDR inquiry");
            byte[] cmd = HciCommands.inquiryCancel();
            mHci.sendCommandSync(cmd);
            mInquiryActive.set(false);
            updateMode();
            cancelTimeout();
            notifyScanStateChanged(isScanning());
            if (!isScanning()) notifyScanComplete();
        });
    }

    // ==================== LE Scanning ====================

    /**
     * Starts LE scanning.
     *
     * @return true if started successfully
     */
    public boolean startLeScan() {
        if (mClosed.get()) {
            return false;
        }

        if (mLeScanActive.get()) {
            CourierLogger.w(TAG, "LE scan already active");
            return false;
        }

        mExecutor.execute(() -> {
            CourierLogger.d(TAG, "Starting LE scan");

            // Configure scan parameters
            byte[] cmd = HciCommands.leSetScanParameters(
                    mParams.leScanType.value,
                    mParams.leScanInterval,
                    mParams.leScanWindow,
                    mParams.ownAddressType.value,
                    0x00); // Accept all advertisements
            byte[] resp = mHci.sendCommandSync(cmd);
            if (!isCommandSuccess(resp)) {
                notifyError("Failed to set LE scan parameters");
                return;
            }

            // Enable scanning
            cmd = HciCommands.leSetScanEnable(true, mParams.filterDuplicates);
            resp = mHci.sendCommandSync(cmd);
            if (!isCommandSuccess(resp)) {
                notifyError("Failed to enable LE scan");
                return;
            }

            mLeScanActive.set(true);
            updateMode();
            notifyScanStateChanged(true);

            scheduleTimeout();
        });
        return true;
    }

    /**
     * Stops LE scanning.
     */
    public void stopLeScan() {
        if (!mLeScanActive.get()) return;

        mExecutor.execute(() -> {
            CourierLogger.d(TAG, "Stopping LE scan");
            byte[] cmd = HciCommands.leSetScanEnable(false, false);
            mHci.sendCommandSync(cmd);
            mLeScanActive.set(false);
            updateMode();
            cancelTimeout();
            notifyScanStateChanged(isScanning());
            if (!isScanning()) notifyScanComplete();
        });
    }

    // ==================== Dual-Mode Scanning ====================

    /**
     * Starts dual-mode scanning (BR/EDR + LE).
     *
     * @return true if at least one mode started
     */
    public boolean startDualScan() {
        boolean leStarted = startLeScan();
        boolean inquiryStarted = startInquiry();
        return leStarted || inquiryStarted;
    }

    /**
     * Stops all scanning.
     */
    public void stopAllScans() {
        stopLeScan();
        stopInquiry();
    }

    /**
     * Stops all scanning synchronously.
     *
     * @param timeoutMs maximum wait time in milliseconds
     * @return true if stopped within timeout
     */
    public boolean stopAllScansSync(long timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);

        mExecutor.execute(() -> {
            if (mLeScanActive.get()) {
                CourierLogger.d(TAG, "Stopping LE scan (sync)");
                byte[] cmd = HciCommands.leSetScanEnable(false, false);
                mHci.sendCommandSync(cmd);
                mLeScanActive.set(false);
            }

            if (mInquiryActive.get()) {
                CourierLogger.d(TAG, "Stopping BR/EDR inquiry (sync)");
                byte[] cmd = HciCommands.inquiryCancel();
                mHci.sendCommandSync(cmd);
                mInquiryActive.set(false);
            }

            updateMode();
            cancelTimeout();
            notifyScanStateChanged(false);
            notifyScanComplete();
            latch.countDown();
        });

        try {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ==================== Discovery by Name ====================

    /**
     * Discovers BR/EDR device by name using inquiry.
     *
     * @param targetName device name to search for (case-insensitive partial match)
     * @param timeoutMs  maximum search time in milliseconds
     * @return discovered device address, or null if not found
     */
    public String discoverBrEdrDeviceByName(String targetName, long timeoutMs) {
        if (targetName == null || targetName.isEmpty()) {
            return null;
        }

        final AtomicReference<String> foundAddress = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);

        IDiscoveryListener discoveryListener = new IDiscoveryListener() {
            @Override
            public void onDeviceFound(DiscoveredDevice device) {
                if (device.isBrEdr() && device.getName() != null) {
                    String name = device.getName();
                    if (name.equalsIgnoreCase(targetName) ||
                            name.toLowerCase().contains(targetName.toLowerCase()) ||
                            targetName.toLowerCase().contains(name.toLowerCase())) {
                        foundAddress.set(device.getAddress());
                        latch.countDown();
                    }
                }
            }

            @Override
            public void onScanStateChanged(boolean scanning) {
                if (!scanning && foundAddress.get() == null) {
                    latch.countDown();
                }
            }

            @Override
            public void onScanComplete() {
                latch.countDown();
            }
        };

        addListener(discoveryListener);

        try {
            mExecutor.execute(() -> {
                CourierLogger.i(TAG, "Starting BR/EDR discovery for: " + targetName);

                // Set extended inquiry mode
                byte[] cmd = HciCommands.writeInquiryMode(0x02);
                byte[] resp = mHci.sendCommandSync(cmd);
                if (!isCommandSuccess(resp)) {
                    cmd = HciCommands.writeInquiryMode(0x01);
                    mHci.sendCommandSync(cmd);
                }

                // Calculate inquiry length
                int inquiryLength = (int) Math.min(30, (timeoutMs / 1280) + 1);

                // Start inquiry
                cmd = HciCommands.inquiry(LAP_GIAC, inquiryLength, 0);
                mHci.sendCommand(cmd);
                mInquiryActive.set(true);
                updateMode();
                notifyScanStateChanged(true);
            });

            latch.await(timeoutMs + 2000, TimeUnit.MILLISECONDS);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            removeListener(discoveryListener);
            stopInquiry();
        }

        String result = foundAddress.get();
        CourierLogger.i(TAG, "BR/EDR discovery result: " + (result != null ? result : "not found"));
        return result;
    }

    // ==================== Shutdown ====================

    /**
     * Shuts down the scanner.
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

        stopAllScans();
        mHci.removeListener(this);

        mExecutor.shutdown();
        mScheduler.shutdown();
        try {
            mExecutor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            mScheduler.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            mExecutor.shutdownNow();
            mScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        mListeners.clear();
        mDevices.clear();
    }

    // ==================== IHciCommandListener ====================

    @Override
    public void onEvent(byte[] event) {
        if (event == null || event.length < 2) return;
        int code = event[0] & 0xFF;

        switch (code) {
            case EVT_INQUIRY_COMPLETE:
                handleInquiryComplete(event);
                break;
            case EVT_INQUIRY_RESULT:
                handleInquiryResult(event);
                break;
            case EVT_INQUIRY_RESULT_RSSI:
                handleInquiryResultRssi(event);
                break;
            case EVT_EXTENDED_INQUIRY_RESULT:
                handleExtendedInquiryResult(event);
                break;
            case EVT_LE_META:
                handleLeMetaEvent(event);
                break;
        }
    }

    @Override
    public void onAclData(byte[] data) {}

    @Override
    public void onScoData(byte[] data) {}

    @Override
    public void onIsoData(byte[] data) {}

    @Override
    public void onError(String message) {
        notifyError(message);
    }

    @Override
    public void onMessage(String message) {}

    // ==================== Event Handlers ====================

    private void handleInquiryComplete(byte[] event) {
        int status = (event.length >= 3) ? event[2] & 0xFF : 0;
        CourierLogger.d(TAG, "Inquiry complete, status=" + status);
        mInquiryActive.set(false);
        updateMode();
        notifyScanStateChanged(isScanning());
        if (!isScanning()) notifyScanComplete();
    }

    private void handleInquiryResult(byte[] event) {
        if (event.length < 17) return;
        int numResponses = event[2] & 0xFF;
        int offset = 3;

        for (int i = 0; i < numResponses; i++) {
            if (offset + 14 > event.length) break;
            String addr = formatAddress(event, offset);
            int cod = getUint24(event, offset + 9);

            DiscoveredDevice device = getOrCreateDevice(addr);
            device.update(null, 0, cod, false, 0);
            notifyDeviceFound(device);

            offset += 14;
        }
    }

    private void handleInquiryResultRssi(byte[] event) {
        if (event.length < 15) return;
        int numResponses = event[2] & 0xFF;
        int offset = 3;

        for (int i = 0; i < numResponses; i++) {
            if (offset + 14 > event.length) break;
            String addr = formatAddress(event, offset);
            int cod = getUint24(event, offset + 8);
            int rssi = (byte) event[offset + 13];

            DiscoveredDevice device = getOrCreateDevice(addr);
            device.update(null, rssi, cod, false, 0);
            notifyDeviceFound(device);

            offset += 14;
        }
    }

    private void handleExtendedInquiryResult(byte[] event) {
        if (event.length < 255) return;

        String addr = formatAddress(event, 3);
        int cod = getUint24(event, 11);
        int rssi = (byte) event[16];

        byte[] eir = new byte[240];
        System.arraycopy(event, 17, eir, 0, 240);

        DiscoveredDevice device = getOrCreateDevice(addr);
        device.update(null, rssi, cod, false, 0);
        device.addEirData(eir);

        parseEirData(device, eir);
        notifyDeviceFound(device);
    }

    private void handleLeMetaEvent(byte[] event) {
        if (event.length < 3) return;
        int subEvent = event[2] & 0xFF;

        switch (subEvent) {
            case LE_ADV_REPORT:
                handleLeAdvertisingReport(event);
                break;
            case LE_EXT_ADV_REPORT:
                handleLeExtAdvertisingReport(event);
                break;
        }
    }

    private void handleLeAdvertisingReport(byte[] event) {
        if (event.length < 12) return;
        int numReports = event[3] & 0xFF;
        int offset = 4;

        for (int i = 0; i < numReports; i++) {
            if (offset + 10 > event.length) break;

            int eventType = event[offset] & 0xFF;
            int addrType = event[offset + 1] & 0xFF;
            String addr = formatAddress(event, offset + 2);
            int dataLen = event[offset + 8] & 0xFF;

            if (offset + 9 + dataLen >= event.length) break;

            byte[] advData = new byte[dataLen];
            System.arraycopy(event, offset + 9, advData, 0, dataLen);
            int rssi = (byte) event[offset + 9 + dataLen];

            DiscoveredDevice device = getOrCreateDevice(addr);
            device.update(null, rssi, 0, true, addrType);
            device.setAdvData(advData);

            parseAdvertisingData(device, advData);
            notifyDeviceFound(device);

            offset += 10 + dataLen;
        }
    }

    private void handleLeExtAdvertisingReport(byte[] event) {
        if (event.length < 26) return;
        int numReports = event[3] & 0xFF;
        int offset = 4;

        for (int i = 0; i < numReports; i++) {
            if (offset + 24 > event.length) break;

            int addrType = event[offset + 3] & 0xFF;
            String addr = formatAddress(event, offset + 4);
            int rssi = (byte) event[offset + 22];
            int dataLen = event[offset + 23] & 0xFF;

            if (offset + 24 + dataLen > event.length) break;

            byte[] advData = new byte[dataLen];
            System.arraycopy(event, offset + 24, advData, 0, dataLen);

            DiscoveredDevice device = getOrCreateDevice(addr);
            device.update(null, rssi, 0, true, addrType);
            device.setAdvData(advData);

            parseAdvertisingData(device, advData);
            notifyDeviceFound(device);

            offset += 24 + dataLen;
        }
    }

    // ==================== Parsing Helpers ====================

    private void parseAdvertisingData(DiscoveredDevice device, byte[] data) {
        int offset = 0;
        while (offset < data.length) {
            int len = data[offset++] & 0xFF;
            if (len == 0 || offset + len > data.length) break;

            int type = data[offset++] & 0xFF;
            int valueLen = len - 1;

            switch (type) {
                case AD_TYPE_FLAGS:
                    if (valueLen >= 1) {
                        int flags = data[offset] & 0xFF;
                        // Bit 2: BR/EDR Not Supported (if 0, BR/EDR is supported)
                        boolean supportsBrEdr = (flags & 0x04) == 0;
                        device.updateLe(null, device.getRssi(), device.getAddressType(), supportsBrEdr);
                    }
                    break;

                case AD_TYPE_SHORT_NAME:
                case AD_TYPE_COMPLETE_NAME:
                    String name = new String(data, offset, valueLen).trim();
                    if (!name.isEmpty()) {
                        device.update(name, device.getRssi(), 0, true, device.getAddressType());
                    }
                    break;

                case AD_TYPE_TX_POWER:
                    if (valueLen >= 1) {
                        device.setTxPower((byte) data[offset]);
                    }
                    break;

                case AD_TYPE_MANUFACTURER:
                    if (valueLen >= 2) {
                        int mfgId = ((data[offset + 1] & 0xFF) << 8) | (data[offset] & 0xFF);
                        byte[] mfgData = new byte[valueLen - 2];
                        System.arraycopy(data, offset + 2, mfgData, 0, mfgData.length);
                        device.setManufacturerData(mfgId, mfgData);
                    }
                    break;

                case AD_TYPE_UUID16_COMPLETE:
                case AD_TYPE_UUID16_INCOMPLETE:
                    for (int j = 0; j < valueLen; j += 2) {
                        if (offset + j + 1 < data.length) {
                            int uuid = ((data[offset + j + 1] & 0xFF) << 8) | (data[offset + j] & 0xFF);
                            device.addServiceUuid(String.format("%04X", uuid));
                        }
                    }
                    break;
            }
            offset += valueLen;
        }
    }

    private void parseEirData(DiscoveredDevice device, byte[] eir) {
        int offset = 0;
        while (offset < eir.length && eir[offset] != 0) {
            int len = eir[offset++] & 0xFF;
            if (len == 0 || offset + len > eir.length) break;

            int type = eir[offset++] & 0xFF;
            int valueLen = len - 1;

            if (type == AD_TYPE_SHORT_NAME || type == AD_TYPE_COMPLETE_NAME) {
                String name = new String(eir, offset, valueLen).trim();
                if (!name.isEmpty()) {
                    device.updateBrEdr(name, device.getRssi(), device.getClassOfDevice());
                }
            }
            offset += valueLen;
        }
    }

    // ==================== Utility Methods ====================

    private DiscoveredDevice getOrCreateDevice(String address) {
        return mDevices.computeIfAbsent(address, DiscoveredDevice::new);
    }

    private String formatAddress(byte[] data, int offset) {
        if (data.length < offset + 6) return "00:00:00:00:00:00";
        return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                data[offset + 5] & 0xFF, data[offset + 4] & 0xFF,
                data[offset + 3] & 0xFF, data[offset + 2] & 0xFF,
                data[offset + 1] & 0xFF, data[offset] & 0xFF);
    }

    private int getUint24(byte[] data, int offset) {
        return (data[offset] & 0xFF) |
                ((data[offset + 1] & 0xFF) << 8) |
                ((data[offset + 2] & 0xFF) << 16);
    }

    private boolean isCommandSuccess(byte[] response) {
        return response != null && response.length >= 6 && response[5] == 0x00;
    }

    private void updateMode() {
        if (mInquiryActive.get() && mLeScanActive.get()) {
            mCurrentMode = ScanMode.DUAL;
        } else if (mInquiryActive.get()) {
            mCurrentMode = ScanMode.INQUIRY;
        } else if (mLeScanActive.get()) {
            mCurrentMode = ScanMode.LE_SCAN;
        } else {
            mCurrentMode = ScanMode.IDLE;
        }
    }

    private void scheduleTimeout() {
        if (mParams.timeoutMs > 0) {
            cancelTimeout();
            mScanTimeoutTask = mScheduler.schedule(this::stopAllScans,
                    mParams.timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    private void cancelTimeout() {
        ScheduledFuture<?> task = mScanTimeoutTask;
        if (task != null) {
            task.cancel(false);
            mScanTimeoutTask = null;
        }
    }

    // ==================== Notifications ====================

    private void notifyDeviceFound(DiscoveredDevice device) {
        for (IDiscoveryListener l : mListeners) {
            try {
                l.onDeviceFound(device);
            } catch (Exception e) {
                CourierLogger.e(TAG, "Listener error in onDeviceFound", e);
            }
        }
    }

    private void notifyScanStateChanged(boolean scanning) {
        for (IDiscoveryListener l : mListeners) {
            try {
                l.onScanStateChanged(scanning);
            } catch (Exception e) {
                CourierLogger.e(TAG, "Listener error in onScanStateChanged", e);
            }
        }
    }

    private void notifyScanComplete() {
        for (IDiscoveryListener l : mListeners) {
            try {
                l.onScanComplete();
            } catch (Exception e) {
                CourierLogger.e(TAG, "Listener error in onScanComplete", e);
            }
        }
    }

    private void notifyError(String message) {
        CourierLogger.e(TAG, message);
        for (IDiscoveryListener l : mListeners) {
            try {
                l.onError(message);
            } catch (Exception e) {
                CourierLogger.e(TAG, "Listener error in onError", e);
            }
        }
    }
}