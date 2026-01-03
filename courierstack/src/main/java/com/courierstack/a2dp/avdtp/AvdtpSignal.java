package com.courierstack.a2dp.avdtp;

import java.nio.ByteBuffer;

/**
 * AVDTP signaling PDU builder.
 *
 * <p>Provides methods for building AVDTP signaling messages including
 * commands, responses, and fragmented messages.
 *
 * <p>PDU format (single packet):
 * <pre>
 * Byte 0: [Transaction Label (4 bits)][Packet Type (2 bits)][Message Type (2 bits)]
 * Byte 1: [RFA (2 bits)][Signal Identifier (6 bits)]
 * Byte 2+: Signal-specific parameters
 * </pre>
 *
 * <p>Thread safety: This class is stateless and thread-safe.
 */
public final class AvdtpSignal {

    private AvdtpSignal() {
        // Utility class - prevent instantiation
    }

    // ==================== Command Building ====================

    /**
     * Builds an AVDTP command PDU.
     *
     * @param label    transaction label (0-15)
     * @param signalId signal identifier
     * @param params   signal parameters (may be null)
     * @return complete PDU bytes
     */
    public static byte[] buildCommand(int label, int signalId, byte[] params) {
        return buildPdu(label, AvdtpConstants.PKT_TYPE_SINGLE,
                AvdtpConstants.MSG_TYPE_COMMAND, signalId, params);
    }

    /**
     * Builds an AVDTP_DISCOVER command.
     *
     * @param label transaction label
     * @return PDU bytes
     */
    public static byte[] buildDiscover(int label) {
        return buildCommand(label, AvdtpConstants.AVDTP_DISCOVER, null);
    }

    /**
     * Builds an AVDTP_GET_CAPABILITIES command.
     *
     * @param label transaction label
     * @param seid  ACP Stream Endpoint ID
     * @return PDU bytes
     */
    public static byte[] buildGetCapabilities(int label, int seid) {
        return buildCommand(label, AvdtpConstants.AVDTP_GET_CAPABILITIES,
                new byte[] { (byte) (seid << 2) });
    }

    /**
     * Builds an AVDTP_GET_ALL_CAPABILITIES command.
     *
     * @param label transaction label
     * @param seid  ACP Stream Endpoint ID
     * @return PDU bytes
     */
    public static byte[] buildGetAllCapabilities(int label, int seid) {
        return buildCommand(label, AvdtpConstants.AVDTP_GET_ALL_CAPABILITIES,
                new byte[] { (byte) (seid << 2) });
    }

    /**
     * Builds an AVDTP_SET_CONFIGURATION command.
     *
     * @param label         transaction label
     * @param acpSeid       ACP Stream Endpoint ID
     * @param intSeid       INT Stream Endpoint ID
     * @param configuration service capability configuration
     * @return PDU bytes
     */
    public static byte[] buildSetConfiguration(int label, int acpSeid, int intSeid,
                                               byte[] configuration) {
        ByteBuffer params = ByteBuffer.allocate(2 + (configuration != null ? configuration.length : 0));
        params.put((byte) (acpSeid << 2));
        params.put((byte) (intSeid << 2));
        if (configuration != null) {
            params.put(configuration);
        }
        return buildCommand(label, AvdtpConstants.AVDTP_SET_CONFIGURATION, params.array());
    }

    /**
     * Builds an AVDTP_GET_CONFIGURATION command.
     *
     * @param label transaction label
     * @param seid  ACP Stream Endpoint ID
     * @return PDU bytes
     */
    public static byte[] buildGetConfiguration(int label, int seid) {
        return buildCommand(label, AvdtpConstants.AVDTP_GET_CONFIGURATION,
                new byte[] { (byte) (seid << 2) });
    }

    /**
     * Builds an AVDTP_RECONFIGURE command.
     *
     * @param label         transaction label
     * @param seid          ACP Stream Endpoint ID
     * @param configuration service capability configuration
     * @return PDU bytes
     */
    public static byte[] buildReconfigure(int label, int seid, byte[] configuration) {
        ByteBuffer params = ByteBuffer.allocate(1 + (configuration != null ? configuration.length : 0));
        params.put((byte) (seid << 2));
        if (configuration != null) {
            params.put(configuration);
        }
        return buildCommand(label, AvdtpConstants.AVDTP_RECONFIGURE, params.array());
    }

    /**
     * Builds an AVDTP_OPEN command.
     *
     * @param label transaction label
     * @param seid  ACP Stream Endpoint ID
     * @return PDU bytes
     */
    public static byte[] buildOpen(int label, int seid) {
        return buildCommand(label, AvdtpConstants.AVDTP_OPEN,
                new byte[] { (byte) (seid << 2) });
    }

    /**
     * Builds an AVDTP_START command.
     *
     * @param label transaction label
     * @param seids ACP Stream Endpoint IDs
     * @return PDU bytes
     */
    public static byte[] buildStart(int label, int... seids) {
        byte[] params = new byte[seids.length];
        for (int i = 0; i < seids.length; i++) {
            params[i] = (byte) (seids[i] << 2);
        }
        return buildCommand(label, AvdtpConstants.AVDTP_START, params);
    }

    /**
     * Builds an AVDTP_CLOSE command.
     *
     * @param label transaction label
     * @param seid  ACP Stream Endpoint ID
     * @return PDU bytes
     */
    public static byte[] buildClose(int label, int seid) {
        return buildCommand(label, AvdtpConstants.AVDTP_CLOSE,
                new byte[] { (byte) (seid << 2) });
    }

    /**
     * Builds an AVDTP_SUSPEND command.
     *
     * @param label transaction label
     * @param seids ACP Stream Endpoint IDs
     * @return PDU bytes
     */
    public static byte[] buildSuspend(int label, int... seids) {
        byte[] params = new byte[seids.length];
        for (int i = 0; i < seids.length; i++) {
            params[i] = (byte) (seids[i] << 2);
        }
        return buildCommand(label, AvdtpConstants.AVDTP_SUSPEND, params);
    }

    /**
     * Builds an AVDTP_ABORT command.
     *
     * @param label transaction label
     * @param seid  ACP Stream Endpoint ID
     * @return PDU bytes
     */
    public static byte[] buildAbort(int label, int seid) {
        return buildCommand(label, AvdtpConstants.AVDTP_ABORT,
                new byte[] { (byte) (seid << 2) });
    }

    /**
     * Builds an AVDTP_DELAY_REPORT command.
     *
     * @param label   transaction label
     * @param seid    ACP Stream Endpoint ID
     * @param delayMs delay in milliseconds (will be converted to 1/10ms units)
     * @return PDU bytes
     */
    public static byte[] buildDelayReport(int label, int seid, int delayMs) {
        int delay = delayMs * 10; // Convert to 1/10 ms units
        byte[] params = new byte[3];
        params[0] = (byte) (seid << 2);
        params[1] = (byte) ((delay >> 8) & 0xFF);
        params[2] = (byte) (delay & 0xFF);
        return buildCommand(label, AvdtpConstants.AVDTP_DELAY_REPORT, params);
    }

    // ==================== Response Building ====================

    /**
     * Builds an accept response PDU.
     *
     * @param label    transaction label (from command)
     * @param signalId signal identifier (from command)
     * @param params   response parameters (may be null)
     * @return complete PDU bytes
     */
    public static byte[] buildAcceptResponse(int label, int signalId, byte[] params) {
        return buildPdu(label, AvdtpConstants.PKT_TYPE_SINGLE,
                AvdtpConstants.MSG_TYPE_RESPONSE_ACCEPT, signalId, params);
    }

    /**
     * Builds a reject response PDU.
     *
     * @param label     transaction label
     * @param signalId  signal identifier
     * @param errorCode error code
     * @return complete PDU bytes
     */
    public static byte[] buildRejectResponse(int label, int signalId, int errorCode) {
        return buildPdu(label, AvdtpConstants.PKT_TYPE_SINGLE,
                AvdtpConstants.MSG_TYPE_RESPONSE_REJECT, signalId,
                new byte[] { (byte) errorCode });
    }

    /**
     * Builds a reject response PDU with service category.
     *
     * @param label           transaction label
     * @param signalId        signal identifier
     * @param serviceCategory failing service category
     * @param errorCode       error code
     * @return complete PDU bytes
     */
    public static byte[] buildRejectResponse(int label, int signalId,
                                             int serviceCategory, int errorCode) {
        return buildPdu(label, AvdtpConstants.PKT_TYPE_SINGLE,
                AvdtpConstants.MSG_TYPE_RESPONSE_REJECT, signalId,
                new byte[] { (byte) serviceCategory, (byte) errorCode });
    }

    /**
     * Builds a general reject response PDU.
     *
     * @param label transaction label
     * @return complete PDU bytes
     */
    public static byte[] buildGeneralReject(int label) {
        return new byte[] {
                (byte) (((label & 0x0F) << 4) | (AvdtpConstants.PKT_TYPE_SINGLE << 2) |
                        AvdtpConstants.MSG_TYPE_GENERAL_REJECT)
        };
    }

    /**
     * Builds a DISCOVER response.
     *
     * @param label     transaction label
     * @param endpoints discovered endpoints
     * @return PDU bytes
     */
    public static byte[] buildDiscoverResponse(int label, StreamEndpoint[] endpoints) {
        byte[] params = new byte[endpoints.length * 2];
        for (int i = 0; i < endpoints.length; i++) {
            StreamEndpoint ep = endpoints[i];
            params[i * 2] = (byte) ((ep.getSeid() << 2) | (ep.isInUse() ? 0x02 : 0));
            params[i * 2 + 1] = (byte) ((ep.getMediaType() << 4) | (ep.getTsep() << 3));
        }
        return buildAcceptResponse(label, AvdtpConstants.AVDTP_DISCOVER, params);
    }

    /**
     * Builds a GET_CAPABILITIES response.
     *
     * @param label        transaction label
     * @param capabilities capability data
     * @return PDU bytes
     */
    public static byte[] buildGetCapabilitiesResponse(int label, byte[] capabilities) {
        return buildAcceptResponse(label, AvdtpConstants.AVDTP_GET_CAPABILITIES, capabilities);
    }

    // ==================== Core PDU Building ====================

    /**
     * Builds an AVDTP PDU.
     *
     * @param label    transaction label (0-15)
     * @param pktType  packet type
     * @param msgType  message type
     * @param signalId signal identifier
     * @param params   parameters (may be null)
     * @return complete PDU bytes
     */
    public static byte[] buildPdu(int label, int pktType, int msgType, int signalId, byte[] params) {
        int paramLen = params != null ? params.length : 0;
        byte[] pdu = new byte[2 + paramLen];

        pdu[0] = (byte) (((label & 0x0F) << 4) | ((pktType & 0x03) << 2) | (msgType & 0x03));
        pdu[1] = (byte) (signalId & 0x3F);

        if (params != null) {
            System.arraycopy(params, 0, pdu, 2, paramLen);
        }

        return pdu;
    }

    // ==================== SBC Configuration Building ====================

    /**
     * Builds SBC codec configuration for SET_CONFIGURATION.
     *
     * @param endpoint endpoint with SBC parameters
     * @return configuration bytes
     */
    public static byte[] buildSbcConfiguration(StreamEndpoint endpoint) {
        return buildSbcConfiguration(
                endpoint.getSbcFrequency(),
                endpoint.getSbcChannelMode(),
                endpoint.getSbcBlockLength(),
                endpoint.getSbcSubbands(),
                endpoint.getSbcAllocation(),
                endpoint.getSbcMinBitpool(),
                endpoint.getSbcMaxBitpool()
        );
    }

    /**
     * Builds SBC codec configuration for SET_CONFIGURATION.
     *
     * @param frequency   SBC frequency flag (single value)
     * @param channelMode SBC channel mode flag (single value)
     * @param blocks      SBC block length flag (single value)
     * @param subbands    SBC subbands flag (single value)
     * @param allocation  SBC allocation method flag (single value)
     * @param minBitpool  minimum bitpool
     * @param maxBitpool  maximum bitpool
     * @return configuration bytes
     */
    public static byte[] buildSbcConfiguration(int frequency, int channelMode, int blocks,
                                               int subbands, int allocation,
                                               int minBitpool, int maxBitpool) {
        return new byte[] {
                (byte) AvdtpConstants.SC_MEDIA_TRANSPORT, 0,
                (byte) AvdtpConstants.SC_MEDIA_CODEC, 6,
                (byte) (AvdtpConstants.MEDIA_TYPE_AUDIO << 4), (byte) AvdtpConstants.CODEC_SBC,
                (byte) (frequency | channelMode),
                (byte) (blocks | subbands | allocation),
                (byte) minBitpool,
                (byte) maxBitpool
        };
    }

    /**
     * Builds SBC capabilities for GET_CAPABILITIES response.
     *
     * @param frequencies  supported frequency flags (OR'd together)
     * @param channelModes supported channel mode flags (OR'd together)
     * @param blocks       supported block length flags (OR'd together)
     * @param subbands     supported subbands flags (OR'd together)
     * @param allocations  supported allocation method flags (OR'd together)
     * @param minBitpool   minimum bitpool
     * @param maxBitpool   maximum bitpool
     * @return capability bytes
     */
    public static byte[] buildSbcCapabilities(int frequencies, int channelModes, int blocks,
                                              int subbands, int allocations,
                                              int minBitpool, int maxBitpool) {
        return buildSbcConfiguration(frequencies, channelModes, blocks, subbands,
                allocations, minBitpool, maxBitpool);
    }

    /**
     * Builds full SBC capabilities (all options supported).
     *
     * @return capability bytes
     */
    public static byte[] buildSbcFullCapabilities() {
        return buildSbcCapabilities(
                AvdtpConstants.SBC_FREQ_ALL,
                AvdtpConstants.SBC_CHANNEL_ALL,
                AvdtpConstants.SBC_BLOCK_ALL,
                AvdtpConstants.SBC_SUBBAND_ALL,
                AvdtpConstants.SBC_ALLOC_ALL,
                AvdtpConstants.SBC_MIN_BITPOOL,
                AvdtpConstants.SBC_MAX_BITPOOL_HQ
        );
    }

    /**
     * Builds default high-quality SBC configuration.
     *
     * @param bitpool bitpool value
     * @return configuration bytes
     */
    public static byte[] buildSbcDefaultConfiguration(int bitpool) {
        return buildSbcConfiguration(
                AvdtpConstants.SBC_FREQ_44100,
                AvdtpConstants.SBC_CHANNEL_JOINT_STEREO,
                AvdtpConstants.SBC_BLOCK_16,
                AvdtpConstants.SBC_SUBBAND_8,
                AvdtpConstants.SBC_ALLOC_LOUDNESS,
                bitpool, bitpool
        );
    }

    // ==================== Utility Methods ====================

    /**
     * Extracts the transaction label from a PDU header.
     *
     * @param header first byte of PDU
     * @return transaction label (0-15)
     */
    public static int getTransactionLabel(int header) {
        return (header >> 4) & 0x0F;
    }

    /**
     * Extracts the packet type from a PDU header.
     *
     * @param header first byte of PDU
     * @return packet type
     */
    public static int getPacketType(int header) {
        return (header >> 2) & 0x03;
    }

    /**
     * Extracts the message type from a PDU header.
     *
     * @param header first byte of PDU
     * @return message type
     */
    public static int getMessageType(int header) {
        return header & 0x03;
    }

    /**
     * Extracts the signal identifier from a PDU.
     *
     * @param signalByte second byte of PDU
     * @return signal identifier
     */
    public static int getSignalId(int signalByte) {
        return signalByte & 0x3F;
    }

    /**
     * Returns true if the message type indicates a command.
     *
     * @param msgType message type
     * @return true if command
     */
    public static boolean isCommand(int msgType) {
        return msgType == AvdtpConstants.MSG_TYPE_COMMAND;
    }

    /**
     * Returns true if the message type indicates a response.
     *
     * @param msgType message type
     * @return true if response (accept or reject)
     */
    public static boolean isResponse(int msgType) {
        return msgType == AvdtpConstants.MSG_TYPE_RESPONSE_ACCEPT ||
                msgType == AvdtpConstants.MSG_TYPE_RESPONSE_REJECT;
    }

    /**
     * Returns true if the message type indicates acceptance.
     *
     * @param msgType message type
     * @return true if accept response
     */
    public static boolean isAccept(int msgType) {
        return msgType == AvdtpConstants.MSG_TYPE_RESPONSE_ACCEPT;
    }

    /**
     * Returns true if the message type indicates rejection.
     *
     * @param msgType message type
     * @return true if reject response or general reject
     */
    public static boolean isReject(int msgType) {
        return msgType == AvdtpConstants.MSG_TYPE_RESPONSE_REJECT ||
                msgType == AvdtpConstants.MSG_TYPE_GENERAL_REJECT;
    }
}