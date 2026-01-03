package com.courierstack.a2dp.avdtp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * RTP media packet handling for AVDTP media transport.
 *
 * <p>Provides methods for building and parsing RTP packets used to
 * transport audio/video data over AVDTP media channels.
 *
 * <p>RTP header format (12 bytes):
 * <pre>
 * Bytes 0-1:  [V(2)][P(1)][X(1)][CC(4)] | [M(1)][PT(7)]
 * Bytes 2-3:  Sequence number
 * Bytes 4-7:  Timestamp
 * Bytes 8-11: SSRC
 * </pre>
 *
 * <p>For A2DP SBC, an additional media payload header precedes the SBC frames:
 * <pre>
 * Byte 0: [F(4)][S(1)][L(1)][RFA(2)]
 * </pre>
 *
 * <p>Thread safety: This class is not thread-safe. Use separate instances
 * per stream.
 */
public class MediaPacket {

    /** RTP header size in bytes. */
    public static final int RTP_HEADER_SIZE = 12;

    /** A2DP media payload header size. */
    public static final int A2DP_HEADER_SIZE = 1;

    /** Total header size (RTP + A2DP). */
    public static final int TOTAL_HEADER_SIZE = RTP_HEADER_SIZE + A2DP_HEADER_SIZE;

    /** Default MTU for media channel. */
    public static final int DEFAULT_MTU = 895;

    // ==================== RTP Header Fields ====================

    /** RTP version (always 2). */
    private int version = AvdtpConstants.RTP_VERSION;

    /** Padding flag. */
    private boolean padding;

    /** Extension flag. */
    private boolean extension;

    /** CSRC count. */
    private int csrcCount;

    /** Marker bit. */
    private boolean marker;

    /** Payload type. */
    private int payloadType = AvdtpConstants.RTP_PAYLOAD_TYPE_DYNAMIC;

    /** Sequence number. */
    private int sequenceNumber;

    /** Timestamp. */
    private int timestamp;

    /** SSRC (Synchronization source identifier). */
    private int ssrc;

    // ==================== A2DP Payload Header Fields ====================

    /** Number of SBC frames. */
    private int frameCount;

    /** Starting packet flag. */
    private boolean startingPacket;

    /** Last packet flag. */
    private boolean lastPacket;

    // ==================== Payload ====================

    /** Media payload data. */
    private byte[] payload;

    // ==================== Sequence Generation ====================

    /** Current sequence number for generation. */
    private int currentSequence;

    /** Current timestamp for generation. */
    private int currentTimestamp;

    // ==================== Constructors ====================

    /**
     * Creates an empty MediaPacket for building outgoing packets.
     */
    public MediaPacket() {
    }

    /**
     * Creates a MediaPacket by parsing incoming data.
     *
     * @param data raw packet data
     */
    public MediaPacket(byte[] data) {
        parse(data);
    }

    // ==================== Packet Building ====================

    /**
     * Builds an RTP packet with the specified payload.
     *
     * @param payload media payload data
     * @return complete RTP packet
     */
    public byte[] build(byte[] payload) {
        return build(payload, 0, payload != null ? payload.length : 0);
    }

    /**
     * Builds an RTP packet with the specified payload.
     *
     * @param payload media payload data
     * @param offset  payload offset
     * @param length  payload length
     * @return complete RTP packet
     */
    public byte[] build(byte[] payload, int offset, int length) {
        ByteBuffer packet = ByteBuffer.allocate(RTP_HEADER_SIZE + length)
                .order(ByteOrder.BIG_ENDIAN);

        // Byte 0: V, P, X, CC
        int byte0 = (version << 6) |
                (padding ? 0x20 : 0) |
                (extension ? 0x10 : 0) |
                (csrcCount & 0x0F);
        packet.put((byte) byte0);

        // Byte 1: M, PT
        int byte1 = (marker ? 0x80 : 0) | (payloadType & 0x7F);
        packet.put((byte) byte1);

        // Bytes 2-3: Sequence number
        packet.putShort((short) sequenceNumber);

        // Bytes 4-7: Timestamp
        packet.putInt(timestamp);

        // Bytes 8-11: SSRC
        packet.putInt(ssrc);

        // Payload
        if (payload != null && length > 0) {
            packet.put(payload, offset, length);
        }

        return packet.array();
    }

    /**
     * Builds an A2DP SBC media packet.
     *
     * <p>Includes the A2DP media payload header with frame count.
     *
     * @param sbcFrames  SBC frame data
     * @param frameCount number of SBC frames in payload
     * @return complete packet
     */
    public byte[] buildSbc(byte[] sbcFrames, int frameCount) {
        return buildSbc(sbcFrames, 0, sbcFrames != null ? sbcFrames.length : 0, frameCount);
    }

    /**
     * Builds an A2DP SBC media packet.
     *
     * @param sbcFrames  SBC frame data
     * @param offset     data offset
     * @param length     data length
     * @param frameCount number of SBC frames
     * @return complete packet
     */
    public byte[] buildSbc(byte[] sbcFrames, int offset, int length, int frameCount) {
        ByteBuffer packet = ByteBuffer.allocate(TOTAL_HEADER_SIZE + length)
                .order(ByteOrder.BIG_ENDIAN);

        // RTP Header
        int byte0 = (version << 6);
        packet.put((byte) byte0);
        packet.put((byte) (payloadType & 0x7F));
        packet.putShort((short) sequenceNumber);
        packet.putInt(timestamp);
        packet.putInt(ssrc);

        // A2DP Media Payload Header
        int mpHeader = ((frameCount & 0x0F) << 4) |
                (startingPacket ? 0x04 : 0) |
                (lastPacket ? 0x02 : 0);
        packet.put((byte) mpHeader);

        // SBC frames
        if (sbcFrames != null && length > 0) {
            packet.put(sbcFrames, offset, length);
        }

        return packet.array();
    }

    /**
     * Builds the next packet in a sequence.
     *
     * <p>Automatically increments sequence number and advances timestamp.
     *
     * @param payload         payload data
     * @param samplesPerFrame samples per codec frame (for timestamp advance)
     * @return complete packet
     */
    public byte[] buildNext(byte[] payload, int samplesPerFrame) {
        sequenceNumber = currentSequence++;
        timestamp = currentTimestamp;
        currentTimestamp += samplesPerFrame;

        return build(payload);
    }

    /**
     * Builds the next A2DP SBC packet in a sequence.
     *
     * @param sbcFrames       SBC frame data
     * @param frameCount      number of frames
     * @param samplesPerFrame samples per SBC frame
     * @return complete packet
     */
    public byte[] buildNextSbc(byte[] sbcFrames, int frameCount, int samplesPerFrame) {
        sequenceNumber = currentSequence++;
        timestamp = currentTimestamp;
        currentTimestamp += samplesPerFrame * frameCount;

        return buildSbc(sbcFrames, frameCount);
    }

    // ==================== Packet Parsing ====================

    /**
     * Parses an RTP packet.
     *
     * @param data raw packet data
     * @return true if parsing succeeded
     */
    public boolean parse(byte[] data) {
        if (data == null || data.length < RTP_HEADER_SIZE) {
            return false;
        }

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        int byte0 = buf.get() & 0xFF;
        version = (byte0 >> 6) & 0x03;
        padding = (byte0 & 0x20) != 0;
        extension = (byte0 & 0x10) != 0;
        csrcCount = byte0 & 0x0F;

        int byte1 = buf.get() & 0xFF;
        marker = (byte1 & 0x80) != 0;
        payloadType = byte1 & 0x7F;

        sequenceNumber = buf.getShort() & 0xFFFF;
        timestamp = buf.getInt();
        ssrc = buf.getInt();

        // Extract payload
        int payloadOffset = RTP_HEADER_SIZE + (csrcCount * 4);
        if (extension && data.length > payloadOffset + 4) {
            // Skip extension header
            buf.position(payloadOffset);
            buf.getShort(); // Profile-specific
            int extLen = buf.getShort() & 0xFFFF;
            payloadOffset += 4 + (extLen * 4);
        }

        if (payloadOffset < data.length) {
            int payloadLen = data.length - payloadOffset;
            if (padding && payloadLen > 0) {
                // Remove padding
                int padLen = data[data.length - 1] & 0xFF;
                payloadLen = Math.max(0, payloadLen - padLen);
            }
            payload = new byte[payloadLen];
            System.arraycopy(data, payloadOffset, payload, 0, payloadLen);
        } else {
            payload = new byte[0];
        }

        return true;
    }

    /**
     * Parses an A2DP SBC media packet.
     *
     * @param data raw packet data
     * @return true if parsing succeeded
     */
    public boolean parseSbc(byte[] data) {
        if (!parse(data)) return false;

        if (payload != null && payload.length > 0) {
            // Parse A2DP media payload header
            int mpHeader = payload[0] & 0xFF;
            frameCount = (mpHeader >> 4) & 0x0F;
            startingPacket = (mpHeader & 0x04) != 0;
            lastPacket = (mpHeader & 0x02) != 0;

            // Extract SBC frames
            if (payload.length > 1) {
                byte[] sbcData = new byte[payload.length - 1];
                System.arraycopy(payload, 1, sbcData, 0, sbcData.length);
                payload = sbcData;
            } else {
                payload = new byte[0];
            }
        }

        return true;
    }

    // ==================== Getters and Setters ====================

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public boolean isPadding() { return padding; }
    public void setPadding(boolean padding) { this.padding = padding; }

    public boolean isExtension() { return extension; }
    public void setExtension(boolean extension) { this.extension = extension; }

    public int getCsrcCount() { return csrcCount; }
    public void setCsrcCount(int csrcCount) { this.csrcCount = csrcCount; }

    public boolean isMarker() { return marker; }
    public void setMarker(boolean marker) { this.marker = marker; }

    public int getPayloadType() { return payloadType; }
    public void setPayloadType(int payloadType) { this.payloadType = payloadType; }

    public int getSequenceNumber() { return sequenceNumber; }
    public void setSequenceNumber(int sequenceNumber) { this.sequenceNumber = sequenceNumber; }

    public int getTimestamp() { return timestamp; }
    public void setTimestamp(int timestamp) { this.timestamp = timestamp; }

    public int getSsrc() { return ssrc; }
    public void setSsrc(int ssrc) { this.ssrc = ssrc; }

    public int getFrameCount() { return frameCount; }
    public void setFrameCount(int frameCount) { this.frameCount = frameCount; }

    public boolean isStartingPacket() { return startingPacket; }
    public void setStartingPacket(boolean startingPacket) { this.startingPacket = startingPacket; }

    public boolean isLastPacket() { return lastPacket; }
    public void setLastPacket(boolean lastPacket) { this.lastPacket = lastPacket; }

    public byte[] getPayload() { return payload; }
    public void setPayload(byte[] payload) { this.payload = payload; }

    // ==================== Sequence Management ====================

    /**
     * Resets the sequence number generator.
     */
    public void resetSequence() {
        currentSequence = 0;
        currentTimestamp = 0;
    }

    /**
     * Resets sequence with initial values.
     *
     * @param initialSequence  starting sequence number
     * @param initialTimestamp starting timestamp
     */
    public void resetSequence(int initialSequence, int initialTimestamp) {
        currentSequence = initialSequence;
        currentTimestamp = initialTimestamp;
    }

    /**
     * Returns the current sequence number (for next packet).
     */
    public int getCurrentSequence() {
        return currentSequence;
    }

    /**
     * Returns the current timestamp (for next packet).
     */
    public int getCurrentTimestamp() {
        return currentTimestamp;
    }

    /**
     * Advances the timestamp manually.
     *
     * @param samples number of samples to advance
     */
    public void advanceTimestamp(int samples) {
        currentTimestamp += samples;
    }

    // ==================== Utility Methods ====================

    /**
     * Calculates the maximum payload size for a given MTU.
     *
     * @param mtu channel MTU
     * @return maximum payload size
     */
    public static int maxPayloadSize(int mtu) {
        return mtu - RTP_HEADER_SIZE;
    }

    /**
     * Calculates the maximum SBC payload size for a given MTU.
     *
     * @param mtu channel MTU
     * @return maximum SBC payload size
     */
    public static int maxSbcPayloadSize(int mtu) {
        return mtu - TOTAL_HEADER_SIZE;
    }

    /**
     * Calculates how many SBC frames fit in a packet.
     *
     * @param mtu       channel MTU
     * @param frameSize SBC frame size
     * @return number of frames (max 15)
     */
    public static int framesPerPacket(int mtu, int frameSize) {
        int maxPayload = maxSbcPayloadSize(mtu);
        int frames = maxPayload / frameSize;
        return Math.min(frames, 15); // A2DP header limits to 4 bits = 15 frames
    }

    /**
     * Creates a builder for constructing MediaPacket instances.
     *
     * @return new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    // ==================== Builder ====================

    /**
     * Builder for MediaPacket.
     */
    public static class Builder {
        private final MediaPacket packet = new MediaPacket();

        public Builder payloadType(int type) {
            packet.payloadType = type;
            return this;
        }

        public Builder ssrc(int ssrc) {
            packet.ssrc = ssrc;
            return this;
        }

        public Builder marker(boolean marker) {
            packet.marker = marker;
            return this;
        }

        public Builder sequenceNumber(int seq) {
            packet.sequenceNumber = seq;
            packet.currentSequence = seq;
            return this;
        }

        public Builder timestamp(int timestamp) {
            packet.timestamp = timestamp;
            packet.currentTimestamp = timestamp;
            return this;
        }

        public MediaPacket build() {
            return packet;
        }
    }

    @Override
    public String toString() {
        return String.format("MediaPacket{seq=%d, ts=%d, pt=%d, payload=%d bytes}",
                sequenceNumber, timestamp, payloadType,
                payload != null ? payload.length : 0);
    }
}