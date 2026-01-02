package com.courierstack.codec;

/**
 * Configuration parameters for audio codecs.
 *
 * This class provides a unified way to configure different audio codecs
 * with their specific parameters. Use the builder pattern or factory
 * methods for common configurations.
 *
 * <h2>Usage Examples:</h2>
 * <pre>
 * // SBC default configuration (44.1kHz, Joint Stereo)
 * AudioCodecConfig config = AudioCodecConfig.sbcDefault();
 *
 * // Custom SBC configuration
 * AudioCodecConfig config = new AudioCodecConfig.Builder(CodecType.SBC)
 *     .sampleRate(48000)
 *     .channelMode(ChannelMode.STEREO)
 *     .sbcBitpool(53)
 *     .sbcBlocks(16)
 *     .sbcSubbands(8)
 *     .build();
 *
 * // AAC configuration
 * AudioCodecConfig config = new AudioCodecConfig.Builder(CodecType.AAC)
 *     .sampleRate(44100)
 *     .channelMode(ChannelMode.STEREO)
 *     .bitrate(256000)
 *     .build();
 * </pre>
 */
public class AudioCodecConfig {

    /**
     * Channel mode for stereo encoding.
     */
    public enum ChannelMode {
        MONO(0x08, 1),
        DUAL_CHANNEL(0x04, 2),
        STEREO(0x02, 2),
        JOINT_STEREO(0x01, 2);

        public final int sbcFlag;
        public final int channelCount;

        ChannelMode(int sbcFlag, int channelCount) {
            this.sbcFlag = sbcFlag;
            this.channelCount = channelCount;
        }

        public static ChannelMode fromSbcFlag(int flag) {
            for (ChannelMode mode : values()) {
                if ((mode.sbcFlag & flag) != 0) return mode;
            }
            return JOINT_STEREO;
        }
    }

    /**
     * SBC bit allocation method.
     */
    public enum AllocationMethod {
        SNR(0x02),
        LOUDNESS(0x01);

        public final int sbcFlag;

        AllocationMethod(int sbcFlag) {
            this.sbcFlag = sbcFlag;
        }

        public static AllocationMethod fromSbcFlag(int flag) {
            return (flag & SNR.sbcFlag) != 0 ? SNR : LOUDNESS;
        }
    }

    // Common parameters
    private final AudioCodec.CodecType codecType;
    private final int sampleRate;
    private final ChannelMode channelMode;
    private final int bitrate;

    // SBC-specific parameters
    private final int sbcBlocks;      // 4, 8, 12, or 16
    private final int sbcSubbands;    // 4 or 8
    private final int sbcBitpool;     // 2-250 (typically 2-53 for A2DP)
    private final int sbcMinBitpool;
    private final int sbcMaxBitpool;
    private final AllocationMethod sbcAllocation;

    // AAC-specific parameters
    private final int aacObjectType;  // MPEG-2 AAC-LC, MPEG-4 AAC-LC, etc.
    private final boolean aacVbr;     // Variable bitrate

    // Vendor-specific parameters
    private final int vendorId;
    private final int vendorCodecId;
    private final byte[] vendorData;

    private AudioCodecConfig(Builder builder) {
        this.codecType = builder.codecType;
        this.sampleRate = builder.sampleRate;
        this.channelMode = builder.channelMode;
        this.bitrate = builder.bitrate;
        this.sbcBlocks = builder.sbcBlocks;
        this.sbcSubbands = builder.sbcSubbands;
        this.sbcBitpool = builder.sbcBitpool;
        this.sbcMinBitpool = builder.sbcMinBitpool;
        this.sbcMaxBitpool = builder.sbcMaxBitpool;
        this.sbcAllocation = builder.sbcAllocation;
        this.aacObjectType = builder.aacObjectType;
        this.aacVbr = builder.aacVbr;
        this.vendorId = builder.vendorId;
        this.vendorCodecId = builder.vendorCodecId;
        this.vendorData = builder.vendorData;
    }

    // ==================== Factory Methods ====================

    /**
     * Creates default SBC configuration for high-quality A2DP streaming.
     * 44.1kHz, Joint Stereo, 16 blocks, 8 subbands, bitpool 53.
     */
    public static AudioCodecConfig sbcDefault() {
        return new Builder(AudioCodec.CodecType.SBC)
                .sampleRate(44100)
                .channelMode(ChannelMode.JOINT_STEREO)
                .sbcBlocks(16)
                .sbcSubbands(8)
                .sbcBitpool(53)
                .sbcAllocation(AllocationMethod.LOUDNESS)
                .build();
    }

    /**
     * Creates SBC configuration for maximum quality.
     * 48kHz, Joint Stereo, 16 blocks, 8 subbands, bitpool 53.
     */
    public static AudioCodecConfig sbcHighQuality() {
        return new Builder(AudioCodec.CodecType.SBC)
                .sampleRate(48000)
                .channelMode(ChannelMode.JOINT_STEREO)
                .sbcBlocks(16)
                .sbcSubbands(8)
                .sbcBitpool(53)
                .sbcAllocation(AllocationMethod.LOUDNESS)
                .build();
    }

    /**
     * Creates SBC configuration for medium quality (lower bandwidth).
     * 44.1kHz, Joint Stereo, 16 blocks, 8 subbands, bitpool 35.
     */
    public static AudioCodecConfig sbcMediumQuality() {
        return new Builder(AudioCodec.CodecType.SBC)
                .sampleRate(44100)
                .channelMode(ChannelMode.JOINT_STEREO)
                .sbcBlocks(16)
                .sbcSubbands(8)
                .sbcBitpool(35)
                .sbcAllocation(AllocationMethod.LOUDNESS)
                .build();
    }

    /**
     * Creates mSBC configuration for HFP wideband speech.
     * 16kHz, Mono, 15 blocks, 8 subbands, bitpool 26.
     */
    public static AudioCodecConfig msbcDefault() {
        return new Builder(AudioCodec.CodecType.SBC)
                .sampleRate(16000)
                .channelMode(ChannelMode.MONO)
                .sbcBlocks(15)
                .sbcSubbands(8)
                .sbcBitpool(26)
                .sbcAllocation(AllocationMethod.LOUDNESS)
                .build();
    }

    /**
     * Creates default AAC configuration.
     * 44.1kHz, Stereo, AAC-LC, 256kbps.
     */
    public static AudioCodecConfig aacDefault() {
        return new Builder(AudioCodec.CodecType.AAC)
                .sampleRate(44100)
                .channelMode(ChannelMode.STEREO)
                .bitrate(256000)
                .aacObjectType(2) // AAC-LC
                .build();
    }

    // ==================== Getters ====================

    public AudioCodec.CodecType getCodecType() { return codecType; }
    public int getSampleRate() { return sampleRate; }
    public ChannelMode getChannelMode() { return channelMode; }
    public int getChannelCount() { return channelMode.channelCount; }
    public int getBitrate() { return bitrate; }

    // SBC getters
    public int getSbcBlocks() { return sbcBlocks; }
    public int getSbcSubbands() { return sbcSubbands; }
    public int getSbcBitpool() { return sbcBitpool; }
    public int getSbcMinBitpool() { return sbcMinBitpool; }
    public int getSbcMaxBitpool() { return sbcMaxBitpool; }
    public AllocationMethod getSbcAllocation() { return sbcAllocation; }

    // AAC getters
    public int getAacObjectType() { return aacObjectType; }
    public boolean isAacVbr() { return aacVbr; }

    // Vendor getters
    public int getVendorId() { return vendorId; }
    public int getVendorCodecId() { return vendorCodecId; }
    public byte[] getVendorData() { return vendorData; }

    // ==================== SBC Helpers ====================

    /**
     * Returns the SBC sampling frequency flag.
     */
    public int getSbcFrequencyFlag() {
        switch (sampleRate) {
            case 16000: return 0x80;
            case 32000: return 0x40;
            case 44100: return 0x20;
            case 48000: return 0x10;
            default: return 0x20;
        }
    }

    /**
     * Returns the SBC block length flag.
     */
    public int getSbcBlocksFlag() {
        switch (sbcBlocks) {
            case 4: return 0x80;
            case 8: return 0x40;
            case 12: return 0x20;
            case 16: return 0x10;
            default: return 0x10;
        }
    }

    /**
     * Returns the SBC subbands flag.
     */
    public int getSbcSubbandsFlag() {
        return sbcSubbands == 4 ? 0x08 : 0x04;
    }

    /**
     * Calculates estimated SBC bitrate.
     */
    public int calculateSbcBitrate() {
        int frameLength = 4 + (4 * sbcSubbands * channelMode.channelCount) / 8;
        if (channelMode == ChannelMode.MONO || channelMode == ChannelMode.DUAL_CHANNEL) {
            frameLength += (sbcBlocks * channelMode.channelCount * sbcBitpool) / 8;
        } else {
            frameLength += (((channelMode == ChannelMode.JOINT_STEREO ? 1 : 0) * sbcSubbands)
                    + sbcBlocks * sbcBitpool) / 8;
        }
        int frameDurationUs = (sbcBlocks * sbcSubbands * 1000000) / sampleRate;
        return (frameLength * 8 * 1000000) / frameDurationUs;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("AudioCodecConfig{");
        sb.append("type=").append(codecType);
        sb.append(", rate=").append(sampleRate);
        sb.append(", mode=").append(channelMode);
        if (codecType == AudioCodec.CodecType.SBC) {
            sb.append(", blocks=").append(sbcBlocks);
            sb.append(", subbands=").append(sbcSubbands);
            sb.append(", bitpool=").append(sbcBitpool);
            sb.append(", alloc=").append(sbcAllocation);
        } else if (codecType == AudioCodec.CodecType.AAC) {
            sb.append(", bitrate=").append(bitrate);
            sb.append(", vbr=").append(aacVbr);
        }
        sb.append("}");
        return sb.toString();
    }

    // ==================== Builder ====================

    public static class Builder {
        private final AudioCodec.CodecType codecType;
        private int sampleRate = 44100;
        private ChannelMode channelMode = ChannelMode.JOINT_STEREO;
        private int bitrate = 328000;

        // SBC defaults
        private int sbcBlocks = 16;
        private int sbcSubbands = 8;
        private int sbcBitpool = 53;
        private int sbcMinBitpool = 2;
        private int sbcMaxBitpool = 53;
        private AllocationMethod sbcAllocation = AllocationMethod.LOUDNESS;

        // AAC defaults
        private int aacObjectType = 2; // AAC-LC
        private boolean aacVbr = false;

        // Vendor defaults
        private int vendorId = 0;
        private int vendorCodecId = 0;
        private byte[] vendorData = null;

        public Builder(AudioCodec.CodecType codecType) {
            this.codecType = codecType;
        }

        public Builder sampleRate(int sampleRate) {
            this.sampleRate = sampleRate;
            return this;
        }

        public Builder channelMode(ChannelMode channelMode) {
            this.channelMode = channelMode;
            return this;
        }

        public Builder bitrate(int bitrate) {
            this.bitrate = bitrate;
            return this;
        }

        public Builder sbcBlocks(int blocks) {
            this.sbcBlocks = blocks;
            return this;
        }

        public Builder sbcSubbands(int subbands) {
            this.sbcSubbands = subbands;
            return this;
        }

        public Builder sbcBitpool(int bitpool) {
            this.sbcBitpool = bitpool;
            return this;
        }

        public Builder sbcBitpoolRange(int min, int max) {
            this.sbcMinBitpool = min;
            this.sbcMaxBitpool = max;
            return this;
        }

        public Builder sbcAllocation(AllocationMethod allocation) {
            this.sbcAllocation = allocation;
            return this;
        }

        public Builder aacObjectType(int objectType) {
            this.aacObjectType = objectType;
            return this;
        }

        public Builder aacVbr(boolean vbr) {
            this.aacVbr = vbr;
            return this;
        }

        public Builder vendorId(int vendorId) {
            this.vendorId = vendorId;
            return this;
        }

        public Builder vendorCodecId(int codecId) {
            this.vendorCodecId = codecId;
            return this;
        }

        public Builder vendorData(byte[] data) {
            this.vendorData = data;
            return this;
        }

        public AudioCodecConfig build() {
            return new AudioCodecConfig(this);
        }
    }
}