package com.courierstack.codec;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Factory for creating and managing audio codecs.
 *
 * This factory provides a central registry for codec implementations,
 * allowing custom codecs to be registered and used transparently.
 *
 * <h2>Built-in Codecs:</h2>
 * <ul>
 *   <li>SBC - Sub-Band Codec (mandatory for A2DP)</li>
 * </ul>
 *
 * <h2>Registering Custom Codecs:</h2>
 * <pre>
 * // Register a custom LDAC codec
 * AudioCodecFactory.registerEncoder(CodecType.VENDOR,
 *     "LDAC", LdacEncoder::new);
 *
 * // Create and use the codec
 * AudioCodec codec = AudioCodecFactory.createEncoder("LDAC");
 * </pre>
 */
public final class AudioCodecFactory {

    private static final String TAG = "AudioCodecFactory";

    // Encoder registry: type -> (name -> supplier)
    private static final Map<AudioCodec.CodecType, Map<String, Supplier<AudioCodec>>>
            sEncoders = new ConcurrentHashMap<>();

    // Decoder registry: type -> (name -> supplier)
    private static final Map<AudioCodec.CodecType, Map<String, Supplier<AudioCodec>>>
            sDecoders = new ConcurrentHashMap<>();

    // Vendor codec registry: vendorId:codecId -> name
    private static final Map<String, String> sVendorCodecNames = new ConcurrentHashMap<>();

    // Default codec names per type
    private static final Map<AudioCodec.CodecType, String> sDefaultEncoders = new ConcurrentHashMap<>();
    private static final Map<AudioCodec.CodecType, String> sDefaultDecoders = new ConcurrentHashMap<>();

    private AudioCodecFactory() {}

    // ==================== Registration ====================

    /**
     * Registers an encoder implementation.
     *
     * @param type codec type
     * @param name codec name for identification
     * @param supplier factory for creating encoder instances
     */
    public static void registerEncoder(AudioCodec.CodecType type, String name,
                                       Supplier<AudioCodec> supplier) {
        sEncoders.computeIfAbsent(type, k -> new ConcurrentHashMap<>())
                .put(name, supplier);
    }

    /**
     * Registers a decoder implementation.
     *
     * @param type codec type
     * @param name codec name for identification
     * @param supplier factory for creating decoder instances
     */
    public static void registerDecoder(AudioCodec.CodecType type, String name,
                                       Supplier<AudioCodec> supplier) {
        sDecoders.computeIfAbsent(type, k -> new ConcurrentHashMap<>())
                .put(name, supplier);
    }

    /**
     * Registers a vendor-specific codec.
     *
     * @param vendorId Bluetooth vendor ID
     * @param codecId vendor-specific codec ID
     * @param name codec name
     * @param encoderSupplier encoder factory
     * @param decoderSupplier decoder factory (can be null)
     */
    public static void registerVendorCodec(int vendorId, int codecId, String name,
                                           Supplier<AudioCodec> encoderSupplier,
                                           Supplier<AudioCodec> decoderSupplier) {
        String key = vendorId + ":" + codecId;
        sVendorCodecNames.put(key, name);

        if (encoderSupplier != null) {
            registerEncoder(AudioCodec.CodecType.VENDOR, name, encoderSupplier);
        }
        if (decoderSupplier != null) {
            registerDecoder(AudioCodec.CodecType.VENDOR, name, decoderSupplier);
        }
    }

    /**
     * Sets the default encoder for a codec type.
     */
    public static void setDefaultEncoder(AudioCodec.CodecType type, String name) {
        sDefaultEncoders.put(type, name);
    }

    /**
     * Sets the default decoder for a codec type.
     */
    public static void setDefaultDecoder(AudioCodec.CodecType type, String name) {
        sDefaultDecoders.put(type, name);
    }

    // ==================== Creation ====================

    /**
     * Creates an encoder of the specified type using the default implementation.
     *
     * @param type codec type
     * @return new encoder instance, or null if not available
     */
    public static AudioCodec createEncoder(AudioCodec.CodecType type) {
        String name = sDefaultEncoders.get(type);
        if (name == null) return null;
        return createEncoder(type, name);
    }

    /**
     * Creates an encoder of the specified type and name.
     */
    public static AudioCodec createEncoder(AudioCodec.CodecType type, String name) {
        Map<String, Supplier<AudioCodec>> encoders = sEncoders.get(type);
        if (encoders == null) return null;
        Supplier<AudioCodec> supplier = encoders.get(name);
        if (supplier == null) return null;
        return supplier.get();
    }

    /**
     * Creates an encoder by name (searches all types).
     */
    public static AudioCodec createEncoder(String name) {
        for (Map<String, Supplier<AudioCodec>> encoders : sEncoders.values()) {
            Supplier<AudioCodec> supplier = encoders.get(name);
            if (supplier != null) return supplier.get();
        }
        return null;
    }

    /**
     * Creates a decoder of the specified type using the default implementation.
     */
    public static AudioCodec createDecoder(AudioCodec.CodecType type) {
        String name = sDefaultDecoders.get(type);
        if (name == null) return null;
        return createDecoder(type, name);
    }

    /**
     * Creates a decoder of the specified type and name.
     */
    public static AudioCodec createDecoder(AudioCodec.CodecType type, String name) {
        Map<String, Supplier<AudioCodec>> decoders = sDecoders.get(type);
        if (decoders == null) return null;
        Supplier<AudioCodec> supplier = decoders.get(name);
        if (supplier == null) return null;
        return supplier.get();
    }

    /**
     * Creates a decoder by name (searches all types).
     */
    public static AudioCodec createDecoder(String name) {
        for (Map<String, Supplier<AudioCodec>> decoders : sDecoders.values()) {
            Supplier<AudioCodec> supplier = decoders.get(name);
            if (supplier != null) return supplier.get();
        }
        return null;
    }

    /**
     * Creates a codec from AVDTP capability data.
     */
    public static AudioCodec createFromCapability(byte[] capability, AudioCodec.Mode mode) {
        if (capability == null || capability.length < 2) return null;

        int mediaType = (capability[0] >> 4) & 0x0F;
        int codecType = capability[1] & 0xFF;

        AudioCodec.CodecType type = AudioCodec.CodecType.fromId(codecType);
        AudioCodec codec = mode == AudioCodec.Mode.ENCODER ?
                createEncoder(type) : createDecoder(type);

        if (codec != null && capability.length > 2) {
            codec.parseConfiguration(capability);
        }

        return codec;
    }

    /**
     * Returns the vendor codec name for a vendor ID and codec ID.
     */
    public static String getVendorCodecName(int vendorId, int codecId) {
        return sVendorCodecNames.get(vendorId + ":" + codecId);
    }

    /**
     * Checks if an encoder is available for the given type.
     */
    public static boolean hasEncoder(AudioCodec.CodecType type) {
        Map<String, Supplier<AudioCodec>> encoders = sEncoders.get(type);
        return encoders != null && !encoders.isEmpty();
    }

    /**
     * Checks if a decoder is available for the given type.
     */
    public static boolean hasDecoder(AudioCodec.CodecType type) {
        Map<String, Supplier<AudioCodec>> decoders = sDecoders.get(type);
        return decoders != null && !decoders.isEmpty();
    }

    /**
     * Returns all available encoder names for a type.
     */
    public static String[] getEncoderNames(AudioCodec.CodecType type) {
        Map<String, Supplier<AudioCodec>> encoders = sEncoders.get(type);
        if (encoders == null) return new String[0];
        return encoders.keySet().toArray(new String[0]);
    }

    /**
     * Returns all available decoder names for a type.
     */
    public static String[] getDecoderNames(AudioCodec.CodecType type) {
        Map<String, Supplier<AudioCodec>> decoders = sDecoders.get(type);
        if (decoders == null) return new String[0];
        return decoders.keySet().toArray(new String[0]);
    }
}