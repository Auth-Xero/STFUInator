package com.courierstack.sdp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Represents an SDP service record.
 *
 * <p>A service record contains a set of service attributes that describe
 * a Bluetooth service. Each attribute is identified by a 16-bit ID and
 * contains a data element value.
 *
 * <p>This class supports:
 * <ul>
 *   <li>Storing and retrieving raw attribute data</li>
 *   <li>Parsing common attributes (protocol descriptors, service class IDs, etc.)</li>
 *   <li>Building service records for registration</li>
 *   <li>Encoding/decoding for SDP protocol</li>
 * </ul>
 *
 * <p>Thread safety: This class is not thread-safe. External synchronization
 * is required for concurrent access.
 *
 * @see SdpConstants for standard attribute IDs
 */
public class ServiceRecord {

    // ==================== Parsed Fields (convenience access) ====================

    /** Service record handle (attribute 0x0000). */
    private int serviceRecordHandle = -1;

    /** Primary service class UUID (first UUID from service class ID list). */
    private UUID primaryServiceUuid;

    /** Human-readable service name (attribute 0x0100). */
    private String serviceName;

    /** Service description (attribute 0x0101). */
    private String serviceDescription;

    /** Provider name (attribute 0x0102). */
    private String providerName;

    /** RFCOMM channel number, or -1 if not applicable. */
    private int rfcommChannel = -1;

    /** L2CAP PSM, or -1 if not applicable. */
    private int l2capPsm = -1;

    /** GOEP L2CAP PSM (from GoepL2capPsm attribute), or -1 if not applicable. */
    private int goepL2capPsm = -1;

    /** All service class UUIDs. */
    private final List<UUID> serviceClassUuids = new ArrayList<>();

    /** Protocol UUIDs from the protocol descriptor list. */
    private final List<UUID> protocolUuids = new ArrayList<>();

    /** Profile descriptors (UUID -> version). */
    private final Map<UUID, Integer> profileDescriptors = new LinkedHashMap<>();

    /** Browse group UUIDs. */
    private final List<UUID> browseGroupUuids = new ArrayList<>();

    // ==================== Raw Attributes ====================

    /** Raw attribute data (attribute ID -> encoded data element). */
    private final Map<Integer, byte[]> attributes = new LinkedHashMap<>();

    // ==================== Constructors ====================

    /**
     * Creates an empty service record.
     */
    public ServiceRecord() {
    }

    /**
     * Creates a service record with a primary service UUID.
     *
     * @param serviceUuid primary service class UUID
     */
    public ServiceRecord(UUID serviceUuid) {
        this.primaryServiceUuid = serviceUuid;
        if (serviceUuid != null) {
            this.serviceClassUuids.add(serviceUuid);
        }
    }

    // ==================== Attribute Access ====================

    /**
     * Sets a raw attribute value.
     *
     * @param attributeId attribute ID
     * @param value       encoded data element value
     */
    public void setAttribute(int attributeId, byte[] value) {
        if (value != null) {
            attributes.put(attributeId, value.clone());
        } else {
            attributes.remove(attributeId);
        }
    }

    /**
     * Returns a raw attribute value.
     *
     * @param attributeId attribute ID
     * @return encoded data element, or null if not present
     */
    public byte[] getAttribute(int attributeId) {
        byte[] value = attributes.get(attributeId);
        return value != null ? value.clone() : null;
    }

    /**
     * Returns whether the service record contains an attribute.
     *
     * @param attributeId attribute ID
     * @return true if attribute is present
     */
    public boolean hasAttribute(int attributeId) {
        return attributes.containsKey(attributeId);
    }

    /**
     * Removes an attribute.
     *
     * @param attributeId attribute ID
     */
    public void removeAttribute(int attributeId) {
        attributes.remove(attributeId);
    }

    /**
     * Returns all attribute IDs in this record.
     *
     * @return unmodifiable set of attribute IDs
     */
    public Iterable<Integer> getAttributeIds() {
        return Collections.unmodifiableSet(attributes.keySet());
    }

    /**
     * Returns the number of attributes.
     *
     * @return attribute count
     */
    public int getAttributeCount() {
        return attributes.size();
    }

    // ==================== Parsed Field Getters ====================

    public int getServiceRecordHandle() {
        return serviceRecordHandle;
    }

    public void setServiceRecordHandle(int handle) {
        this.serviceRecordHandle = handle;
    }

    public UUID getPrimaryServiceUuid() {
        return primaryServiceUuid;
    }

    public void setPrimaryServiceUuid(UUID uuid) {
        this.primaryServiceUuid = uuid;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String name) {
        this.serviceName = name;
    }

    public String getServiceDescription() {
        return serviceDescription;
    }

    public void setServiceDescription(String description) {
        this.serviceDescription = description;
    }

    public String getProviderName() {
        return providerName;
    }

    public void setProviderName(String name) {
        this.providerName = name;
    }

    public int getRfcommChannel() {
        return rfcommChannel;
    }

    public void setRfcommChannel(int channel) {
        this.rfcommChannel = channel;
    }

    public int getL2capPsm() {
        return l2capPsm;
    }

    public void setL2capPsm(int psm) {
        this.l2capPsm = psm;
    }

    public int getGoepL2capPsm() {
        return goepL2capPsm;
    }

    public void setGoepL2capPsm(int psm) {
        this.goepL2capPsm = psm;
    }

    public List<UUID> getServiceClassUuids() {
        return Collections.unmodifiableList(serviceClassUuids);
    }

    public void addServiceClassUuid(UUID uuid) {
        if (uuid != null && !serviceClassUuids.contains(uuid)) {
            serviceClassUuids.add(uuid);
            if (primaryServiceUuid == null) {
                primaryServiceUuid = uuid;
            }
        }
    }

    public List<UUID> getProtocolUuids() {
        return Collections.unmodifiableList(protocolUuids);
    }

    public void addProtocolUuid(UUID uuid) {
        if (uuid != null && !protocolUuids.contains(uuid)) {
            protocolUuids.add(uuid);
        }
    }

    public Map<UUID, Integer> getProfileDescriptors() {
        return Collections.unmodifiableMap(profileDescriptors);
    }

    public void addProfileDescriptor(UUID profileUuid, int version) {
        profileDescriptors.put(profileUuid, version);
    }

    public List<UUID> getBrowseGroupUuids() {
        return Collections.unmodifiableList(browseGroupUuids);
    }

    public void addBrowseGroupUuid(UUID uuid) {
        if (uuid != null && !browseGroupUuids.contains(uuid)) {
            browseGroupUuids.add(uuid);
        }
    }

    // ==================== Convenience Checks ====================

    /**
     * Returns whether this service supports RFCOMM.
     *
     * @return true if RFCOMM channel is available
     */
    public boolean hasRfcomm() {
        return rfcommChannel >= 0;
    }

    /**
     * Returns whether this service supports L2CAP.
     *
     * @return true if L2CAP PSM is available
     */
    public boolean hasL2cap() {
        return l2capPsm >= 0;
    }

    /**
     * Returns whether this service supports GOEP over L2CAP.
     *
     * @return true if GOEP L2CAP PSM is available
     */
    public boolean hasGoepL2cap() {
        return goepL2capPsm >= 0;
    }

    /**
     * Returns whether this service matches the given UUID.
     *
     * @param uuid UUID to check
     * @return true if this service has the UUID in its service class list
     */
    public boolean matchesServiceClass(UUID uuid) {
        return uuid != null && serviceClassUuids.contains(uuid);
    }

    // ==================== Encoding ====================

    /**
     * Encodes this service record as an attribute list data element.
     *
     * <p>The returned byte array is a sequence of attribute ID/value pairs
     * suitable for SDP responses.
     *
     * @return encoded attribute list
     */
    public byte[] encode() {
        List<byte[]> elements = new ArrayList<>();

        for (Map.Entry<Integer, byte[]> entry : attributes.entrySet()) {
            // Attribute ID (16-bit uint)
            elements.add(SdpDataElement.encodeUint16(entry.getKey()));
            // Attribute value
            elements.add(entry.getValue());
        }

        return SdpDataElement.encodeSequence(elements);
    }

    /**
     * Encodes specific attributes as an attribute list.
     *
     * @param attributeIds attribute IDs to include
     * @return encoded attribute list
     */
    public byte[] encode(int... attributeIds) {
        List<byte[]> elements = new ArrayList<>();

        for (int attrId : attributeIds) {
            byte[] value = attributes.get(attrId);
            if (value != null) {
                elements.add(SdpDataElement.encodeUint16(attrId));
                elements.add(value);
            }
        }

        return SdpDataElement.encodeSequence(elements);
    }

    // ==================== Builder Pattern ====================

    /**
     * Creates a builder for constructing service records.
     *
     * @return new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing SDP service records.
     */
    public static class Builder {
        private final ServiceRecord record = new ServiceRecord();
        private int recordHandle = 0;
        private final List<UUID> serviceClasses = new ArrayList<>();
        private final List<ProtocolDescriptor> protocols = new ArrayList<>();
        private final List<ProfileDescriptor> profiles = new ArrayList<>();

        /**
         * Sets the service record handle.
         */
        public Builder serviceRecordHandle(int handle) {
            this.recordHandle = handle;
            return this;
        }

        /**
         * Adds a service class UUID.
         */
        public Builder serviceClass(UUID uuid) {
            if (uuid != null) {
                serviceClasses.add(uuid);
            }
            return this;
        }

        /**
         * Sets the service name.
         */
        public Builder serviceName(String name) {
            record.setServiceName(name);
            return this;
        }

        /**
         * Sets the service description.
         */
        public Builder serviceDescription(String description) {
            record.setServiceDescription(description);
            return this;
        }

        /**
         * Sets the provider name.
         */
        public Builder providerName(String name) {
            record.setProviderName(name);
            return this;
        }

        /**
         * Adds L2CAP protocol with PSM.
         */
        public Builder l2capProtocol(int psm) {
            protocols.add(new ProtocolDescriptor(SdpConstants.UUID_L2CAP, psm));
            record.setL2capPsm(psm);
            return this;
        }

        /**
         * Adds L2CAP protocol without specific PSM.
         */
        public Builder l2capProtocol() {
            protocols.add(new ProtocolDescriptor(SdpConstants.UUID_L2CAP, -1));
            return this;
        }

        /**
         * Adds RFCOMM protocol with channel number.
         */
        public Builder rfcommProtocol(int channel) {
            protocols.add(new ProtocolDescriptor(SdpConstants.UUID_RFCOMM, channel));
            record.setRfcommChannel(channel);
            return this;
        }

        /**
         * Adds a generic protocol descriptor.
         */
        public Builder protocol(UUID protocolUuid, int... params) {
            protocols.add(new ProtocolDescriptor(protocolUuid, params));
            return this;
        }

        /**
         * Adds a Bluetooth profile descriptor.
         */
        public Builder profileDescriptor(UUID profileUuid, int version) {
            profiles.add(new ProfileDescriptor(profileUuid, version));
            return this;
        }

        /**
         * Sets the browse group to PublicBrowseRoot.
         */
        public Builder publicBrowseRoot() {
            record.addBrowseGroupUuid(
                    UUID.fromString("00001002-0000-1000-8000-00805F9B34FB"));
            return this;
        }

        /**
         * Sets a custom attribute.
         */
        public Builder attribute(int attributeId, byte[] value) {
            record.setAttribute(attributeId, value);
            return this;
        }

        /**
         * Builds the service record.
         */
        public ServiceRecord build() {
            // Service Record Handle (0x0000)
            record.setServiceRecordHandle(recordHandle);
            record.setAttribute(SdpConstants.ATTR_SERVICE_RECORD_HANDLE,
                    SdpDataElement.encodeUint32(recordHandle));

            // Service Class ID List (0x0001)
            if (!serviceClasses.isEmpty()) {
                List<byte[]> uuidElements = new ArrayList<>();
                for (UUID uuid : serviceClasses) {
                    uuidElements.add(SdpDataElement.encodeUuid(uuid));
                    record.addServiceClassUuid(uuid);
                }
                record.setAttribute(SdpConstants.ATTR_SERVICE_CLASS_ID_LIST,
                        SdpDataElement.encodeSequence(uuidElements));
            }

            // Protocol Descriptor List (0x0004)
            if (!protocols.isEmpty()) {
                List<byte[]> protoElements = new ArrayList<>();
                for (ProtocolDescriptor proto : protocols) {
                    protoElements.add(proto.encode());
                    record.addProtocolUuid(proto.uuid);
                }
                record.setAttribute(SdpConstants.ATTR_PROTOCOL_DESCRIPTOR_LIST,
                        SdpDataElement.encodeSequence(protoElements));
            }

            // Browse Group List (0x0005)
            if (!record.browseGroupUuids.isEmpty()) {
                List<byte[]> browseElements = new ArrayList<>();
                for (UUID uuid : record.browseGroupUuids) {
                    browseElements.add(SdpDataElement.encodeUuid(uuid));
                }
                record.setAttribute(SdpConstants.ATTR_BROWSE_GROUP_LIST,
                        SdpDataElement.encodeSequence(browseElements));
            }

            // Bluetooth Profile Descriptor List (0x0009)
            if (!profiles.isEmpty()) {
                List<byte[]> profileElements = new ArrayList<>();
                for (ProfileDescriptor profile : profiles) {
                    profileElements.add(profile.encode());
                    record.addProfileDescriptor(profile.uuid, profile.version);
                }
                record.setAttribute(SdpConstants.ATTR_BT_PROFILE_DESCRIPTOR_LIST,
                        SdpDataElement.encodeSequence(profileElements));
            }

            // Service Name (0x0100)
            if (record.serviceName != null) {
                record.setAttribute(SdpConstants.ATTR_SERVICE_NAME,
                        SdpDataElement.encodeString(record.serviceName));
            }

            // Service Description (0x0101)
            if (record.serviceDescription != null) {
                record.setAttribute(SdpConstants.ATTR_SERVICE_DESCRIPTION,
                        SdpDataElement.encodeString(record.serviceDescription));
            }

            // Provider Name (0x0102)
            if (record.providerName != null) {
                record.setAttribute(SdpConstants.ATTR_PROVIDER_NAME,
                        SdpDataElement.encodeString(record.providerName));
            }

            return record;
        }

        private static class ProtocolDescriptor {
            final UUID uuid;
            final int[] params;

            ProtocolDescriptor(UUID uuid, int... params) {
                this.uuid = uuid;
                this.params = params;
            }

            byte[] encode() {
                List<byte[]> elements = new ArrayList<>();
                elements.add(SdpDataElement.encodeUuid(uuid));
                for (int param : params) {
                    if (param >= 0) {
                        elements.add(SdpDataElement.encodeUint8(param));
                    }
                }
                return SdpDataElement.encodeSequence(elements);
            }
        }

        private static class ProfileDescriptor {
            final UUID uuid;
            final int version;

            ProfileDescriptor(UUID uuid, int version) {
                this.uuid = uuid;
                this.version = version;
            }

            byte[] encode() {
                return SdpDataElement.encodeSequence(
                        SdpDataElement.encodeUuid(uuid),
                        SdpDataElement.encodeUint16(version)
                );
            }
        }
    }

    // ==================== Object Methods ====================

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ServiceRecord{");
        sb.append("handle=").append(serviceRecordHandle);

        if (primaryServiceUuid != null) {
            sb.append(", uuid=").append(SdpConstants.getServiceName(primaryServiceUuid));
        }

        if (serviceName != null) {
            sb.append(", name=\"").append(serviceName).append("\"");
        }

        if (rfcommChannel >= 0) {
            sb.append(", rfcomm=").append(rfcommChannel);
        }

        if (l2capPsm >= 0) {
            sb.append(", psm=0x").append(Integer.toHexString(l2capPsm));
        }

        if (goepL2capPsm >= 0) {
            sb.append(", goepPsm=0x").append(Integer.toHexString(goepL2capPsm));
        }

        sb.append(", attrs=").append(attributes.size());
        return sb.append("}").toString();
    }
}