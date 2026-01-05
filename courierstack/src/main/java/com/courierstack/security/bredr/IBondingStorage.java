package com.courierstack.security.bredr;

import java.util.Map;

/**
 * Interface for persistent storage of bonding information.
 *
 * <p>Implementations should persist bonding data (link keys) to durable storage
 * so that previously paired devices can reconnect after app restart.
 *
 * <p>Example implementations:
 * <ul>
 *   <li>SharedPreferences (Android)</li>
 *   <li>SQLite database</li>
 *   <li>File-based storage</li>
 * </ul>
 *
 * <p>Thread Safety: Implementations must be thread-safe as methods may be called
 * from multiple threads concurrently.
 */
public interface IBondingStorage {

    /**
     * Stores bonding information for a device.
     *
     * <p>If bonding info already exists for this address, it should be replaced.
     *
     * @param info bonding information to store
     */
    void store(BondingInfo info);

    /**
     * Retrieves bonding information for a device address.
     *
     * @param addressString device address in format "XX:XX:XX:XX:XX:XX"
     * @return bonding info or null if not found
     */
    BondingInfo load(String addressString);

    /**
     * Removes bonding information for a device.
     *
     * @param addressString device address in format "XX:XX:XX:XX:XX:XX"
     * @return true if removed, false if not found
     */
    boolean remove(String addressString);

    /**
     * Loads all stored bonding information.
     *
     * @return map of address string to bonding info
     */
    Map<String, BondingInfo> loadAll();

    /**
     * Clears all stored bonding information.
     */
    void clear();
}