package com.courierstack.avdtp;

import java.util.Arrays;

/**
 * Represents an AVDTP Stream Endpoint (SEP).
 *
 * <p>A stream endpoint is the basic unit of audio/video streaming in AVDTP.
 * Each endpoint has a unique SEID (1-63), type (source/sink), and capabilities.
 *
 * <p>This class supports both local endpoints (registered for incoming connections)
 * and remote endpoints (discovered via AVDTP_DISCOVER).
 *
 * <p>Usage example:
 * <pre>{@code
 * // Create a local SBC source endpoint
 * StreamEndpoint source = StreamEndpoint.builder()
 *     .seid(1)
 *     .source()
 *     .audio()
 *     .codecSbc()
 *     .sbcConfig(44100, SBC_CHANNEL_JOINT_STEREO, 16, 8, SBC_ALLOC_LOUDNESS, 2, 53)
 *     .build();
 *
 * // Create a local SBC sink endpoint
 * StreamEndpoint sink = StreamEndpoint.builder()
 *     .seid(2)
 *     .sink()
 *     .audio()
 *     .codecSbc()
 *     .sbcAllCapabilities()
 *     .build();
 * }</pre>
 *
 * <p>Thread safety: This class is not thread-safe. External synchronization
 * is required for concurrent access.
 */
public class StreamEndpoint {

    // ==================== Core Fields ====================

    /** Stream Endpoint ID (1-63). */
    private int seid;

    /** True if endpoint is currently in use. */
    private boolean inUse;

    /** Media type (audio/video/multimedia). */
    private int mediaType;

    /** Endpoint type (0=Source, 1=Sink). */
    private int tsep;

    /** Codec type (SBC, AAC, etc.). */
    private int codecType;

    // ==================== Capability/Configuration Data ====================

    /** Raw capability data from AVDTP capability exchange. */
    private byte[] capabilities;

    /** Configuration data from SET_CONFIGURATION. */
    private byte[] configuration;

    // ==================== Parsed SBC Parameters ====================

    /** SBC sampling frequency flag. */
    private int sbcFrequency = AvdtpConstants.SBC_FREQ_44100;

    /** SBC channel mode flag. */
    private int sbcChannelMode = AvdtpConstants.SBC_CHANNEL_JOINT_STEREO;

    /** SBC block length flag. */
    private int sbcBlockLength = AvdtpConstants.SBC_BLOCK_16;

    /** SBC subbands flag. */
    private int sbcSubbands = AvdtpConstants.SBC_SUBBAND_8;

    /** SBC allocation method flag. */
    private int sbcAllocation = AvdtpConstants.SBC_ALLOC_LOUDNESS;

    /** SBC minimum bitpool. */
    private int sbcMinBitpool = AvdtpConstants.SBC_MIN_BITPOOL;

    /** SBC maximum bitpool. */
    private int sbcMaxBitpool = AvdtpConstants.SBC_MAX_BITPOOL_HQ;

    // ==================== AAC Parameters ====================

    /** AAC object type. */
    private int aacObjectType;

    /** AAC sampling frequency. */
    private int aacSampleRate;

    /** AAC channels. */
    private int aacChannels;

    /** AAC bitrate. */
    private int aacBitrate;

    /** AAC VBR supported. */
    private boolean aacVbrSupported;

    // ==================== Constructors ====================

    /**
     * Default constructor.
     */
    public StreamEndpoint() {
    }

    /**
     * Creates a stream endpoint with specified parameters.
     *
     * @param seid      Stream Endpoint ID (1-63)
     * @param tsep      Endpoint type (TSEP_SRC or TSEP_SNK)
     * @param mediaType Media type (MEDIA_TYPE_AUDIO, etc.)
     * @param codecType Codec type (CODEC_SBC, etc.)
     */
    public StreamEndpoint(int seid, int tsep, int mediaType, int codecType) {
        this.seid = seid;
        this.tsep = tsep;
        this.mediaType = mediaType;
        this.codecType = codecType;
    }

    /**
     * Copy constructor.
     *
     * @param other endpoint to copy
     */
    public StreamEndpoint(StreamEndpoint other) {
        this.seid = other.seid;
        this.inUse = other.inUse;
        this.mediaType = other.mediaType;
        this.tsep = other.tsep;
        this.codecType = other.codecType;
        this.capabilities = other.capabilities != null ? other.capabilities.clone() : null;
        this.configuration = other.configuration != null ? other.configuration.clone() : null;
        this.sbcFrequency = other.sbcFrequency;
        this.sbcChannelMode = other.sbcChannelMode;
        this.sbcBlockLength = other.sbcBlockLength;
        this.sbcSubbands = other.sbcSubbands;
        this.sbcAllocation = other.sbcAllocation;
        this.sbcMinBitpool = other.sbcMinBitpool;
        this.sbcMaxBitpool = other.sbcMaxBitpool;
        this.aacObjectType = other.aacObjectType;
        this.aacSampleRate = other.aacSampleRate;
        this.aacChannels = other.aacChannels;
        this.aacBitrate = other.aacBitrate;
        this.aacVbrSupported = other.aacVbrSupported;
    }

    // ==================== Getters and Setters ====================

    public int getSeid() { return seid; }
    public void setSeid(int seid) { this.seid = seid; }

    public boolean isInUse() { return inUse; }
    public void setInUse(boolean inUse) { this.inUse = inUse; }

    public int getMediaType() { return mediaType; }
    public void setMediaType(int mediaType) { this.mediaType = mediaType; }

    public int getTsep() { return tsep; }
    public void setTsep(int tsep) { this.tsep = tsep; }

    public int getCodecType() { return codecType; }
    public void setCodecType(int codecType) { this.codecType = codecType; }

    public byte[] getCapabilities() {
        return capabilities != null ? capabilities.clone() : null;
    }
    public void setCapabilities(byte[] capabilities) {
        this.capabilities = capabilities != null ? capabilities.clone() : null;
    }

    public byte[] getConfiguration() {
        return configuration != null ? configuration.clone() : null;
    }
    public void setConfiguration(byte[] configuration) {
        this.configuration = configuration != null ? configuration.clone() : null;
    }

    // SBC getters/setters
    public int getSbcFrequency() { return sbcFrequency; }
    public void setSbcFrequency(int sbcFrequency) { this.sbcFrequency = sbcFrequency; }

    public int getSbcChannelMode() { return sbcChannelMode; }
    public void setSbcChannelMode(int sbcChannelMode) { this.sbcChannelMode = sbcChannelMode; }

    public int getSbcBlockLength() { return sbcBlockLength; }
    public void setSbcBlockLength(int sbcBlockLength) { this.sbcBlockLength = sbcBlockLength; }

    public int getSbcSubbands() { return sbcSubbands; }
    public void setSbcSubbands(int sbcSubbands) { this.sbcSubbands = sbcSubbands; }

    public int getSbcAllocation() { return sbcAllocation; }
    public void setSbcAllocation(int sbcAllocation) { this.sbcAllocation = sbcAllocation; }

    public int getSbcMinBitpool() { return sbcMinBitpool; }
    public void setSbcMinBitpool(int sbcMinBitpool) { this.sbcMinBitpool = sbcMinBitpool; }

    public int getSbcMaxBitpool() { return sbcMaxBitpool; }
    public void setSbcMaxBitpool(int sbcMaxBitpool) { this.sbcMaxBitpool = sbcMaxBitpool; }

    // AAC getters/setters
    public int getAacObjectType() { return aacObjectType; }
    public void setAacObjectType(int aacObjectType) { this.aacObjectType = aacObjectType; }

    public int getAacSampleRate() { return aacSampleRate; }
    public void setAacSampleRate(int aacSampleRate) { this.aacSampleRate = aacSampleRate; }

    public int getAacChannels() { return aacChannels; }
    public void setAacChannels(int aacChannels) { this.aacChannels = aacChannels; }

    public int getAacBitrate() { return aacBitrate; }
    public void setAacBitrate(int aacBitrate) { this.aacBitrate = aacBitrate; }

    public boolean isAacVbrSupported() { return aacVbrSupported; }
    public void setAacVbrSupported(boolean aacVbrSupported) { this.aacVbrSupported = aacVbrSupported; }

    // ==================== Convenience Methods ====================

    /**
     * Returns true if this is a source endpoint.
     */
    public boolean isSource() {
        return tsep == AvdtpConstants.TSEP_SRC;
    }

    /**
     * Returns true if this is a sink endpoint.
     */
    public boolean isSink() {
        return tsep == AvdtpConstants.TSEP_SNK;
    }

    /**
     * Returns true if this is an audio endpoint.
     */
    public boolean isAudio() {
        return mediaType == AvdtpConstants.MEDIA_TYPE_AUDIO;
    }

    /**
     * Returns true if this endpoint uses SBC codec.
     */
    public boolean isSbc() {
        return codecType == AvdtpConstants.CODEC_SBC;
    }

    /**
     * Returns true if this endpoint uses AAC codec.
     */
    public boolean isAac() {
        return codecType == AvdtpConstants.CODEC_MPEG24_AAC;
    }

    /**
     * Returns the sample rate in Hz based on configured frequency.
     */
    public int getSampleRateHz() {
        return AvdtpConstants.sbcFrequencyToHz(sbcFrequency);
    }

    /**
     * Returns the number of audio channels.
     */
    public int getChannelCount() {
        return AvdtpConstants.sbcChannelCount(sbcChannelMode);
    }

    /**
     * Returns the number of SBC blocks.
     */
    public int getBlockCount() {
        return AvdtpConstants.sbcBlocksToValue(sbcBlockLength);
    }

    /**
     * Returns the number of SBC subbands.
     */
    public int getSubbandCount() {
        return AvdtpConstants.sbcSubbandsToValue(sbcSubbands);
    }

    /**
     * Returns true if joint stereo mode is configured.
     */
    public boolean isJointStereo() {
        return (sbcChannelMode & AvdtpConstants.SBC_CHANNEL_JOINT_STEREO) != 0;
    }

    /**
     * Returns samples per frame for the configured SBC settings.
     */
    public int getSamplesPerFrame() {
        return getBlockCount() * getSubbandCount();
    }

    /**
     * Calculates the SBC frame size for the configured settings.
     *
     * @param bitpool bitpool value to use
     * @return frame size in bytes
     */
    public int calculateFrameSize(int bitpool) {
        return AvdtpConstants.calculateSbcFrameSize(
                getSubbandCount(),
                getBlockCount(),
                getChannelCount(),
                bitpool,
                isJointStereo()
        );
    }

    // ==================== Builder ====================

    /**
     * Creates a new builder for StreamEndpoint.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating StreamEndpoint instances.
     */
    public static class Builder {
        private final StreamEndpoint endpoint = new StreamEndpoint();

        /**
         * Sets the Stream Endpoint ID.
         *
         * @param seid SEID (1-63)
         * @return this builder
         */
        public Builder seid(int seid) {
            endpoint.seid = seid;
            return this;
        }

        /**
         * Sets this endpoint as a source.
         *
         * @return this builder
         */
        public Builder source() {
            endpoint.tsep = AvdtpConstants.TSEP_SRC;
            return this;
        }

        /**
         * Sets this endpoint as a sink.
         *
         * @return this builder
         */
        public Builder sink() {
            endpoint.tsep = AvdtpConstants.TSEP_SNK;
            return this;
        }

        /**
         * Sets the endpoint type.
         *
         * @param tsep TSEP_SRC or TSEP_SNK
         * @return this builder
         */
        public Builder tsep(int tsep) {
            endpoint.tsep = tsep;
            return this;
        }

        /**
         * Sets media type to audio.
         *
         * @return this builder
         */
        public Builder audio() {
            endpoint.mediaType = AvdtpConstants.MEDIA_TYPE_AUDIO;
            return this;
        }

        /**
         * Sets the media type.
         *
         * @param mediaType media type constant
         * @return this builder
         */
        public Builder mediaType(int mediaType) {
            endpoint.mediaType = mediaType;
            return this;
        }

        /**
         * Sets codec to SBC.
         *
         * @return this builder
         */
        public Builder codecSbc() {
            endpoint.codecType = AvdtpConstants.CODEC_SBC;
            return this;
        }

        /**
         * Sets codec to AAC.
         *
         * @return this builder
         */
        public Builder codecAac() {
            endpoint.codecType = AvdtpConstants.CODEC_MPEG24_AAC;
            return this;
        }

        /**
         * Sets the codec type.
         *
         * @param codecType codec type constant
         * @return this builder
         */
        public Builder codecType(int codecType) {
            endpoint.codecType = codecType;
            return this;
        }

        /**
         * Configures SBC with specific parameters.
         *
         * @param sampleRate    sample rate in Hz
         * @param channelMode   SBC channel mode flag
         * @param blocks        number of blocks
         * @param subbands      number of subbands
         * @param allocation    allocation method flag
         * @param minBitpool    minimum bitpool
         * @param maxBitpool    maximum bitpool
         * @return this builder
         */
        public Builder sbcConfig(int sampleRate, int channelMode, int blocks, int subbands,
                                 int allocation, int minBitpool, int maxBitpool) {
            endpoint.sbcFrequency = AvdtpConstants.hzToSbcFrequency(sampleRate);
            endpoint.sbcChannelMode = channelMode;
            endpoint.sbcBlockLength = AvdtpConstants.valueToSbcBlocks(blocks);
            endpoint.sbcSubbands = AvdtpConstants.valueToSbcSubbands(subbands);
            endpoint.sbcAllocation = allocation;
            endpoint.sbcMinBitpool = minBitpool;
            endpoint.sbcMaxBitpool = maxBitpool;
            return this;
        }

        /**
         * Configures SBC with all capabilities supported.
         *
         * @return this builder
         */
        public Builder sbcAllCapabilities() {
            endpoint.sbcFrequency = AvdtpConstants.SBC_FREQ_ALL;
            endpoint.sbcChannelMode = AvdtpConstants.SBC_CHANNEL_ALL;
            endpoint.sbcBlockLength = AvdtpConstants.SBC_BLOCK_ALL;
            endpoint.sbcSubbands = AvdtpConstants.SBC_SUBBAND_ALL;
            endpoint.sbcAllocation = AvdtpConstants.SBC_ALLOC_ALL;
            endpoint.sbcMinBitpool = AvdtpConstants.SBC_MIN_BITPOOL;
            endpoint.sbcMaxBitpool = AvdtpConstants.SBC_MAX_BITPOOL_HQ;
            return this;
        }

        /**
         * Configures SBC for high quality (44.1kHz, Joint Stereo, bitpool 53).
         *
         * @return this builder
         */
        public Builder sbcHighQuality() {
            endpoint.sbcFrequency = AvdtpConstants.SBC_FREQ_44100;
            endpoint.sbcChannelMode = AvdtpConstants.SBC_CHANNEL_JOINT_STEREO;
            endpoint.sbcBlockLength = AvdtpConstants.SBC_BLOCK_16;
            endpoint.sbcSubbands = AvdtpConstants.SBC_SUBBAND_8;
            endpoint.sbcAllocation = AvdtpConstants.SBC_ALLOC_LOUDNESS;
            endpoint.sbcMinBitpool = AvdtpConstants.SBC_MIN_BITPOOL;
            endpoint.sbcMaxBitpool = AvdtpConstants.SBC_MAX_BITPOOL_HQ;
            return this;
        }

        /**
         * Sets the SBC sampling frequency.
         *
         * @param frequency SBC frequency flag
         * @return this builder
         */
        public Builder sbcFrequency(int frequency) {
            endpoint.sbcFrequency = frequency;
            return this;
        }

        /**
         * Sets the SBC channel mode.
         *
         * @param channelMode SBC channel mode flag
         * @return this builder
         */
        public Builder sbcChannelMode(int channelMode) {
            endpoint.sbcChannelMode = channelMode;
            return this;
        }

        /**
         * Sets the SBC bitpool range.
         *
         * @param min minimum bitpool
         * @param max maximum bitpool
         * @return this builder
         */
        public Builder sbcBitpool(int min, int max) {
            endpoint.sbcMinBitpool = min;
            endpoint.sbcMaxBitpool = max;
            return this;
        }

        /**
         * Sets raw capabilities data.
         *
         * @param capabilities capability bytes
         * @return this builder
         */
        public Builder capabilities(byte[] capabilities) {
            endpoint.capabilities = capabilities != null ? capabilities.clone() : null;
            return this;
        }

        /**
         * Builds the StreamEndpoint.
         *
         * @return new StreamEndpoint instance
         */
        public StreamEndpoint build() {
            // Build capabilities if not set
            if (endpoint.capabilities == null && endpoint.codecType == AvdtpConstants.CODEC_SBC) {
                endpoint.capabilities = buildSbcCapabilities(endpoint);
            }

            return endpoint;
        }

        private byte[] buildSbcCapabilities(StreamEndpoint ep) {
            return new byte[] {
                    (byte) AvdtpConstants.SC_MEDIA_TRANSPORT, 0,
                    (byte) AvdtpConstants.SC_MEDIA_CODEC, 6,
                    (byte) (AvdtpConstants.MEDIA_TYPE_AUDIO << 4), (byte) AvdtpConstants.CODEC_SBC,
                    (byte) (ep.sbcFrequency | ep.sbcChannelMode),
                    (byte) (ep.sbcBlockLength | ep.sbcSubbands | ep.sbcAllocation),
                    (byte) ep.sbcMinBitpool,
                    (byte) ep.sbcMaxBitpool
            };
        }
    }

    // ==================== Object Methods ====================

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("StreamEndpoint{");
        sb.append("seid=").append(seid);
        sb.append(", type=").append(AvdtpConstants.getMediaTypeName(mediaType));
        sb.append(", tsep=").append(isSource() ? "Source" : "Sink");
        sb.append(", codec=").append(AvdtpConstants.getCodecName(codecType));
        sb.append(", inUse=").append(inUse);
        if (codecType == AvdtpConstants.CODEC_SBC) {
            sb.append(", sbc=[");
            sb.append(getSampleRateHz()).append("Hz, ");
            sb.append(getChannelCount()).append("ch, ");
            sb.append("bp=").append(sbcMinBitpool).append("-").append(sbcMaxBitpool);
            sb.append("]");
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StreamEndpoint that = (StreamEndpoint) o;
        return seid == that.seid &&
                mediaType == that.mediaType &&
                tsep == that.tsep &&
                codecType == that.codecType;
    }

    @Override
    public int hashCode() {
        return 31 * seid + mediaType + tsep + codecType;
    }
}