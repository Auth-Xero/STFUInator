package com.courierstack.pairing;

/**
 * Supported BR/EDR pairing modes per Bluetooth Core Spec v5.3.
 *
 * <p>The pairing mode determines the user interaction required during
 * the pairing process and the level of security provided.
 */
public enum PairingMode {
    /**
     * Just Works - No user interaction required.
     *
     * <p>Provides no MITM protection. Used when neither device has
     * input/output capabilities or when MITM protection is not required.
     */
    JUST_WORKS,

    /**
     * Numeric Comparison - Both devices display the same 6-digit number.
     *
     * <p>User confirms the numbers match on both devices.
     * Provides MITM protection. Requires DisplayYesNo capability on both devices.
     */
    NUMERIC_COMPARISON,

    /**
     * Passkey Entry - One device displays, the other enters a passkey.
     *
     * <p>User enters the passkey displayed on one device into the other.
     * Provides MITM protection. Requires keyboard or display capability.
     */
    PASSKEY_ENTRY,

    /**
     * Legacy PIN - Legacy pairing with PIN code.
     *
     * <p>Used for legacy (non-SSP) devices. User enters matching PIN
     * on both devices. Security depends on PIN complexity.
     */
    LEGACY_PIN;

    /**
     * Returns whether this pairing mode provides MITM protection.
     *
     * @return true if MITM protected
     */
    public boolean isMitmProtected() {
        return this == NUMERIC_COMPARISON || this == PASSKEY_ENTRY;
    }

    /**
     * Returns whether this pairing mode requires user interaction.
     *
     * @return true if user interaction is required
     */
    public boolean requiresUserInteraction() {
        return this != JUST_WORKS;
    }

    /**
     * Returns whether this is a legacy (non-SSP) pairing mode.
     *
     * @return true if legacy mode
     */
    public boolean isLegacy() {
        return this == LEGACY_PIN;
    }
}