package com.courierstack.security.bredr;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.util.HashMap;
import java.util.Map;

/**
 * SharedPreferences-based implementation of IBondingStorage.
 *
 * <p>Stores bonding information in Android SharedPreferences for persistence
 * across app restarts. Link keys are stored as Base64-encoded strings.
 *
 * <p>Thread Safety: SharedPreferences is thread-safe. This implementation
 * uses apply() for async writes and commit() is available if sync is needed.
 */
public class SharedPrefsBondingStorage implements IBondingStorage {

    private static final String PREFS_NAME = "courier_bonding";
    private static final String KEY_PREFIX = "bond_";
    private static final String FIELD_LINK_KEY = "_linkKey";
    private static final String FIELD_KEY_TYPE = "_keyType";
    private static final String FIELD_AUTHENTICATED = "_authenticated";
    private static final String FIELD_TIMESTAMP = "_timestamp";

    private final SharedPreferences mPrefs;

    /**
     * Creates a new SharedPreferences-based bonding storage.
     *
     * @param context Android context
     */
    public SharedPrefsBondingStorage(Context context) {
        mPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Override
    public void store(BondingInfo info) {
        if (info == null) return;

        String prefix = KEY_PREFIX + sanitizeAddress(info.getAddressString());

        mPrefs.edit()
                .putString(prefix + FIELD_LINK_KEY, Base64.encodeToString(info.getLinkKey(), Base64.NO_WRAP))
                .putInt(prefix + FIELD_KEY_TYPE, info.getLinkKeyType())
                .putBoolean(prefix + FIELD_AUTHENTICATED, info.isAuthenticated())
                .putLong(prefix + FIELD_TIMESTAMP, info.getTimestamp())
                .apply();
    }

    @Override
    public BondingInfo load(String addressString) {
        if (addressString == null) return null;

        String prefix = KEY_PREFIX + sanitizeAddress(addressString);
        String linkKeyBase64 = mPrefs.getString(prefix + FIELD_LINK_KEY, null);

        if (linkKeyBase64 == null) {
            return null;
        }

        try {
            byte[] linkKey = Base64.decode(linkKeyBase64, Base64.NO_WRAP);
            int keyType = mPrefs.getInt(prefix + FIELD_KEY_TYPE, 0);
            boolean authenticated = mPrefs.getBoolean(prefix + FIELD_AUTHENTICATED, false);
            long timestamp = mPrefs.getLong(prefix + FIELD_TIMESTAMP, System.currentTimeMillis());

            // Parse address back to bytes
            byte[] address = parseAddress(addressString);

            return BondingInfo.builder()
                    .address(address)
                    .linkKey(linkKey)
                    .linkKeyType(keyType)
                    .authenticated(authenticated)
                    .timestamp(timestamp)
                    .build();
        } catch (Exception e) {
            // Corrupted data - remove it
            remove(addressString);
            return null;
        }
    }

    @Override
    public boolean remove(String addressString) {
        if (addressString == null) return false;

        String prefix = KEY_PREFIX + sanitizeAddress(addressString);
        boolean exists = mPrefs.contains(prefix + FIELD_LINK_KEY);

        if (exists) {
            mPrefs.edit()
                    .remove(prefix + FIELD_LINK_KEY)
                    .remove(prefix + FIELD_KEY_TYPE)
                    .remove(prefix + FIELD_AUTHENTICATED)
                    .remove(prefix + FIELD_TIMESTAMP)
                    .apply();
        }

        return exists;
    }

    @Override
    public Map<String, BondingInfo> loadAll() {
        Map<String, BondingInfo> result = new HashMap<>();
        Map<String, ?> all = mPrefs.getAll();

        // Find all unique addresses
        for (String key : all.keySet()) {
            if (key.startsWith(KEY_PREFIX) && key.endsWith(FIELD_LINK_KEY)) {
                // Extract address from key: bond_XX_XX_XX_XX_XX_XX_linkKey
                String sanitized = key.substring(KEY_PREFIX.length(), key.length() - FIELD_LINK_KEY.length());
                String addressString = unsanitizeAddress(sanitized);

                BondingInfo info = load(addressString);
                if (info != null) {
                    result.put(addressString, info);
                }
            }
        }

        return result;
    }

    @Override
    public void clear() {
        SharedPreferences.Editor editor = mPrefs.edit();

        for (String key : mPrefs.getAll().keySet()) {
            if (key.startsWith(KEY_PREFIX)) {
                editor.remove(key);
            }
        }

        editor.apply();
    }

    /**
     * Sanitizes address for use as SharedPreferences key.
     * Replaces colons with underscores.
     */
    private String sanitizeAddress(String address) {
        return address.replace(":", "_");
    }

    /**
     * Reverses sanitization to get original address format.
     */
    private String unsanitizeAddress(String sanitized) {
        return sanitized.replace("_", ":");
    }

    /**
     * Parses address string to byte array.
     */
    private byte[] parseAddress(String addressString) {
        String[] parts = addressString.split(":");
        if (parts.length != 6) {
            throw new IllegalArgumentException("Invalid address: " + addressString);
        }

        byte[] address = new byte[6];
        // Address is stored big-endian in string, but Bluetooth uses little-endian
        for (int i = 0; i < 6; i++) {
            address[5 - i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return address;
    }
}