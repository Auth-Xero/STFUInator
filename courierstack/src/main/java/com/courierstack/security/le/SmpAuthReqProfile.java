package com.courierstack.security.le;

import static com.courierstack.security.le.SmpConstants.*;

/**
 * SMP Authentication Requirement profiles for auto-retry pairing.
 */
public enum SmpAuthReqProfile {

    JUST_WORKS_LEGACY(
            "Just Works (Legacy)",
            AUTH_REQ_BONDING,
            IO_CAP_NO_INPUT_NO_OUTPUT,
            false
    ),

    JUST_WORKS_SC(
            "Just Works (SC)",
            AUTH_REQ_BONDING | AUTH_REQ_SC,
            IO_CAP_NO_INPUT_NO_OUTPUT,
            true
    ),

    JUST_WORKS_SC_CT2(
            "Just Works (SC+CT2)",
            AUTH_REQ_BONDING | AUTH_REQ_SC | AUTH_REQ_CT2,
            IO_CAP_NO_INPUT_NO_OUTPUT,
            true
    ),

    NO_IO_WITH_MITM(
            "NoIO+MITM",
            AUTH_REQ_BONDING | AUTH_REQ_MITM,
            IO_CAP_NO_INPUT_NO_OUTPUT,
            false
    ),

    NO_IO_SC_MITM(
            "NoIO+SC+MITM",
            AUTH_REQ_BONDING | AUTH_REQ_MITM | AUTH_REQ_SC,
            IO_CAP_NO_INPUT_NO_OUTPUT,
            true
    ),

    MITM_LEGACY(
            "MITM (Legacy)",
            AUTH_REQ_BONDING | AUTH_REQ_MITM,
            IO_CAP_KEYBOARD_DISPLAY,
            false
    ),

    MITM_SC(
            "MITM (SC)",
            AUTH_REQ_BONDING | AUTH_REQ_MITM | AUTH_REQ_SC,
            IO_CAP_KEYBOARD_DISPLAY,
            true
    ),

    FULL_SECURITY(
            "Full Security",
            AUTH_REQ_BONDING | AUTH_REQ_MITM | AUTH_REQ_SC | AUTH_REQ_KEYPRESS | AUTH_REQ_CT2,
            IO_CAP_KEYBOARD_DISPLAY,
            true
    );

    private final String displayName;
    private final int authReq;
    private final int ioCap;
    private final boolean secureConnections;

    SmpAuthReqProfile(String displayName, int authReq, int ioCap, boolean secureConnections) {
        this.displayName = displayName;
        this.authReq = authReq;
        this.ioCap = ioCap;
        this.secureConnections = secureConnections;
    }

    public String getDisplayName() { return displayName; }
    public int getAuthReq() { return authReq; }
    public int getIoCap() { return ioCap; }
    public boolean usesSecureConnections() { return secureConnections; }

    public String getAuthReqString() {
        return String.format("0x%02X (%s)", authReq, SmpConstants.formatAuthReq(authReq));
    }

    public static SmpAuthReqProfile[] getDefaultRetrySequence() {
        return new SmpAuthReqProfile[] {
                JUST_WORKS_SC, JUST_WORKS_LEGACY, NO_IO_SC_MITM, NO_IO_WITH_MITM,
                JUST_WORKS_SC_CT2, MITM_SC, MITM_LEGACY, FULL_SECURITY
        };
    }

    public static SmpAuthReqProfile[] getIdentityResolutionSequence() {
        return new SmpAuthReqProfile[] {
                JUST_WORKS_SC, JUST_WORKS_LEGACY, NO_IO_SC_MITM, NO_IO_WITH_MITM, JUST_WORKS_SC_CT2
        };
    }

    public static SmpAuthReqProfile[] getScPreferredSequence() {
        return new SmpAuthReqProfile[] {
                JUST_WORKS_SC, NO_IO_SC_MITM, JUST_WORKS_SC_CT2, MITM_SC,
                JUST_WORKS_LEGACY, NO_IO_WITH_MITM, MITM_LEGACY, FULL_SECURITY
        };
    }

    @Override
    public String toString() {
        return String.format("%s [%s]", displayName, getAuthReqString());
    }
}