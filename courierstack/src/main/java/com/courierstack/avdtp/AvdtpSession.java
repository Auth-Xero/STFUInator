package com.courierstack.avdtp;

import com.courierstack.l2cap.L2capChannel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Represents an AVDTP session with a remote device.
 *
 * <p>A session manages the connection state, stream endpoints, codec
 * configuration, and media transport for A2DP streaming.
 *
 * <p>Thread safety: This class is not thread-safe. External synchronization
 * is required for concurrent access.
 */
public class AvdtpSession {

    // ==================== Connection Info ====================

    /** ACL connection handle. */
    private final int connectionHandle;

    /** Remote device Bluetooth address. */
    private final byte[] peerAddress;

    /** Signaling L2CAP channel. */
    private L2capChannel signalingChannel;

    /** Media transport L2CAP channel. */
    private L2capChannel mediaChannel;

    // ==================== Stream State ====================

    /** Current stream state. */
    private StreamState state = StreamState.IDLE;

    /** Local stream endpoints. */
    private final List<StreamEndpoint> localEndpoints = new ArrayList<>();

    /** Remote stream endpoints (discovered). */
    private final List<StreamEndpoint> remoteEndpoints = new ArrayList<>();

    /** Currently active endpoint. */
    private StreamEndpoint activeEndpoint;

    /** Remote endpoint we're connected to. */
    private StreamEndpoint remoteEndpoint;

    // ==================== Codec Configuration ====================

    /** Configured codec type. */
    private int codecType = AvdtpConstants.CODEC_SBC;

    /** Raw codec configuration bytes. */
    private byte[] codecConfig;

    // SBC parameters
    private int sbcFrequency = AvdtpConstants.SBC_FREQ_44100;
    private int sbcChannelMode = AvdtpConstants.SBC_CHANNEL_JOINT_STEREO;
    private int sbcBlockLength = AvdtpConstants.SBC_BLOCK_16;
    private int sbcSubbands = AvdtpConstants.SBC_SUBBAND_8;
    private int sbcAllocation = AvdtpConstants.SBC_ALLOC_LOUDNESS;
    private int sbcBitpool = AvdtpConstants.SBC_MAX_BITPOOL_HQ;

    // ==================== Media Transport ====================

    /** Media packet builder. */
    private final MediaPacket mediaPacket = new MediaPacket();

    /** Reported delay (in ms). */
    private int delayMs;

    // ==================== Callback ====================

    /** Session callback. */
    private IAvdtpSessionCallback callback;

    // ==================== Constructors ====================

    /**
     * Creates a new AVDTP session.
     *
     * @param connectionHandle ACL connection handle
     * @param peerAddress      remote device address (6 bytes)
     */
    public AvdtpSession(int connectionHandle, byte[] peerAddress) {
        this.connectionHandle = connectionHandle;
        this.peerAddress = peerAddress != null ? Arrays.copyOf(peerAddress, 6) : new byte[6];
    }

    // ==================== Connection Info ====================

    /**
     * Returns the ACL connection handle.
     */
    public int getConnectionHandle() {
        return connectionHandle;
    }

    /**
     * Returns the peer device address.
     */
    public byte[] getPeerAddress() {
        return peerAddress.clone();
    }

    /**
     * Returns the formatted peer address string.
     */
    public String getFormattedAddress() {
        return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
                peerAddress[5] & 0xFF, peerAddress[4] & 0xFF, peerAddress[3] & 0xFF,
                peerAddress[2] & 0xFF, peerAddress[1] & 0xFF, peerAddress[0] & 0xFF);
    }

    // ==================== Channel Management ====================

    /**
     * Returns the signaling channel.
     */
    public L2capChannel getSignalingChannel() {
        return signalingChannel;
    }

    /**
     * Sets the signaling channel.
     */
    public void setSignalingChannel(L2capChannel channel) {
        this.signalingChannel = channel;
    }

    /**
     * Returns the media channel.
     */
    public L2capChannel getMediaChannel() {
        return mediaChannel;
    }

    /**
     * Sets the media channel.
     */
    public void setMediaChannel(L2capChannel channel) {
        this.mediaChannel = channel;
    }

    /**
     * Returns true if signaling channel is connected.
     */
    public boolean isSignalingConnected() {
        return signalingChannel != null && signalingChannel.isOpen();
    }

    /**
     * Returns true if media channel is connected.
     */
    public boolean isMediaConnected() {
        return mediaChannel != null && mediaChannel.isOpen();
    }

    // ==================== Stream State ====================

    /**
     * Returns the current stream state.
     */
    public StreamState getState() {
        return state;
    }

    /**
     * Sets the stream state.
     */
    public void setState(StreamState state) {
        this.state = state;
    }

    /**
     * Returns true if in streaming state.
     */
    public boolean isStreaming() {
        return state == StreamState.STREAMING;
    }

    /**
     * Returns true if stream is open (configured and ready).
     */
    public boolean isOpen() {
        return state == StreamState.OPEN || state == StreamState.STREAMING;
    }

    /**
     * Returns true if stream is configured.
     */
    public boolean isConfigured() {
        return state != StreamState.IDLE;
    }

    // ==================== Endpoint Management ====================

    /**
     * Returns the local endpoints (unmodifiable).
     */
    public List<StreamEndpoint> getLocalEndpoints() {
        return Collections.unmodifiableList(localEndpoints);
    }

    /**
     * Adds a local endpoint.
     */
    public void addLocalEndpoint(StreamEndpoint endpoint) {
        localEndpoints.add(endpoint);
    }

    /**
     * Removes a local endpoint.
     */
    public void removeLocalEndpoint(StreamEndpoint endpoint) {
        localEndpoints.remove(endpoint);
    }

    /**
     * Clears all local endpoints.
     */
    public void clearLocalEndpoints() {
        localEndpoints.clear();
    }

    /**
     * Finds a local endpoint by SEID.
     */
    public StreamEndpoint findLocalEndpoint(int seid) {
        for (StreamEndpoint ep : localEndpoints) {
            if (ep.getSeid() == seid) return ep;
        }
        return null;
    }

    /**
     * Returns the remote endpoints (unmodifiable).
     */
    public List<StreamEndpoint> getRemoteEndpoints() {
        return Collections.unmodifiableList(remoteEndpoints);
    }

    /**
     * Sets the discovered remote endpoints.
     */
    public void setRemoteEndpoints(List<StreamEndpoint> endpoints) {
        remoteEndpoints.clear();
        if (endpoints != null) {
            remoteEndpoints.addAll(endpoints);
        }
    }

    /**
     * Finds a remote endpoint by SEID.
     */
    public StreamEndpoint findRemoteEndpoint(int seid) {
        for (StreamEndpoint ep : remoteEndpoints) {
            if (ep.getSeid() == seid) return ep;
        }
        return null;
    }

    /**
     * Finds a remote sink endpoint for a given codec.
     */
    public StreamEndpoint findRemoteSink(int codecType) {
        for (StreamEndpoint ep : remoteEndpoints) {
            if (ep.isSink() && ep.isAudio() && ep.getCodecType() == codecType && !ep.isInUse()) {
                return ep;
            }
        }
        return null;
    }

    /**
     * Returns the active local endpoint.
     */
    public StreamEndpoint getActiveEndpoint() {
        return activeEndpoint;
    }

    /**
     * Sets the active local endpoint.
     */
    public void setActiveEndpoint(StreamEndpoint endpoint) {
        this.activeEndpoint = endpoint;
    }

    /**
     * Returns the connected remote endpoint.
     */
    public StreamEndpoint getRemoteEndpoint() {
        return remoteEndpoint;
    }

    /**
     * Sets the connected remote endpoint.
     */
    public void setRemoteEndpoint(StreamEndpoint endpoint) {
        this.remoteEndpoint = endpoint;
    }

    // ==================== Codec Configuration ====================

    /**
     * Returns the configured codec type.
     */
    public int getCodecType() {
        return codecType;
    }

    /**
     * Sets the codec type.
     */
    public void setCodecType(int codecType) {
        this.codecType = codecType;
    }

    /**
     * Returns the raw codec configuration.
     */
    public byte[] getCodecConfig() {
        return codecConfig != null ? codecConfig.clone() : null;
    }

    /**
     * Sets the raw codec configuration.
     */
    public void setCodecConfig(byte[] config) {
        this.codecConfig = config != null ? config.clone() : null;
    }

    // SBC parameters
    public int getSbcFrequency() { return sbcFrequency; }
    public void setSbcFrequency(int frequency) { this.sbcFrequency = frequency; }

    public int getSbcChannelMode() { return sbcChannelMode; }
    public void setSbcChannelMode(int channelMode) { this.sbcChannelMode = channelMode; }

    public int getSbcBlockLength() { return sbcBlockLength; }
    public void setSbcBlockLength(int blockLength) { this.sbcBlockLength = blockLength; }

    public int getSbcSubbands() { return sbcSubbands; }
    public void setSbcSubbands(int subbands) { this.sbcSubbands = subbands; }

    public int getSbcAllocation() { return sbcAllocation; }
    public void setSbcAllocation(int allocation) { this.sbcAllocation = allocation; }

    public int getSbcBitpool() { return sbcBitpool; }
    public void setSbcBitpool(int bitpool) { this.sbcBitpool = bitpool; }

    /**
     * Returns the configured sample rate in Hz.
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
     * Returns samples per SBC frame.
     */
    public int getSamplesPerFrame() {
        return getBlockCount() * getSubbandCount();
    }

    /**
     * Returns true if joint stereo mode.
     */
    public boolean isJointStereo() {
        return (sbcChannelMode & AvdtpConstants.SBC_CHANNEL_JOINT_STEREO) != 0;
    }

    /**
     * Calculates the SBC frame size for current configuration.
     */
    public int calculateFrameSize() {
        return AvdtpConstants.calculateSbcFrameSize(
                getSubbandCount(),
                getBlockCount(),
                getChannelCount(),
                sbcBitpool,
                isJointStereo()
        );
    }

    /**
     * Applies SBC configuration from parsed config.
     */
    public void applySbcConfig(AvdtpParser.SbcConfig config) {
        if (config != null) {
            this.sbcFrequency = config.frequency;
            this.sbcChannelMode = config.channelMode;
            this.sbcBlockLength = config.blocks;
            this.sbcSubbands = config.subbands;
            this.sbcAllocation = config.allocation;
            this.sbcBitpool = config.maxBitpool;
        }
    }

    // ==================== Media Transport ====================

    /**
     * Returns the media packet builder.
     */
    public MediaPacket getMediaPacket() {
        return mediaPacket;
    }

    /**
     * Resets media sequence numbers.
     */
    public void resetMediaSequence() {
        mediaPacket.resetSequence();
    }

    /**
     * Returns the reported delay in milliseconds.
     */
    public int getDelayMs() {
        return delayMs;
    }

    /**
     * Sets the reported delay.
     */
    public void setDelayMs(int delayMs) {
        this.delayMs = delayMs;
    }

    /**
     * Returns the media channel MTU.
     */
    public int getMediaMtu() {
        return mediaChannel != null ? mediaChannel.getMtu() : MediaPacket.DEFAULT_MTU;
    }

    /**
     * Calculates frames per packet for the current configuration.
     */
    public int getFramesPerPacket() {
        return MediaPacket.framesPerPacket(getMediaMtu(), calculateFrameSize());
    }

    // ==================== Callback ====================

    /**
     * Returns the session callback.
     */
    public IAvdtpSessionCallback getCallback() {
        return callback;
    }

    /**
     * Sets the session callback.
     */
    public void setCallback(IAvdtpSessionCallback callback) {
        this.callback = callback;
    }

    // ==================== Lifecycle ====================

    /**
     * Resets the session to idle state.
     */
    public void reset() {
        state = StreamState.IDLE;
        remoteEndpoints.clear();
        activeEndpoint = null;
        remoteEndpoint = null;
        codecConfig = null;
        mediaPacket.resetSequence();
    }

    /**
     * Closes the session and releases resources.
     */
    public void close() {
        reset();
        signalingChannel = null;
        mediaChannel = null;
    }

    // ==================== Object Methods ====================

    @Override
    public String toString() {
        return String.format("AvdtpSession{handle=%d, addr=%s, state=%s, codec=%s}",
                connectionHandle,
                getFormattedAddress(),
                state,
                AvdtpConstants.getCodecName(codecType));
    }

    // ==================== Session Callback ====================

    /**
     * Callback interface for session events.
     */
    public interface IAvdtpSessionCallback {
        /**
         * Called when connection is established.
         */
        void onConnectionComplete(boolean success);

        /**
         * Called when stream is configured.
         */
        void onStreamConfigured(StreamEndpoint endpoint);

        /**
         * Called when streaming starts.
         */
        void onStreamStarted();

        /**
         * Called when streaming is suspended.
         */
        void onStreamSuspended();

        /**
         * Called when stream is closed.
         */
        void onStreamClosed();

        /**
         * Called when media data is received.
         */
        void onMediaDataReceived(byte[] data, int timestamp);
    }
}