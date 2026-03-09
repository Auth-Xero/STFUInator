package com.courierstack.hid;

import com.courierstack.l2cap.AclConnection;
import com.courierstack.l2cap.L2capChannel;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents a connected HID device (when acting as HID Host).
 *
 * <p>This class maintains the state of an HID connection including:
 * <ul>
 *   <li>L2CAP channels (Control and Interrupt)</li>
 *   <li>Protocol mode (Boot or Report)</li>
 *   <li>Device information (name, type, capabilities)</li>
 *   <li>HID report descriptor</li>
 *   <li>Connection state</li>
 * </ul>
 *
 * <p>Thread safety: This class uses atomic operations for state management.
 *
 * @see HidManager
 * @see HidDeviceProfile for emulating HID devices
 */
public class HidDevice {

    /**
     * HID device connection states.
     */
    public enum State {
        DISCONNECTED,
        CONNECTING_CONTROL,
        CONNECTING_INTERRUPT,
        CONFIGURING,
        CONNECTED,
        DISCONNECTING
    }

    // Identity
    private final String deviceId;
    private final byte[] address;
    private volatile AclConnection aclConnection;
    public final long createdAt;

    // L2CAP Channels
    private volatile L2capChannel controlChannel;
    private volatile L2capChannel interruptChannel;

    // Device State
    private final AtomicReference<State> state;
    private final AtomicInteger protocolMode;
    private volatile int idleRate;
    private volatile boolean suspended;
    private volatile long lastActivityTime;

    // Device Information
    private volatile String name;
    private volatile int deviceType;
    private volatile int bootProtocol;
    private volatile int subclass;
    private volatile int countryCode;
    private volatile boolean supportsVirtualCable;
    private volatile boolean supportsReconnectInitiate;
    private volatile boolean batteryPowered;
    private volatile boolean supportsRemoteWake;
    private volatile int profileVersion;
    private volatile int supervisionTimeout;
    private volatile boolean normallyConnectable;
    private volatile boolean supportsBootProtocol;
    private volatile int deviceReleaseVersion;
    private volatile int parserVersion;

    // HID Descriptor
    private volatile byte[] reportDescriptor;
    private volatile HidReportDescriptor parsedDescriptor;

    // ==================== Constructors ====================

    public HidDevice(byte[] address) {
        Objects.requireNonNull(address, "address must not be null");
        if (address.length != 6) {
            throw new IllegalArgumentException("address must be 6 bytes");
        }

        this.address = address.clone();
        this.deviceId = formatAddress(address);
        this.createdAt = System.currentTimeMillis();
        this.lastActivityTime = createdAt;

        this.state = new AtomicReference<>(State.DISCONNECTED);
        this.protocolMode = new AtomicInteger(HidConstants.PROTOCOL_REPORT);
        this.idleRate = HidConstants.IDLE_RATE_INDEFINITE;

        this.deviceType = HidConstants.DEVICE_TYPE_UNKNOWN;
        this.bootProtocol = HidConstants.BOOT_PROTOCOL_NONE;
        this.profileVersion = 0x0111;
    }

    public HidDevice(AclConnection connection) {
        this(connection.getPeerAddress());
        this.aclConnection = connection;
    }

    // ==================== Identity ====================

    public String getDeviceId() { return deviceId; }
    public byte[] getAddress() { return address.clone(); }
    public String getFormattedAddress() { return deviceId; }

    public boolean matchesAddress(byte[] otherAddress) {
        return otherAddress != null && Arrays.equals(address, otherAddress);
    }

    // ==================== ACL Connection ====================

    public AclConnection getAclConnection() { return aclConnection; }
    public void setAclConnection(AclConnection connection) { this.aclConnection = connection; }

    public int getConnectionHandle() {
        AclConnection conn = aclConnection;
        return conn != null ? conn.handle : -1;
    }

    // ==================== L2CAP Channels ====================

    public L2capChannel getControlChannel() { return controlChannel; }
    public void setControlChannel(L2capChannel channel) { this.controlChannel = channel; }

    public L2capChannel getInterruptChannel() { return interruptChannel; }
    public void setInterruptChannel(L2capChannel channel) { this.interruptChannel = channel; }

    // ==================== State Management ====================

    public State getState() { return state.get(); }
    public void setState(State newState) { state.set(newState); }

    public boolean compareAndSetState(State expected, State newState) {
        return state.compareAndSet(expected, newState);
    }

    public boolean isConnected() { return state.get() == State.CONNECTED; }
    public boolean isConnecting() {
        State s = state.get();
        return s == State.CONNECTING_CONTROL || s == State.CONNECTING_INTERRUPT || s == State.CONFIGURING;
    }

    // ==================== Protocol ====================

    public int getProtocolMode() { return protocolMode.get(); }
    public void setProtocolMode(int mode) { protocolMode.set(mode); }
    public boolean isBootProtocol() { return protocolMode.get() == HidConstants.PROTOCOL_BOOT; }
    public boolean isReportProtocol() { return protocolMode.get() == HidConstants.PROTOCOL_REPORT; }

    public String getProtocolModeName() {
        return HidConstants.getProtocolName(protocolMode.get());
    }

    public int getIdleRate() { return idleRate; }
    public void setIdleRate(int rate) { this.idleRate = rate; }

    public boolean isSuspended() { return suspended; }
    public void setSuspended(boolean suspended) { this.suspended = suspended; }

    // ==================== Device Information ====================

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getDeviceType() { return deviceType; }
    public void setDeviceType(int type) { this.deviceType = type; }

    public String getDeviceTypeName() {
        return HidConstants.getDeviceTypeName(deviceType);
    }

    public int getBootProtocol() { return bootProtocol; }
    public void setBootProtocol(int protocol) { this.bootProtocol = protocol; }

    public int getSubclass() { return subclass; }
    public void setSubclass(int subclass) { this.subclass = subclass; }

    public int getCountryCode() { return countryCode; }
    public void setCountryCode(int code) { this.countryCode = code; }

    public boolean isSupportsVirtualCable() { return supportsVirtualCable; }
    public void setSupportsVirtualCable(boolean supports) { this.supportsVirtualCable = supports; }

    public boolean isSupportsReconnectInitiate() { return supportsReconnectInitiate; }
    public void setSupportsReconnectInitiate(boolean supports) { this.supportsReconnectInitiate = supports; }

    public boolean isBatteryPowered() { return batteryPowered; }
    public void setBatteryPowered(boolean battery) { this.batteryPowered = battery; }

    public boolean isSupportsRemoteWake() { return supportsRemoteWake; }
    public void setSupportsRemoteWake(boolean supports) { this.supportsRemoteWake = supports; }

    public int getProfileVersion() { return profileVersion; }
    public void setProfileVersion(int version) { this.profileVersion = version; }

    public String getProfileVersionString() {
        return String.format("%d.%d.%d",
                (profileVersion >> 8) & 0xFF,
                (profileVersion >> 4) & 0x0F,
                profileVersion & 0x0F);
    }

    public int getSupervisionTimeout() { return supervisionTimeout; }
    public void setSupervisionTimeout(int timeout) { this.supervisionTimeout = timeout; }

    public boolean isNormallyConnectable() { return normallyConnectable; }
    public void setNormallyConnectable(boolean connectable) { this.normallyConnectable = connectable; }

    public boolean isSupportsBootProtocol() { return supportsBootProtocol; }
    public void setSupportsBootProtocol(boolean supports) { this.supportsBootProtocol = supports; }

    public int getDeviceReleaseVersion() { return deviceReleaseVersion; }
    public void setDeviceReleaseVersion(int version) { this.deviceReleaseVersion = version; }

    public int getParserVersion() { return parserVersion; }
    public void setParserVersion(int version) { this.parserVersion = version; }

    // ==================== Device Type Helpers ====================

    public boolean isKeyboard() {
        return deviceType == HidConstants.DEVICE_TYPE_KEYBOARD ||
                deviceType == HidConstants.DEVICE_TYPE_KEYBOARD_MOUSE_COMBO ||
                bootProtocol == HidConstants.BOOT_PROTOCOL_KEYBOARD;
    }

    public boolean isMouse() {
        return deviceType == HidConstants.DEVICE_TYPE_MOUSE ||
                deviceType == HidConstants.DEVICE_TYPE_KEYBOARD_MOUSE_COMBO ||
                bootProtocol == HidConstants.BOOT_PROTOCOL_MOUSE;
    }

    public boolean isGamepad() {
        return deviceType == HidConstants.DEVICE_TYPE_GAMEPAD ||
                deviceType == HidConstants.DEVICE_TYPE_JOYSTICK;
    }

    // ==================== Channel Helpers ====================

    /**
     * Returns whether this device has a control channel connected.
     *
     * @return true if control channel is set
     */
    public boolean hasControlChannel() {
        return controlChannel != null;
    }

    /**
     * Returns whether this device has an interrupt channel connected.
     *
     * @return true if interrupt channel is set
     */
    public boolean hasInterruptChannel() {
        return interruptChannel != null;
    }

    /**
     * Returns whether this device uses report IDs.
     *
     * <p>This checks the parsed report descriptor to determine if report IDs are used.
     * If no descriptor is available, returns false.
     *
     * @return true if the device uses report IDs
     */
    public boolean usesReportIds() {
        HidReportDescriptor desc = parsedDescriptor;
        if (desc != null) {
            return desc.usesReportIds();
        }
        return false;
    }

    /**
     * Alias for isSupportsBootProtocol() for convenience.
     *
     * @return true if boot protocol is supported
     */
    public boolean supportsBootProtocol() {
        return supportsBootProtocol;
    }

    // ==================== Report Descriptor ====================

    public boolean hasReportDescriptor() {
        return reportDescriptor != null && reportDescriptor.length > 0;
    }

    public byte[] getReportDescriptor() {
        return reportDescriptor != null ? reportDescriptor.clone() : null;
    }

    public void setReportDescriptor(byte[] descriptor) {
        this.reportDescriptor = descriptor != null ? descriptor.clone() : null;
        if (descriptor != null && descriptor.length > 0) {
            try {
                this.parsedDescriptor = HidReportDescriptor.parse(descriptor);
                // Update device type from descriptor if not already set
                if (deviceType == HidConstants.DEVICE_TYPE_UNKNOWN) {
                    deviceType = parsedDescriptor.getDeviceType();
                }
            } catch (Exception e) {
                this.parsedDescriptor = null;
            }
        } else {
            this.parsedDescriptor = null;
        }
    }

    public HidReportDescriptor getParsedDescriptor() {
        return parsedDescriptor;
    }

    // ==================== Activity Tracking ====================

    public long getLastActivityTime() { return lastActivityTime; }
    public void updateActivityTime() { this.lastActivityTime = System.currentTimeMillis(); }
    public long getIdleTimeMs() { return System.currentTimeMillis() - lastActivityTime; }
    public long getConnectionAgeMs() { return System.currentTimeMillis() - createdAt; }

    // ==================== Object Methods ====================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HidDevice that = (HidDevice) o;
        return Arrays.equals(address, that.address);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(address);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("HidDevice{");
        sb.append("addr=").append(deviceId);
        if (name != null) {
            sb.append(", name='").append(name).append("'");
        }
        sb.append(", type=").append(getDeviceTypeName());
        sb.append(", state=").append(state.get());
        sb.append(", protocol=").append(getProtocolModeName());
        if (hasReportDescriptor()) {
            sb.append(", descriptor=").append(reportDescriptor.length).append("B");
        }
        sb.append("}");
        return sb.toString();
    }

    public String toDetailedString() {
        StringBuilder sb = new StringBuilder();
        sb.append("HID Device: ").append(deviceId).append("\n");
        if (name != null) sb.append("  Name: ").append(name).append("\n");
        sb.append("  Type: ").append(getDeviceTypeName()).append("\n");
        sb.append("  State: ").append(state.get()).append("\n");
        sb.append("  Protocol: ").append(getProtocolModeName()).append("\n");
        sb.append("  Profile Version: ").append(getProfileVersionString()).append("\n");
        if (deviceReleaseVersion != 0) {
            sb.append("  Device Release: 0x").append(Integer.toHexString(deviceReleaseVersion)).append("\n");
        }
        sb.append("  Capabilities: VirtualCable=").append(supportsVirtualCable);
        sb.append(", RemoteWake=").append(supportsRemoteWake);
        sb.append(", BootProtocol=").append(supportsBootProtocol).append("\n");
        if (hasReportDescriptor()) {
            sb.append("  Report Descriptor: ").append(reportDescriptor.length).append(" bytes\n");
        }
        return sb.toString();
    }

    // ==================== Utility Methods ====================

    private static String formatAddress(byte[] addr) {
        return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                addr[5] & 0xFF, addr[4] & 0xFF, addr[3] & 0xFF,
                addr[2] & 0xFF, addr[1] & 0xFF, addr[0] & 0xFF);
    }

    public void reset() {
        controlChannel = null;
        interruptChannel = null;
        state.set(State.DISCONNECTED);
        suspended = false;
    }

    // ==================== Builder ====================

    public static Builder builder(byte[] address) {
        return new Builder(address);
    }

    public static class Builder {
        private final byte[] address;
        private String name;
        private int deviceType = HidConstants.DEVICE_TYPE_UNKNOWN;
        private int bootProtocol = HidConstants.BOOT_PROTOCOL_NONE;
        private int subclass = HidConstants.SUBCLASS_NONE;
        private boolean virtualCable = false;
        private boolean remoteWake = false;
        private boolean bootProtocolSupported = false;
        private int profileVersion = 0x0111;
        private byte[] reportDescriptor;

        private Builder(byte[] address) {
            this.address = address;
        }

        public Builder name(String name) { this.name = name; return this; }
        public Builder deviceType(int type) { this.deviceType = type; return this; }
        public Builder bootProtocol(int protocol) { this.bootProtocol = protocol; return this; }
        public Builder subclass(int subclass) { this.subclass = subclass; return this; }
        public Builder virtualCable(boolean supported) { this.virtualCable = supported; return this; }
        public Builder remoteWake(boolean supported) { this.remoteWake = supported; return this; }
        public Builder bootProtocolSupported(boolean supported) { this.bootProtocolSupported = supported; return this; }
        public Builder profileVersion(int version) { this.profileVersion = version; return this; }
        public Builder reportDescriptor(byte[] descriptor) { this.reportDescriptor = descriptor; return this; }

        public HidDevice build() {
            HidDevice device = new HidDevice(address);
            device.setName(name);
            device.setDeviceType(deviceType);
            device.setBootProtocol(bootProtocol);
            device.setSubclass(subclass);
            device.setSupportsVirtualCable(virtualCable);
            device.setSupportsRemoteWake(remoteWake);
            device.setSupportsBootProtocol(bootProtocolSupported);
            device.setProfileVersion(profileVersion);
            if (reportDescriptor != null) {
                device.setReportDescriptor(reportDescriptor);
            }
            return device;
        }
    }
}