package com.courierstack.a2dp.avdtp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Audio streaming utilities for AVDTP.
 *
 * <p>Provides helper methods for:
 * <ul>
 *   <li>Audio generation (tones, chirps, silence)</li>
 *   <li>Audio conversion (resampling, mono/stereo)</li>
 *   <li>WAV file parsing</li>
 *   <li>Volume adjustment</li>
 * </ul>
 *
 * <p>Thread safety: All static methods are thread-safe.
 */
public final class AudioStreamer {

    private AudioStreamer() {
        // Utility class - prevent instantiation
    }

    // ==================== Audio Generation ====================

    /**
     * Generates a sine wave tone.
     *
     * @param frequency  tone frequency in Hz
     * @param durationMs duration in milliseconds
     * @param sampleRate sample rate in Hz
     * @param volume     volume 0.0 to 1.0
     * @return stereo interleaved PCM samples (16-bit signed)
     */
    public static short[] generateTone(int frequency, int durationMs,
                                       int sampleRate, double volume) {
        int numSamples = (sampleRate * durationMs) / 1000;
        short[] samples = new short[numSamples * 2]; // Stereo

        double amplitude = 32767.0 * Math.min(1.0, Math.max(0.0, volume));
        double angularFreq = 2.0 * Math.PI * frequency / sampleRate;

        for (int i = 0; i < numSamples; i++) {
            short sample = (short) (amplitude * Math.sin(angularFreq * i));
            samples[i * 2] = sample;     // Left
            samples[i * 2 + 1] = sample; // Right
        }

        return samples;
    }

    /**
     * Generates a frequency sweep (chirp).
     *
     * @param startFreq  starting frequency in Hz
     * @param endFreq    ending frequency in Hz
     * @param durationMs duration in milliseconds
     * @param sampleRate sample rate in Hz
     * @param volume     volume 0.0 to 1.0
     * @return stereo interleaved PCM samples
     */
    public static short[] generateChirp(int startFreq, int endFreq, int durationMs,
                                        int sampleRate, double volume) {
        int numSamples = (sampleRate * durationMs) / 1000;
        short[] samples = new short[numSamples * 2];

        double amplitude = 32767.0 * Math.min(1.0, Math.max(0.0, volume));
        double phase = 0;

        for (int i = 0; i < numSamples; i++) {
            double t = (double) i / numSamples;
            double freq = startFreq + (endFreq - startFreq) * t;
            double angularFreq = 2.0 * Math.PI * freq / sampleRate;
            phase += angularFreq;

            short sample = (short) (amplitude * Math.sin(phase));
            samples[i * 2] = sample;
            samples[i * 2 + 1] = sample;
        }

        return samples;
    }

    /**
     * Generates silence.
     *
     * @param durationMs duration in milliseconds
     * @param sampleRate sample rate in Hz
     * @return stereo PCM samples (all zeros)
     */
    public static short[] generateSilence(int durationMs, int sampleRate) {
        int numSamples = (sampleRate * durationMs) / 1000;
        return new short[numSamples * 2]; // Stereo silence
    }

    /**
     * Generates white noise.
     *
     * @param durationMs duration in milliseconds
     * @param sampleRate sample rate in Hz
     * @param volume     volume 0.0 to 1.0
     * @return stereo PCM samples
     */
    public static short[] generateNoise(int durationMs, int sampleRate, double volume) {
        int numSamples = (sampleRate * durationMs) / 1000;
        short[] samples = new short[numSamples * 2];

        double amplitude = 32767.0 * Math.min(1.0, Math.max(0.0, volume));
        java.util.Random random = new java.util.Random();

        for (int i = 0; i < numSamples; i++) {
            short sample = (short) (amplitude * (random.nextDouble() * 2 - 1));
            samples[i * 2] = sample;
            samples[i * 2 + 1] = sample;
        }

        return samples;
    }

    /**
     * Generates a square wave.
     *
     * @param frequency  frequency in Hz
     * @param durationMs duration in milliseconds
     * @param sampleRate sample rate in Hz
     * @param volume     volume 0.0 to 1.0
     * @return stereo PCM samples
     */
    public static short[] generateSquareWave(int frequency, int durationMs,
                                             int sampleRate, double volume) {
        int numSamples = (sampleRate * durationMs) / 1000;
        short[] samples = new short[numSamples * 2];

        double amplitude = 32767.0 * Math.min(1.0, Math.max(0.0, volume));
        int samplesPerCycle = sampleRate / frequency;
        int halfCycle = samplesPerCycle / 2;

        for (int i = 0; i < numSamples; i++) {
            short sample = (short) ((i % samplesPerCycle) < halfCycle ? amplitude : -amplitude);
            samples[i * 2] = sample;
            samples[i * 2 + 1] = sample;
        }

        return samples;
    }

    // ==================== Audio Conversion ====================

    /**
     * Resamples audio using linear interpolation.
     *
     * @param input    input PCM samples
     * @param srcRate  source sample rate
     * @param dstRate  destination sample rate
     * @param channels number of channels
     * @return resampled audio
     */
    public static short[] resample(short[] input, int srcRate, int dstRate, int channels) {
        if (srcRate == dstRate) return input;

        int inputFrames = input.length / channels;
        double ratio = (double) dstRate / srcRate;
        int outputFrames = (int) (inputFrames * ratio);
        short[] output = new short[outputFrames * channels];

        for (int frame = 0; frame < outputFrames; frame++) {
            double srcPos = frame / ratio;
            int srcFrame = (int) srcPos;
            double frac = srcPos - srcFrame;

            for (int ch = 0; ch < channels; ch++) {
                int idx = srcFrame * channels + ch;
                int nextIdx = (srcFrame + 1) * channels + ch;

                if (nextIdx < input.length) {
                    double sample = input[idx] * (1 - frac) + input[nextIdx] * frac;
                    output[frame * channels + ch] = (short) Math.round(sample);
                } else if (idx < input.length) {
                    output[frame * channels + ch] = input[idx];
                }
            }
        }

        return output;
    }

    /**
     * Converts mono audio to stereo.
     *
     * @param mono mono PCM samples
     * @return stereo interleaved samples
     */
    public static short[] monoToStereo(short[] mono) {
        short[] stereo = new short[mono.length * 2];
        for (int i = 0; i < mono.length; i++) {
            stereo[i * 2] = mono[i];
            stereo[i * 2 + 1] = mono[i];
        }
        return stereo;
    }

    /**
     * Converts stereo audio to mono by averaging channels.
     *
     * @param stereo stereo interleaved samples
     * @return mono samples
     */
    public static short[] stereoToMono(short[] stereo) {
        short[] mono = new short[stereo.length / 2];
        for (int i = 0; i < mono.length; i++) {
            int left = stereo[i * 2];
            int right = stereo[i * 2 + 1];
            mono[i] = (short) ((left + right) / 2);
        }
        return mono;
    }

    /**
     * Applies volume gain to audio samples.
     *
     * @param samples input samples
     * @param gain    gain multiplier (1.0 = no change)
     * @return samples with adjusted volume
     */
    public static short[] applyGain(short[] samples, float gain) {
        if (gain == 1.0f) return samples;

        short[] output = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            int sample = (int) (samples[i] * gain);
            output[i] = (short) Math.max(-32768, Math.min(32767, sample));
        }
        return output;
    }

    /**
     * Converts byte array to short array (little-endian).
     *
     * @param bytes input bytes
     * @return short samples
     */
    public static short[] bytesToShorts(byte[] bytes) {
        short[] shorts = new short[bytes.length / 2];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts);
        return shorts;
    }

    /**
     * Converts short array to byte array (little-endian).
     *
     * @param shorts input samples
     * @return byte array
     */
    public static byte[] shortsToBytes(short[] shorts) {
        byte[] bytes = new byte[shorts.length * 2];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shorts);
        return bytes;
    }

    // ==================== WAV File Parsing ====================

    /**
     * Parsed WAV file information.
     */
    public static class WavInfo {
        /** Sample rate in Hz. */
        public int sampleRate;

        /** Number of channels. */
        public int channels;

        /** Bits per sample. */
        public int bitsPerSample;

        /** PCM sample data. */
        public short[] pcmData;

        /** Duration in milliseconds. */
        public int durationMs;

        @Override
        public String toString() {
            return String.format("WavInfo{%dHz, %dch, %dbit, %dms}",
                    sampleRate, channels, bitsPerSample, durationMs);
        }
    }

    /**
     * Parses a WAV file.
     *
     * @param wavBytes raw WAV file bytes
     * @return parsed info, or null if invalid
     */
    public static WavInfo parseWav(byte[] wavBytes) {
        if (wavBytes == null || wavBytes.length < 44) {
            System.err.println("AudioStreamer: WAV too short: " +
                    (wavBytes == null ? "null" : wavBytes.length));
            return null;
        }

        // Check RIFF header (compare as unsigned bytes)
        if ((wavBytes[0] & 0xFF) != 'R' || (wavBytes[1] & 0xFF) != 'I' ||
                (wavBytes[2] & 0xFF) != 'F' || (wavBytes[3] & 0xFF) != 'F') {
            System.err.println("AudioStreamer: Invalid RIFF header: " +
                    String.format("%02X %02X %02X %02X", wavBytes[0], wavBytes[1], wavBytes[2], wavBytes[3]));
            return null;
        }

        // Check WAVE format
        if ((wavBytes[8] & 0xFF) != 'W' || (wavBytes[9] & 0xFF) != 'A' ||
                (wavBytes[10] & 0xFF) != 'V' || (wavBytes[11] & 0xFF) != 'E') {
            System.err.println("AudioStreamer: Invalid WAVE format");
            return null;
        }

        WavInfo info = new WavInfo();
        ByteBuffer buffer = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(12);

        int dataOffset = -1;
        int dataSize = 0;
        boolean fmtFound = false;

        // Parse chunks
        while (buffer.remaining() >= 8) {
            int chunkStart = buffer.position();
            byte[] chunkId = new byte[4];
            buffer.get(chunkId);
            int chunkSize = buffer.getInt();
            String id = new String(chunkId);

            // Handle negative chunk size (unsigned overflow for large files)
            long unsignedChunkSize = chunkSize & 0xFFFFFFFFL;

            System.out.println("AudioStreamer: Chunk '" + id.trim() + "' size=" + unsignedChunkSize +
                    " at offset=" + chunkStart);

            if ("fmt ".equals(id)) {
                if (chunkSize < 16) {
                    System.err.println("AudioStreamer: fmt chunk too small: " + chunkSize);
                    return null;
                }

                int audioFormat = buffer.getShort() & 0xFFFF;
                info.channels = buffer.getShort() & 0xFFFF;
                info.sampleRate = buffer.getInt();
                buffer.getInt(); // byte rate
                buffer.getShort(); // block align
                info.bitsPerSample = buffer.getShort() & 0xFFFF;

                System.out.println("AudioStreamer: Format=" + audioFormat +
                        " channels=" + info.channels +
                        " rate=" + info.sampleRate +
                        " bits=" + info.bitsPerSample);

                // Support PCM (1) and IEEE float (3)
                if (audioFormat != 1 && audioFormat != 3) {
                    System.err.println("AudioStreamer: Unsupported format: " + audioFormat +
                            " (only PCM=1 and IEEE float=3 supported)");
                    return null;
                }

                // Skip extra format bytes (preserve word alignment)
                int extraBytes = chunkSize - 16;
                if (extraBytes > 0 && buffer.remaining() >= extraBytes) {
                    buffer.position(buffer.position() + extraBytes);
                }
                fmtFound = true;

            } else if ("data".equals(id)) {
                dataOffset = buffer.position();
                dataSize = chunkSize;
                System.out.println("AudioStreamer: Found data at offset=" + dataOffset + " size=" + dataSize);
                break;

            } else {
                // Skip unknown chunk (LIST, JUNK, bext, fact, etc.)
                // WAV chunks must be word-aligned (even byte boundary)
                int skipSize = chunkSize;
                if ((chunkSize & 1) != 0) {
                    skipSize++; // Add padding byte for odd-sized chunks
                }

                if (skipSize > 0 && buffer.remaining() >= skipSize) {
                    buffer.position(buffer.position() + skipSize);
                } else if (chunkSize < 0 || skipSize > buffer.remaining()) {
                    // Chunk size is invalid or too large - search for data chunk
                    System.out.println("AudioStreamer: Chunk skip failed, searching for data...");
                    dataOffset = findDataChunk(wavBytes, buffer.position());
                    if (dataOffset > 0 && dataOffset >= 8) {
                        dataSize = readInt32LE(wavBytes, dataOffset - 4);
                        System.out.println("AudioStreamer: Found data by search at " + dataOffset);
                    }
                    break;
                }
            }
        }

        if (!fmtFound) {
            System.err.println("AudioStreamer: No fmt chunk found");
            return null;
        }

        if (dataOffset < 0 || dataSize <= 0) {
            // Last resort: search for data chunk
            System.out.println("AudioStreamer: No data chunk in normal parse, searching...");
            dataOffset = findDataChunk(wavBytes, 12);
            if (dataOffset > 0 && dataOffset >= 8) {
                dataSize = readInt32LE(wavBytes, dataOffset - 4);
                System.out.println("AudioStreamer: Found data by fallback search at " + dataOffset +
                        " size=" + dataSize);
            }
        }

        if (dataOffset < 0) {
            System.err.println("AudioStreamer: Could not find data chunk");
            return null;
        }

        // Handle negative dataSize (unsigned 32-bit overflow)
        long actualDataSize = dataSize & 0xFFFFFFFFL;
        int availableData = wavBytes.length - dataOffset;
        int actualSize = (int) Math.min(actualDataSize, availableData);

        // Limit to prevent OOM (max ~100MB PCM)
        int maxPcmSize = 100 * 1024 * 1024;
        if (actualSize > maxPcmSize) {
            System.out.println("AudioStreamer: Limiting PCM from " + actualSize + " to " + maxPcmSize);
            actualSize = maxPcmSize;
        }

        if (actualSize <= 0) {
            System.err.println("AudioStreamer: No PCM data available");
            return null;
        }

        System.out.println("AudioStreamer: Extracting " + actualSize + " bytes PCM data");

        byte[] pcmBytes = new byte[actualSize];
        System.arraycopy(wavBytes, dataOffset, pcmBytes, 0, actualSize);

        if (info.bitsPerSample == 16) {
            info.pcmData = bytesToShorts(pcmBytes);
        } else if (info.bitsPerSample == 8) {
            // Convert 8-bit unsigned to 16-bit signed
            info.pcmData = new short[pcmBytes.length];
            for (int i = 0; i < pcmBytes.length; i++) {
                info.pcmData[i] = (short) (((pcmBytes[i] & 0xFF) - 128) << 8);
            }
        } else if (info.bitsPerSample == 24) {
            // Convert 24-bit to 16-bit (use upper 16 bits)
            int numSamples = pcmBytes.length / 3;
            info.pcmData = new short[numSamples];
            for (int i = 0; i < numSamples; i++) {
                int sample = ((pcmBytes[i * 3 + 2] << 8) | (pcmBytes[i * 3 + 1] & 0xFF));
                info.pcmData[i] = (short) sample;
            }
            System.out.println("AudioStreamer: Converted 24-bit to 16-bit");
        } else if (info.bitsPerSample == 32) {
            // 32-bit PCM - take upper 16 bits
            int numSamples = pcmBytes.length / 4;
            info.pcmData = new short[numSamples];
            ByteBuffer bb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < numSamples; i++) {
                int sample = bb.getInt();
                info.pcmData[i] = (short) (sample >> 16);
            }
            System.out.println("AudioStreamer: Converted 32-bit to 16-bit");
        } else {
            System.err.println("AudioStreamer: Unsupported bit depth: " + info.bitsPerSample);
            return null;
        }

        // Calculate duration
        int totalSamples = info.pcmData.length / info.channels;
        info.durationMs = (totalSamples * 1000) / info.sampleRate;

        System.out.println("AudioStreamer: Parsed successfully: " + info);
        return info;
    }

    /**
     * Search for "data" chunk marker in raw bytes.
     */
    private static int findDataChunk(byte[] data, int startOffset) {
        for (int i = startOffset; i < data.length - 8; i++) {
            if ((data[i] & 0xFF) == 'd' && (data[i + 1] & 0xFF) == 'a' &&
                    (data[i + 2] & 0xFF) == 't' && (data[i + 3] & 0xFF) == 'a') {
                return i + 8; // Return offset after "data" + size field
            }
        }
        return -1;
    }

    /**
     * Read 32-bit little-endian integer from byte array.
     */
    private static int readInt32LE(byte[] data, int offset) {
        if (offset < 0 || offset + 4 > data.length) return 0;
        return (data[offset] & 0xFF) |
                ((data[offset + 1] & 0xFF) << 8) |
                ((data[offset + 2] & 0xFF) << 16) |
                ((data[offset + 3] & 0xFF) << 24);
    }

    /**
     * Checks if data appears to be a WAV file.
     *
     * @param data file data
     * @return true if WAV header is detected
     */
    public static boolean isWavFile(byte[] data) {
        return data != null && data.length >= 12 &&
                data[0] == 'R' && data[1] == 'I' &&
                data[2] == 'F' && data[3] == 'F' &&
                data[8] == 'W' && data[9] == 'A' &&
                data[10] == 'V' && data[11] == 'E';
    }

    // ==================== Audio Analysis ====================

    /**
     * Calculates the RMS (root mean square) level of audio.
     *
     * @param samples PCM samples
     * @return RMS level (0.0 to 1.0)
     */
    public static double calculateRms(short[] samples) {
        if (samples == null || samples.length == 0) return 0;

        double sumSquares = 0;
        for (short sample : samples) {
            double normalized = sample / 32768.0;
            sumSquares += normalized * normalized;
        }

        return Math.sqrt(sumSquares / samples.length);
    }

    /**
     * Calculates peak level of audio.
     *
     * @param samples PCM samples
     * @return peak level (0.0 to 1.0)
     */
    public static double calculatePeak(short[] samples) {
        if (samples == null || samples.length == 0) return 0;

        int maxAbs = 0;
        for (short sample : samples) {
            int abs = Math.abs(sample);
            if (abs > maxAbs) maxAbs = abs;
        }

        return maxAbs / 32768.0;
    }

    /**
     * Detects if audio is silence (below threshold).
     *
     * @param samples   PCM samples
     * @param threshold RMS threshold (e.g., 0.01)
     * @return true if audio is effectively silent
     */
    public static boolean isSilent(short[] samples, double threshold) {
        return calculateRms(samples) < threshold;
    }

    // ==================== Timing Calculations ====================

    /**
     * Calculates audio duration in milliseconds.
     *
     * @param sampleCount number of samples
     * @param sampleRate  sample rate in Hz
     * @param channels    number of channels
     * @return duration in milliseconds
     */
    public static int calculateDurationMs(int sampleCount, int sampleRate, int channels) {
        int frames = sampleCount / channels;
        return (frames * 1000) / sampleRate;
    }

    /**
     * Calculates samples needed for a given duration.
     *
     * @param durationMs duration in milliseconds
     * @param sampleRate sample rate in Hz
     * @param channels   number of channels
     * @return number of samples
     */
    public static int calculateSampleCount(int durationMs, int sampleRate, int channels) {
        int frames = (sampleRate * durationMs) / 1000;
        return frames * channels;
    }

    /**
     * Calculates the frame duration for SBC codec.
     *
     * @param blocks     number of blocks
     * @param subbands   number of subbands
     * @param sampleRate sample rate in Hz
     * @return frame duration in microseconds
     */
    public static int calculateSbcFrameDurationUs(int blocks, int subbands, int sampleRate) {
        int samplesPerFrame = blocks * subbands;
        return (samplesPerFrame * 1000000) / sampleRate;
    }
}