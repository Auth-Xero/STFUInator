package com.courierstack.codec;

/**
 * SBC (Sub-Band Codec) Encoder for A2DP audio streaming.
 *
 * Based on the Google LLC SBC implementation (Copyright 2022).
 * Implements both 4 and 8 subband encoding as per A2DP specification.
 *
 * Licensed under Apache License, Version 2.0
 */
public class SbcCodec implements AudioCodec {

    // ========== Constants ==========

    public static final byte SBC_SYNCWORD = (byte) 0x9C;
    public static final byte MSBC_SYNCWORD = (byte) 0xAD;

    // Sampling frequencies
    public static final int FREQ_16K = 0;
    public static final int FREQ_32K = 1;
    public static final int FREQ_44K1 = 2;
    public static final int FREQ_48K = 3;

    // Backward-compatible aliases for sampling frequencies
    public static final int FREQ_16000 = FREQ_16K;
    public static final int FREQ_32000 = FREQ_32K;
    public static final int FREQ_44100 = FREQ_44K1;
    public static final int FREQ_48000 = FREQ_48K;

    // Channel modes
    public static final int MODE_MONO = 0;
    public static final int MODE_DUAL_CHANNEL = 1;
    public static final int MODE_STEREO = 2;
    public static final int MODE_JOINT_STEREO = 3;

    // Bit allocation methods
    public static final int BAM_LOUDNESS = 0;
    public static final int BAM_SNR = 1;

    // Backward-compatible aliases for allocation methods
    public static final int ALLOC_LOUDNESS = BAM_LOUDNESS;
    public static final int ALLOC_SNR = BAM_SNR;

    private static final int SBC_MAX_SUBBANDS = 8;
    private static final int SBC_MAX_BLOCKS = 16;
    private static final int SBC_MAX_SAMPLES = SBC_MAX_BLOCKS * SBC_MAX_SUBBANDS;
    private static final int SBC_HEADER_SIZE = 4;

    // ========== Encoder Configuration ==========

    private int mFreq;
    private int mMode;
    private int mBam;
    private int mNsubbands;
    private int mNblocks;
    private int mBitpool;
    private boolean mMsbc;
    private boolean mConfigured;
    private AudioCodecConfig mConfig;
    private CodecListener mListener;

    // ========== Encoder State ==========

    // Analysis filter state per channel
    private final AnalysisState[] mEstates = new AnalysisState[2];

    // ========== Analysis Filter State ==========

    private static class AnalysisState {
        // Sample history buffer organized for windowing
        // x[odd/even][sample_pair][history_position]
        final short[][][] x = new short[2][SBC_MAX_SUBBANDS][5];
        // Accumulated values for inter-block processing
        final int[] y = new int[4];
        // Circular buffer index
        int idx = 0;

        void reset() {
            for (int i = 0; i < 2; i++) {
                for (int j = 0; j < SBC_MAX_SUBBANDS; j++) {
                    for (int k = 0; k < 5; k++) {
                        x[i][j][k] = 0;
                    }
                }
            }
            for (int i = 0; i < 4; i++) {
                y[i] = 0;
            }
            idx = 0;
        }
    }

    // ========== Windowing Coefficients (fixed 2.13) ==========

    // 4-subband window coefficients from Google implementation
    private static final short[][][] WINDOW_4 = {
            {   //   0     1     2     3     4
                    {    0,  358, 4443,-4443, -358 },  // 0
                    {   49,  946, 8082, -944,   61 },  // 2
                    {   18,  670, 6389,-2544, -100 },  // 1
                    {   90, 1055, 9235,  201,  128 },  // 3
            },
            {   //   0     1     2     3     4
                    {  126,  848, 9644,  848,  126 },  // 0
                    {   61, -944, 8082,  946,   49 },  // 2
                    {  128,  201, 9235, 1055,   90 },  // 1
                    { -100,-2544, 6389,  670,   18 },  // 3
            },
    };

    // 8-subband window coefficients from Google implementation
    private static final short[][][] WINDOW_8 = {
            {   //   0     1     2     3     4
                    {    0,  185, 2228,-2228, -185 },  // 0
                    {   27,  480, 4039, -480,   30 },  // 4
                    {    5,  263, 2719,-1743, -115 },  // 1
                    {   58,  502, 4764,  290,   69 },  // 7
                    {   11,  343, 3197,-1280,  -54 },  // 2
                    {   48,  532, 4612,   96,   65 },  // 6
                    {   18,  418, 3644, -856,   -6 },  // 3
                    {   37,  521, 4367, -161,   53 },  // 5
            },
            {   //   0     1     2     3     4
                    {   66,  424, 4815,  424,   66 },  // 0
                    {   30, -480, 4039,  480,   27 },  // 4
                    {   69,  290, 4764,  502,   58 },  // 1
                    { -115,-1743, 2719,  263,    5 },  // 7
                    {   65,   96, 4612,  532,   48 },  // 2
                    {  -54,-1280, 3197,  343,   11 },  // 6
                    {   53, -161, 4367,  521,   37 },  // 3
                    {   -6, -856, 3644,  418,   18 },  // 5
            },
    };

    // Cosine matrices for subband output (fixed 0.13)
    // cos(i*pi/8) for 4 subbands
    private static final short[] COS8 = { 8192, 7568, 5793, 3135 };

    // Cosine matrix for 8-subband output
    private static final short[][] COSMAT_8 = {
            {  5793,  6811,  7568,  8035,   4551,  3135,  1598, 8192 },
            { -5793, -1598,  3135,  6811,  -8035, -7568, -4551, 8192 },
            { -5793, -8035, -3135,  4551,   1598,  7568,  6811, 8192 },
            {  5793, -4551, -7568,  1598,   6811, -3135, -8035, 8192 },
            {  5793,  4551, -7568, -1598,  -6811, -3135,  8035, 8192 },
            { -5793,  8035, -3135, -4551,  -1598,  7568, -6811, 8192 },
            { -5793,  1598,  3135, -6811,   8035, -7568,  4551, 8192 },
            {  5793, -6811,  7568, -8035,  -4551,  3135, -1598, 8192 },
    };

    // Loudness offsets for bit allocation
    private static final int[][] LOUDNESS_OFFSET_4 = {
            { -1,  0,  0,  0 },  // 16K
            { -2,  0,  0,  1 },  // 32K
            { -2,  0,  0,  1 },  // 44.1K
            { -2,  0,  0,  1 },  // 48K
    };

    private static final int[][] LOUDNESS_OFFSET_8 = {
            { -2,  0,  0,  0,  0,  0,  0,  1 },  // 16K
            { -3,  0,  0,  0,  0,  0,  1,  2 },  // 32K
            { -4,  0,  0,  0,  0,  0,  1,  2 },  // 44.1K
            { -4,  0,  0,  0,  0,  0,  1,  2 },  // 48K
    };

    // CRC-8 lookup table (polynomial 0x1D)
    private static final int[] CRC_TABLE = {
            0x00, 0x1d, 0x3a, 0x27, 0x74, 0x69, 0x4e, 0x53,
            0xe8, 0xf5, 0xd2, 0xcf, 0x9c, 0x81, 0xa6, 0xbb,
            0xcd, 0xd0, 0xf7, 0xea, 0xb9, 0xa4, 0x83, 0x9e,
            0x25, 0x38, 0x1f, 0x02, 0x51, 0x4c, 0x6b, 0x76,
            0x87, 0x9a, 0xbd, 0xa0, 0xf3, 0xee, 0xc9, 0xd4,
            0x6f, 0x72, 0x55, 0x48, 0x1b, 0x06, 0x21, 0x3c,
            0x4a, 0x57, 0x70, 0x6d, 0x3e, 0x23, 0x04, 0x19,
            0xa2, 0xbf, 0x98, 0x85, 0xd6, 0xcb, 0xec, 0xf1,
            0x13, 0x0e, 0x29, 0x34, 0x67, 0x7a, 0x5d, 0x40,
            0xfb, 0xe6, 0xc1, 0xdc, 0x8f, 0x92, 0xb5, 0xa8,
            0xde, 0xc3, 0xe4, 0xf9, 0xaa, 0xb7, 0x90, 0x8d,
            0x36, 0x2b, 0x0c, 0x11, 0x42, 0x5f, 0x78, 0x65,
            0x94, 0x89, 0xae, 0xb3, 0xe0, 0xfd, 0xda, 0xc7,
            0x7c, 0x61, 0x46, 0x5b, 0x08, 0x15, 0x32, 0x2f,
            0x59, 0x44, 0x63, 0x7e, 0x2d, 0x30, 0x17, 0x0a,
            0xb1, 0xac, 0x8b, 0x96, 0xc5, 0xd8, 0xff, 0xe2,
            0x26, 0x3b, 0x1c, 0x01, 0x52, 0x4f, 0x68, 0x75,
            0xce, 0xd3, 0xf4, 0xe9, 0xba, 0xa7, 0x80, 0x9d,
            0xeb, 0xf6, 0xd1, 0xcc, 0x9f, 0x82, 0xa5, 0xb8,
            0x03, 0x1e, 0x39, 0x24, 0x77, 0x6a, 0x4d, 0x50,
            0xa1, 0xbc, 0x9b, 0x86, 0xd5, 0xc8, 0xef, 0xf2,
            0x49, 0x54, 0x73, 0x6e, 0x3d, 0x20, 0x07, 0x1a,
            0x6c, 0x71, 0x56, 0x4b, 0x18, 0x05, 0x22, 0x3f,
            0x84, 0x99, 0xbe, 0xa3, 0xf0, 0xed, 0xca, 0xd7,
            0x35, 0x28, 0x0f, 0x12, 0x41, 0x5c, 0x7b, 0x66,
            0xdd, 0xc0, 0xe7, 0xfa, 0xa9, 0xb4, 0x93, 0x8e,
            0xf8, 0xe5, 0xc2, 0xdf, 0x8c, 0x91, 0xb6, 0xab,
            0x10, 0x0d, 0x2a, 0x37, 0x64, 0x79, 0x5e, 0x43,
            0xb2, 0xaf, 0x88, 0x95, 0xc6, 0xdb, 0xfc, 0xe1,
            0x5a, 0x47, 0x60, 0x7d, 0x2e, 0x33, 0x14, 0x09,
            0x7f, 0x62, 0x45, 0x58, 0x0b, 0x16, 0x31, 0x2c,
            0x97, 0x8a, 0xad, 0xb0, 0xe3, 0xfe, 0xd9, 0xc4,
    };

    // ========== Constructor ==========

    /**
     * Create a default SBC encoder (44.1kHz, Joint Stereo, 16 blocks, 8 subbands, bitpool 53).
     */
    public SbcCodec() {
        this(FREQ_44K1, MODE_JOINT_STEREO, 16, 8, BAM_LOUDNESS, 53);
    }

    /**
     * Create an SBC encoder with custom settings.
     */
    public SbcCodec(int freq, int mode, int nblocks, int nsubbands, int bam, int bitpool) {
        mEstates[0] = new AnalysisState();
        mEstates[1] = new AnalysisState();
        configure(freq, mode, nblocks, nsubbands, bam, bitpool);
    }

    /**
     * Configure encoder parameters.
     */
    public void configure(int freq, int mode, int nblocks, int nsubbands, int bam, int bitpool) {
        mFreq = freq;
        mMode = mode;
        mNblocks = nblocks;
        mNsubbands = nsubbands;
        mBam = bam;
        mBitpool = bitpool;
        mMsbc = false;
        mConfigured = true;
        reset();
    }

    /**
     * Configure for mSBC (16kHz, mono, 15 blocks, 8 subbands, bitpool 26).
     */
    public void configureMsbc() {
        mFreq = FREQ_16K;
        mMode = MODE_MONO;
        mNblocks = 15;
        mNsubbands = 8;
        mBam = BAM_LOUDNESS;
        mBitpool = 26;
        mMsbc = true;
        mConfigured = true;
        reset();
    }

    /**
     * Reset encoder state.
     */
    @Override
    public void reset() {
        mEstates[0].reset();
        mEstates[1].reset();
    }

    // ========== Frame Size Calculation ==========

    /**
     * Get the number of PCM samples needed per channel for one SBC frame.
     */
    @Override
    public int getSamplesPerFrame() {
        return mNblocks * mNsubbands;
    }

    /**
     * Get the encoded frame size in bytes.
     */
    public int getFrameSize() {
        boolean twoChannels = (mMode != MODE_MONO);
        boolean dualMode = (mMode == MODE_DUAL_CHANNEL);
        boolean jointMode = (mMode == MODE_JOINT_STEREO);

        int nbits = ((4 * mNsubbands) << (twoChannels ? 1 : 0)) +
                ((mNblocks * mBitpool) << (dualMode ? 1 : 0)) +
                (jointMode ? mNsubbands : 0);

        return SBC_HEADER_SIZE + ((nbits + 7) >> 3);
    }

    // ========== Utility Functions ==========

    private static short sat16(int v) {
        if (v > Short.MAX_VALUE) return Short.MAX_VALUE;
        if (v < Short.MIN_VALUE) return Short.MIN_VALUE;
        return (short) v;
    }

    private static int clz(int n) {
        return Integer.numberOfLeadingZeros(n);
    }

    // ========== Analysis Filter Bank ==========

    /**
     * Transform 4 PCM samples into 4 subband samples.
     */
    private void analyze4(AnalysisState state, short[] in, int inOffset, int pitch, short[] out) {
        int idx = state.idx >> 1;
        int odd = state.idx & 1;

        short[][] x = state.x[odd];
        int inIdx = idx != 0 ? 5 - idx : 0;

        // Load samples in order: [0, 2, (1, 3)]
        x[0][inIdx] = in[inOffset + (3 - 0) * pitch];
        x[1][inIdx] = in[inOffset + (3 - 2) * pitch];
        x[2][inIdx] = in[inOffset + (3 - 1) * pitch];
        x[3][inIdx] = in[inOffset + (3 - 3) * pitch];

        // Window and process
        short[][] w0 = new short[4][5];
        short[][] w1 = new short[4][5];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 5; j++) {
                int srcIdx = (idx + j) % 5;
                w0[i][j] = WINDOW_4[0][i][srcIdx];
                w1[i][j] = WINDOW_4[1][i][srcIdx];
            }
        }

        int y0, y1, y2, y3;
        short[] y = new short[4];

        y0 = x[0][0] * w0[0][0] + x[0][1] * w0[0][1] +
                x[0][2] * w0[0][2] + x[0][3] * w0[0][3] +
                x[0][4] * w0[0][4] + state.y[0];

        state.y[0] = x[0][0] * w1[0][0] + x[0][1] * w1[0][1] +
                x[0][2] * w1[0][2] + x[0][3] * w1[0][3] +
                x[0][4] * w1[0][4];

        y1 = x[2][0] * w0[2][0] + x[2][1] * w0[2][1] +
                x[2][2] * w0[2][2] + x[2][3] * w0[2][3] +
                x[2][4] * w0[2][4] + x[3][0] * w0[3][0] +
                x[3][1] * w0[3][1] + x[3][2] * w0[3][2] +
                x[3][3] * w0[3][3] + x[3][4] * w0[3][4];

        y2 = state.y[1];
        state.y[1] = x[2][0] * w1[2][0] + x[2][1] * w1[2][1] +
                x[2][2] * w1[2][2] + x[2][3] * w1[2][3] +
                x[2][4] * w1[2][4] - x[3][0] * w1[3][0] -
                x[3][1] * w1[3][1] - x[3][2] * w1[3][2] -
                x[3][3] * w1[3][3] - x[3][4] * w1[3][4];

        y3 = x[1][0] * w0[1][0] + x[1][1] * w0[1][1] +
                x[1][2] * w0[1][2] + x[1][3] * w0[1][3] +
                x[1][4] * w0[1][4];

        y[0] = sat16((y0 + (1 << 14)) >> 15);
        y[1] = sat16((y1 + (1 << 14)) >> 15);
        y[2] = sat16((y2 + (1 << 14)) >> 15);
        y[3] = sat16((y3 + (1 << 14)) >> 15);

        state.idx = state.idx < 9 ? state.idx + 1 : 0;

        // DCT output
        int s0, s1, s2, s3;
        s0 =  y[0] * COS8[2] + y[1] * COS8[1] + y[2] * COS8[3] + (y[3] << 13);
        s1 = -y[0] * COS8[2] + y[1] * COS8[3] - y[2] * COS8[1] + (y[3] << 13);
        s2 = -y[0] * COS8[2] - y[1] * COS8[3] + y[2] * COS8[1] + (y[3] << 13);
        s3 =  y[0] * COS8[2] - y[1] * COS8[1] - y[2] * COS8[3] + (y[3] << 13);

        out[0] = sat16((s0 + (1 << 12)) >> 13);
        out[1] = sat16((s1 + (1 << 12)) >> 13);
        out[2] = sat16((s2 + (1 << 12)) >> 13);
        out[3] = sat16((s3 + (1 << 12)) >> 13);
    }

    /**
     * Transform 8 PCM samples into 8 subband samples.
     */
    private void analyze8(AnalysisState state, short[] in, int inOffset, int pitch, short[] out) {
        int idx = state.idx >> 1;
        int odd = state.idx & 1;

        short[][] x = state.x[odd];
        int inIdx = idx != 0 ? 5 - idx : 0;

        // Load samples in order: [0, 4, (1, 7), (2, 6), (3, 5)]
        x[0][inIdx] = in[inOffset + (7 - 0) * pitch];
        x[1][inIdx] = in[inOffset + (7 - 4) * pitch];
        x[2][inIdx] = in[inOffset + (7 - 1) * pitch];
        x[3][inIdx] = in[inOffset + (7 - 7) * pitch];
        x[4][inIdx] = in[inOffset + (7 - 2) * pitch];
        x[5][inIdx] = in[inOffset + (7 - 6) * pitch];
        x[6][inIdx] = in[inOffset + (7 - 3) * pitch];
        x[7][inIdx] = in[inOffset + (7 - 5) * pitch];

        // Window and process
        short[][] w0 = new short[8][5];
        short[][] w1 = new short[8][5];
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 5; j++) {
                int srcIdx = (idx + j) % 5;
                w0[i][j] = WINDOW_8[0][i][srcIdx];
                w1[i][j] = WINDOW_8[1][i][srcIdx];
            }
        }

        int y0, y1, y2, y3, y4, y5, y6, y7;
        short[] y = new short[8];

        y0 = x[0][0] * w0[0][0] + x[0][1] * w0[0][1] +
                x[0][2] * w0[0][2] + x[0][3] * w0[0][3] +
                x[0][4] * w0[0][4] + state.y[0];

        state.y[0] = x[0][0] * w1[0][0] + x[0][1] * w1[0][1] +
                x[0][2] * w1[0][2] + x[0][3] * w1[0][3] +
                x[0][4] * w1[0][4];

        y1 = x[2][0] * w0[2][0] + x[2][1] * w0[2][1] +
                x[2][2] * w0[2][2] + x[2][3] * w0[2][3] +
                x[2][4] * w0[2][4] + x[3][0] * w0[3][0] +
                x[3][1] * w0[3][1] + x[3][2] * w0[3][2] +
                x[3][3] * w0[3][3] + x[3][4] * w0[3][4];

        y4 = state.y[1];
        state.y[1] = x[2][0] * w1[2][0] + x[2][1] * w1[2][1] +
                x[2][2] * w1[2][2] + x[2][3] * w1[2][3] +
                x[2][4] * w1[2][4] - x[3][0] * w1[3][0] -
                x[3][1] * w1[3][1] - x[3][2] * w1[3][2] -
                x[3][3] * w1[3][3] - x[3][4] * w1[3][4];

        y2 = x[4][0] * w0[4][0] + x[4][1] * w0[4][1] +
                x[4][2] * w0[4][2] + x[4][3] * w0[4][3] +
                x[4][4] * w0[4][4] + x[5][0] * w0[5][0] +
                x[5][1] * w0[5][1] + x[5][2] * w0[5][2] +
                x[5][3] * w0[5][3] + x[5][4] * w0[5][4];

        y5 = state.y[2];
        state.y[2] = x[4][0] * w1[4][0] + x[4][1] * w1[4][1] +
                x[4][2] * w1[4][2] + x[4][3] * w1[4][3] +
                x[4][4] * w1[4][4] - x[5][0] * w1[5][0] -
                x[5][1] * w1[5][1] - x[5][2] * w1[5][2] -
                x[5][3] * w1[5][3] - x[5][4] * w1[5][4];

        y3 = x[6][0] * w0[6][0] + x[6][1] * w0[6][1] +
                x[6][2] * w0[6][2] + x[6][3] * w0[6][3] +
                x[6][4] * w0[6][4] + x[7][0] * w0[7][0] +
                x[7][1] * w0[7][1] + x[7][2] * w0[7][2] +
                x[7][3] * w0[7][3] + x[7][4] * w0[7][4];

        y6 = state.y[3];
        state.y[3] = x[6][0] * w1[6][0] + x[6][1] * w1[6][1] +
                x[6][2] * w1[6][2] + x[6][3] * w1[6][3] +
                x[6][4] * w1[6][4] - x[7][0] * w1[7][0] -
                x[7][1] * w1[7][1] - x[7][2] * w1[7][2] -
                x[7][3] * w1[7][3] - x[7][4] * w1[7][4];

        y7 = x[1][0] * w0[1][0] + x[1][1] * w0[1][1] +
                x[1][2] * w0[1][2] + x[1][3] * w0[1][3] +
                x[1][4] * w0[1][4];

        y[0] = sat16((y0 + (1 << 14)) >> 15);
        y[1] = sat16((y1 + (1 << 14)) >> 15);
        y[2] = sat16((y2 + (1 << 14)) >> 15);
        y[3] = sat16((y3 + (1 << 14)) >> 15);
        y[4] = sat16((y4 + (1 << 14)) >> 15);
        y[5] = sat16((y5 + (1 << 14)) >> 15);
        y[6] = sat16((y6 + (1 << 14)) >> 15);
        y[7] = sat16((y7 + (1 << 14)) >> 15);

        state.idx = state.idx < 9 ? state.idx + 1 : 0;

        // DCT output using cosine matrix
        for (int i = 0; i < 8; i++) {
            int s = y[0] * COSMAT_8[i][0] + y[1] * COSMAT_8[i][1] +
                    y[2] * COSMAT_8[i][2] + y[3] * COSMAT_8[i][3] +
                    y[4] * COSMAT_8[i][4] + y[5] * COSMAT_8[i][5] +
                    y[6] * COSMAT_8[i][6] + y[7] * COSMAT_8[i][7];
            out[i] = sat16((s + (1 << 12)) >> 13);
        }
    }

    /**
     * Analyze PCM samples into subband samples for a channel.
     */
    private void analyze(AnalysisState state, short[] in, int inOffset, int pitch, short[][] out) {
        short[] blockOut = new short[mNsubbands];
        for (int iblk = 0; iblk < mNblocks; iblk++) {
            if (mNsubbands == 4) {
                analyze4(state, in, inOffset, pitch, blockOut);
            } else {
                analyze8(state, in, inOffset, pitch, blockOut);
            }
            System.arraycopy(blockOut, 0, out[iblk], 0, mNsubbands);
            inOffset += mNsubbands * pitch;
        }
    }

    // ========== Scale Factor Computation ==========

    /**
     * Compute scale factors from subband samples.
     */
    private void computeScaleFactors(short[][][] sbSamples, int[][] scaleFactors) {
        int nchannels = mMode == MODE_MONO ? 1 : 2;

        for (int ich = 0; ich < nchannels; ich++) {
            for (int isb = 0; isb < mNsubbands; isb++) {
                int m = 0;
                for (int iblk = 0; iblk < mNblocks; iblk++) {
                    int s = sbSamples[ich][iblk][isb];
                    m |= s < 0 ? ~s : s;
                }
                int scf = m != 0 ? (31 - clz(m)) : 0;
                scaleFactors[ich][isb] = scf;
            }
        }
    }

    /**
     * Compute scale factors with joint stereo optimization.
     * Returns the joint stereo mask.
     */
    private int computeScaleFactorsJs(short[][][] sbSamples, int[][] scaleFactors) {
        int mjoint = 0;

        for (int isb = 0; isb < mNsubbands; isb++) {
            int[] m = new int[2];
            int[] mj = new int[2];

            for (int iblk = 0; iblk < mNblocks; iblk++) {
                int s0 = sbSamples[0][iblk][isb];
                int s1 = sbSamples[1][iblk][isb];

                m[0] |= s0 < 0 ? ~s0 : s0;
                m[1] |= s1 < 0 ? ~s1 : s1;

                int sum = s0 + s1;
                int diff = s0 - s1;
                mj[0] |= sum < 0 ? ~sum : sum;
                mj[1] |= diff < 0 ? ~diff : diff;
            }

            int scf0 = m[0] != 0 ? (31 - clz(m[0])) : 0;
            int scf1 = m[1] != 0 ? (31 - clz(m[1])) : 0;
            int js0 = mj[0] != 0 ? (31 - clz(mj[0])) : 0;
            int js1 = mj[1] != 0 ? (31 - clz(mj[1])) : 0;

            // Use joint stereo if more efficient (except for last subband)
            if (isb < mNsubbands - 1 && js0 + js1 < scf0 + scf1) {
                mjoint |= 1 << isb;
                scaleFactors[0][isb] = js0;
                scaleFactors[1][isb] = js1;
            } else {
                scaleFactors[0][isb] = scf0;
                scaleFactors[1][isb] = scf1;
            }
        }

        return mjoint;
    }

    // ========== Bit Allocation ==========

    /**
     * Compute bit allocation for subbands.
     */
    private void computeNbits(int[][] scaleFactors, int[][] nbits) {
        int[] loudnessOffset = mNsubbands == 4 ?
                LOUDNESS_OFFSET_4[mFreq] : LOUDNESS_OFFSET_8[mFreq];

        boolean stereoMode = mMode == MODE_STEREO || mMode == MODE_JOINT_STEREO;
        int nchannels = stereoMode ? 2 : 1;

        int[][] bitneeds = new int[2][SBC_MAX_SUBBANDS];
        int maxBitneed = 0;

        // Calculate bitneeds
        for (int ich = 0; ich < nchannels; ich++) {
            for (int isb = 0; isb < mNsubbands; isb++) {
                int bitneed;
                int scf = scaleFactors[ich][isb];

                if (mBam == BAM_LOUDNESS) {
                    bitneed = scf != 0 ? scf - loudnessOffset[isb] : -5;
                    bitneed >>= (bitneed > 0 ? 1 : 0);
                } else {
                    bitneed = scf;
                }

                if (bitneed > maxBitneed) {
                    maxBitneed = bitneed;
                }
                bitneeds[ich][isb] = bitneed;
            }
        }

        // Bit allocation loop
        int bitcount = 0;
        int bitslice = maxBitneed + 1;

        for (int bc = 0; bc < mBitpool; ) {
            int bs = bitslice--;
            bitcount = bc;
            if (bitcount == mBitpool) break;

            for (int ich = 0; ich < nchannels; ich++) {
                for (int isb = 0; isb < mNsubbands; isb++) {
                    int bn = bitneeds[ich][isb];
                    bc += (bn >= bs && bn < bs + 15 ? 1 : 0) + (bn == bs ? 1 : 0);
                }
            }
        }

        // Initial bit distribution
        for (int ich = 0; ich < nchannels; ich++) {
            for (int isb = 0; isb < mNsubbands; isb++) {
                int nbit = bitneeds[ich][isb] - bitslice;
                nbits[ich][isb] = nbit < 2 ? 0 : nbit > 16 ? 16 : nbit;
            }
        }

        // Allocate remaining bits
        for (int isb = 0; isb < mNsubbands && bitcount < mBitpool; isb++) {
            for (int ich = 0; ich < nchannels && bitcount < mBitpool; ich++) {
                int n = 0;
                if (nbits[ich][isb] != 0 && nbits[ich][isb] < 16) {
                    n = 1;
                } else if (bitneeds[ich][isb] == bitslice + 1 && mBitpool > bitcount + 1) {
                    n = 2;
                }
                nbits[ich][isb] += n;
                bitcount += n;
            }
        }

        for (int isb = 0; isb < mNsubbands && bitcount < mBitpool; isb++) {
            for (int ich = 0; ich < nchannels && bitcount < mBitpool; ich++) {
                int n = (nbits[ich][isb] < 16) ? 1 : 0;
                nbits[ich][isb] += n;
                bitcount += n;
            }
        }
    }

    // ========== Bitstream Writer ==========

    private static class BitWriter {
        private byte[] buffer;
        private int bytePos;
        private int bitPos;  // Bits remaining in current byte (8 = full)
        private int accu;    // Accumulator for current byte

        BitWriter(int size) {
            buffer = new byte[size];
            bytePos = 0;
            bitPos = 8;
            accu = 0;
        }

        void putBits(int value, int nbits) {
            while (nbits > 0) {
                int bitsToWrite = Math.min(nbits, bitPos);
                int shift = nbits - bitsToWrite;
                int mask = (1 << bitsToWrite) - 1;

                accu = (accu << bitsToWrite) | ((value >> shift) & mask);
                bitPos -= bitsToWrite;
                nbits -= bitsToWrite;

                if (bitPos == 0) {
                    buffer[bytePos++] = (byte) accu;
                    bitPos = 8;
                    accu = 0;
                }
            }
        }

        void flush() {
            if (bitPos < 8) {
                buffer[bytePos++] = (byte) (accu << bitPos);
            }
        }

        int tell() {
            return bytePos * 8 + (8 - bitPos);
        }

        byte[] getBytes() {
            return buffer;
        }
    }

    // ========== CRC Calculation ==========

    /**
     * Compute CRC for frame.
     */
    private int computeCrc(byte[] data, int size) {
        int nch = mMode == MODE_MONO ? 1 : 2;
        int nbit = nch * mNsubbands * 4 + (mMode == MODE_JOINT_STEREO ? mNsubbands : 0);

        int crc = 0x0F;
        crc = CRC_TABLE[crc ^ (data[1] & 0xFF)];
        crc = CRC_TABLE[crc ^ (data[2] & 0xFF)];

        int i;
        for (i = 4; i < 4 + nbit / 8; i++) {
            crc = CRC_TABLE[crc ^ (data[i] & 0xFF)];
        }

        if (nbit % 8 != 0) {
            crc = ((crc << 4) ^ CRC_TABLE[((crc >> 4) ^ (data[i] >> 4)) & 0xFF]) & 0xFF;
        }

        return crc;
    }

    // ========== Encoding ==========

    /**
     * Encode one frame of stereo PCM audio to SBC.
     */
    public byte[] encode(short[] pcmLeft, short[] pcmRight, int offset) {
        int nchannels = mMode == MODE_MONO ? 1 : 2;

        // Analyze PCM into subband samples
        short[][][] sbSamples = new short[2][mNblocks][mNsubbands];

        analyze(mEstates[0], pcmLeft, offset, 1, sbSamples[0]);
        if (nchannels == 2 && pcmRight != null) {
            analyze(mEstates[1], pcmRight, offset, 1, sbSamples[1]);
        }

        // Compute scale factors
        int[][] scaleFactors = new int[2][SBC_MAX_SUBBANDS];
        int mjoint = 0;

        if (mMode == MODE_JOINT_STEREO) {
            mjoint = computeScaleFactorsJs(sbSamples, scaleFactors);
        } else {
            computeScaleFactors(sbSamples, scaleFactors);
        }

        if (mMode == MODE_DUAL_CHANNEL) {
            // Compute scale factors for second channel separately
            short[][][] ch1Samples = { sbSamples[1], null };
            int[][] ch1Sf = { scaleFactors[1], new int[SBC_MAX_SUBBANDS] };
            computeScaleFactors(ch1Samples, ch1Sf);
        }

        // Compute bit allocation
        int[][] nbits = new int[2][SBC_MAX_SUBBANDS];
        computeNbits(scaleFactors, nbits);
        if (mMode == MODE_DUAL_CHANNEL) {
            int[][] ch1Sf = { scaleFactors[1], new int[SBC_MAX_SUBBANDS] };
            int[][] ch1Nbits = { nbits[1], new int[SBC_MAX_SUBBANDS] };
            computeNbits(ch1Sf, ch1Nbits);
        }

        // Apply joint stereo coupling
        if (mMode == MODE_JOINT_STEREO) {
            for (int isb = 0; isb < mNsubbands; isb++) {
                if (((mjoint >> isb) & 1) == 0) continue;

                for (int iblk = 0; iblk < mNblocks; iblk++) {
                    short s0 = sbSamples[0][iblk][isb];
                    short s1 = sbSamples[1][iblk][isb];
                    sbSamples[0][iblk][isb] = (short) ((s0 + s1) >> 1);
                    sbSamples[1][iblk][isb] = (short) ((s0 - s1) >> 1);
                }
            }
        }

        // Encode frame
        int frameSize = getFrameSize();
        byte[] frame = new byte[frameSize];
        BitWriter bits = new BitWriter(frameSize);

        // Write header
        bits.putBits(mMsbc ? 0xAD : 0x9C, 8);
        if (!mMsbc) {
            bits.putBits(mFreq, 2);
            bits.putBits((mNblocks >> 2) - 1, 2);
            bits.putBits(mMode, 2);
            bits.putBits(mBam, 1);
            bits.putBits((mNsubbands >> 2) - 1, 1);
            bits.putBits(mBitpool, 8);
        } else {
            bits.putBits(0, 16);  // Reserved
        }
        bits.putBits(0, 8);  // CRC placeholder

        // Write joint stereo mask
        if (mMode == MODE_JOINT_STEREO) {
            if (mNsubbands == 4) {
                int j = ((mjoint & 0x01) << 3) | ((mjoint & 0x02) << 1) |
                        ((mjoint & 0x04) >> 1);
                bits.putBits(j, 4);
            } else {
                int j = ((mjoint & 0x01) << 7) | ((mjoint & 0x02) << 5) |
                        ((mjoint & 0x04) << 3) | ((mjoint & 0x08) << 1) |
                        ((mjoint & 0x10) >> 1) | ((mjoint & 0x20) >> 3) |
                        ((mjoint & 0x40) >> 5);
                bits.putBits(j, 8);
            }
        }

        // Write scale factors
        for (int ich = 0; ich < nchannels; ich++) {
            for (int isb = 0; isb < mNsubbands; isb++) {
                bits.putBits(scaleFactors[ich][isb], 4);
            }
        }

        // Write quantized samples
        for (int iblk = 0; iblk < mNblocks; iblk++) {
            for (int ich = 0; ich < nchannels; ich++) {
                for (int isb = 0; isb < mNsubbands; isb++) {
                    int nbit = nbits[ich][isb];
                    if (nbit == 0) continue;

                    int scf = scaleFactors[ich][isb];
                    int s = sbSamples[ich][iblk][isb];
                    int range = (1 << nbit) - 1;

                    // Quantize: ((s * range) >> (scf + 1) + range) >> 1
                    int quantized = (((s * range) >> (scf + 1)) + range) >> 1;
                    quantized = Math.max(0, Math.min(range, quantized));
                    bits.putBits(quantized, nbit);
                }
            }
        }

        // Padding
        int paddingBits = 8 - (bits.tell() % 8);
        if (paddingBits < 8) {
            bits.putBits(0, paddingBits);
        }

        bits.flush();
        System.arraycopy(bits.getBytes(), 0, frame, 0, frameSize);

        // Compute and insert CRC
        int crc = computeCrc(frame, frameSize);
        frame[3] = (byte) crc;

        return frame;
    }

    /**
     * Encode mono PCM to SBC frame.
     */
    public byte[] encodeMono(short[] pcm, int offset) {
        return encode(pcm, null, offset);
    }

    /**
     * Encode interleaved stereo PCM to SBC frame.
     */
    public byte[] encodeInterleaved(short[] pcm, int offset) {
        int samplesPerFrame = getSamplesPerFrame();
        short[] left = new short[samplesPerFrame];
        short[] right = new short[samplesPerFrame];

        for (int i = 0; i < samplesPerFrame; i++) {
            int idx = offset + i * 2;
            left[i] = (idx < pcm.length) ? pcm[idx] : 0;
            right[i] = (idx + 1 < pcm.length) ? pcm[idx + 1] : 0;
        }

        return encode(left, right, 0);
    }

    /**
     * Create a silent SBC frame.
     */
    public byte[] createSilentFrame() {
        short[] silence = new short[getSamplesPerFrame()];
        if (mMode == MODE_MONO) {
            return encodeMono(silence, 0);
        } else {
            return encode(silence, silence, 0);
        }
    }

    // ========== Getters ==========

    public int getNumChannels() {
        return mMode == MODE_MONO ? 1 : 2;
    }

    public int getSamplingFrequencyHz() {
        switch (mFreq) {
            case FREQ_16K: return 16000;
            case FREQ_32K: return 32000;
            case FREQ_44K1: return 44100;
            case FREQ_48K: return 48000;
            default: return 44100;
        }
    }

    public int getSamplingFrequency() { return mFreq; }
    public int getChannelMode() { return mMode; }
    public int getBlockLength() { return mNblocks; }
    public int getNumSubbands() { return mNsubbands; }
    public int getAllocationMethod() { return mBam; }
    public int getBitpool() { return mBitpool; }

    public void setBitpool(int bitpool) {
        mBitpool = bitpool;
    }

    // ========== AudioCodec Interface Implementation ==========

    @Override
    public CodecType getType() {
        return CodecType.SBC;
    }

    @Override
    public Mode getMode() {
        return Mode.ENCODER;
    }

    @Override
    public String getName() {
        return mMsbc ? "mSBC" : "SBC";
    }

    @Override
    public boolean configure(AudioCodecConfig config) {
        if (config == null || config.getCodecType() != CodecType.SBC) {
            return false;
        }

        mConfig = config;

        int freq = sampleRateToFreqCode(config.getSampleRate());
        int mode = channelModeToCode(config.getChannelMode());
        int blocks = config.getSbcBlocks();
        int subbands = config.getSbcSubbands();
        int bam = config.getSbcAllocation() == AudioCodecConfig.AllocationMethod.SNR ? BAM_SNR : BAM_LOUDNESS;
        int bitpool = config.getSbcBitpool();

        configure(freq, mode, blocks, subbands, bam, bitpool);
        return true;
    }

    @Override
    public AudioCodecConfig getConfig() {
        return mConfig;
    }

    @Override
    public boolean isReady() {
        return mConfigured;
    }

    @Override
    public int getChannelCount() {
        return mMode == MODE_MONO ? 1 : 2;
    }

    @Override
    public int getMaxFrameSize() {
        // Maximum possible frame size with bitpool 250
        return SBC_HEADER_SIZE + ((4 * mNsubbands * 2 + mNblocks * 250 * 2 + mNsubbands + 7) >> 3);
    }

    @Override
    public int getEncodedFrameSize() {
        return getFrameSize();
    }

    @Override
    public byte[] encode(short[] pcmData) {
        return encode(pcmData, 0, pcmData.length);
    }

    @Override
    public byte[] encode(short[] pcmData, int offset, int length) {
        if (!mConfigured || pcmData == null) return null;

        int samplesPerFrame = getSamplesPerFrame();
        int channels = getChannelCount();

        if (channels == 1) {
            // Mono
            if (offset + samplesPerFrame > pcmData.length) return null;
            short[] mono = new short[samplesPerFrame];
            System.arraycopy(pcmData, offset, mono, 0, samplesPerFrame);
            return encodeMono(mono, 0);
        } else {
            // Stereo (interleaved)
            if (offset + samplesPerFrame * 2 > pcmData.length) return null;
            return encodeInterleaved(pcmData, offset);
        }
    }

    @Override
    public int encode(short[] pcmData, int pcmOffset, int pcmLength,
                      byte[] output, int outputOffset) {
        byte[] encoded = encode(pcmData, pcmOffset, pcmLength);
        if (encoded == null) return -1;
        System.arraycopy(encoded, 0, output, outputOffset, encoded.length);
        return encoded.length;
    }

    @Override
    public short[] decode(byte[] encodedData) {
        // This is an encoder only - decoding not implemented
        return null;
    }

    @Override
    public short[] decode(byte[] encodedData, int offset, int length) {
        return null;
    }

    @Override
    public int decode(byte[] encodedData, int encodedOffset, int encodedLength,
                      short[] pcmOutput, int pcmOffset) {
        return -1;
    }

    @Override
    public byte[] buildCapabilities() {
        // Build AVDTP SBC capability IE
        // [Media Type (4b) | RFA (4b)] [Codec Type] [Freq|Chan] [Blocks|Subbands|Alloc] [MinBitpool] [MaxBitpool]
        return new byte[] {
                (byte) 0x00,  // Media Type = Audio
                (byte) 0x00,  // Codec Type = SBC
                (byte) 0xFF,  // All frequencies and channel modes supported
                (byte) 0xFF,  // All block lengths, subbands, allocation methods
                (byte) 2,     // Min bitpool
                (byte) 53     // Max bitpool
        };
    }

    @Override
    public byte[] buildConfiguration() {
        int freqChan = (getSbcFrequencyFlag() | getSbcChannelModeFlag());
        int blocksSubbandsAlloc = (getSbcBlocksFlag() | getSbcSubbandsFlag() | getSbcAllocationFlag());

        return new byte[] {
                (byte) 0x00,  // Media Type = Audio
                (byte) 0x00,  // Codec Type = SBC
                (byte) freqChan,
                (byte) blocksSubbandsAlloc,
                (byte) mBitpool,  // Min = Max for configuration
                (byte) mBitpool
        };
    }

    @Override
    public boolean parseConfiguration(byte[] config) {
        if (config == null || config.length < 6) return false;

        int freqChan = config[2] & 0xFF;
        int blocksSubbandsAlloc = config[3] & 0xFF;
        int minBitpool = config[4] & 0xFF;
        int maxBitpool = config[5] & 0xFF;

        // Parse frequency
        int freq;
        if ((freqChan & 0x80) != 0) freq = FREQ_16K;
        else if ((freqChan & 0x40) != 0) freq = FREQ_32K;
        else if ((freqChan & 0x20) != 0) freq = FREQ_44K1;
        else if ((freqChan & 0x10) != 0) freq = FREQ_48K;
        else freq = FREQ_44K1;

        // Parse channel mode
        int mode;
        if ((freqChan & 0x08) != 0) mode = MODE_MONO;
        else if ((freqChan & 0x04) != 0) mode = MODE_DUAL_CHANNEL;
        else if ((freqChan & 0x02) != 0) mode = MODE_STEREO;
        else if ((freqChan & 0x01) != 0) mode = MODE_JOINT_STEREO;
        else mode = MODE_JOINT_STEREO;

        // Parse blocks
        int blocks;
        if ((blocksSubbandsAlloc & 0x80) != 0) blocks = 4;
        else if ((blocksSubbandsAlloc & 0x40) != 0) blocks = 8;
        else if ((blocksSubbandsAlloc & 0x20) != 0) blocks = 12;
        else if ((blocksSubbandsAlloc & 0x10) != 0) blocks = 16;
        else blocks = 16;

        // Parse subbands
        int subbands = ((blocksSubbandsAlloc & 0x08) != 0) ? 4 : 8;

        // Parse allocation
        int bam = ((blocksSubbandsAlloc & 0x02) != 0) ? BAM_SNR : BAM_LOUDNESS;

        configure(freq, mode, blocks, subbands, bam, maxBitpool);
        return true;
    }

    @Override
    public int getBitrate() {
        int frameSize = getFrameSize();
        int samplesPerFrame = getSamplesPerFrame();
        int sampleRate = getSampleRate();
        return (frameSize * 8 * sampleRate) / samplesPerFrame;
    }

    @Override
    public int getSampleRate() {
        switch (mFreq) {
            case FREQ_16K: return 16000;
            case FREQ_32K: return 32000;
            case FREQ_44K1: return 44100;
            case FREQ_48K: return 48000;
            default: return 44100;
        }
    }

    @Override
    public void release() {
        mConfigured = false;
    }

    @Override
    public void setListener(CodecListener listener) {
        mListener = listener;
    }

    // ========== Helper Methods for AudioCodec ==========

    private int sampleRateToFreqCode(int sampleRate) {
        switch (sampleRate) {
            case 16000: return FREQ_16K;
            case 32000: return FREQ_32K;
            case 44100: return FREQ_44K1;
            case 48000: return FREQ_48K;
            default: return FREQ_44K1;
        }
    }

    private int channelModeToCode(AudioCodecConfig.ChannelMode mode) {
        switch (mode) {
            case MONO: return MODE_MONO;
            case DUAL_CHANNEL: return MODE_DUAL_CHANNEL;
            case STEREO: return MODE_STEREO;
            case JOINT_STEREO: return MODE_JOINT_STEREO;
            default: return MODE_JOINT_STEREO;
        }
    }

    private int getSbcFrequencyFlag() {
        switch (mFreq) {
            case FREQ_16K: return 0x80;
            case FREQ_32K: return 0x40;
            case FREQ_44K1: return 0x20;
            case FREQ_48K: return 0x10;
            default: return 0x20;
        }
    }

    private int getSbcChannelModeFlag() {
        switch (mMode) {
            case MODE_MONO: return 0x08;
            case MODE_DUAL_CHANNEL: return 0x04;
            case MODE_STEREO: return 0x02;
            case MODE_JOINT_STEREO: return 0x01;
            default: return 0x01;
        }
    }

    private int getSbcBlocksFlag() {
        switch (mNblocks) {
            case 4: return 0x80;
            case 8: return 0x40;
            case 12: return 0x20;
            case 16: return 0x10;
            default: return 0x10;
        }
    }

    private int getSbcSubbandsFlag() {
        return mNsubbands == 4 ? 0x08 : 0x04;
    }

    private int getSbcAllocationFlag() {
        return mBam == BAM_SNR ? 0x02 : 0x01;
    }

    // ========== Static Registration ==========

    static {
        // Register with AudioCodecFactory
        AudioCodecFactory.registerEncoder(CodecType.SBC, "SBC", SbcCodec::new);
        AudioCodecFactory.setDefaultEncoder(CodecType.SBC, "SBC");
    }
}