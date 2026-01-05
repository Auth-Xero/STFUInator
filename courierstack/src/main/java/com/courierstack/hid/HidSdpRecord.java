package com.courierstack.hid;

import com.courierstack.l2cap.L2capConstants;
import com.courierstack.sdp.SdpConstants;
import com.courierstack.sdp.SdpDataElement;
import com.courierstack.sdp.SdpParser;
import com.courierstack.sdp.ServiceRecord;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for building and parsing HID SDP service records.
 *
 * <p>This class properly integrates with the SDP infrastructure to create
 * HID-specific service records with all required attributes per the
 * HID Profile specification.
 *
 * <p>Usage example:
 * <pre>{@code
 * ServiceRecord record = HidSdpRecord.builder()
 *     .deviceName("My Keyboard")
 *     .deviceType(HidConstants.DEVICE_TYPE_KEYBOARD)
 *     .subclass(HidConstants.SUBCLASS_BOOT_INTERFACE)
 *     .reportDescriptor(ReportDescriptorTemplates.BOOT_KEYBOARD)
 *     .supportsBootProtocol(true)
 *     .build();
 *
 * int handle = sdpDatabase.registerService(record);
 * }</pre>
 *
 * @see ServiceRecord
 * @see HidDeviceProfile
 */
public final class HidSdpRecord {

    private HidSdpRecord() {}

    /**
     * Creates a new builder for HID service records.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Parses HID-specific attributes from a service record.
     *
     * <p>Extracts HID information such as report descriptor, device subclass,
     * virtual cable support, etc.
     *
     * @param record service record to parse
     * @return parsed HID attributes, or null if not an HID record
     */
    public static HidAttributes parseAttributes(ServiceRecord record) {
        if (record == null) return null;

        HidAttributes attrs = new HidAttributes();

        // Device release
        byte[] data = record.getAttribute(HidConstants.SDP_ATTR_HID_DEVICE_RELEASE);
        if (data != null) {
            attrs.deviceRelease = parseUint16(data);
        }

        // Parser version
        data = record.getAttribute(HidConstants.SDP_ATTR_HID_PARSER_VERSION);
        if (data != null) {
            attrs.parserVersion = parseUint16(data);
        }

        // Subclass
        data = record.getAttribute(HidConstants.SDP_ATTR_HID_DEVICE_SUBCLASS);
        if (data != null) {
            attrs.subclass = parseUint8(data);
        }

        // Country code
        data = record.getAttribute(HidConstants.SDP_ATTR_HID_COUNTRY_CODE);
        if (data != null) {
            attrs.countryCode = parseUint8(data);
        }

        // Virtual cable
        data = record.getAttribute(HidConstants.SDP_ATTR_HID_VIRTUAL_CABLE);
        if (data != null) {
            attrs.virtualCable = parseBoolean(data);
        }

        // Reconnect initiate
        data = record.getAttribute(HidConstants.SDP_ATTR_HID_RECONNECT_INITIATE);
        if (data != null) {
            attrs.reconnectInitiate = parseBoolean(data);
        }

        // Report descriptor
        data = record.getAttribute(HidConstants.SDP_ATTR_HID_DESCRIPTOR_LIST);
        if (data != null) {
            attrs.reportDescriptor = parseDescriptorList(data);
        }

        // Battery power
        data = record.getAttribute(HidConstants.SDP_ATTR_HID_BATTERY_POWER);
        if (data != null) {
            attrs.batteryPowered = parseBoolean(data);
        }

        // Remote wake
        data = record.getAttribute(HidConstants.SDP_ATTR_HID_REMOTE_WAKE);
        if (data != null) {
            attrs.remoteWake = parseBoolean(data);
        }

        // Profile version
        data = record.getAttribute(HidConstants.SDP_ATTR_HID_PROFILE_VERSION);
        if (data != null) {
            attrs.profileVersion = parseUint16(data);
        }

        // Supervision timeout
        data = record.getAttribute(HidConstants.SDP_ATTR_HID_SUPERVISION_TIMEOUT);
        if (data != null) {
            attrs.supervisionTimeout = parseUint16(data);
        }

        // Normally connectable
        data = record.getAttribute(HidConstants.SDP_ATTR_HID_NORMALLY_CONNECTABLE);
        if (data != null) {
            attrs.normallyConnectable = parseBoolean(data);
        }

        // Boot device
        data = record.getAttribute(HidConstants.SDP_ATTR_HID_BOOT_DEVICE);
        if (data != null) {
            attrs.supportsBootProtocol = parseBoolean(data);
        }

        // Service name
        attrs.deviceName = record.getServiceName();

        return attrs;
    }

    /**
     * Parsed HID attributes from an SDP service record.
     */
    public static class HidAttributes {
        public String deviceName;
        public int deviceRelease;
        public int parserVersion;
        public int subclass;
        public int countryCode;
        public boolean virtualCable;
        public boolean reconnectInitiate;
        public byte[] reportDescriptor;
        public boolean batteryPowered;
        public boolean remoteWake;
        public int profileVersion;
        public int supervisionTimeout;
        public boolean normallyConnectable;
        public boolean supportsBootProtocol;

        /**
         * Determines the device type based on subclass.
         */
        public int getDeviceType() {
            if (subclass == HidConstants.SUBCLASS_BOOT_INTERFACE) {
                // Check descriptor for keyboard or mouse
                if (reportDescriptor != null) {
                    HidReportDescriptor parsed = HidReportDescriptor.parse(reportDescriptor);
                    return parsed.getDeviceType();
                }
            }
            return HidConstants.DEVICE_TYPE_UNKNOWN;
        }

        @Override
        public String toString() {
            return String.format("HidAttributes{name='%s', subclass=%d, virtualCable=%s, " +
                            "bootProtocol=%s, profileVersion=0x%04X}",
                    deviceName, subclass, virtualCable, supportsBootProtocol, profileVersion);
        }
    }

    // ==================== Builder ====================

    /**
     * Builder for constructing HID SDP service records.
     */
    public static class Builder {
        private String deviceName = "Bluetooth HID Device";
        private String providerName = null;
        private String description = null;
        private int deviceRelease = 0x0100;
        private int parserVersion = 0x0111;
        private int subclass = HidConstants.SUBCLASS_NONE;
        private int countryCode = 0;
        private boolean virtualCable = true;
        private boolean reconnectInitiate = true;
        private byte[] reportDescriptor;
        private boolean batteryPowered = false;
        private boolean remoteWake = true;
        private int profileVersion = 0x0111;
        private int supervisionTimeout = 3200;
        private boolean normallyConnectable = true;
        private boolean supportsBootProtocol = false;
        private int ssrHostMaxLatency = 0;
        private int ssrHostMinTimeout = 0;

        public Builder deviceName(String name) {
            this.deviceName = name;
            return this;
        }

        public Builder providerName(String name) {
            this.providerName = name;
            return this;
        }

        public Builder description(String desc) {
            this.description = desc;
            return this;
        }

        public Builder deviceRelease(int release) {
            this.deviceRelease = release;
            return this;
        }

        public Builder parserVersion(int version) {
            this.parserVersion = version;
            return this;
        }

        public Builder subclass(int subclass) {
            this.subclass = subclass;
            return this;
        }

        public Builder countryCode(int code) {
            this.countryCode = code;
            return this;
        }

        public Builder virtualCable(boolean enabled) {
            this.virtualCable = enabled;
            return this;
        }

        public Builder reconnectInitiate(boolean enabled) {
            this.reconnectInitiate = enabled;
            return this;
        }

        public Builder reportDescriptor(byte[] descriptor) {
            this.reportDescriptor = descriptor;
            return this;
        }

        public Builder batteryPowered(boolean battery) {
            this.batteryPowered = battery;
            return this;
        }

        public Builder remoteWake(boolean enabled) {
            this.remoteWake = enabled;
            return this;
        }

        public Builder profileVersion(int version) {
            this.profileVersion = version;
            return this;
        }

        public Builder supervisionTimeout(int timeout) {
            this.supervisionTimeout = timeout;
            return this;
        }

        public Builder normallyConnectable(boolean connectable) {
            this.normallyConnectable = connectable;
            return this;
        }

        public Builder supportsBootProtocol(boolean supported) {
            this.supportsBootProtocol = supported;
            return this;
        }

        public Builder ssrHostLatency(int maxLatency, int minTimeout) {
            this.ssrHostMaxLatency = maxLatency;
            this.ssrHostMinTimeout = minTimeout;
            return this;
        }

        /**
         * Configures as a keyboard device.
         */
        public Builder asKeyboard() {
            this.subclass = HidConstants.SUBCLASS_BOOT_INTERFACE;
            this.supportsBootProtocol = true;
            this.reportDescriptor = ReportDescriptorTemplates.BOOT_KEYBOARD;
            return this;
        }

        /**
         * Configures as a mouse device.
         */
        public Builder asMouse() {
            this.subclass = HidConstants.SUBCLASS_BOOT_INTERFACE;
            this.supportsBootProtocol = true;
            this.reportDescriptor = ReportDescriptorTemplates.BOOT_MOUSE;
            return this;
        }

        /**
         * Configures as a keyboard+mouse combo device.
         */
        public Builder asKeyboardMouse() {
            this.subclass = HidConstants.SUBCLASS_BOOT_INTERFACE;
            this.supportsBootProtocol = true;
            this.reportDescriptor = ReportDescriptorTemplates.KEYBOARD_MOUSE_COMBO;
            return this;
        }

        /**
         * Configures as a gamepad device.
         */
        public Builder asGamepad() {
            this.subclass = HidConstants.SUBCLASS_NONE;
            this.supportsBootProtocol = false;
            this.reportDescriptor = ReportDescriptorTemplates.GAMEPAD;
            return this;
        }

        /**
         * Builds the service record.
         *
         * @return configured HID service record
         * @throws IllegalStateException if report descriptor is not set
         */
        public ServiceRecord build() {
            if (reportDescriptor == null || reportDescriptor.length == 0) {
                throw new IllegalStateException("Report descriptor is required");
            }

            ServiceRecord record = new ServiceRecord();

            // Service Class ID List - HID UUID
            record.addServiceClassUuid(SdpConstants.UUID_HID);
            record.setAttribute(SdpConstants.ATTR_SERVICE_CLASS_ID_LIST,
                    SdpDataElement.encodeSequence(SdpDataElement.encodeUuid(SdpConstants.UUID_HID)));

            // Protocol Descriptor List
            record.setAttribute(SdpConstants.ATTR_PROTOCOL_DESCRIPTOR_LIST,
                    buildProtocolDescriptor());

            // Additional Protocol Descriptor List (for interrupt channel)
            record.setAttribute(SdpConstants.ATTR_ADDITIONAL_PROTOCOL_DESC_LISTS,
                    buildAdditionalProtocolDescriptor());

            // Language Base Attribute ID List
            record.setAttribute(SdpConstants.ATTR_LANGUAGE_BASE_ATTR_LIST,
                    buildLanguageBase());

            // Bluetooth Profile Descriptor List
            record.addProfileDescriptor(SdpConstants.UUID_HID, profileVersion);
            record.setAttribute(SdpConstants.ATTR_BT_PROFILE_DESCRIPTOR_LIST,
                    buildProfileDescriptor());

            // Browse Group List
            record.addBrowseGroupUuid(SdpConstants.UUID_PUBLIC_BROWSE_ROOT);
            record.setAttribute(SdpConstants.ATTR_BROWSE_GROUP_LIST,
                    SdpDataElement.encodeSequence(
                            SdpDataElement.encodeUuid(SdpConstants.UUID_PUBLIC_BROWSE_ROOT)));

            // Service Name
            record.setServiceName(deviceName);
            record.setAttribute(SdpConstants.ATTR_SERVICE_NAME,
                    SdpDataElement.encodeString(deviceName));

            // Service Description
            if (description != null) {
                record.setServiceDescription(description);
                record.setAttribute(SdpConstants.ATTR_SERVICE_DESCRIPTION,
                        SdpDataElement.encodeString(description));
            }

            // Provider Name
            if (providerName != null) {
                record.setProviderName(providerName);
                record.setAttribute(SdpConstants.ATTR_PROVIDER_NAME,
                        SdpDataElement.encodeString(providerName));
            }

            // HID-specific attributes
            record.setAttribute(HidConstants.SDP_ATTR_HID_DEVICE_RELEASE,
                    SdpDataElement.encodeUint16(deviceRelease));
            record.setAttribute(HidConstants.SDP_ATTR_HID_PARSER_VERSION,
                    SdpDataElement.encodeUint16(parserVersion));
            record.setAttribute(HidConstants.SDP_ATTR_HID_DEVICE_SUBCLASS,
                    SdpDataElement.encodeUint8(subclass));
            record.setAttribute(HidConstants.SDP_ATTR_HID_COUNTRY_CODE,
                    SdpDataElement.encodeUint8(countryCode));
            record.setAttribute(HidConstants.SDP_ATTR_HID_VIRTUAL_CABLE,
                    SdpDataElement.encodeBoolean(virtualCable));
            record.setAttribute(HidConstants.SDP_ATTR_HID_RECONNECT_INITIATE,
                    SdpDataElement.encodeBoolean(reconnectInitiate));
            record.setAttribute(HidConstants.SDP_ATTR_HID_DESCRIPTOR_LIST,
                    buildDescriptorList());
            record.setAttribute(HidConstants.SDP_ATTR_HID_LANGID_BASE_LIST,
                    buildLangIdBaseList());
            record.setAttribute(HidConstants.SDP_ATTR_HID_BATTERY_POWER,
                    SdpDataElement.encodeBoolean(batteryPowered));
            record.setAttribute(HidConstants.SDP_ATTR_HID_REMOTE_WAKE,
                    SdpDataElement.encodeBoolean(remoteWake));
            record.setAttribute(HidConstants.SDP_ATTR_HID_PROFILE_VERSION,
                    SdpDataElement.encodeUint16(profileVersion));
            record.setAttribute(HidConstants.SDP_ATTR_HID_SUPERVISION_TIMEOUT,
                    SdpDataElement.encodeUint16(supervisionTimeout));
            record.setAttribute(HidConstants.SDP_ATTR_HID_NORMALLY_CONNECTABLE,
                    SdpDataElement.encodeBoolean(normallyConnectable));
            record.setAttribute(HidConstants.SDP_ATTR_HID_BOOT_DEVICE,
                    SdpDataElement.encodeBoolean(supportsBootProtocol));

            // SSR Host Max Latency and Min Timeout (if set)
            if (ssrHostMaxLatency > 0) {
                record.setAttribute(HidConstants.SDP_ATTR_HID_SSR_HOST_MAX_LATENCY,
                        SdpDataElement.encodeUint16(ssrHostMaxLatency));
            }
            if (ssrHostMinTimeout > 0) {
                record.setAttribute(HidConstants.SDP_ATTR_HID_SSR_HOST_MIN_TIMEOUT,
                        SdpDataElement.encodeUint16(ssrHostMinTimeout));
            }

            return record;
        }

        private byte[] buildProtocolDescriptor() {
            // L2CAP (Control PSM) -> HIDP
            byte[] l2cap = SdpDataElement.encodeSequence(
                    SdpDataElement.encodeUuid(SdpConstants.UUID_L2CAP),
                    SdpDataElement.encodeUint16(L2capConstants.PSM_HID_CONTROL)
            );
            byte[] hidp = SdpDataElement.encodeSequence(
                    SdpDataElement.encodeUuid(SdpConstants.UUID_HIDP)
            );
            return SdpDataElement.encodeSequence(l2cap, hidp);
        }

        private byte[] buildAdditionalProtocolDescriptor() {
            // L2CAP (Interrupt PSM) -> HIDP
            byte[] l2cap = SdpDataElement.encodeSequence(
                    SdpDataElement.encodeUuid(SdpConstants.UUID_L2CAP),
                    SdpDataElement.encodeUint16(L2capConstants.PSM_HID_INTERRUPT)
            );
            byte[] hidp = SdpDataElement.encodeSequence(
                    SdpDataElement.encodeUuid(SdpConstants.UUID_HIDP)
            );
            byte[] protoList = SdpDataElement.encodeSequence(l2cap, hidp);
            return SdpDataElement.encodeSequence(protoList);
        }

        private byte[] buildProfileDescriptor() {
            byte[] profile = SdpDataElement.encodeSequence(
                    SdpDataElement.encodeUuid(SdpConstants.UUID_HID),
                    SdpDataElement.encodeUint16(profileVersion)
            );
            return SdpDataElement.encodeSequence(profile);
        }

        private byte[] buildLanguageBase() {
            // Language = English (0x656E), Encoding = UTF-8 (106), Base = 0x0100
            byte[] entry = SdpDataElement.encodeSequence(
                    SdpDataElement.encodeUint16(0x656E),
                    SdpDataElement.encodeUint16(106),
                    SdpDataElement.encodeUint16(0x0100)
            );
            return SdpDataElement.encodeSequence(entry);
        }

        private byte[] buildDescriptorList() {
            // Descriptor type 0x22 = Report descriptor
            // The descriptor list is a sequence containing a sequence of
            // (descriptor type, descriptor value) pairs
            byte[] descriptorType = SdpDataElement.encodeUint8(0x22);

            // The descriptor itself is encoded as a string (byte sequence)
            byte[] descriptorValue = encodeDescriptorValue(reportDescriptor);

            byte[] pair = SdpDataElement.encodeSequence(descriptorType, descriptorValue);
            return SdpDataElement.encodeSequence(pair);
        }

        private byte[] encodeDescriptorValue(byte[] descriptor) {
            // Encode as SDP string (which is just a byte sequence)
            int length = descriptor.length;
            ByteBuffer buf;

            if (length <= 0xFF) {
                buf = ByteBuffer.allocate(2 + length).order(ByteOrder.BIG_ENDIAN);
                buf.put((byte) ((SdpConstants.DE_STRING << 3) | 5));
                buf.put((byte) length);
            } else {
                buf = ByteBuffer.allocate(3 + length).order(ByteOrder.BIG_ENDIAN);
                buf.put((byte) ((SdpConstants.DE_STRING << 3) | 6));
                buf.putShort((short) length);
            }

            buf.put(descriptor);
            return buf.array();
        }

        private byte[] buildLangIdBaseList() {
            // Language = English (US) 0x0409, Base = 0x0100
            byte[] entry = SdpDataElement.encodeSequence(
                    SdpDataElement.encodeUint16(0x0409),
                    SdpDataElement.encodeUint16(0x0100)
            );
            return SdpDataElement.encodeSequence(entry);
        }
    }

    // ==================== Parsing Helpers ====================

    private static int parseUint8(byte[] data) {
        if (data == null || data.length < 2) return 0;
        // Skip the type header byte
        return data[1] & 0xFF;
    }

    private static int parseUint16(byte[] data) {
        if (data == null || data.length < 3) return 0;
        // Skip the type header byte
        return ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
    }

    private static boolean parseBoolean(byte[] data) {
        if (data == null || data.length < 2) return false;
        return data[1] != 0;
    }

    private static byte[] parseDescriptorList(byte[] data) {
        if (data == null || data.length < 5) return null;

        // The descriptor list is a sequence containing sequences.
        // We need to find the report descriptor (type 0x22) within it.
        try {
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

            // Skip outer sequence header
            int header = buf.get() & 0xFF;
            int type = header >> 3;
            if (type != SdpConstants.DE_SEQ) return null;

            int sizeIndex = header & 0x07;
            skipLengthField(buf, sizeIndex);

            // Now parse inner sequence(s)
            while (buf.hasRemaining()) {
                int pos = buf.position();
                header = buf.get() & 0xFF;
                type = header >> 3;

                if (type == SdpConstants.DE_SEQ) {
                    sizeIndex = header & 0x07;
                    int seqLen = readLengthField(buf, sizeIndex);
                    int seqEnd = buf.position() + seqLen;

                    // First element should be descriptor type
                    if (buf.hasRemaining()) {
                        int typeHeader = buf.get() & 0xFF;
                        if ((typeHeader >> 3) == SdpConstants.DE_UINT) {
                            int descType = buf.get() & 0xFF;
                            if (descType == 0x22) { // Report descriptor
                                // Next is the descriptor value
                                if (buf.hasRemaining()) {
                                    int valHeader = buf.get() & 0xFF;
                                    int valType = valHeader >> 3;
                                    if (valType == SdpConstants.DE_STRING) {
                                        int valSizeIndex = valHeader & 0x07;
                                        int valLen = readLengthField(buf, valSizeIndex);
                                        byte[] descriptor = new byte[valLen];
                                        buf.get(descriptor);
                                        return descriptor;
                                    }
                                }
                            }
                        }
                    }
                    buf.position(seqEnd);
                } else {
                    // Skip this element
                    buf.position(pos);
                    SdpDataElement.skipElement(buf);
                }
            }
        } catch (Exception e) {
            // Parsing failed
        }
        return null;
    }

    private static void skipLengthField(ByteBuffer buf, int sizeIndex) {
        readLengthField(buf, sizeIndex);
    }

    private static int readLengthField(ByteBuffer buf, int sizeIndex) {
        switch (sizeIndex) {
            case 5: return buf.get() & 0xFF;
            case 6: return buf.getShort() & 0xFFFF;
            case 7: return buf.getInt();
            default: return 0;
        }
    }
}
