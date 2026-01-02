package com.courierstack.avdtp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for AVDTP signaling messages and capabilities.
 *
 * <p>Provides methods for parsing AVDTP responses, service capabilities,
 * codec configurations, and other protocol data structures.
 *
 * <p>Thread safety: This class is stateless and thread-safe.
 */
public final class AvdtpParser {

    private AvdtpParser() {
        // Utility class - prevent instantiation
    }

    // ==================== PDU Header Parsing ====================

    /**
     * Parsed AVDTP PDU header.
     */
    public static class PduHeader {
        /** Transaction label (0-15). */
        public final int label;

        /** Packet type (single, start, continue, end). */
        public final int packetType;

        /** Message type (command, accept, reject). */
        public final int messageType;

        /** Signal identifier. */
        public final int signalId;

        public PduHeader(int label, int packetType, int messageType, int signalId) {
            this.label = label;
            this.packetType = packetType;
            this.messageType = messageType;
            this.signalId = signalId;
        }

        public boolean isCommand() {
            return messageType == AvdtpConstants.MSG_TYPE_COMMAND;
        }

        public boolean isAccept() {
            return messageType == AvdtpConstants.MSG_TYPE_RESPONSE_ACCEPT;
        }

        public boolean isReject() {
            return messageType == AvdtpConstants.MSG_TYPE_RESPONSE_REJECT ||
                    messageType == AvdtpConstants.MSG_TYPE_GENERAL_REJECT;
        }

        @Override
        public String toString() {
            return String.format("PduHeader{label=%d, signal=%s, type=%s}",
                    label,
                    AvdtpConstants.getSignalName(signalId),
                    AvdtpConstants.getMessageTypeName(messageType));
        }
    }

    /**
     * Parses an AVDTP PDU header.
     *
     * @param data raw PDU data (at least 2 bytes)
     * @return parsed header, or null if data is too short
     */
    public static PduHeader parsePduHeader(byte[] data) {
        if (data == null || data.length < 2) return null;

        int header = data[0] & 0xFF;
        int label = (header >> 4) & 0x0F;
        int packetType = (header >> 2) & 0x03;
        int messageType = header & 0x03;
        int signalId = data[1] & 0x3F;

        return new PduHeader(label, packetType, messageType, signalId);
    }

    // ==================== Discover Response Parsing ====================

    /**
     * Parses an AVDTP_DISCOVER response.
     *
     * @param data response data (including header)
     * @return list of discovered endpoints
     */
    public static List<StreamEndpoint> parseDiscoverResponse(byte[] data) {
        List<StreamEndpoint> endpoints = new ArrayList<>();
        if (data == null || data.length < 4) return endpoints;

        // Skip 2-byte header
        int offset = 2;
        while (offset + 2 <= data.length) {
            int seidInfo = data[offset] & 0xFF;
            int mediaInfo = data[offset + 1] & 0xFF;

            int seid = (seidInfo >> 2) & 0x3F;
            boolean inUse = (seidInfo & 0x02) != 0;
            int mediaType = (mediaInfo >> 4) & 0x0F;
            int tsep = (mediaInfo >> 3) & 0x01;

            StreamEndpoint ep = new StreamEndpoint(seid, tsep, mediaType, 0);
            ep.setInUse(inUse);
            endpoints.add(ep);

            offset += 2;
        }

        return endpoints;
    }

    // ==================== Capabilities Parsing ====================

    /**
     * Parsed service capability.
     */
    public static class ServiceCapability {
        /** Service category. */
        public final int category;

        /** Capability data (excluding category and length bytes). */
        public final byte[] data;

        public ServiceCapability(int category, byte[] data) {
            this.category = category;
            this.data = data;
        }

        @Override
        public String toString() {
            return String.format("Capability{%s, len=%d}",
                    AvdtpConstants.getServiceCategoryName(category),
                    data != null ? data.length : 0);
        }
    }

    /**
     * Parses service capabilities from a GET_CAPABILITIES response.
     *
     * @param data capability data (after header)
     * @return list of parsed capabilities
     */
    public static List<ServiceCapability> parseCapabilities(byte[] data) {
        return parseCapabilities(data, 0, data != null ? data.length : 0);
    }

    /**
     * Parses service capabilities from capability data.
     *
     * @param data   capability data
     * @param offset starting offset
     * @param length data length
     * @return list of parsed capabilities
     */
    public static List<ServiceCapability> parseCapabilities(byte[] data, int offset, int length) {
        List<ServiceCapability> capabilities = new ArrayList<>();
        if (data == null) return capabilities;

        int end = offset + length;
        while (offset + 2 <= end) {
            int category = data[offset] & 0xFF;
            int capLen = data[offset + 1] & 0xFF;
            offset += 2;

            byte[] capData = null;
            if (capLen > 0 && offset + capLen <= end) {
                capData = new byte[capLen];
                System.arraycopy(data, offset, capData, 0, capLen);
                offset += capLen;
            }

            capabilities.add(new ServiceCapability(category, capData));
        }

        return capabilities;
    }

    /**
     * Parses capabilities from a GET_CAPABILITIES response PDU.
     *
     * @param data response PDU data
     * @return list of parsed capabilities
     */
    public static List<ServiceCapability> parseCapabilitiesResponse(byte[] data) {
        if (data == null || data.length < 2) return new ArrayList<>();
        return parseCapabilities(data, 2, data.length - 2);
    }

    /**
     * Finds a specific service capability.
     *
     * @param capabilities list of capabilities
     * @param category     service category to find
     * @return capability data, or null if not found
     */
    public static ServiceCapability findCapability(List<ServiceCapability> capabilities, int category) {
        for (ServiceCapability cap : capabilities) {
            if (cap.category == category) {
                return cap;
            }
        }
        return null;
    }

    // ==================== SBC Configuration Parsing ====================

    /**
     * Parsed SBC codec parameters.
     */
    public static class SbcConfig {
        /** Sampling frequency flags. */
        public int frequency;

        /** Channel mode flags. */
        public int channelMode;

        /** Block length flags. */
        public int blocks;

        /** Subbands flags. */
        public int subbands;

        /** Allocation method flags. */
        public int allocation;

        /** Minimum bitpool. */
        public int minBitpool;

        /** Maximum bitpool. */
        public int maxBitpool;

        /**
         * Returns sample rate in Hz (selects highest if multiple flags set).
         */
        public int getSampleRateHz() {
            return AvdtpConstants.sbcFrequencyToHz(frequency);
        }

        /**
         * Returns number of channels.
         */
        public int getChannelCount() {
            return AvdtpConstants.sbcChannelCount(channelMode);
        }

        /**
         * Returns number of blocks (selects highest if multiple flags set).
         */
        public int getBlockCount() {
            return AvdtpConstants.sbcBlocksToValue(blocks);
        }

        /**
         * Returns number of subbands (selects highest if multiple flags set).
         */
        public int getSubbandCount() {
            return AvdtpConstants.sbcSubbandsToValue(subbands);
        }

        /**
         * Returns true if joint stereo is enabled.
         */
        public boolean isJointStereo() {
            return (channelMode & AvdtpConstants.SBC_CHANNEL_JOINT_STEREO) != 0;
        }

        /**
         * Applies this configuration to a StreamEndpoint.
         *
         * @param endpoint endpoint to configure
         */
        public void applyTo(StreamEndpoint endpoint) {
            endpoint.setSbcFrequency(frequency);
            endpoint.setSbcChannelMode(channelMode);
            endpoint.setSbcBlockLength(blocks);
            endpoint.setSbcSubbands(subbands);
            endpoint.setSbcAllocation(allocation);
            endpoint.setSbcMinBitpool(minBitpool);
            endpoint.setSbcMaxBitpool(maxBitpool);
        }

        @Override
        public String toString() {
            return String.format("SbcConfig{%dHz, %dch, blocks=%d, subbands=%d, bitpool=%d-%d}",
                    getSampleRateHz(), getChannelCount(), getBlockCount(),
                    getSubbandCount(), minBitpool, maxBitpool);
        }
    }

    /**
     * Parses SBC codec configuration from capability data.
     *
     * @param data SBC-specific capability data (4 bytes)
     * @return parsed SBC config, or null if invalid
     */
    public static SbcConfig parseSbcConfig(byte[] data) {
        if (data == null || data.length < 4) return null;

        SbcConfig config = new SbcConfig();

        int byte0 = data[0] & 0xFF;
        int byte1 = data[1] & 0xFF;

        config.frequency = (byte0 >> 4) & 0x0F;
        config.channelMode = byte0 & 0x0F;
        config.blocks = (byte1 >> 4) & 0x0F;
        config.subbands = (byte1 >> 2) & 0x03;
        config.allocation = byte1 & 0x03;
        config.minBitpool = data[2] & 0xFF;
        config.maxBitpool = data[3] & 0xFF;

        return config;
    }

    /**
     * Parses SBC configuration from a media codec capability.
     *
     * @param capability media codec capability
     * @return parsed SBC config, or null if not SBC
     */
    public static SbcConfig parseSbcFromCapability(ServiceCapability capability) {
        if (capability == null || capability.category != AvdtpConstants.SC_MEDIA_CODEC) {
            return null;
        }

        byte[] data = capability.data;
        if (data == null || data.length < 6) return null;

        // Check media type and codec type
        int mediaType = (data[0] >> 4) & 0x0F;
        int codecType = data[1] & 0xFF;

        if (mediaType != AvdtpConstants.MEDIA_TYPE_AUDIO ||
                codecType != AvdtpConstants.CODEC_SBC) {
            return null;
        }

        // Parse SBC parameters starting at offset 2
        byte[] sbcData = new byte[4];
        System.arraycopy(data, 2, sbcData, 0, 4);
        return parseSbcConfig(sbcData);
    }

    /**
     * Parses SBC configuration directly from GET_CAPABILITIES response.
     *
     * @param responseData response PDU data
     * @return parsed SBC config, or null if not found
     */
    public static SbcConfig parseSbcFromResponse(byte[] responseData) {
        List<ServiceCapability> caps = parseCapabilitiesResponse(responseData);
        ServiceCapability codecCap = findCapability(caps, AvdtpConstants.SC_MEDIA_CODEC);
        return parseSbcFromCapability(codecCap);
    }

    // ==================== Configuration Parsing ====================

    /**
     * Parses a SET_CONFIGURATION request and applies to endpoint.
     *
     * @param data     configuration data (after ACP/INT SEID bytes)
     * @param endpoint endpoint to configure
     * @return true if parsing succeeded
     */
    public static boolean parseConfiguration(byte[] data, StreamEndpoint endpoint) {
        if (data == null || endpoint == null) return false;

        List<ServiceCapability> caps = parseCapabilities(data);
        ServiceCapability codecCap = findCapability(caps, AvdtpConstants.SC_MEDIA_CODEC);

        if (codecCap != null && codecCap.data != null && codecCap.data.length >= 6) {
            int codecType = codecCap.data[1] & 0xFF;
            endpoint.setCodecType(codecType);

            if (codecType == AvdtpConstants.CODEC_SBC) {
                SbcConfig sbcConfig = parseSbcFromCapability(codecCap);
                if (sbcConfig != null) {
                    sbcConfig.applyTo(endpoint);
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Parses a SET_CONFIGURATION command.
     *
     * @param data command PDU data
     * @return parsed configuration result
     */
    public static SetConfigResult parseSetConfiguration(byte[] data) {
        if (data == null || data.length < 4) return null;

        SetConfigResult result = new SetConfigResult();
        result.acpSeid = (data[2] & 0xFF) >> 2;
        result.intSeid = (data[3] & 0xFF) >> 2;

        if (data.length > 4) {
            result.configuration = new byte[data.length - 4];
            System.arraycopy(data, 4, result.configuration, 0, result.configuration.length);
            result.capabilities = parseCapabilities(result.configuration);
        }

        return result;
    }

    /**
     * Parsed SET_CONFIGURATION command.
     */
    public static class SetConfigResult {
        /** ACP Stream Endpoint ID. */
        public int acpSeid;

        /** INT Stream Endpoint ID. */
        public int intSeid;

        /** Raw configuration data. */
        public byte[] configuration;

        /** Parsed capabilities. */
        public List<ServiceCapability> capabilities;
    }

    // ==================== Reject Response Parsing ====================

    /**
     * Parsed reject response.
     */
    public static class RejectInfo {
        /** Service category that caused rejection (if applicable). */
        public int serviceCategory;

        /** Error code. */
        public int errorCode;

        /** Signal ID that was rejected. */
        public int signalId;

        @Override
        public String toString() {
            return String.format("Reject{signal=%s, error=%s, category=%s}",
                    AvdtpConstants.getSignalName(signalId),
                    AvdtpConstants.getErrorString(errorCode),
                    serviceCategory > 0 ? AvdtpConstants.getServiceCategoryName(serviceCategory) : "N/A");
        }
    }

    /**
     * Parses a reject response.
     *
     * @param data response PDU data
     * @return parsed reject info
     */
    public static RejectInfo parseReject(byte[] data) {
        if (data == null || data.length < 2) return null;

        RejectInfo info = new RejectInfo();
        info.signalId = data[1] & 0x3F;

        if (data.length >= 4) {
            // Has service category
            info.serviceCategory = data[2] & 0xFF;
            info.errorCode = data[3] & 0xFF;
        } else if (data.length >= 3) {
            // Just error code
            info.errorCode = data[2] & 0xFF;
        }

        return info;
    }

    // ==================== Delay Report Parsing ====================

    /**
     * Parses an AVDTP_DELAY_REPORT command.
     *
     * @param data command PDU data
     * @return delay in milliseconds, or -1 if invalid
     */
    public static int parseDelayReport(byte[] data) {
        if (data == null || data.length < 5) return -1;

        int seid = (data[2] & 0xFF) >> 2;
        int delay = ((data[3] & 0xFF) << 8) | (data[4] & 0xFF);

        // Convert from 1/10 ms units to ms
        return delay / 10;
    }

    // ==================== Endpoint Update ====================

    /**
     * Updates a StreamEndpoint with capability data.
     *
     * @param endpoint endpoint to update
     * @param data     capability data
     */
    public static void updateEndpointCapabilities(StreamEndpoint endpoint, byte[] data) {
        if (endpoint == null || data == null) return;

        endpoint.setCapabilities(data);

        List<ServiceCapability> caps = parseCapabilities(data);
        ServiceCapability codecCap = findCapability(caps, AvdtpConstants.SC_MEDIA_CODEC);

        if (codecCap != null && codecCap.data != null && codecCap.data.length >= 2) {
            int codecType = codecCap.data[1] & 0xFF;
            endpoint.setCodecType(codecType);

            if (codecType == AvdtpConstants.CODEC_SBC) {
                SbcConfig sbcConfig = parseSbcFromCapability(codecCap);
                if (sbcConfig != null) {
                    sbcConfig.applyTo(endpoint);
                }
            }
        }
    }

    // ==================== Codec Info Extraction ====================

    /**
     * Extracts codec type from capability data.
     *
     * @param capabilityData raw capability data
     * @return codec type, or -1 if not found
     */
    public static int extractCodecType(byte[] capabilityData) {
        List<ServiceCapability> caps = parseCapabilities(capabilityData);
        ServiceCapability codecCap = findCapability(caps, AvdtpConstants.SC_MEDIA_CODEC);

        if (codecCap != null && codecCap.data != null && codecCap.data.length >= 2) {
            return codecCap.data[1] & 0xFF;
        }

        return -1;
    }

    /**
     * Checks if capabilities include delay reporting support.
     *
     * @param capabilityData raw capability data
     * @return true if delay reporting is supported
     */
    public static boolean supportsDelayReporting(byte[] capabilityData) {
        List<ServiceCapability> caps = parseCapabilities(capabilityData);
        return findCapability(caps, AvdtpConstants.SC_DELAY_REPORTING) != null;
    }

    /**
     * Checks if capabilities include content protection.
     *
     * @param capabilityData raw capability data
     * @return true if content protection is supported
     */
    public static boolean supportsContentProtection(byte[] capabilityData) {
        List<ServiceCapability> caps = parseCapabilities(capabilityData);
        return findCapability(caps, AvdtpConstants.SC_CONTENT_PROTECTION) != null;
    }
}