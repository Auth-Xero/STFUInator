package com.courierstack.hid;

import com.courierstack.l2cap.AclConnection;
import com.courierstack.l2cap.ChannelState;
import com.courierstack.l2cap.IL2capListener;
import com.courierstack.l2cap.IL2capServerListener;
import com.courierstack.l2cap.L2capChannel;
import com.courierstack.l2cap.L2capConstants;
import com.courierstack.l2cap.L2capManager;
import com.courierstack.sdp.SdpConstants;
import com.courierstack.sdp.SdpDatabase;
import com.courierstack.sdp.SdpDataElement;
import com.courierstack.sdp.ServiceRecord;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HID Device Profile implementation for emulating Bluetooth HID devices.
 *
 * <p>This class allows emulating various HID devices such as keyboards, mice,
 * gamepads, etc. It implements the HID Device (HID-D) role as opposed to
 * HID Host (HID-H).
 *
 * <p>Features:
 * <ul>
 *   <li>Device emulation (keyboard, mouse, gamepad, combo)</li>
 *   <li>SDP service record registration</li>
 *   <li>Both Boot and Report protocol modes</li>
 *   <li>Input report sending on interrupt channel</li>
 *   <li>Output report reception for keyboard LEDs, etc.</li>
 *   <li>Multiple simultaneous host connections</li>
 * </ul>
 *
 * <p>Usage example - Keyboard emulation:
 * <pre>{@code
 * HidDeviceProfile hidDevice = new HidDeviceProfile(l2capManager, sdpDatabase);
 *
 * hidDevice.setListener(new HidDeviceProfile.Listener() {
 *     @Override
 *     public void onHostConnected(HostConnection host) {
 *         Log.i("HID", "Host connected: " + host.getAddress());
 *     }
 *
 *     @Override
 *     public void onOutputReport(HostConnection host, HidReport report) {
 *         // Handle LED updates from host
 *         int leds = report.getByte(0);
 *         updateKeyboardLeds(leds);
 *     }
 * });
 *
 * // Configure as keyboard
 * hidDevice.configure(HidDeviceProfile.Config.keyboard()
 *     .deviceName("My BT Keyboard")
 *     .build());
 *
 * hidDevice.start();
 *
 * // Send key press
 * hidDevice.sendKeyPress(host, HidConstants.KEY_A, 0);
 *
 * // Send mouse movement (for combo devices)
 * hidDevice.sendMouseMove(host, 10, -5, 0);
 * }</pre>
 *
 * @see HidManager for HID Host implementation
 */
public class HidDeviceProfile implements Closeable {

    private static final String TAG = "HidDeviceProfile";
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_MS = 5000;

    // Dependencies
    private final L2capManager mL2capManager;
    private final SdpDatabase mSdpDatabase;
    private final ExecutorService mExecutor;

    // Configuration
    private volatile Config mConfig;
    private volatile int mSdpHandle = -1;

    // State
    private final AtomicBoolean mStarted = new AtomicBoolean(false);
    private final AtomicBoolean mClosed = new AtomicBoolean(false);

    // Connected hosts
    private final Map<String, HostConnection> mHosts = new ConcurrentHashMap<>();
    private final Map<Integer, HostConnection> mHostsByControlCid = new ConcurrentHashMap<>();
    private final Map<Integer, HostConnection> mHostsByInterruptCid = new ConcurrentHashMap<>();

    // Listeners
    private volatile Listener mListener;
    private final List<Listener> mAdditionalListeners = new CopyOnWriteArrayList<>();

    // L2CAP listeners
    private final DeviceL2capListener mL2capListener = new DeviceL2capListener();

    // Protocol state
    private final AtomicInteger mProtocolMode = new AtomicInteger(HidConstants.PROTOCOL_REPORT);
    private volatile int mIdleRate = HidConstants.IDLE_RATE_INDEFINITE;
    private volatile boolean mSuspended = false;

    // ==================== Listener Interface ====================

    /**
     * Listener interface for HID Device events.
     */
    public interface Listener {
        /** Called when a host connects. */
        default void onHostConnected(HostConnection host) {}

        /** Called when a host disconnects. */
        default void onHostDisconnected(HostConnection host, int reason) {}

        /** Called when an output report is received (e.g., keyboard LEDs). */
        default void onOutputReport(HostConnection host, HidReport report) {}

        /** Called when a feature report is requested (GET_REPORT). */
        default byte[] onGetFeatureReport(HostConnection host, int reportId) { return null; }

        /** Called when a feature report is set (SET_REPORT). */
        default void onSetFeatureReport(HostConnection host, HidReport report) {}

        /** Called when protocol mode changes. */
        default void onProtocolChanged(int protocol) {}

        /** Called when idle rate changes. */
        default void onIdleRateChanged(int rate) {}

        /** Called when host requests suspend. */
        default void onSuspend() {}

        /** Called when host requests exit suspend. */
        default void onExitSuspend() {}

        /** Called for virtual cable unplug. */
        default void onVirtualCableUnplug(HostConnection host) {}

        /** Called on errors. */
        default void onError(String message) {}

        /** Called for informational messages. */
        default void onMessage(String message) {}
    }

    // ==================== Host Connection ====================

    /**
     * Represents a connected HID Host.
     */
    public static class HostConnection {
        private final byte[] address;
        private final String addressString;
        private volatile AclConnection aclConnection;
        private volatile L2capChannel controlChannel;
        private volatile L2capChannel interruptChannel;
        private volatile boolean connected;
        private final long connectedAt;

        HostConnection(byte[] address) {
            this.address = address.clone();
            this.addressString = formatAddress(address);
            this.connectedAt = System.currentTimeMillis();
        }

        public byte[] getAddress() { return address.clone(); }
        public String getAddressString() { return addressString; }
        public boolean isConnected() { return connected && controlChannel != null && interruptChannel != null; }
        public long getConnectionAge() { return System.currentTimeMillis() - connectedAt; }

        void setControlChannel(L2capChannel channel) { this.controlChannel = channel; }
        void setInterruptChannel(L2capChannel channel) { this.interruptChannel = channel; }
        void setConnected(boolean connected) { this.connected = connected; }
        void setAclConnection(AclConnection conn) { this.aclConnection = conn; }

        L2capChannel getControlChannel() { return controlChannel; }
        L2capChannel getInterruptChannel() { return interruptChannel; }
        AclConnection getAclConnection() { return aclConnection; }

        private static String formatAddress(byte[] addr) {
            return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                    addr[5] & 0xFF, addr[4] & 0xFF, addr[3] & 0xFF,
                    addr[2] & 0xFF, addr[1] & 0xFF, addr[0] & 0xFF);
        }

        @Override
        public String toString() {
            return "HostConnection{" + addressString + ", connected=" + connected + "}";
        }
    }

    // ==================== Configuration ====================

    /**
     * HID Device configuration.
     */
    public static class Config {
        public final int deviceType;
        public final int subclass;
        public final String deviceName;
        public final String providerName;
        public final byte[] reportDescriptor;
        public final boolean supportsBootProtocol;
        public final boolean virtualCable;
        public final boolean reconnectInitiate;
        public final boolean batteryPowered;
        public final boolean remoteWake;
        public final boolean normallyConnectable;
        public final int countryCode;
        public final int parserVersion;
        public final int deviceRelease;
        public final int profileVersion;
        public final int supervisionTimeout;

        private Config(Builder builder) {
            this.deviceType = builder.deviceType;
            this.subclass = builder.subclass;
            this.deviceName = builder.deviceName;
            this.providerName = builder.providerName;
            this.reportDescriptor = builder.reportDescriptor;
            this.supportsBootProtocol = builder.supportsBootProtocol;
            this.virtualCable = builder.virtualCable;
            this.reconnectInitiate = builder.reconnectInitiate;
            this.batteryPowered = builder.batteryPowered;
            this.remoteWake = builder.remoteWake;
            this.normallyConnectable = builder.normallyConnectable;
            this.countryCode = builder.countryCode;
            this.parserVersion = builder.parserVersion;
            this.deviceRelease = builder.deviceRelease;
            this.profileVersion = builder.profileVersion;
            this.supervisionTimeout = builder.supervisionTimeout;
        }

        public static Builder keyboard() {
            return new Builder()
                    .deviceType(HidConstants.DEVICE_TYPE_KEYBOARD)
                    .subclass(HidConstants.SUBCLASS_BOOT_INTERFACE)
                    .supportsBootProtocol(true)
                    .reportDescriptor(ReportDescriptorTemplates.BOOT_KEYBOARD);
        }

        public static Builder mouse() {
            return new Builder()
                    .deviceType(HidConstants.DEVICE_TYPE_MOUSE)
                    .subclass(HidConstants.SUBCLASS_BOOT_INTERFACE)
                    .supportsBootProtocol(true)
                    .reportDescriptor(ReportDescriptorTemplates.BOOT_MOUSE);
        }

        public static Builder keyboardMouse() {
            return new Builder()
                    .deviceType(HidConstants.DEVICE_TYPE_KEYBOARD_MOUSE_COMBO)
                    .subclass(HidConstants.SUBCLASS_BOOT_INTERFACE)
                    .supportsBootProtocol(true)
                    .reportDescriptor(ReportDescriptorTemplates.KEYBOARD_MOUSE_COMBO);
        }

        public static Builder gamepad() {
            return new Builder()
                    .deviceType(HidConstants.DEVICE_TYPE_GAMEPAD)
                    .subclass(HidConstants.SUBCLASS_NONE)
                    .supportsBootProtocol(false)
                    .reportDescriptor(ReportDescriptorTemplates.GAMEPAD);
        }

        public static Builder custom() {
            return new Builder();
        }

        public static class Builder {
            private int deviceType = HidConstants.DEVICE_TYPE_UNKNOWN;
            private int subclass = HidConstants.SUBCLASS_NONE;
            private String deviceName = "Bluetooth HID Device";
            private String providerName = "CourierStack";
            private byte[] reportDescriptor;
            private boolean supportsBootProtocol = false;
            private boolean virtualCable = true;
            private boolean reconnectInitiate = true;
            private boolean batteryPowered = false;
            private boolean remoteWake = true;
            private boolean normallyConnectable = true;
            private int countryCode = 0;
            private int parserVersion = 0x0111;
            private int deviceRelease = 0x0100;
            private int profileVersion = 0x0111;
            private int supervisionTimeout = 3200;

            public Builder deviceType(int type) { this.deviceType = type; return this; }
            public Builder subclass(int subclass) { this.subclass = subclass; return this; }
            public Builder deviceName(String name) { this.deviceName = name; return this; }
            public Builder providerName(String name) { this.providerName = name; return this; }
            public Builder reportDescriptor(byte[] descriptor) { this.reportDescriptor = descriptor; return this; }
            public Builder supportsBootProtocol(boolean supported) { this.supportsBootProtocol = supported; return this; }
            public Builder virtualCable(boolean enabled) { this.virtualCable = enabled; return this; }
            public Builder reconnectInitiate(boolean enabled) { this.reconnectInitiate = enabled; return this; }
            public Builder batteryPowered(boolean battery) { this.batteryPowered = battery; return this; }
            public Builder remoteWake(boolean enabled) { this.remoteWake = enabled; return this; }
            public Builder normallyConnectable(boolean connectable) { this.normallyConnectable = connectable; return this; }
            public Builder countryCode(int code) { this.countryCode = code; return this; }
            public Builder parserVersion(int version) { this.parserVersion = version; return this; }
            public Builder deviceRelease(int release) { this.deviceRelease = release; return this; }
            public Builder profileVersion(int version) { this.profileVersion = version; return this; }
            public Builder supervisionTimeout(int timeout) { this.supervisionTimeout = timeout; return this; }

            public Config build() {
                if (reportDescriptor == null || reportDescriptor.length == 0) {
                    throw new IllegalStateException("Report descriptor is required");
                }
                return new Config(this);
            }
        }
    }

    // ==================== Constructor ====================

    /**
     * Creates a new HID Device Profile instance.
     *
     * @param l2capManager L2CAP manager for channel management
     * @param sdpDatabase  SDP database for service record registration
     */
    public HidDeviceProfile(L2capManager l2capManager, SdpDatabase sdpDatabase) {
        mL2capManager = Objects.requireNonNull(l2capManager, "l2capManager");
        mSdpDatabase = Objects.requireNonNull(sdpDatabase, "sdpDatabase");
        mExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "HID-Device");
            t.setDaemon(true);
            return t;
        });
    }

    // ==================== Configuration ====================

    /**
     * Configures the HID device profile.
     *
     * @param config device configuration
     */
    public void configure(Config config) {
        Objects.requireNonNull(config, "config");
        if (mStarted.get()) {
            throw new IllegalStateException("Cannot configure while started");
        }
        mConfig = config;
    }

    /**
     * Sets the event listener.
     */
    public void setListener(Listener listener) {
        mListener = listener;
    }

    /**
     * Adds an additional event listener.
     */
    public void addListener(Listener listener) {
        if (listener != null) {
            mAdditionalListeners.add(listener);
        }
    }

    /**
     * Removes an event listener.
     */
    public void removeListener(Listener listener) {
        mAdditionalListeners.remove(listener);
    }

    // ==================== Lifecycle ====================

    /**
     * Starts the HID Device profile.
     *
     * <p>Registers the SDP service record and begins accepting connections.
     *
     * @return true if started successfully
     */
    public boolean start() {
        if (mClosed.get()) {
            notifyError("HID Device is closed");
            return false;
        }

        if (mConfig == null) {
            notifyError("HID Device not configured");
            return false;
        }

        if (!mStarted.compareAndSet(false, true)) {
            return true; // Already started
        }

        // Register L2CAP listener
        mL2capManager.addListener(mL2capListener);

        // Register servers for HID Control and Interrupt channels
        mL2capManager.registerServer(L2capConstants.PSM_HID_CONTROL, new HidControlServerListener());
        mL2capManager.registerServer(L2capConstants.PSM_HID_INTERRUPT, new HidInterruptServerListener());

        // Register SDP service record
        mSdpHandle = registerSdpRecord();
        if (mSdpHandle < 0) {
            notifyError("Failed to register SDP record");
            stop();
            return false;
        }

        notifyMessage("HID Device started: " + mConfig.deviceName);
        return true;
    }

    /**
     * Stops the HID Device profile.
     */
    public void stop() {
        if (!mStarted.compareAndSet(true, false)) {
            return;
        }

        // Unregister SDP record
        if (mSdpHandle >= 0) {
            mSdpDatabase.unregisterService(mSdpHandle);
            mSdpHandle = -1;
        }

        // Unregister L2CAP servers
        mL2capManager.unregisterServer(L2capConstants.PSM_HID_CONTROL);
        mL2capManager.unregisterServer(L2capConstants.PSM_HID_INTERRUPT);

        // Disconnect all hosts
        for (HostConnection host : mHosts.values()) {
            disconnectHost(host);
        }

        // Remove listener
        mL2capManager.removeListener(mL2capListener);

        notifyMessage("HID Device stopped");
    }

    @Override
    public void close() {
        if (mClosed.compareAndSet(false, true)) {
            stop();
            mExecutor.shutdown();
            try {
                mExecutor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Returns whether the device profile is started.
     */
    public boolean isStarted() {
        return mStarted.get() && !mClosed.get();
    }

    // ==================== Input Report Sending ====================

    /**
     * Sends an input report to a connected host.
     *
     * @param host   target host connection
     * @param report input report to send
     * @return true if sent successfully
     */
    public boolean sendInputReport(HostConnection host, HidReport report) {
        if (!isStarted() || host == null || !host.isConnected()) {
            return false;
        }

        L2capChannel interruptChannel = host.getInterruptChannel();
        if (interruptChannel == null || !interruptChannel.isOpen()) {
            return false;
        }

        byte[] data = buildInputReportPacket(report);
        mL2capManager.sendData(interruptChannel, data);
        return true;
    }

    /**
     * Sends an input report to all connected hosts.
     *
     * @param report input report to send
     */
    public void sendInputReportToAll(HidReport report) {
        for (HostConnection host : mHosts.values()) {
            if (host.isConnected()) {
                sendInputReport(host, report);
            }
        }
    }

    /**
     * Sends a keyboard key press/release.
     *
     * @param host      target host
     * @param keyCode   HID key code (0 = no key)
     * @param modifiers modifier key flags
     * @return true if sent successfully
     */
    public boolean sendKeyPress(HostConnection host, int keyCode, int modifiers) {
        return sendKeyboard(host, modifiers, keyCode, 0, 0, 0, 0, 0);
    }

    /**
     * Sends a complete keyboard report.
     *
     * @param host      target host
     * @param modifiers modifier key flags
     * @param keys      up to 6 key codes
     * @return true if sent successfully
     */
    public boolean sendKeyboard(HostConnection host, int modifiers, int... keys) {
        byte[] data = new byte[8];
        data[0] = (byte) modifiers;
        data[1] = 0; // Reserved
        for (int i = 0; i < Math.min(keys.length, 6); i++) {
            data[2 + i] = (byte) keys[i];
        }
        return sendInputReport(host, HidReport.input(data));
    }

    /**
     * Sends a mouse movement report.
     *
     * @param host    target host
     * @param buttons button flags
     * @param dx      X movement (-127 to 127)
     * @param dy      Y movement (-127 to 127)
     * @param wheel   wheel movement (-127 to 127)
     * @return true if sent successfully
     */
    public boolean sendMouse(HostConnection host, int buttons, int dx, int dy, int wheel) {
        byte[] data = new byte[4];
        data[0] = (byte) buttons;
        data[1] = (byte) Math.max(-127, Math.min(127, dx));
        data[2] = (byte) Math.max(-127, Math.min(127, dy));
        data[3] = (byte) Math.max(-127, Math.min(127, wheel));
        return sendInputReport(host, HidReport.input(data));
    }

    /**
     * Sends mouse movement only.
     */
    public boolean sendMouseMove(HostConnection host, int dx, int dy, int wheel) {
        return sendMouse(host, 0, dx, dy, wheel);
    }

    /**
     * Sends mouse click.
     */
    public boolean sendMouseClick(HostConnection host, int buttons) {
        return sendMouse(host, buttons, 0, 0, 0);
    }

    // ==================== Host Management ====================

    /**
     * Returns all connected hosts.
     */
    public List<HostConnection> getConnectedHosts() {
        List<HostConnection> connected = new ArrayList<>();
        for (HostConnection host : mHosts.values()) {
            if (host.isConnected()) {
                connected.add(host);
            }
        }
        return connected;
    }

    /**
     * Returns the number of connected hosts.
     */
    public int getConnectedHostCount() {
        return (int) mHosts.values().stream().filter(HostConnection::isConnected).count();
    }

    /**
     * Disconnects a specific host.
     */
    public void disconnectHost(HostConnection host) {
        if (host == null) return;

        L2capChannel ctrl = host.getControlChannel();
        L2capChannel intr = host.getInterruptChannel();

        // Disconnect using connection handle with Remote User Terminated reason (0x13)
        if (intr != null && intr.connection != null) {
            mL2capManager.disconnect(intr.connection.handle, 0x13);
        }
        if (ctrl != null && ctrl.connection != null) {
            mL2capManager.disconnect(ctrl.connection.handle, 0x13);
        }
    }

    /**
     * Sends virtual cable unplug to a host.
     */
    public void sendVirtualCableUnplug(HostConnection host) {
        if (host == null || !host.isConnected()) return;

        L2capChannel ctrl = host.getControlChannel();
        if (ctrl != null && ctrl.isOpen()) {
            byte[] data = {HidConstants.createHeader(HidConstants.TRANS_HID_CONTROL,
                    HidConstants.CTRL_VIRTUAL_CABLE_UNPLUG)};
            mL2capManager.sendData(ctrl, data);
        }

        disconnectHost(host);
    }

    // ==================== Protocol Control ====================

    /**
     * Gets the current protocol mode.
     */
    public int getProtocolMode() {
        return mProtocolMode.get();
    }

    /**
     * Gets the current idle rate.
     */
    public int getIdleRate() {
        return mIdleRate;
    }

    /**
     * Returns whether the device is suspended.
     */
    public boolean isSuspended() {
        return mSuspended;
    }

    // ==================== SDP Registration ====================

    private int registerSdpRecord() {
        Config cfg = mConfig;
        if (cfg == null) return -1;

        // Build the HID SDP service record
        ServiceRecord record = new ServiceRecord();

        // Service Class ID List - HID UUID
        record.addServiceClassUuid(SdpConstants.UUID_HID);

        // Protocol Descriptor List: L2CAP -> HIDP (Control channel)
        record.setAttribute(SdpConstants.ATTR_PROTOCOL_DESCRIPTOR_LIST,
                buildHidProtocolDescriptor());

        // Additional Protocol Descriptor List: L2CAP -> HIDP (Interrupt channel)
        // This is REQUIRED for HID - hosts use this to find the interrupt PSM
        record.setAttribute(SdpConstants.ATTR_ADDITIONAL_PROTOCOL_DESC_LISTS,
                buildAdditionalProtocolDescriptor());

        // Service Name
        record.setServiceName(cfg.deviceName);
        record.setAttribute(SdpConstants.ATTR_SERVICE_NAME,
                SdpDataElement.encodeString(cfg.deviceName));

        // Provider Name
        if (cfg.providerName != null) {
            record.setProviderName(cfg.providerName);
            record.setAttribute(SdpConstants.ATTR_PROVIDER_NAME,
                    SdpDataElement.encodeString(cfg.providerName));
        }

        // Bluetooth Profile Descriptor List
        record.addProfileDescriptor(SdpConstants.UUID_HID, cfg.profileVersion);
        record.setAttribute(SdpConstants.ATTR_BT_PROFILE_DESCRIPTOR_LIST,
                buildProfileDescriptor(cfg.profileVersion));

        // HID-specific attributes
        record.setAttribute(HidConstants.SDP_ATTR_HID_DEVICE_RELEASE,
                SdpDataElement.encodeUint16(cfg.deviceRelease));
        record.setAttribute(HidConstants.SDP_ATTR_HID_PARSER_VERSION,
                SdpDataElement.encodeUint16(cfg.parserVersion));
        record.setAttribute(HidConstants.SDP_ATTR_HID_DEVICE_SUBCLASS,
                SdpDataElement.encodeUint8(cfg.subclass));
        record.setAttribute(HidConstants.SDP_ATTR_HID_COUNTRY_CODE,
                SdpDataElement.encodeUint8(cfg.countryCode));
        record.setAttribute(HidConstants.SDP_ATTR_HID_VIRTUAL_CABLE,
                SdpDataElement.encodeBoolean(cfg.virtualCable));
        record.setAttribute(HidConstants.SDP_ATTR_HID_RECONNECT_INITIATE,
                SdpDataElement.encodeBoolean(cfg.reconnectInitiate));
        record.setAttribute(HidConstants.SDP_ATTR_HID_DESCRIPTOR_LIST,
                buildDescriptorList(cfg.reportDescriptor));
        record.setAttribute(HidConstants.SDP_ATTR_HID_LANGID_BASE_LIST,
                buildLangIdBaseList());
        record.setAttribute(HidConstants.SDP_ATTR_HID_BATTERY_POWER,
                SdpDataElement.encodeBoolean(cfg.batteryPowered));
        record.setAttribute(HidConstants.SDP_ATTR_HID_REMOTE_WAKE,
                SdpDataElement.encodeBoolean(cfg.remoteWake));
        record.setAttribute(HidConstants.SDP_ATTR_HID_PROFILE_VERSION,
                SdpDataElement.encodeUint16(cfg.profileVersion));
        record.setAttribute(HidConstants.SDP_ATTR_HID_SUPERVISION_TIMEOUT,
                SdpDataElement.encodeUint16(cfg.supervisionTimeout));
        record.setAttribute(HidConstants.SDP_ATTR_HID_NORMALLY_CONNECTABLE,
                SdpDataElement.encodeBoolean(cfg.normallyConnectable));
        record.setAttribute(HidConstants.SDP_ATTR_HID_BOOT_DEVICE,
                SdpDataElement.encodeBoolean(cfg.supportsBootProtocol));

        // Browse group
        record.addBrowseGroupUuid(SdpConstants.UUID_PUBLIC_BROWSE_ROOT);

        return mSdpDatabase.registerService(record);
    }

    private byte[] buildHidProtocolDescriptor() {
        // L2CAP descriptor with Control PSM
        byte[] l2capControl = SdpDataElement.encodeSequence(
                SdpDataElement.encodeUuid(SdpConstants.UUID_L2CAP),
                SdpDataElement.encodeUint16(L2capConstants.PSM_HID_CONTROL)
        );

        // HIDP descriptor
        byte[] hidp = SdpDataElement.encodeSequence(
                SdpDataElement.encodeUuid(SdpConstants.UUID_HIDP)
        );

        return SdpDataElement.encodeSequence(l2capControl, hidp);
    }

    private byte[] buildAdditionalProtocolDescriptor() {
        // L2CAP descriptor with Interrupt PSM
        byte[] l2capInterrupt = SdpDataElement.encodeSequence(
                SdpDataElement.encodeUuid(SdpConstants.UUID_L2CAP),
                SdpDataElement.encodeUint16(L2capConstants.PSM_HID_INTERRUPT)
        );

        // HIDP descriptor
        byte[] hidp = SdpDataElement.encodeSequence(
                SdpDataElement.encodeUuid(SdpConstants.UUID_HIDP)
        );

        // The Additional Protocol Descriptor Lists is a sequence of protocol lists
        byte[] protoList = SdpDataElement.encodeSequence(l2capInterrupt, hidp);
        return SdpDataElement.encodeSequence(protoList);
    }

    private byte[] buildProfileDescriptor(int version) {
        byte[] profile = SdpDataElement.encodeSequence(
                SdpDataElement.encodeUuid(SdpConstants.UUID_HID),
                SdpDataElement.encodeUint16(version)
        );
        return SdpDataElement.encodeSequence(profile);
    }

    private byte[] buildDescriptorList(byte[] reportDescriptor) {
        // Descriptor type 0x22 = Report descriptor
        byte[] descriptorType = SdpDataElement.encodeUint8(0x22);
        byte[] descriptorValue = SdpDataElement.encodeString(new String(reportDescriptor,
                java.nio.charset.StandardCharsets.ISO_8859_1));

        byte[] descriptorPair = SdpDataElement.encodeSequence(descriptorType, descriptorValue);
        return SdpDataElement.encodeSequence(descriptorPair);
    }

    private byte[] buildLangIdBaseList() {
        // Language = English (US), Base = 0x0100
        byte[] langEntry = SdpDataElement.encodeSequence(
                SdpDataElement.encodeUint16(0x0409),
                SdpDataElement.encodeUint16(0x0100)
        );
        return SdpDataElement.encodeSequence(langEntry);
    }

    // ==================== Packet Building ====================

    private byte[] buildInputReportPacket(HidReport report) {
        byte header = HidConstants.createHeader(HidConstants.TRANS_DATA,
                HidConstants.REPORT_TYPE_INPUT);

        byte[] reportData = report.getData();
        byte[] packet;

        if (report.hasReportId()) {
            packet = new byte[2 + reportData.length];
            packet[0] = header;
            packet[1] = (byte) report.getReportId();
            System.arraycopy(reportData, 0, packet, 2, reportData.length);
        } else {
            packet = new byte[1 + reportData.length];
            packet[0] = header;
            System.arraycopy(reportData, 0, packet, 1, reportData.length);
        }

        return packet;
    }

    // ==================== L2CAP Event Handling ====================

    private class DeviceL2capListener implements IL2capListener {
        @Override
        public void onConnectionComplete(AclConnection connection) {}

        @Override
        public void onDisconnectionComplete(int handle, int reason) {
            // Find and clean up any hosts using this connection
            for (HostConnection host : mHosts.values()) {
                AclConnection acl = host.getAclConnection();
                if (acl != null && acl.handle == handle) {
                    cleanupHost(host, reason);
                    break;
                }
            }
        }

        @Override
        public void onChannelOpened(L2capChannel channel) {}

        @Override
        public void onChannelClosed(L2capChannel channel) {
            HostConnection host = mHostsByControlCid.get(channel.localCid);
            if (host == null) {
                host = mHostsByInterruptCid.get(channel.localCid);
            }
            if (host != null) {
                cleanupHost(host, 0);
            }
        }

        @Override
        public void onDataReceived(L2capChannel channel, byte[] data) {
            if (data == null || data.length < 1) return;

            HostConnection host = mHostsByControlCid.get(channel.localCid);
            if (host != null) {
                handleControlData(host, data);
            } else {
                host = mHostsByInterruptCid.get(channel.localCid);
                if (host != null) {
                    handleInterruptData(host, data);
                }
            }
        }

        @Override
        public void onError(String message) {
            notifyError(message);
        }
    }

    private class HidControlServerListener implements IL2capServerListener {
        @Override
        public boolean onConnectionRequest(L2capChannel channel) {
            return isStarted();
        }

        @Override
        public void onChannelOpened(L2capChannel channel) {
            handleControlChannelOpened(channel);
        }

        @Override
        public void onChannelClosed(L2capChannel channel) {
            handleControlChannelClosed(channel);
        }

        @Override
        public void onDataReceived(L2capChannel channel, byte[] data) {
            HostConnection host = mHostsByControlCid.get(channel.localCid);
            if (host != null) {
                handleControlData(host, data);
            }
        }

        @Override
        public void onError(L2capChannel channel, String message) {
            notifyError(message);
        }
    }

    private class HidInterruptServerListener implements IL2capServerListener {
        @Override
        public boolean onConnectionRequest(L2capChannel channel) {
            return isStarted();
        }

        @Override
        public void onChannelOpened(L2capChannel channel) {
            handleInterruptChannelOpened(channel);
        }

        @Override
        public void onChannelClosed(L2capChannel channel) {
            handleInterruptChannelClosed(channel);
        }

        @Override
        public void onDataReceived(L2capChannel channel, byte[] data) {
            HostConnection host = mHostsByInterruptCid.get(channel.localCid);
            if (host != null) {
                handleInterruptData(host, data);
            }
        }

        @Override
        public void onError(L2capChannel channel, String message) {
            notifyError(message);
        }
    }

    // ==================== Channel Handling ====================

    private void handleControlChannelOpened(L2capChannel channel) {
        String address = channel.connection.getFormattedAddress();
        HostConnection host = mHosts.get(address);

        if (host == null) {
            host = new HostConnection(channel.connection.getPeerAddress());
            host.setAclConnection(channel.connection);
            mHosts.put(address, host);
        }

        host.setControlChannel(channel);
        mHostsByControlCid.put(channel.localCid, host);

        checkHostConnected(host);
    }

    private void handleInterruptChannelOpened(L2capChannel channel) {
        String address = channel.connection.getFormattedAddress();
        HostConnection host = mHosts.get(address);

        if (host == null) {
            host = new HostConnection(channel.connection.getPeerAddress());
            host.setAclConnection(channel.connection);
            mHosts.put(address, host);
        }

        host.setInterruptChannel(channel);
        mHostsByInterruptCid.put(channel.localCid, host);

        checkHostConnected(host);
    }

    private void checkHostConnected(HostConnection host) {
        if (host.getControlChannel() != null && host.getInterruptChannel() != null) {
            host.setConnected(true);
            notifyHostConnected(host);
        }
    }

    private void handleControlChannelClosed(L2capChannel channel) {
        HostConnection host = mHostsByControlCid.remove(channel.localCid);
        if (host != null) {
            cleanupHost(host, 0);
        }
    }

    private void handleInterruptChannelClosed(L2capChannel channel) {
        HostConnection host = mHostsByInterruptCid.remove(channel.localCid);
        if (host != null) {
            cleanupHost(host, 0);
        }
    }

    private void cleanupHost(HostConnection host, int reason) {
        if (host.connected) {
            host.setConnected(false);
            notifyHostDisconnected(host, reason);
        }

        L2capChannel ctrl = host.getControlChannel();
        L2capChannel intr = host.getInterruptChannel();

        if (ctrl != null) {
            mHostsByControlCid.remove(ctrl.localCid);
        }
        if (intr != null) {
            mHostsByInterruptCid.remove(intr.localCid);
        }

        mHosts.remove(host.getAddressString());
    }

    // ==================== Data Handling ====================

    private void handleControlData(HostConnection host, byte[] data) {
        if (data.length < 1) return;

        int header = data[0] & 0xFF;
        int transType = HidConstants.getTransactionType(header);
        int param = HidConstants.getParameter(header);

        switch (transType) {
            case HidConstants.TRANS_HID_CONTROL:
                handleHidControl(host, param);
                break;
            case HidConstants.TRANS_GET_REPORT:
                handleGetReport(host, param, data);
                break;
            case HidConstants.TRANS_SET_REPORT:
                handleSetReport(host, param, data);
                break;
            case HidConstants.TRANS_GET_PROTOCOL:
                handleGetProtocol(host);
                break;
            case HidConstants.TRANS_SET_PROTOCOL:
                handleSetProtocol(host, param);
                break;
            case HidConstants.TRANS_GET_IDLE:
                handleGetIdle(host);
                break;
            case HidConstants.TRANS_SET_IDLE:
                handleSetIdle(host, data);
                break;
            default:
                sendHandshake(host, HidConstants.HANDSHAKE_ERR_UNSUPPORTED_REQUEST);
        }
    }

    private void handleInterruptData(HostConnection host, byte[] data) {
        if (data.length < 1) return;

        int header = data[0] & 0xFF;
        int transType = HidConstants.getTransactionType(header);
        int reportType = HidConstants.getParameter(header) & 0x03;

        if (transType == HidConstants.TRANS_DATA) {
            if (reportType == HidConstants.REPORT_TYPE_OUTPUT) {
                byte[] reportData = new byte[data.length - 1];
                System.arraycopy(data, 1, reportData, 0, reportData.length);
                HidReport report = HidReport.output(reportData);
                notifyOutputReport(host, report);
            }
        }
    }

    // ==================== Transaction Handlers ====================

    private void handleHidControl(HostConnection host, int param) {
        switch (param) {
            case HidConstants.CTRL_SUSPEND:
                mSuspended = true;
                notifySuspend();
                break;
            case HidConstants.CTRL_EXIT_SUSPEND:
                mSuspended = false;
                notifyExitSuspend();
                break;
            case HidConstants.CTRL_VIRTUAL_CABLE_UNPLUG:
                notifyVirtualCableUnplug(host);
                disconnectHost(host);
                break;
            case HidConstants.CTRL_HARD_RESET:
            case HidConstants.CTRL_SOFT_RESET:
                mProtocolMode.set(HidConstants.PROTOCOL_REPORT);
                mIdleRate = HidConstants.IDLE_RATE_INDEFINITE;
                mSuspended = false;
                break;
        }
    }

    private void handleGetReport(HostConnection host, int param, byte[] data) {
        int reportType = param & 0x03;
        boolean hasSize = (param & 0x08) != 0;

        int reportId = 0;
        if (data.length > 1) {
            reportId = data[1] & 0xFF;
        }

        byte[] reportData = null;
        if (reportType == HidConstants.REPORT_TYPE_FEATURE) {
            reportData = notifyGetFeatureReport(host, reportId);
        }

        if (reportData != null) {
            sendDataReport(host, reportType, reportId, reportData);
        } else {
            sendHandshake(host, HidConstants.HANDSHAKE_ERR_INVALID_REPORT_ID);
        }
    }

    private void handleSetReport(HostConnection host, int param, byte[] data) {
        int reportType = param & 0x03;

        if (data.length > 1) {
            byte[] reportData = new byte[data.length - 1];
            System.arraycopy(data, 1, reportData, 0, reportData.length);

            HidReport report = new HidReport(reportType, 0, reportData);

            if (reportType == HidConstants.REPORT_TYPE_OUTPUT) {
                notifyOutputReport(host, report);
            } else if (reportType == HidConstants.REPORT_TYPE_FEATURE) {
                notifySetFeatureReport(host, report);
            }

            sendHandshake(host, HidConstants.HANDSHAKE_SUCCESSFUL);
        } else {
            sendHandshake(host, HidConstants.HANDSHAKE_ERR_INVALID_PARAMETER);
        }
    }

    private void handleGetProtocol(HostConnection host) {
        byte[] response = new byte[2];
        response[0] = HidConstants.createHeader(HidConstants.TRANS_DATA, 0);
        response[1] = (byte) mProtocolMode.get();
        sendOnControl(host, response);
    }

    private void handleSetProtocol(HostConnection host, int protocol) {
        if (HidConstants.isValidProtocol(protocol)) {
            mProtocolMode.set(protocol);
            sendHandshake(host, HidConstants.HANDSHAKE_SUCCESSFUL);
            notifyProtocolChanged(protocol);
        } else {
            sendHandshake(host, HidConstants.HANDSHAKE_ERR_INVALID_PARAMETER);
        }
    }

    private void handleGetIdle(HostConnection host) {
        byte[] response = new byte[2];
        response[0] = HidConstants.createHeader(HidConstants.TRANS_DATA, 0);
        response[1] = (byte) mIdleRate;
        sendOnControl(host, response);
    }

    private void handleSetIdle(HostConnection host, byte[] data) {
        if (data.length > 1) {
            mIdleRate = data[1] & 0xFF;
            sendHandshake(host, HidConstants.HANDSHAKE_SUCCESSFUL);
            notifyIdleRateChanged(mIdleRate);
        } else {
            sendHandshake(host, HidConstants.HANDSHAKE_ERR_INVALID_PARAMETER);
        }
    }

    // ==================== Response Sending ====================

    private void sendHandshake(HostConnection host, int result) {
        byte[] data = {HidConstants.createHeader(HidConstants.TRANS_HANDSHAKE, result)};
        sendOnControl(host, data);
    }

    private void sendDataReport(HostConnection host, int reportType, int reportId, byte[] reportData) {
        int headerParam = reportType;
        byte header = HidConstants.createHeader(HidConstants.TRANS_DATA, headerParam);

        byte[] data;
        if (reportId != 0) {
            data = new byte[2 + reportData.length];
            data[0] = header;
            data[1] = (byte) reportId;
            System.arraycopy(reportData, 0, data, 2, reportData.length);
        } else {
            data = new byte[1 + reportData.length];
            data[0] = header;
            System.arraycopy(reportData, 0, data, 1, reportData.length);
        }

        sendOnControl(host, data);
    }

    private void sendOnControl(HostConnection host, byte[] data) {
        L2capChannel channel = host.getControlChannel();
        if (channel != null && channel.isOpen()) {
            mL2capManager.sendData(channel, data);
        }
    }

    // ==================== Notifications ====================

    private void notifyHostConnected(HostConnection host) {
        notifyMessage("Host connected: " + host.getAddressString());
        Listener l = mListener;
        if (l != null) l.onHostConnected(host);
        for (Listener listener : mAdditionalListeners) {
            listener.onHostConnected(host);
        }
    }

    private void notifyHostDisconnected(HostConnection host, int reason) {
        notifyMessage("Host disconnected: " + host.getAddressString());
        Listener l = mListener;
        if (l != null) l.onHostDisconnected(host, reason);
        for (Listener listener : mAdditionalListeners) {
            listener.onHostDisconnected(host, reason);
        }
    }

    private void notifyOutputReport(HostConnection host, HidReport report) {
        Listener l = mListener;
        if (l != null) l.onOutputReport(host, report);
        for (Listener listener : mAdditionalListeners) {
            listener.onOutputReport(host, report);
        }
    }

    private byte[] notifyGetFeatureReport(HostConnection host, int reportId) {
        Listener l = mListener;
        if (l != null) {
            byte[] data = l.onGetFeatureReport(host, reportId);
            if (data != null) return data;
        }
        for (Listener listener : mAdditionalListeners) {
            byte[] data = listener.onGetFeatureReport(host, reportId);
            if (data != null) return data;
        }
        return null;
    }

    private void notifySetFeatureReport(HostConnection host, HidReport report) {
        Listener l = mListener;
        if (l != null) l.onSetFeatureReport(host, report);
        for (Listener listener : mAdditionalListeners) {
            listener.onSetFeatureReport(host, report);
        }
    }

    private void notifyProtocolChanged(int protocol) {
        Listener l = mListener;
        if (l != null) l.onProtocolChanged(protocol);
        for (Listener listener : mAdditionalListeners) {
            listener.onProtocolChanged(protocol);
        }
    }

    private void notifyIdleRateChanged(int rate) {
        Listener l = mListener;
        if (l != null) l.onIdleRateChanged(rate);
        for (Listener listener : mAdditionalListeners) {
            listener.onIdleRateChanged(rate);
        }
    }

    private void notifySuspend() {
        Listener l = mListener;
        if (l != null) l.onSuspend();
        for (Listener listener : mAdditionalListeners) {
            listener.onSuspend();
        }
    }

    private void notifyExitSuspend() {
        Listener l = mListener;
        if (l != null) l.onExitSuspend();
        for (Listener listener : mAdditionalListeners) {
            listener.onExitSuspend();
        }
    }

    private void notifyVirtualCableUnplug(HostConnection host) {
        Listener l = mListener;
        if (l != null) l.onVirtualCableUnplug(host);
        for (Listener listener : mAdditionalListeners) {
            listener.onVirtualCableUnplug(host);
        }
    }

    private void notifyError(String message) {
        Listener l = mListener;
        if (l != null) l.onError(message);
        for (Listener listener : mAdditionalListeners) {
            listener.onError(message);
        }
    }

    private void notifyMessage(String message) {
        Listener l = mListener;
        if (l != null) l.onMessage(message);
        for (Listener listener : mAdditionalListeners) {
            listener.onMessage(message);
        }
    }

    // ==================== Status ====================

    public String getStatus() {
        return String.format("HidDeviceProfile{started=%s, hosts=%d, protocol=%s, suspended=%s}",
                mStarted.get(), getConnectedHostCount(),
                HidConstants.getProtocolName(mProtocolMode.get()), mSuspended);
    }
}