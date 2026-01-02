package com.courierstack.avdtp;

/**
 * AVDTP (Audio/Video Distribution Transport Protocol) constants.
 *
 * <p>Defines all protocol constants per AVDTP Spec v1.3 and A2DP Spec v1.3.
 * Includes signal IDs, service categories, media types, codec parameters,
 * and error codes.
 *
 * <p>Thread safety: This class is immutable and thread-safe.
 */
public final class AvdtpConstants {

    private AvdtpConstants() {
        // Utility class - prevent instantiation
    }

    // ==================== PSM ====================

    /** AVDTP L2CAP PSM. */
    public static final int PSM_AVDTP = 0x0019;

    // ==================== TSEP (Stream Endpoint Type) ====================

    /** Source endpoint (sends media). */
    public static final int TSEP_SRC = 0x00;

    /** Sink endpoint (receives media). */
    public static final int TSEP_SNK = 0x01;

    // ==================== Signal IDs ====================

    /** Discover stream endpoints. */
    public static final int AVDTP_DISCOVER = 0x01;

    /** Get endpoint capabilities. */
    public static final int AVDTP_GET_CAPABILITIES = 0x02;

    /** Set stream configuration. */
    public static final int AVDTP_SET_CONFIGURATION = 0x03;

    /** Get current configuration. */
    public static final int AVDTP_GET_CONFIGURATION = 0x04;

    /** Reconfigure stream. */
    public static final int AVDTP_RECONFIGURE = 0x05;

    /** Open stream. */
    public static final int AVDTP_OPEN = 0x06;

    /** Start streaming. */
    public static final int AVDTP_START = 0x07;

    /** Close stream. */
    public static final int AVDTP_CLOSE = 0x08;

    /** Suspend streaming. */
    public static final int AVDTP_SUSPEND = 0x09;

    /** Abort stream. */
    public static final int AVDTP_ABORT = 0x0A;

    /** Security control (optional). */
    public static final int AVDTP_SECURITY_CONTROL = 0x0B;

    /** Get all capabilities (AVDTP 1.3+). */
    public static final int AVDTP_GET_ALL_CAPABILITIES = 0x0C;

    /** Delay report (AVDTP 1.3+). */
    public static final int AVDTP_DELAY_REPORT = 0x0D;

    // Legacy signal ID aliases
    public static final int DISCOVER = AVDTP_DISCOVER;
    public static final int GET_CAPABILITIES = AVDTP_GET_CAPABILITIES;
    public static final int SET_CONFIGURATION = AVDTP_SET_CONFIGURATION;
    public static final int GET_CONFIGURATION = AVDTP_GET_CONFIGURATION;
    public static final int RECONFIGURE = AVDTP_RECONFIGURE;
    public static final int OPEN = AVDTP_OPEN;
    public static final int START = AVDTP_START;
    public static final int CLOSE = AVDTP_CLOSE;
    public static final int SUSPEND = AVDTP_SUSPEND;
    public static final int ABORT = AVDTP_ABORT;
    public static final int GET_ALL_CAPABILITIES = AVDTP_GET_ALL_CAPABILITIES;
    public static final int DELAY_REPORT = AVDTP_DELAY_REPORT;

    // ==================== Message Types ====================

    /** Command message. */
    public static final int MSG_TYPE_COMMAND = 0x00;

    /** General reject. */
    public static final int MSG_TYPE_GENERAL_REJECT = 0x01;

    /** Response accept. */
    public static final int MSG_TYPE_RESPONSE_ACCEPT = 0x02;

    /** Response reject. */
    public static final int MSG_TYPE_RESPONSE_REJECT = 0x03;

    // ==================== Packet Types ====================

    /** Single packet (complete message). */
    public static final int PKT_TYPE_SINGLE = 0x00;

    /** Start of fragmented message. */
    public static final int PKT_TYPE_START = 0x01;

    /** Continuation of fragmented message. */
    public static final int PKT_TYPE_CONTINUE = 0x02;

    /** End of fragmented message. */
    public static final int PKT_TYPE_END = 0x03;

    // ==================== Service Categories ====================

    /** Media transport (required). */
    public static final int SC_MEDIA_TRANSPORT = 0x01;

    /** Reporting (optional). */
    public static final int SC_REPORTING = 0x02;

    /** Recovery (optional). */
    public static final int SC_RECOVERY = 0x03;

    /** Content protection (optional). */
    public static final int SC_CONTENT_PROTECTION = 0x04;

    /** Header compression (optional). */
    public static final int SC_HEADER_COMPRESSION = 0x05;

    /** Multiplexing (optional). */
    public static final int SC_MULTIPLEXING = 0x06;

    /** Media codec. */
    public static final int SC_MEDIA_CODEC = 0x07;

    /** Delay reporting (AVDTP 1.3+). */
    public static final int SC_DELAY_REPORTING = 0x08;

    // Service category aliases
    public static final int SERVICE_CAT_MEDIA_TRANSPORT = SC_MEDIA_TRANSPORT;
    public static final int SERVICE_CAT_REPORTING = SC_REPORTING;
    public static final int SERVICE_CAT_RECOVERY = SC_RECOVERY;
    public static final int SERVICE_CAT_CONTENT_PROTECTION = SC_CONTENT_PROTECTION;
    public static final int SERVICE_CAT_HEADER_COMPRESSION = SC_HEADER_COMPRESSION;
    public static final int SERVICE_CAT_MULTIPLEXING = SC_MULTIPLEXING;
    public static final int SERVICE_CAT_MEDIA_CODEC = SC_MEDIA_CODEC;
    public static final int SERVICE_CAT_DELAY_REPORTING = SC_DELAY_REPORTING;

    // ==================== Media Types ====================

    /** Audio media. */
    public static final int MEDIA_TYPE_AUDIO = 0x00;

    /** Video media. */
    public static final int MEDIA_TYPE_VIDEO = 0x01;

    /** Multimedia. */
    public static final int MEDIA_TYPE_MULTIMEDIA = 0x02;

    // ==================== Audio Codec Types ====================

    /** SBC (mandatory). */
    public static final int CODEC_SBC = 0x00;

    /** MPEG-1,2 Audio. */
    public static final int CODEC_MPEG12_AUDIO = 0x01;

    /** MPEG-2,4 AAC. */
    public static final int CODEC_MPEG24_AAC = 0x02;

    /** ATRAC family. */
    public static final int CODEC_ATRAC = 0x04;

    /** Vendor specific (aptX, LDAC, etc.). */
    public static final int CODEC_VENDOR_SPECIFIC = 0xFF;

    // ==================== SBC Sampling Frequencies ====================

    /** 16 kHz. */
    public static final int SBC_FREQ_16000 = 0x80;

    /** 32 kHz. */
    public static final int SBC_FREQ_32000 = 0x40;

    /** 44.1 kHz. */
    public static final int SBC_FREQ_44100 = 0x20;

    /** 48 kHz. */
    public static final int SBC_FREQ_48000 = 0x10;

    /** All frequencies supported. */
    public static final int SBC_FREQ_ALL = 0xF0;

    // ==================== SBC Channel Modes ====================

    /** Mono. */
    public static final int SBC_CHANNEL_MONO = 0x08;

    /** Dual channel. */
    public static final int SBC_CHANNEL_DUAL = 0x04;

    /** Stereo. */
    public static final int SBC_CHANNEL_STEREO = 0x02;

    /** Joint stereo. */
    public static final int SBC_CHANNEL_JOINT_STEREO = 0x01;

    /** All channel modes supported. */
    public static final int SBC_CHANNEL_ALL = 0x0F;

    // ==================== SBC Block Lengths ====================

    /** 4 blocks. */
    public static final int SBC_BLOCK_4 = 0x80;

    /** 8 blocks. */
    public static final int SBC_BLOCK_8 = 0x40;

    /** 12 blocks. */
    public static final int SBC_BLOCK_12 = 0x20;

    /** 16 blocks. */
    public static final int SBC_BLOCK_16 = 0x10;

    /** All block lengths supported. */
    public static final int SBC_BLOCK_ALL = 0xF0;

    // ==================== SBC Subbands ====================

    /** 4 subbands. */
    public static final int SBC_SUBBAND_4 = 0x08;

    /** 8 subbands. */
    public static final int SBC_SUBBAND_8 = 0x04;

    /** All subbands supported. */
    public static final int SBC_SUBBAND_ALL = 0x0C;

    // ==================== SBC Allocation Methods ====================

    /** SNR allocation. */
    public static final int SBC_ALLOC_SNR = 0x02;

    /** Loudness allocation. */
    public static final int SBC_ALLOC_LOUDNESS = 0x01;

    /** All allocation methods supported. */
    public static final int SBC_ALLOC_ALL = 0x03;

    // ==================== SBC Bitpool Range ====================

    /** Minimum bitpool value. */
    public static final int SBC_MIN_BITPOOL = 2;

    /** Maximum bitpool for high quality. */
    public static final int SBC_MAX_BITPOOL_HQ = 53;

    /** Maximum bitpool for middle quality. */
    public static final int SBC_MAX_BITPOOL_MQ = 35;

    /** Absolute maximum bitpool. */
    public static final int SBC_MAX_BITPOOL = 250;

    // ==================== Error Codes ====================

    /** Bad header format. */
    public static final int ERR_BAD_HEADER_FORMAT = 0x01;

    /** Bad packet length. */
    public static final int ERR_BAD_LENGTH = 0x11;

    /** Invalid ACP SEID. */
    public static final int ERR_BAD_ACP_SEID = 0x12;

    /** SEP in use. */
    public static final int ERR_SEP_IN_USE = 0x13;

    /** SEP not in use. */
    public static final int ERR_SEP_NOT_IN_USE = 0x14;

    /** Bad service category. */
    public static final int ERR_BAD_SERVICE_CATEGORY = 0x17;

    /** Bad payload format. */
    public static final int ERR_BAD_PAYLOAD_FORMAT = 0x18;

    /** Not supported command. */
    public static final int ERR_NOT_SUPPORTED_COMMAND = 0x19;

    /** Invalid capabilities. */
    public static final int ERR_INVALID_CAPABILITIES = 0x1A;

    /** Bad recovery type. */
    public static final int ERR_BAD_RECOVERY_TYPE = 0x22;

    /** Bad media transport format. */
    public static final int ERR_BAD_MEDIA_TRANSPORT_FORMAT = 0x23;

    /** Bad recovery format. */
    public static final int ERR_BAD_RECOVERY_FORMAT = 0x25;

    /** Bad header compression format. */
    public static final int ERR_BAD_ROHC_FORMAT = 0x26;

    /** Bad content protection format. */
    public static final int ERR_BAD_CP_FORMAT = 0x27;

    /** Bad multiplexing format. */
    public static final int ERR_BAD_MULTIPLEXING_FORMAT = 0x28;

    /** Unsupported configuration. */
    public static final int ERR_UNSUPPORTED_CONFIGURATION = 0x29;

    /** Bad state. */
    public static final int ERR_BAD_STATE = 0x31;

    // ==================== RTP Constants ====================

    /** RTP version 2. */
    public static final int RTP_VERSION = 2;

    /** RTP payload type for dynamic assignment. */
    public static final int RTP_PAYLOAD_TYPE_DYNAMIC = 96;

    /** Default RTP SSRC. */
    public static final int RTP_SSRC_DEFAULT = 0;

    // ==================== Content Protection Types ====================

    /** DTCP content protection. */
    public static final int CP_TYPE_DTCP = 0x0001;

    /** SCMS-T content protection. */
    public static final int CP_TYPE_SCMS_T = 0x0002;

    // ==================== Utility Methods ====================

    /**
     * Returns the signal name for a signal ID.
     *
     * @param signalId signal identifier
     * @return human-readable signal name
     */
    public static String getSignalName(int signalId) {
        switch (signalId) {
            case AVDTP_DISCOVER: return "DISCOVER";
            case AVDTP_GET_CAPABILITIES: return "GET_CAPABILITIES";
            case AVDTP_SET_CONFIGURATION: return "SET_CONFIGURATION";
            case AVDTP_GET_CONFIGURATION: return "GET_CONFIGURATION";
            case AVDTP_RECONFIGURE: return "RECONFIGURE";
            case AVDTP_OPEN: return "OPEN";
            case AVDTP_START: return "START";
            case AVDTP_CLOSE: return "CLOSE";
            case AVDTP_SUSPEND: return "SUSPEND";
            case AVDTP_ABORT: return "ABORT";
            case AVDTP_SECURITY_CONTROL: return "SECURITY_CONTROL";
            case AVDTP_GET_ALL_CAPABILITIES: return "GET_ALL_CAPABILITIES";
            case AVDTP_DELAY_REPORT: return "DELAY_REPORT";
            default: return "UNKNOWN(0x" + Integer.toHexString(signalId) + ")";
        }
    }

    /**
     * Returns the message type name.
     *
     * @param msgType message type
     * @return human-readable name
     */
    public static String getMessageTypeName(int msgType) {
        switch (msgType) {
            case MSG_TYPE_COMMAND: return "COMMAND";
            case MSG_TYPE_GENERAL_REJECT: return "GENERAL_REJECT";
            case MSG_TYPE_RESPONSE_ACCEPT: return "RESPONSE_ACCEPT";
            case MSG_TYPE_RESPONSE_REJECT: return "RESPONSE_REJECT";
            default: return "UNKNOWN";
        }
    }

    /**
     * Returns the service category name.
     *
     * @param category service category
     * @return human-readable name
     */
    public static String getServiceCategoryName(int category) {
        switch (category) {
            case SC_MEDIA_TRANSPORT: return "Media Transport";
            case SC_REPORTING: return "Reporting";
            case SC_RECOVERY: return "Recovery";
            case SC_CONTENT_PROTECTION: return "Content Protection";
            case SC_HEADER_COMPRESSION: return "Header Compression";
            case SC_MULTIPLEXING: return "Multiplexing";
            case SC_MEDIA_CODEC: return "Media Codec";
            case SC_DELAY_REPORTING: return "Delay Reporting";
            default: return "Unknown(0x" + Integer.toHexString(category) + ")";
        }
    }

    /**
     * Returns the codec name.
     *
     * @param codecType codec type
     * @return human-readable name
     */
    public static String getCodecName(int codecType) {
        switch (codecType) {
            case CODEC_SBC: return "SBC";
            case CODEC_MPEG12_AUDIO: return "MPEG-1,2 Audio";
            case CODEC_MPEG24_AAC: return "AAC";
            case CODEC_ATRAC: return "ATRAC";
            case CODEC_VENDOR_SPECIFIC: return "Vendor Specific";
            default: return "Unknown(0x" + Integer.toHexString(codecType) + ")";
        }
    }

    /**
     * Returns the media type name.
     *
     * @param mediaType media type
     * @return human-readable name
     */
    public static String getMediaTypeName(int mediaType) {
        switch (mediaType) {
            case MEDIA_TYPE_AUDIO: return "Audio";
            case MEDIA_TYPE_VIDEO: return "Video";
            case MEDIA_TYPE_MULTIMEDIA: return "Multimedia";
            default: return "Unknown";
        }
    }

    /**
     * Returns the error description.
     *
     * @param errorCode error code
     * @return human-readable description
     */
    public static String getErrorString(int errorCode) {
        switch (errorCode) {
            case ERR_BAD_HEADER_FORMAT: return "Bad header format";
            case ERR_BAD_LENGTH: return "Bad length";
            case ERR_BAD_ACP_SEID: return "Invalid ACP SEID";
            case ERR_SEP_IN_USE: return "SEP in use";
            case ERR_SEP_NOT_IN_USE: return "SEP not in use";
            case ERR_BAD_SERVICE_CATEGORY: return "Bad service category";
            case ERR_BAD_PAYLOAD_FORMAT: return "Bad payload format";
            case ERR_NOT_SUPPORTED_COMMAND: return "Command not supported";
            case ERR_INVALID_CAPABILITIES: return "Invalid capabilities";
            case ERR_BAD_RECOVERY_TYPE: return "Bad recovery type";
            case ERR_BAD_MEDIA_TRANSPORT_FORMAT: return "Bad media transport format";
            case ERR_BAD_RECOVERY_FORMAT: return "Bad recovery format";
            case ERR_BAD_ROHC_FORMAT: return "Bad header compression format";
            case ERR_BAD_CP_FORMAT: return "Bad content protection format";
            case ERR_BAD_MULTIPLEXING_FORMAT: return "Bad multiplexing format";
            case ERR_UNSUPPORTED_CONFIGURATION: return "Unsupported configuration";
            case ERR_BAD_STATE: return "Bad state";
            default: return "Unknown error (0x" + Integer.toHexString(errorCode) + ")";
        }
    }

    /**
     * Converts SBC frequency flag to Hz.
     *
     * @param freqFlag SBC frequency flag
     * @return sample rate in Hz
     */
    public static int sbcFrequencyToHz(int freqFlag) {
        if ((freqFlag & SBC_FREQ_16000) != 0) return 16000;
        if ((freqFlag & SBC_FREQ_32000) != 0) return 32000;
        if ((freqFlag & SBC_FREQ_44100) != 0) return 44100;
        if ((freqFlag & SBC_FREQ_48000) != 0) return 48000;
        return 44100; // Default
    }

    /**
     * Converts Hz to SBC frequency flag.
     *
     * @param hz sample rate in Hz
     * @return SBC frequency flag
     */
    public static int hzToSbcFrequency(int hz) {
        switch (hz) {
            case 16000: return SBC_FREQ_16000;
            case 32000: return SBC_FREQ_32000;
            case 44100: return SBC_FREQ_44100;
            case 48000: return SBC_FREQ_48000;
            default: return SBC_FREQ_44100;
        }
    }

    /**
     * Returns the channel count for an SBC channel mode.
     *
     * @param channelMode SBC channel mode flag
     * @return number of channels (1 or 2)
     */
    public static int sbcChannelCount(int channelMode) {
        return (channelMode & SBC_CHANNEL_MONO) != 0 ? 1 : 2;
    }

    /**
     * Converts SBC block length flag to value.
     *
     * @param blockFlag SBC block length flag
     * @return number of blocks (4, 8, 12, or 16)
     */
    public static int sbcBlocksToValue(int blockFlag) {
        if ((blockFlag & SBC_BLOCK_4) != 0) return 4;
        if ((blockFlag & SBC_BLOCK_8) != 0) return 8;
        if ((blockFlag & SBC_BLOCK_12) != 0) return 12;
        if ((blockFlag & SBC_BLOCK_16) != 0) return 16;
        return 16; // Default
    }

    /**
     * Converts block count to SBC flag.
     *
     * @param blocks number of blocks
     * @return SBC block length flag
     */
    public static int valueToSbcBlocks(int blocks) {
        switch (blocks) {
            case 4: return SBC_BLOCK_4;
            case 8: return SBC_BLOCK_8;
            case 12: return SBC_BLOCK_12;
            case 16: return SBC_BLOCK_16;
            default: return SBC_BLOCK_16;
        }
    }

    /**
     * Converts SBC subbands flag to value.
     *
     * @param subbandsFlag SBC subbands flag
     * @return number of subbands (4 or 8)
     */
    public static int sbcSubbandsToValue(int subbandsFlag) {
        return (subbandsFlag & SBC_SUBBAND_4) != 0 ? 4 : 8;
    }

    /**
     * Converts subbands count to SBC flag.
     *
     * @param subbands number of subbands
     * @return SBC subbands flag
     */
    public static int valueToSbcSubbands(int subbands) {
        return subbands == 4 ? SBC_SUBBAND_4 : SBC_SUBBAND_8;
    }

    /**
     * Calculates SBC frame size.
     *
     * @param subbands    number of subbands (4 or 8)
     * @param blocks      number of blocks (4, 8, 12, or 16)
     * @param channels    number of channels (1 or 2)
     * @param bitpool     bitpool value
     * @param jointStereo true if joint stereo mode
     * @return frame size in bytes
     */
    public static int calculateSbcFrameSize(int subbands, int blocks, int channels,
                                            int bitpool, boolean jointStereo) {
        int frameLength = 4; // Header
        frameLength += (4 * subbands * channels) / 8; // Scale factors

        if (channels == 1) {
            frameLength += (blocks * bitpool) / 8;
        } else if (jointStereo) {
            frameLength += (subbands + blocks * bitpool) / 8;
        } else {
            frameLength += (2 * blocks * bitpool) / 8;
        }

        return frameLength;
    }

    /**
     * Calculates samples per SBC frame.
     *
     * @param subbands number of subbands
     * @param blocks   number of blocks
     * @return samples per frame per channel
     */
    public static int calculateSbcSamplesPerFrame(int subbands, int blocks) {
        return subbands * blocks;
    }
}