package com.courierstack.sdp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Parser for SDP protocol data units and attribute lists.
 *
 * <p>This class provides methods for parsing:
 * <ul>
 *   <li>SDP PDU headers</li>
 *   <li>Service attribute lists</li>
 *   <li>Protocol descriptor lists</li>
 *   <li>Service class ID lists</li>
 *   <li>Profile descriptor lists</li>
 * </ul>
 *
 * <p>Thread safety: This class is stateless and thread-safe.
 */
public final class SdpParser {

    private static final String TAG = "SdpParser";

    private SdpParser() {
        // Utility class
    }

    // ==================== PDU Parsing ====================

    /**
     * Parsed SDP PDU header.
     */
    public static class PduHeader {
        public final int pduId;
        public final int transactionId;
        public final int parameterLength;

        PduHeader(int pduId, int transactionId, int parameterLength) {
            this.pduId = pduId;
            this.transactionId = transactionId;
            this.parameterLength = parameterLength;
        }
    }

    /**
     * Parses an SDP PDU header.
     *
     * @param data raw PDU data (at least 5 bytes)
     * @return parsed header, or null if data is too short
     */
    public static PduHeader parsePduHeader(byte[] data) {
        if (data == null || data.length < 5) return null;

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int pduId = buf.get() & 0xFF;
        int transactionId = buf.getShort() & 0xFFFF;
        int parameterLength = buf.getShort() & 0xFFFF;

        return new PduHeader(pduId, transactionId, parameterLength);
    }

    /**
     * Parses an SDP Error Response.
     *
     * @param data PDU data starting after the header
     * @return error code, or -1 if invalid
     */
    public static int parseErrorResponse(byte[] data) {
        if (data == null || data.length < 2) return -1;
        return ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
    }

    // ==================== Service Record Parsing ====================

    /**
     * Parses a ServiceSearchAttributeResponse attribute list.
     *
     * <p>The data should be the attribute list bytes from the response
     * (after the AttributeListByteCount).
     *
     * @param data      attribute list data
     * @param targetUuid UUID that was searched for (may be null)
     * @return list of parsed service records
     */
    public static List<ServiceRecord> parseAttributeListResponse(byte[] data, UUID targetUuid) {
        List<ServiceRecord> records = new ArrayList<>();
        if (data == null || data.length == 0) return records;

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        // Outer sequence contains attribute lists for each matching service
        int outerHeader = buf.get() & 0xFF;
        int outerType = outerHeader >> 3;

        if (outerType != SdpConstants.DE_SEQ) {
            // Single service record
            buf.position(0);
            ServiceRecord record = parseAttributeList(buf, targetUuid);
            if (record != null) {
                records.add(record);
            }
            return records;
        }

        int outerLen = getDataElementLength(buf, outerHeader & 0x07);
        int outerEnd = buf.position() + outerLen;

        while (buf.position() < outerEnd && buf.hasRemaining()) {
            ServiceRecord record = parseAttributeList(buf, targetUuid);
            if (record != null) {
                records.add(record);
            }
        }

        return records;
    }

    /**
     * Parses a single service record's attribute list.
     *
     * @param buf       buffer positioned at the attribute list sequence
     * @param targetUuid UUID that was searched for (may be null)
     * @return parsed service record, or null if invalid
     */
    public static ServiceRecord parseAttributeList(ByteBuffer buf, UUID targetUuid) {
        if (!buf.hasRemaining()) return null;

        int header = buf.get() & 0xFF;
        int type = header >> 3;

        if (type != SdpConstants.DE_SEQ) {
            return null;
        }

        int seqLen = getDataElementLength(buf, header & 0x07);
        int seqEnd = buf.position() + seqLen;

        ServiceRecord record = new ServiceRecord(targetUuid);

        while (buf.position() < seqEnd && buf.hasRemaining()) {
            // Read attribute ID
            int attrHeader = buf.get() & 0xFF;
            if ((attrHeader >> 3) != SdpConstants.DE_UINT) {
                // Invalid attribute list
                break;
            }
            int attrIdLen = getDataElementLength(buf, attrHeader & 0x07);
            int attrId;
            if (attrIdLen == 2) {
                attrId = buf.getShort() & 0xFFFF;
            } else {
                break;
            }

            // Read and store raw attribute value
            int valueStart = buf.position();
            byte[] rawValue = SdpDataElement.readElement(buf);
            if (rawValue == null) break;

            record.setAttribute(attrId, rawValue);

            // Parse specific attributes for convenience access
            ByteBuffer valueBuf = ByteBuffer.wrap(rawValue).order(ByteOrder.BIG_ENDIAN);
            parseKnownAttribute(record, attrId, valueBuf);
        }

        // Ensure buffer is positioned at end of sequence
        if (buf.position() < seqEnd) {
            buf.position(Math.min(seqEnd, buf.limit()));
        }

        return record;
    }

    /**
     * Parses a known attribute and updates the service record.
     */
    private static void parseKnownAttribute(ServiceRecord record, int attrId, ByteBuffer buf) {
        switch (attrId) {
            case SdpConstants.ATTR_SERVICE_RECORD_HANDLE:
                long handle = SdpDataElement.decodeUint(buf);
                if (handle >= 0) {
                    record.setServiceRecordHandle((int) handle);
                }
                break;

            case SdpConstants.ATTR_SERVICE_CLASS_ID_LIST:
                parseServiceClassIdList(record, buf);
                break;

            case SdpConstants.ATTR_PROTOCOL_DESCRIPTOR_LIST:
                parseProtocolDescriptorList(record, buf);
                break;

            case SdpConstants.ATTR_BROWSE_GROUP_LIST:
                parseBrowseGroupList(record, buf);
                break;

            case SdpConstants.ATTR_BT_PROFILE_DESCRIPTOR_LIST:
                parseProfileDescriptorList(record, buf);
                break;

            case SdpConstants.ATTR_SERVICE_NAME:
                String name = SdpDataElement.decodeString(buf);
                if (name != null) {
                    record.setServiceName(name);
                }
                break;

            case SdpConstants.ATTR_SERVICE_DESCRIPTION:
                String desc = SdpDataElement.decodeString(buf);
                if (desc != null) {
                    record.setServiceDescription(desc);
                }
                break;

            case SdpConstants.ATTR_PROVIDER_NAME:
                String provider = SdpDataElement.decodeString(buf);
                if (provider != null) {
                    record.setProviderName(provider);
                }
                break;

            case 0x0200: // GoepL2capPsm (used by OBEX profiles)
                long goepPsm = SdpDataElement.decodeUint(buf);
                if (goepPsm >= 0) {
                    record.setGoepL2capPsm((int) goepPsm);
                }
                break;
        }
    }

    // ==================== Specific Attribute Parsers ====================

    /**
     * Parses ServiceClassIDList attribute (0x0001).
     */
    private static void parseServiceClassIdList(ServiceRecord record, ByteBuffer buf) {
        int header = buf.get() & 0xFF;
        int type = header >> 3;

        if (type != SdpConstants.DE_SEQ) return;

        int seqLen = getDataElementLength(buf, header & 0x07);
        int seqEnd = buf.position() + seqLen;

        while (buf.position() < seqEnd && buf.hasRemaining()) {
            UUID uuid = SdpDataElement.decodeUuid(buf);
            if (uuid != null) {
                record.addServiceClassUuid(uuid);
            } else {
                break;
            }
        }

        // Set primary UUID if not already set
        if (record.getPrimaryServiceUuid() == null && !record.getServiceClassUuids().isEmpty()) {
            record.setPrimaryServiceUuid(record.getServiceClassUuids().get(0));
        }
    }

    /**
     * Parses ProtocolDescriptorList attribute (0x0004).
     *
     * <p>Structure: DataElementSequence of protocol descriptor sequences.
     * Each protocol descriptor: UUID followed by optional parameters.
     */
    private static void parseProtocolDescriptorList(ServiceRecord record, ByteBuffer buf) {
        int header = buf.get() & 0xFF;
        int type = header >> 3;

        if (type != SdpConstants.DE_SEQ && type != SdpConstants.DE_ALT) return;

        int listLen = getDataElementLength(buf, header & 0x07);
        int listEnd = buf.position() + listLen;

        while (buf.position() < listEnd && buf.hasRemaining()) {
            parseProtocolDescriptor(record, buf);
        }
    }

    /**
     * Parses a single protocol descriptor.
     */
    private static void parseProtocolDescriptor(ServiceRecord record, ByteBuffer buf) {
        int descHeader = buf.get() & 0xFF;
        int descType = descHeader >> 3;

        if (descType != SdpConstants.DE_SEQ) {
            SdpDataElement.skipElement(buf);
            return;
        }

        int descLen = getDataElementLength(buf, descHeader & 0x07);
        int descEnd = buf.position() + descLen;

        // First element should be protocol UUID
        UUID protocolUuid = SdpDataElement.decodeUuid(buf);
        if (protocolUuid == null) {
            buf.position(Math.min(descEnd, buf.limit()));
            return;
        }

        record.addProtocolUuid(protocolUuid);

        // Check for L2CAP or RFCOMM specific parameters
        String uuidStr = protocolUuid.toString().toUpperCase();

        // L2CAP UUID: 00000100-0000-1000-8000-00805F9B34FB
        if (uuidStr.startsWith("00000100")) {
            // L2CAP - next parameter is PSM (if present)
            if (buf.position() < descEnd && buf.hasRemaining()) {
                long psm = SdpDataElement.decodeUint(buf);
                if (psm >= 0 && record.getL2capPsm() < 0) {
                    record.setL2capPsm((int) psm);
                }
            }
        }
        // RFCOMM UUID: 00000003-0000-1000-8000-00805F9B34FB
        else if (uuidStr.startsWith("00000003")) {
            // RFCOMM - next parameter is channel number
            if (buf.position() < descEnd && buf.hasRemaining()) {
                long channel = SdpDataElement.decodeUint(buf);
                if (channel >= 0) {
                    record.setRfcommChannel((int) channel);
                }
            }
        }

        // Skip remaining parameters
        buf.position(Math.min(descEnd, buf.limit()));
    }

    /**
     * Parses BrowseGroupList attribute (0x0005).
     */
    private static void parseBrowseGroupList(ServiceRecord record, ByteBuffer buf) {
        int header = buf.get() & 0xFF;
        int type = header >> 3;

        if (type != SdpConstants.DE_SEQ) return;

        int seqLen = getDataElementLength(buf, header & 0x07);
        int seqEnd = buf.position() + seqLen;

        while (buf.position() < seqEnd && buf.hasRemaining()) {
            UUID uuid = SdpDataElement.decodeUuid(buf);
            if (uuid != null) {
                record.addBrowseGroupUuid(uuid);
            } else {
                break;
            }
        }
    }

    /**
     * Parses BluetoothProfileDescriptorList attribute (0x0009).
     */
    private static void parseProfileDescriptorList(ServiceRecord record, ByteBuffer buf) {
        int header = buf.get() & 0xFF;
        int type = header >> 3;

        if (type != SdpConstants.DE_SEQ) return;

        int listLen = getDataElementLength(buf, header & 0x07);
        int listEnd = buf.position() + listLen;

        while (buf.position() < listEnd && buf.hasRemaining()) {
            int profHeader = buf.get() & 0xFF;
            if ((profHeader >> 3) != SdpConstants.DE_SEQ) break;

            int profLen = getDataElementLength(buf, profHeader & 0x07);
            int profEnd = buf.position() + profLen;

            // UUID
            UUID profileUuid = SdpDataElement.decodeUuid(buf);
            if (profileUuid == null) {
                buf.position(Math.min(profEnd, buf.limit()));
                continue;
            }

            // Version (16-bit uint)
            int version = 0;
            if (buf.position() < profEnd && buf.hasRemaining()) {
                long ver = SdpDataElement.decodeUint(buf);
                if (ver >= 0) {
                    version = (int) ver;
                }
            }

            record.addProfileDescriptor(profileUuid, version);
            buf.position(Math.min(profEnd, buf.limit()));
        }
    }

    // ==================== Service Search Response Parsing ====================

    /**
     * Parses a ServiceSearchResponse.
     *
     * @param data PDU data starting after the header
     * @return list of service record handles, or empty list if invalid
     */
    public static List<Integer> parseServiceSearchResponse(byte[] data) {
        List<Integer> handles = new ArrayList<>();
        if (data == null || data.length < 4) return handles;

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        int totalCount = buf.getShort() & 0xFFFF;
        int currentCount = buf.getShort() & 0xFFFF;

        for (int i = 0; i < currentCount && buf.remaining() >= 4; i++) {
            handles.add(buf.getInt());
        }

        return handles;
    }

    /**
     * Parses a ServiceAttributeResponse.
     *
     * @param data PDU data starting after the header
     * @param targetUuid UUID for the service record (may be null)
     * @return parsed service record, or null if invalid
     */
    public static ServiceRecord parseServiceAttributeResponse(byte[] data, UUID targetUuid) {
        if (data == null || data.length < 2) return null;

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int attrListByteCount = buf.getShort() & 0xFFFF;

        if (buf.remaining() < attrListByteCount) return null;

        return parseAttributeList(buf, targetUuid);
    }

    // ==================== Utility Methods ====================

    /**
     * Extracts the continuation state from an SDP response.
     *
     * @param data     full response data
     * @param paramLen parameter length from PDU header
     * @return continuation state bytes, or null if none
     */
    public static byte[] extractContinuationState(byte[] data, int paramLen) {
        if (data == null || data.length < 5 + paramLen) return null;

        // Continuation state is at the end of parameters
        int contStatePos = 5 + paramLen - 1;
        if (contStatePos >= data.length) return null;

        int contStateLen = data[contStatePos] & 0xFF;
        if (contStateLen == 0) return null;

        if (contStatePos + 1 + contStateLen > data.length) return null;

        byte[] contState = new byte[contStateLen];
        System.arraycopy(data, contStatePos + 1, contState, 0, contStateLen);
        return contState;
    }

    /**
     * Gets data element length based on size index.
     */
    private static int getDataElementLength(ByteBuffer buf, int sizeIndex) {
        switch (sizeIndex) {
            case 0:
                return 1;
            case 1:
                return 2;
            case 2:
                return 4;
            case 3:
                return 8;
            case 4:
                return 16;
            case 5:
                return buf.hasRemaining() ? buf.get() & 0xFF : 0;
            case 6:
                return buf.remaining() >= 2 ? buf.getShort() & 0xFFFF : 0;
            case 7:
                return buf.remaining() >= 4 ? buf.getInt() : 0;
            default:
                return 0;
        }
    }
}