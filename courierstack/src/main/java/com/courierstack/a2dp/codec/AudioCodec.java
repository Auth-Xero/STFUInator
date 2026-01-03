package com.courierstack.a2dp.codec;

/**
 * Audio codec interface for A2DP streaming.
 *
 * This interface provides an extensible foundation for audio encoding/decoding
 * in Bluetooth A2DP applications. Implementations can provide SBC, AAC, aptX,
 * LDAC, or other codec support.
 *
 * <h2>Usage Example:</h2>
 * <pre>
 * // Create an SBC encoder
 * AudioCodec codec = AudioCodecFactory.createEncoder(CodecType.SBC);
 * codec.configure(AudioCodecConfig.sbcDefault());
 *
 * // Encode PCM data
 * short[] pcmSamples = ...;
 * byte[] encoded = codec.encode(pcmSamples);
 *
 * // Send via AVDTP
 * avdtpManager.sendMediaData(handle, encoded);
 * </pre>
 *
 * <h2>Implementing Custom Codecs:</h2>
 * <pre>
 * public class LdacCodec implements AudioCodec {
 *     // Implement all methods
 *     // Register with: AudioCodecFactory.registerCodec(CodecType.LDAC, LdacCodec::new);
 * }
 * </pre>
 *
 */
public interface AudioCodec {

    /**
     * Codec type identifiers matching A2DP specification.
     */
    enum CodecType {
        /** Sub-Band Codec - mandatory for A2DP */
        SBC(0x00),
        /** MPEG-1,2 Audio */
        MPEG12(0x01),
        /** MPEG-2,4 AAC */
        AAC(0x02),
        /** ATRAC family */
        ATRAC(0x04),
        /** Vendor-specific (aptX, LDAC, etc.) */
        VENDOR(0xFF);

        public final int id;

        CodecType(int id) {
            this.id = id;
        }

        public static CodecType fromId(int id) {
            for (CodecType type : values()) {
                if (type.id == id) return type;
            }
            return VENDOR;
        }
    }

    /**
     * Codec operating mode.
     */
    enum Mode {
        ENCODER,
        DECODER
    }

    /**
     * Returns the codec type.
     * @return codec type identifier
     */
    CodecType getType();

    /**
     * Returns the operating mode.
     * @return encoder or decoder mode
     */
    Mode getMode();

    /**
     * Returns the codec name for display.
     * @return human-readable codec name
     */
    String getName();

    /**
     * Configures the codec with the specified parameters.
     * Must be called before encoding/decoding.
     *
     * @param config codec configuration
     * @return true if configuration was successful
     */
    boolean configure(AudioCodecConfig config);

    /**
     * Returns the current configuration.
     * @return current configuration or null if not configured
     */
    AudioCodecConfig getConfig();

    /**
     * Returns true if the codec is configured and ready.
     * @return true if ready for encoding/decoding
     */
    boolean isReady();

    /**
     * Resets the codec state.
     * Call this when starting a new stream.
     */
    void reset();

    /**
     * Returns the number of PCM samples required per frame per channel.
     * For SBC this is typically blocks * subbands (e.g., 16 * 8 = 128).
     *
     * @return samples per frame per channel
     */
    int getSamplesPerFrame();

    /**
     * Returns the number of channels.
     * @return 1 for mono, 2 for stereo
     */
    int getChannelCount();

    /**
     * Returns the maximum encoded frame size in bytes.
     * @return maximum frame size
     */
    int getMaxFrameSize();

    /**
     * Returns the actual encoded frame size for current configuration.
     * @return encoded frame size in bytes
     */
    int getEncodedFrameSize();

    /**
     * Encodes PCM audio samples to codec format.
     *
     * @param pcmData interleaved PCM samples (16-bit signed)
     * @return encoded audio data, or null on error
     */
    byte[] encode(short[] pcmData);

    /**
     * Encodes PCM audio samples with offset and length.
     *
     * @param pcmData PCM sample buffer
     * @param offset starting offset in buffer
     * @param length number of samples to encode
     * @return encoded audio data, or null on error
     */
    byte[] encode(short[] pcmData, int offset, int length);

    /**
     * Encodes PCM data into provided output buffer.
     *
     * @param pcmData input PCM samples
     * @param pcmOffset input offset
     * @param pcmLength input sample count
     * @param output output buffer
     * @param outputOffset output offset
     * @return number of bytes written, or -1 on error
     */
    int encode(short[] pcmData, int pcmOffset, int pcmLength,
               byte[] output, int outputOffset);

    /**
     * Decodes audio data to PCM samples.
     *
     * @param encodedData encoded audio frame
     * @return decoded PCM samples (interleaved), or null on error
     */
    short[] decode(byte[] encodedData);

    /**
     * Decodes audio data with offset and length.
     *
     * @param encodedData encoded data buffer
     * @param offset starting offset
     * @param length data length
     * @return decoded PCM samples, or null on error
     */
    short[] decode(byte[] encodedData, int offset, int length);

    /**
     * Decodes into provided output buffer.
     *
     * @param encodedData input encoded data
     * @param encodedOffset input offset
     * @param encodedLength input length
     * @param pcmOutput output PCM buffer
     * @param pcmOffset output offset
     * @return number of PCM samples written, or -1 on error
     */
    int decode(byte[] encodedData, int encodedOffset, int encodedLength,
               short[] pcmOutput, int pcmOffset);

    /**
     * Builds the AVDTP capability information element for this codec.
     * Used during capability exchange.
     *
     * @return capability bytes for AVDTP
     */
    byte[] buildCapabilities();

    /**
     * Builds the AVDTP configuration for the current settings.
     * Used during stream configuration.
     *
     * @return configuration bytes for AVDTP SET_CONFIGURATION
     */
    byte[] buildConfiguration();

    /**
     * Parses AVDTP configuration and updates codec settings.
     *
     * @param config configuration bytes from SET_CONFIGURATION
     * @return true if configuration was parsed successfully
     */
    boolean parseConfiguration(byte[] config);

    /**
     * Returns the bitrate in bits per second.
     * @return current bitrate
     */
    int getBitrate();

    /**
     * Returns the sample rate in Hz.
     * @return sample rate (e.g., 44100, 48000)
     */
    int getSampleRate();

    /**
     * Releases resources held by the codec.
     * Call when done using the codec.
     */
    void release();

    /**
     * Listener for codec events.
     */
    interface CodecListener {
        /**
         * Called when a frame is encoded.
         * @param frameData encoded frame
         * @param timestamp presentation timestamp
         */
        void onFrameEncoded(byte[] frameData, long timestamp);

        /**
         * Called when a frame is decoded.
         * @param pcmData decoded PCM samples
         * @param timestamp presentation timestamp
         */
        void onFrameDecoded(short[] pcmData, long timestamp);

        /**
         * Called on codec error.
         * @param error error description
         */
        void onError(String error);
    }

    /**
     * Sets the codec event listener.
     * @param listener listener for codec events
     */
    void setListener(CodecListener listener);
}