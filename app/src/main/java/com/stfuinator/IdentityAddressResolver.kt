package com.stfuinator

import com.courierstack.gatt.GattCallback
import com.courierstack.gatt.GattManager
import com.courierstack.gatt.GattService
import com.courierstack.gatt.GattCharacteristic
import com.courierstack.l2cap.L2capManager
import com.courierstack.l2cap.AclConnection
import com.courierstack.l2cap.ConnectionType
import com.courierstack.l2cap.IL2capConnectionCallback
import com.courierstack.l2cap.IL2capListener
import com.courierstack.l2cap.L2capChannel
import com.courierstack.security.le.SmpManager
import com.courierstack.security.le.SmpAutoRetryPairing
import com.courierstack.security.le.SmpAuthReqProfile
import com.courierstack.security.le.SmpConstants
import com.courierstack.util.CourierLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Resolves BLE random addresses to identity (public) addresses.
 *
 * Strategy:
 * 1. Connect via LE to the random address
 * 2. Perform GATT service discovery
 * 3. Try to read identity address from GAP service or Device Info service
 * 4. If GATT fails, fall back to SMP pairing to get Identity Address Info
 * 5. Disconnect and return the resolved address
 */
class IdentityAddressResolver(
    private val l2capManager: L2capManager,
    private val gattManager: GattManager,
    private val smpManager: SmpManager,
    private val logger: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "IdentityResolver"

        // Standard GATT Service UUIDs
        val GAP_SERVICE_UUID: UUID = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
        val DEVICE_INFO_SERVICE_UUID: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")

        // GAP Characteristic UUIDs
        val DEVICE_NAME_UUID: UUID = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")
        val APPEARANCE_UUID: UUID = UUID.fromString("00002a01-0000-1000-8000-00805f9b34fb")
        val CENTRAL_ADDRESS_RESOLUTION_UUID: UUID = UUID.fromString("00002a52-0000-1000-8000-00805f9b34fb")
        val RESOLVABLE_PRIVATE_ADDRESS_ONLY_UUID: UUID = UUID.fromString("00002ac9-0000-1000-8000-00805f9b34fb")

        // Device Information Characteristic UUIDs
        val SYSTEM_ID_UUID: UUID = UUID.fromString("00002a23-0000-1000-8000-00805f9b34fb")
        val PNP_ID_UUID: UUID = UUID.fromString("00002a50-0000-1000-8000-00805f9b34fb")

        // Some vendors put BD_ADDR in custom characteristics
        // Harman/JBL often uses this pattern
        val HARMAN_SERVICE_UUID: UUID = UUID.fromString("e49a1800-f69a-11e8-8eb2-f2801f1b9fd1")

        // Connection timeouts
        private const val CONNECT_TIMEOUT_MS = 10000L
        private const val DISCOVERY_TIMEOUT_MS = 15000L
        private const val READ_TIMEOUT_MS = 5000L
        private const val SMP_TIMEOUT_MS = 30000L
    }

    data class ResolutionResult(
        val success: Boolean,
        val identityAddress: String?,
        val addressType: Int = 0,  // 0 = public, 1 = random static
        val method: ResolutionMethod = ResolutionMethod.NONE,
        val errorMessage: String? = null
    )

    enum class ResolutionMethod {
        NONE,
        GATT_GAP_SERVICE,
        GATT_DEVICE_INFO,
        GATT_VENDOR_SPECIFIC,
        GATT_SYSTEM_ID,
        SMP_IDENTITY_INFO,
        ALREADY_PUBLIC
    }

    private fun log(message: String) {
        logger?.invoke("[$TAG] $message")
        CourierLogger.d(TAG, message)
    }

    /**
     * Check if an address is already a public address (no resolution needed).
     * Public addresses have specific OUI patterns and address type 0.
     */
    fun isPublicAddress(addressType: Int): Boolean {
        return addressType == 0
    }

    /**
     * Main entry point: Resolve identity address using GATT first, then SMP fallback.
     */
    suspend fun resolveIdentityAddress(
        leAddress: ByteArray,
        addressType: Int,
        timeoutMs: Long = 30000L
    ): ResolutionResult = withContext(Dispatchers.IO) {

        val addressStr = formatAddress(leAddress)
        log("Starting identity resolution for $addressStr (type=$addressType)")

        // If already a public address, no resolution needed
        if (isPublicAddress(addressType)) {
            log("Address is already public, no resolution needed")
            return@withContext ResolutionResult(
                success = true,
                identityAddress = addressStr,
                addressType = 0,
                method = ResolutionMethod.ALREADY_PUBLIC
            )
        }

        // Connect via LE
        var connectionHandle: Int? = null
        val connectionLatch = CountDownLatch(1)
        var connectionError: String? = null

        val l2capListener = object : IL2capListener {
            override fun onConnectionComplete(connection: AclConnection) {
                if (connection.type == ConnectionType.LE) {
                    connectionHandle = connection.handle
                    log("LE connected, handle=0x${Integer.toHexString(connection.handle)}")
                    connectionLatch.countDown()
                }
            }
            override fun onDisconnectionComplete(handle: Int, reason: Int) {
                log("LE disconnected, reason=0x${Integer.toHexString(reason)}")
            }
            override fun onConnectionRequest(handle: Int, psm: Int, sourceCid: Int) {}
            override fun onChannelOpened(channel: L2capChannel) {}
            override fun onChannelClosed(channel: L2capChannel) {}
            override fun onDataReceived(channel: L2capChannel, data: ByteArray) {}
            override fun onError(message: String) {
                log("L2CAP error: $message")
                connectionError = message
                connectionLatch.countDown()
            }
            override fun onMessage(message: String) {}
        }

        l2capManager.addListener(l2capListener)

        try {
            // Create LE connection
            l2capManager.createLeConnection(leAddress, addressType, IL2capConnectionCallback.create(
                { _ -> },
                { reason ->
                    log("LE connection failed: $reason")
                    connectionError = reason
                    connectionLatch.countDown()
                }
            ))

            val connected = connectionLatch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!connected || connectionHandle == null) {
                return@withContext ResolutionResult(
                    success = false,
                    identityAddress = null,
                    errorMessage = connectionError ?: "Connection timeout"
                )
            }

            val handle = connectionHandle!!

            // =====================================================
            // PHASE 1: Try GATT-based resolution (no pairing needed)
            // =====================================================
            log("Phase 1: Attempting GATT-based identity resolution...")

            val gattResult = tryGattResolution(handle, leAddress, addressType)
            if (gattResult.success && gattResult.identityAddress != null) {
                log("GATT resolution succeeded: ${gattResult.identityAddress} via ${gattResult.method}")
                disconnectAndCleanup(handle, l2capListener)
                return@withContext gattResult
            }

            log("GATT resolution failed: ${gattResult.errorMessage}")

            // =====================================================
            // PHASE 2: Fall back to SMP pairing
            // =====================================================
            log("Phase 2: Falling back to SMP pairing for identity resolution...")

            val smpResult = trySmpResolution(handle, leAddress, addressType, SMP_TIMEOUT_MS)

            disconnectAndCleanup(handle, l2capListener)

            return@withContext smpResult

        } finally {
            l2capManager.removeListener(l2capListener)
        }
    }

    /**
     * Try to resolve identity address via GATT service discovery.
     */
    private suspend fun tryGattResolution(
        handle: Int,
        leAddress: ByteArray,
        addressType: Int
    ): ResolutionResult {
        try {
            // Discover services
            log("Discovering GATT services...")
            val services = discoverServices(handle, DISCOVERY_TIMEOUT_MS)

            if (services.isEmpty()) {
                return ResolutionResult(
                    success = false,
                    identityAddress = null,
                    errorMessage = "No GATT services found"
                )
            }

            log("Found ${services.size} services")
            services.forEach { service ->
                log("  - ${service.uuid} (${service.characteristics.size} characteristics)")
            }

            // Strategy 1: Check GAP Service for address-related characteristics
            val gapService = services.find { it.uuid == GAP_SERVICE_UUID }
            if (gapService != null) {
                log("Found GAP Service, checking characteristics...")

                // Check Central Address Resolution - indicates if device supports resolution
                val carChar = gapService.characteristics.find { it.uuid == CENTRAL_ADDRESS_RESOLUTION_UUID }
                if (carChar != null) {
                    val carValue = readCharacteristic(handle, carChar)
                    if (carValue != null && carValue.isNotEmpty()) {
                        val supportsResolution = carValue[0].toInt() == 1
                        log("Central Address Resolution: $supportsResolution")
                    }
                }
            }

            // Strategy 2: Check Device Information Service for System ID
            // System ID often contains the BD_ADDR in a predictable format
            val deviceInfoService = services.find { it.uuid == DEVICE_INFO_SERVICE_UUID }
            if (deviceInfoService != null) {
                log("Found Device Information Service, checking System ID...")

                val systemIdChar = deviceInfoService.characteristics.find { it.uuid == SYSTEM_ID_UUID }
                if (systemIdChar != null) {
                    val systemId = readCharacteristic(handle, systemIdChar)
                    if (systemId != null && systemId.size >= 6) {
                        // System ID format: 5 bytes OUI + 3 bytes unique ID (or reversed)
                        // Often the BD_ADDR can be extracted from this
                        val extractedAddr = extractAddressFromSystemId(systemId)
                        if (extractedAddr != null) {
                            log("Extracted address from System ID: $extractedAddr")
                            return ResolutionResult(
                                success = true,
                                identityAddress = extractedAddr,
                                addressType = 0,
                                method = ResolutionMethod.GATT_SYSTEM_ID
                            )
                        }
                    }
                }
            }

            // Strategy 3: Check vendor-specific services (Harman/JBL)
            val harmanService = services.find { it.uuid == HARMAN_SERVICE_UUID }
            if (harmanService != null) {
                log("Found Harman vendor service, checking for BD_ADDR...")

                // Harman devices often expose the BD_ADDR in a custom characteristic
                for (char in harmanService.characteristics) {
                    val value = readCharacteristic(handle, char)
                    if (value != null && value.size == 6) {
                        // Could be a BD_ADDR
                        val potentialAddr = formatAddress(value)
                        log("Found potential address in Harman service: $potentialAddr")
                        return ResolutionResult(
                            success = true,
                            identityAddress = potentialAddr,
                            addressType = 0,
                            method = ResolutionMethod.GATT_VENDOR_SPECIFIC
                        )
                    }
                }
            }

            // Strategy 4: Check any unknown services for 6-byte values that look like addresses
            for (service in services) {
                if (service.uuid == GAP_SERVICE_UUID || service.uuid == DEVICE_INFO_SERVICE_UUID) {
                    continue  // Already checked
                }

                for (char in service.characteristics) {
                    try {
                        val value = readCharacteristic(handle, char)
                        if (value != null && value.size == 6) {
                            val potentialAddr = formatAddress(value)
                            // Validate it looks like a real address (not all zeros/ones)
                            if (isValidAddress(value)) {
                                log("Found potential address in ${service.uuid}: $potentialAddr")
                                // Don't return immediately - could be coincidence
                                // But log it for manual inspection
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore read errors for scanning
                    }
                }
            }

            return ResolutionResult(
                success = false,
                identityAddress = null,
                errorMessage = "No identity address found in GATT services"
            )

        } catch (e: Exception) {
            log("GATT resolution error: ${e.message}")
            return ResolutionResult(
                success = false,
                identityAddress = null,
                errorMessage = e.message
            )
        }
    }

    /**
     * Try to resolve identity address via SMP pairing.
     */
    private suspend fun trySmpResolution(
        handle: Int,
        leAddress: ByteArray,
        addressType: Int,
        timeoutMs: Long
    ): ResolutionResult {
        try {
            val autoRetry = SmpAutoRetryPairing(smpManager)
            autoRetry.useIdentityResolutionSequence()

            autoRetry.setListener(object : SmpAutoRetryPairing.SimpleAutoRetryListener() {
                override fun onAttemptStarted(attemptNumber: Int, profile: SmpAuthReqProfile) {
                    log("SMP attempt $attemptNumber: ${profile.displayName}")
                }
                override fun onRetrying(attemptNumber: Int, failedProfile: SmpAuthReqProfile,
                                        errorCode: Int, errorMessage: String?) {
                    log("SMP retry after ${failedProfile.displayName}: ${SmpConstants.getErrorString(errorCode)}")
                }
            })

            log("Starting SMP pairing...")
            val result = autoRetry.pairWithAutoRetry(handle, leAddress, addressType, timeoutMs)

            if (result.isSuccess) {
                log("SMP pairing succeeded")

                // Give device time to send Identity Address Info
                delay(500)

                val identityAddr = smpManager.getIdentityAddress(handle)
                val identityType = smpManager.getIdentityAddressType(handle)

                if (identityAddr != null) {
                    val addrStr = formatAddress(identityAddr)
                    log("Got identity address via SMP: $addrStr (type=$identityType)")
                    return ResolutionResult(
                        success = true,
                        identityAddress = addrStr,
                        addressType = identityType,
                        method = ResolutionMethod.SMP_IDENTITY_INFO
                    )
                } else {
                    log("SMP pairing succeeded but no identity address received")
                    return ResolutionResult(
                        success = false,
                        identityAddress = null,
                        errorMessage = "Pairing succeeded but device didn't provide identity address"
                    )
                }
            } else {
                log("SMP pairing failed: ${result.lastErrorMessage}")
                return ResolutionResult(
                    success = false,
                    identityAddress = null,
                    errorMessage = result.lastErrorMessage ?: "SMP pairing failed"
                )
            }

        } catch (e: Exception) {
            log("SMP resolution error: ${e.message}")
            return ResolutionResult(
                success = false,
                identityAddress = null,
                errorMessage = e.message
            )
        }
    }

    /**
     * Discover GATT services with timeout.
     */
    private suspend fun discoverServices(handle: Int, timeoutMs: Long): List<GattService> =
        suspendCancellableCoroutine { continuation ->
             var resumed = false
            val lock = Object()

            val callback = GattCallback.Discovery.create(
                { services ->
                    synchronized(lock) {
                        if (!resumed) {
                            resumed = true
                            continuation.resume(services ?: emptyList())
                        }
                    }
                },
                { errorCode, message ->
                    synchronized(lock) {
                        if (!resumed) {
                            resumed = true
                            log("Discovery error: $errorCode - $message")
                            continuation.resume(emptyList())
                        }
                    }
                }
            )

            gattManager.discoverServices(handle, callback)

            // Timeout handling using a thread
            Thread {
                try {
                    Thread.sleep(timeoutMs)
                    synchronized(lock) {
                        if (!resumed) {
                            resumed = true
                            continuation.resume(emptyList())
                        }
                    }
                } catch (e: InterruptedException) {
                    // Cancelled
                }
            }.start()
        }

    /**
     * Read a GATT characteristic with timeout.
     */
    private suspend fun readCharacteristic(handle: Int, characteristic: GattCharacteristic): ByteArray? =
        suspendCancellableCoroutine { continuation ->
             var resumed = false
            val lock = Object()

            val callback = GattCallback.Operation.create(
                { data ->
                    synchronized(lock) {
                        if (!resumed) {
                            resumed = true
                            continuation.resume(data)
                        }
                    }
                },
                { errorCode, message ->
                    synchronized(lock) {
                        if (!resumed) {
                            resumed = true
                            log("Read error for ${characteristic.uuid}: $errorCode - $message")
                            continuation.resume(null)
                        }
                    }
                }
            )

            gattManager.readCharacteristic(handle, characteristic, callback)

            // Timeout handling using a thread
            Thread {
                try {
                    Thread.sleep(READ_TIMEOUT_MS)
                    synchronized(lock) {
                        if (!resumed) {
                            resumed = true
                            continuation.resume(null)
                        }
                    }
                } catch (e: InterruptedException) {
                    // Cancelled
                }
            }.start()
        }

    /**
     * Extract BD_ADDR from System ID characteristic.
     *
     * System ID format (IEEE 11073-20601):
     * - Bytes 0-4: OUI (manufacturer identifier)
     * - Bytes 5-7: Unique identifier
     *
     * Often the BD_ADDR is embedded as: OUI[0:3] + Unique[0:3]
     */
    private fun extractAddressFromSystemId(systemId: ByteArray): String? {
        if (systemId.size < 8) return null

        // Common pattern: BD_ADDR = systemId[0:6] (little-endian)
        // Or: BD_ADDR = systemId[5:7] + systemId[0:3] (with FFFE in middle removed)

        // Try direct extraction (first 6 bytes)
        val addr1 = ByteArray(6)
        System.arraycopy(systemId, 0, addr1, 0, 6)
        if (isValidAddress(addr1)) {
            return formatAddress(addr1)
        }

        // Try IEEE pattern (remove FFFE)
        if (systemId.size >= 8 && systemId[3] == 0xFE.toByte() && systemId[4] == 0xFF.toByte()) {
            val addr2 = ByteArray(6)
            addr2[0] = systemId[0]
            addr2[1] = systemId[1]
            addr2[2] = systemId[2]
            addr2[3] = systemId[5]
            addr2[4] = systemId[6]
            addr2[5] = systemId[7]
            if (isValidAddress(addr2)) {
                return formatAddress(addr2)
            }
        }

        return null
    }

    /**
     * Check if a byte array looks like a valid BD_ADDR.
     */
    private fun isValidAddress(addr: ByteArray): Boolean {
        if (addr.size != 6) return false

        // Not all zeros
        if (addr.all { it == 0.toByte() }) return false

        // Not all ones
        if (addr.all { it == 0xFF.toByte() }) return false

        return true
    }

    /**
     * Disconnect and cleanup.
     */
    private suspend fun disconnectAndCleanup(handle: Int, listener: IL2capListener) {
        try {
            val disconnectLatch = CountDownLatch(1)
            val disconnectListener = object : IL2capListener {
                override fun onDisconnectionComplete(h: Int, reason: Int) {
                    if (h == handle) disconnectLatch.countDown()
                }
                override fun onConnectionComplete(connection: AclConnection) {}
                override fun onChannelOpened(channel: L2capChannel) {}
                override fun onChannelClosed(channel: L2capChannel) {}
                override fun onDataReceived(channel: L2capChannel, data: ByteArray) {}
                override fun onConnectionRequest(handle: Int, psm: Int, sourceCid: Int) {}
                override fun onError(message: String) {}
                override fun onMessage(message: String) {}
            }

            l2capManager.addListener(disconnectListener)
            l2capManager.disconnect(handle, 0x13)  // Remote user terminated

            disconnectLatch.await(3, TimeUnit.SECONDS)
            l2capManager.removeListener(disconnectListener)

        } catch (e: Exception) {
            log("Disconnect error: ${e.message}")
        }

        l2capManager.removeListener(listener)
    }

    /**
     * Format byte array as MAC address string.
     */
    private fun formatAddress(addr: ByteArray): String {
        return addr.joinToString(":") { String.format("%02X", it) }
    }

    /**
     * Parse MAC address string to byte array.
     */
    fun parseAddress(addr: String): ByteArray {
        return addr.split(":").map { it.toInt(16).toByte() }.toByteArray()
    }
}