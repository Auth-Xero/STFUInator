package com.courierstack.smp;

/**
 * SMP pairing methods per Bluetooth Core Spec v5.3, Vol 3, Part H, Section 2.3.5.
 */
public enum SmpPairingMethod {
    /** No MITM protection; TK = 0. */
    JUST_WORKS,
    /** User enters passkey on one or both devices. */
    PASSKEY_ENTRY,
    /** Initiator displays passkey, responder inputs. */
    PASSKEY_ENTRY_INITIATOR_DISPLAYS,
    /** Responder displays passkey, initiator inputs. */
    PASSKEY_ENTRY_RESPONDER_DISPLAYS,
    /** Both devices input same passkey. */
    PASSKEY_ENTRY_BOTH_INPUT,
    /** SC only: both devices display and confirm 6-digit value. */
    NUMERIC_COMPARISON,
    /** Legacy pairing with OOB data. */
    OOB_LEGACY,
    /** SC pairing with OOB data. */
    OOB_SC;

    /**
     * Returns whether this method provides MITM protection.
     *
     * @return true if MITM protected
     */
    public boolean isMitmProtected() {
        return this != JUST_WORKS;
    }

    /**
     * Returns whether this is a passkey-based method.
     *
     * @return true if passkey entry required
     */
    public boolean isPasskeyMethod() {
        switch (this) {
            case PASSKEY_ENTRY:
            case PASSKEY_ENTRY_INITIATOR_DISPLAYS:
            case PASSKEY_ENTRY_RESPONDER_DISPLAYS:
            case PASSKEY_ENTRY_BOTH_INPUT:
                return true;
            default:
                return false;
        }
    }

    /**
     * Returns whether this method requires user interaction.
     *
     * @return true if user interaction needed
     */
    public boolean requiresUserInteraction() {
        return this != JUST_WORKS;
    }

    /**
     * Returns whether this is an OOB method.
     *
     * @return true if OOB-based
     */
    public boolean isOobMethod() {
        return this == OOB_LEGACY || this == OOB_SC;
    }

    /**
     * Returns whether this is a Secure Connections-only method.
     *
     * @return true if SC-only
     */
    public boolean isSecureConnectionsOnly() {
        return this == NUMERIC_COMPARISON || this == OOB_SC;
    }
}