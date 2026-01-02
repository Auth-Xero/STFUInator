package com.courierstack.rfcomm;

import java.util.UUID;

/**
 * RFCOMM protocol constants per Bluetooth Core Spec v5.3, Vol 3, Part F (RFCOMM)
 * and 3GPP TS 27.010 (GSM 07.10 multiplexer).
 */
public final class RfcommConstants {

    private RfcommConstants() {}

    // ===== Standard Service UUIDs (Assigned Numbers) =====
    public static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    public static final UUID OBEX_OBJECT_PUSH = UUID.fromString("00001105-0000-1000-8000-00805F9B34FB");
    public static final UUID OBEX_FILE_TRANSFER = UUID.fromString("00001106-0000-1000-8000-00805F9B34FB");
    public static final UUID HFP_AG = UUID.fromString("0000111F-0000-1000-8000-00805F9B34FB");
    public static final UUID HFP_HF = UUID.fromString("0000111E-0000-1000-8000-00805F9B34FB");
    public static final UUID HSP_AG = UUID.fromString("00001112-0000-1000-8000-00805F9B34FB");
    public static final UUID HSP_HS = UUID.fromString("00001108-0000-1000-8000-00805F9B34FB");
    public static final UUID A2DP_SOURCE = UUID.fromString("0000110A-0000-1000-8000-00805F9B34FB");
    public static final UUID A2DP_SINK = UUID.fromString("0000110B-0000-1000-8000-00805F9B34FB");
    public static final UUID AVRCP_TARGET = UUID.fromString("0000110C-0000-1000-8000-00805F9B34FB");
    public static final UUID AVRCP_CONTROLLER = UUID.fromString("0000110E-0000-1000-8000-00805F9B34FB");
    public static final UUID PBAP_PSE = UUID.fromString("0000112F-0000-1000-8000-00805F9B34FB");
    public static final UUID PBAP_PCE = UUID.fromString("0000112E-0000-1000-8000-00805F9B34FB");
    public static final UUID MAP_MAS = UUID.fromString("00001132-0000-1000-8000-00805F9B34FB");
    public static final UUID MAP_MNS = UUID.fromString("00001133-0000-1000-8000-00805F9B34FB");
    public static final UUID DUN = UUID.fromString("00001103-0000-1000-8000-00805F9B34FB");
    public static final UUID SAP = UUID.fromString("0000112D-0000-1000-8000-00805F9B34FB");

    // ===== Frame Types (TS 27.010 Section 5.2.1.3) =====
    /** Set Asynchronous Balanced Mode - connection request. */
    public static final int FRAME_SABM = 0x2F;
    /** Unnumbered Acknowledgement - positive response. */
    public static final int FRAME_UA = 0x63;
    /** Disconnected Mode - negative response or no connection. */
    public static final int FRAME_DM = 0x0F;
    /** Disconnect - close connection. */
    public static final int FRAME_DISC = 0x43;
    /** Unnumbered Information with Header check - data frame. */
    public static final int FRAME_UIH = 0xEF;
    /** Unnumbered Information - data frame with full FCS. */
    public static final int FRAME_UI = 0x03;

    // ===== Multiplexer Control Commands (TS 27.010 Section 5.4.6) =====
    /** Parameter Negotiation. */
    public static final int MCC_PN = 0x20;
    /** Test Command. */
    public static final int MCC_TEST = 0x08;
    /** Flow Control On. */
    public static final int MCC_FCON = 0x28;
    /** Flow Control Off. */
    public static final int MCC_FCOFF = 0x18;
    /** Modem Status Command. */
    public static final int MCC_MSC = 0x38;
    /** Non-Supported Command Response. */
    public static final int MCC_NSC = 0x04;
    /** Remote Port Negotiation. */
    public static final int MCC_RPN = 0x24;
    /** Remote Line Status. */
    public static final int MCC_RLS = 0x14;
    /** Power Saving Control (optional). */
    public static final int MCC_PSC = 0x10;
    /** Service Negotiation Command (optional). */
    public static final int MCC_SNC = 0x34;

    // ===== MSC Modem Status Bits (TS 27.010 Section 5.4.6.3.7) =====
    /** Flow Control - set when device cannot accept frames. */
    public static final int MSC_FC = 0x02;
    /** Ready To Communicate. */
    public static final int MSC_RTC = 0x04;
    /** Ready To Receive. */
    public static final int MSC_RTR = 0x08;
    /** Incoming Call indicator. */
    public static final int MSC_IC = 0x40;
    /** Data Valid - set when valid data is being sent. */
    public static final int MSC_DV = 0x80;

    // ===== RLS Line Status Bits (TS 27.010 Section 5.4.6.3.10) =====
    public static final int RLS_OVERRUN_ERROR = 0x01;
    public static final int RLS_PARITY_ERROR = 0x02;
    public static final int RLS_FRAMING_ERROR = 0x04;

    // ===== PN Convergence Layer Types =====
    public static final int PN_NO_CREDIT_FLOW = 0x00;
    public static final int PN_CREDIT_FLOW_REQ = 0xF0;
    public static final int PN_CREDIT_FLOW_ACK = 0xE0;

    // ===== RPN Baud Rates (TS 27.010 Section 5.4.6.3.9) =====
    public static final int RPN_BAUD_2400 = 0x00;
    public static final int RPN_BAUD_4800 = 0x01;
    public static final int RPN_BAUD_7200 = 0x02;
    public static final int RPN_BAUD_9600 = 0x03;
    public static final int RPN_BAUD_19200 = 0x04;
    public static final int RPN_BAUD_38400 = 0x05;
    public static final int RPN_BAUD_57600 = 0x06;
    public static final int RPN_BAUD_115200 = 0x07;
    public static final int RPN_BAUD_230400 = 0x08;

    // ===== Default Values =====
    public static final int DEFAULT_MTU = 127;
    public static final int MAX_MTU = 32767;
    public static final int DEFAULT_CREDITS = 7;
    public static final int MAX_CREDITS = 255;
    public static final int DEFAULT_PRIORITY = 0;
    public static final int MAX_SERVER_CHANNEL = 30;

    /**
     * Returns human-readable name for frame type.
     */
    public static String getFrameTypeName(int type) {
        switch (type) {
            case FRAME_SABM: return "SABM";
            case FRAME_UA: return "UA";
            case FRAME_DM: return "DM";
            case FRAME_DISC: return "DISC";
            case FRAME_UIH: return "UIH";
            case FRAME_UI: return "UI";
            default: return String.format("0x%02X", type);
        }
    }

    /**
     * Returns human-readable name for MCC command type.
     */
    public static String getMccTypeName(int type) {
        switch (type) {
            case MCC_PN: return "PN";
            case MCC_TEST: return "TEST";
            case MCC_FCON: return "FCON";
            case MCC_FCOFF: return "FCOFF";
            case MCC_MSC: return "MSC";
            case MCC_NSC: return "NSC";
            case MCC_RPN: return "RPN";
            case MCC_RLS: return "RLS";
            default: return String.format("0x%02X", type);
        }
    }
}