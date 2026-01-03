package com.courierstack.security.le;

import com.courierstack.util.CourierLogger;
import com.courierstack.hci.HciCommandManager;
import com.courierstack.hci.HciCommands;
import com.courierstack.hci.IHciCommandListener;
import com.courierstack.l2cap.L2capManager;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.courierstack.security.le.SmpConstants.*;

/**
 * Security Manager Protocol implementation for BLE.
 *
 * <p>Implements the SMP protocol per Bluetooth Core Spec v5.3, Vol 3, Part H.
 * Supports both Legacy Pairing and LE Secure Connections, with automatic
 * fallback from SC to Legacy when needed.
 *
 * <p>Key features:
 * <ul>
 *   <li>Legacy Pairing with Just Works, Passkey Entry, and OOB</li>
 *   <li>LE Secure Connections with Numeric Comparison</li>
 *   <li>Automatic SC to Legacy fallback</li>
 *   <li>Bonding database for reconnection</li>
 *   <li>Full key distribution support</li>
 * </ul>
 *
 * <p>Thread Safety: This class is thread-safe. All public methods can be called
 * from any thread. Callbacks are dispatched on the executor thread.
 *
 * @see ISmpListener
 * @see BondingInfo
 */
public class SmpManager implements IHciCommandListener, L2capManager.IFixedChannelListener, Closeable {

    private static final String TAG = "SmpManager";

    // ==================== Configuration ====================

    /** Default timeout for synchronous pairing (ms). */
    private static final long DEFAULT_PAIRING_TIMEOUT_MS = 30000;

    /** Delay between key distribution PDUs (ms). */
    private static final int KEY_DIST_DELAY_MS = 50;

    /** Timeout for public key/DHKey generation (ms). */
    private static final int ECDH_TIMEOUT_MS = 5000;

    /** Executor shutdown timeout (seconds). */
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;

    // ==================== Dependencies ====================

    private final L2capManager mL2capManager;
    private volatile HciCommandManager mHciManager;
    private final ISmpListener mListener;
    private final ExecutorService mExecutor;
    private final SecureRandom mSecureRandom;

    // ==================== State ====================

    private final AtomicBoolean mInitialized = new AtomicBoolean(false);
    private final AtomicBoolean mClosed = new AtomicBoolean(false);

    /** Active pairing sessions by connection handle. */
    private final Map<Integer, SmpSession> mSessions = new ConcurrentHashMap<>();

    /** Bonding database by address string. */
    private final Map<String, BondingInfo> mBondingDatabase = new ConcurrentHashMap<>();

    /** Cache of identity addresses by connection handle (survives session cleanup). */
    private final Map<Integer, byte[]> mIdentityAddressCache = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> mIdentityAddressTypeCache = new ConcurrentHashMap<>();

    /** Derived BR/EDR link keys from CTKD by address string. */
    private final Map<String, byte[]> mDerivedLinkKeys = new ConcurrentHashMap<>();

    // ==================== Local Configuration ====================

    private volatile byte[] mLocalAddress = new byte[6];
    private volatile int mLocalAddressType = 0;
    private final byte[] mLocalIrk = new byte[16];
    private final byte[] mLocalCsrk = new byte[16];

    /** Default IO capability. */
    private volatile int mDefaultIoCap = IO_CAP_NO_INPUT_NO_OUTPUT;

    /** Default authentication requirements (bonding + CT2 for CTKD support). */
    private volatile int mDefaultAuthReq = AUTH_REQ_BONDING | AUTH_REQ_CT2;

    /** Default max encryption key size. */
    private volatile int mDefaultMaxKeySize = MAX_ENC_KEY_SIZE;

    /** Whether to prefer Secure Connections. */
    private volatile boolean mPreferSecureConnections = false;

    // ==================== ECDH State ====================

    private volatile byte[] mLocalP256PublicKeyX = null;
    private volatile byte[] mLocalP256PublicKeyY = null;
    private volatile CountDownLatch mPublicKeyLatch = null;
    private volatile CountDownLatch mDhKeyLatch = null;
    private volatile byte[] mGeneratedDhKey = null;

    // ==================== Constructor ====================

    /**
     * Creates a new SMP manager.
     *
     * @param l2capManager L2CAP manager for sending SMP PDUs
     * @param listener listener for pairing events
     * @throws NullPointerException if l2capManager or listener is null
     */
    public SmpManager(L2capManager l2capManager, ISmpListener listener) {
        mL2capManager = Objects.requireNonNull(l2capManager, "l2capManager must not be null");
        mListener = Objects.requireNonNull(listener, "listener must not be null");
        mExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "SmpManager-Worker");
            t.setDaemon(true);
            return t;
        });
        mSecureRandom = new SecureRandom();

        // Generate local IRK and CSRK
        mSecureRandom.nextBytes(mLocalIrk);
        mSecureRandom.nextBytes(mLocalCsrk);

        CourierLogger.i(TAG, "SmpManager created");
    }

    /**
     * Initializes the SMP manager.
     *
     * <p>Must be called before any pairing operations. Registers with
     * the HCI manager and L2CAP manager for events.
     *
     * @return true if initialization succeeded
     */
    public boolean initialize() {
        if (mClosed.get()) {
            CourierLogger.e(TAG, "Cannot initialize - already closed");
            return false;
        }

        if (mInitialized.getAndSet(true)) {
            CourierLogger.w(TAG, "Already initialized");
            return true;
        }

        mHciManager = mL2capManager.getHciManager();
        if (mHciManager == null) {
            CourierLogger.e(TAG, "Failed to get HciCommandManager");
            mListener.onError("HCI manager not available");
            mInitialized.set(false);
            return false;
        }

        mHciManager.addListener(this);
        mL2capManager.registerFixedChannelListener(SMP_CID, this);

        CourierLogger.i(TAG, "SmpManager initialized");
        return true;
    }

    private void checkInitialized() {
        if (!mInitialized.get()) {
            throw new IllegalStateException("SmpManager not initialized");
        }
        if (mClosed.get()) {
            throw new IllegalStateException("SmpManager is closed");
        }
    }

    // ==================== Configuration ====================

    /**
     * Sets the local Bluetooth address.
     *
     * @param address 6-byte address
     * @param addressType address type (0=public, 1=random)
     */
    public void setLocalAddress(byte[] address, int addressType) {
        Objects.requireNonNull(address, "address must not be null");
        if (address.length != 6) {
            throw new IllegalArgumentException("address must be 6 bytes");
        }
        mLocalAddress = Arrays.copyOf(address, 6);
        mLocalAddressType = addressType;
        CourierLogger.d(TAG, "Local address set: " + formatAddress(address) + " type=" + addressType);
    }

    /**
     * Sets the default IO capability.
     *
     * @param ioCap IO capability value
     */
    public void setDefaultIoCapability(int ioCap) {
        mDefaultIoCap = ioCap;
    }

    /**
     * Sets the default authentication requirements.
     *
     * @param authReq authentication requirements flags
     */
    public void setDefaultAuthReq(int authReq) {
        mDefaultAuthReq = authReq;
    }

    /**
     * Sets whether Secure Connections is preferred.
     *
     * @param enabled true to prefer SC
     */
    public void setSecureConnectionsEnabled(boolean enabled) {
        mPreferSecureConnections = enabled;
        if (enabled) {
            mDefaultAuthReq |= AUTH_REQ_SC;
        } else {
            mDefaultAuthReq &= ~AUTH_REQ_SC;
        }
        CourierLogger.i(TAG, "Secure Connections " + (enabled ? "enabled" : "disabled") +
                ", AuthReq=0x" + Integer.toHexString(mDefaultAuthReq));
    }

    // ==================== Bonding Database ====================

    /**
     * Stores bonding information.
     *
     * @param info bonding info to store
     */
    public void storeBondingInfo(BondingInfo info) {
        Objects.requireNonNull(info, "info must not be null");
        mBondingDatabase.put(info.getAddressString(), info);
        CourierLogger.i(TAG, "Stored bonding info for " + info.getAddressString());
    }

    /**
     * Gets bonding information for an address.
     *
     * @param address peer address
     * @return bonding info or null
     */
    public BondingInfo getBondingInfo(byte[] address) {
        return mBondingDatabase.get(formatAddress(address));
    }

    /**
     * Checks if a device is bonded.
     *
     * @param address peer address
     * @return true if bonded with LTK
     */
    public boolean isBonded(byte[] address) {
        BondingInfo info = getBondingInfo(address);
        return info != null && info.hasLtk();
    }

    /**
     * Gets all bonding information.
     *
     * @return unmodifiable map of bonding info
     */
    public Map<String, BondingInfo> getAllBondingInfo() {
        return Collections.unmodifiableMap(mBondingDatabase);
    }

    /**
     * Removes bonding information.
     *
     * @param address peer address
     * @return removed bonding info or null
     */
    public BondingInfo removeBondingInfo(byte[] address) {
        return mBondingDatabase.remove(formatAddress(address));
    }

    /**
     * Gets a derived BR/EDR link key for an address.
     *
     * <p>Link keys are derived from LE LTK using Cross-Transport Key Derivation (CTKD)
     * when both devices support CT2.
     *
     * @param address peer address (LE or identity address)
     * @return derived link key (16 bytes) or null if not available
     */
    public byte[] getDerivedLinkKey(byte[] address) {
        return mDerivedLinkKeys.get(formatAddress(address));
    }

    /**
     * Gets a derived BR/EDR link key for an address string.
     *
     * @param addressString formatted address (XX:XX:XX:XX:XX:XX)
     * @return derived link key (16 bytes) or null if not available
     */
    public byte[] getDerivedLinkKey(String addressString) {
        return mDerivedLinkKeys.get(addressString);
    }

    /**
     * Checks if a derived BR/EDR link key is available for an address.
     *
     * @param address peer address
     * @return true if a derived link key exists
     */
    public boolean hasDerivedLinkKey(byte[] address) {
        return mDerivedLinkKeys.containsKey(formatAddress(address));
    }

    /**
     * Stores a derived link key manually.
     *
     * @param address peer address
     * @param linkKey 16-byte link key
     */
    public void storeDerivedLinkKey(byte[] address, byte[] linkKey) {
        if (address != null && linkKey != null && linkKey.length == 16) {
            mDerivedLinkKeys.put(formatAddress(address), linkKey);
        }
    }

    /**
     * Gets the identity address for a connection if available.
     *
     * @param connectionHandle connection handle
     * @return identity address or null
     */
    public byte[] getIdentityAddress(int connectionHandle) {
        // First check active session
        SmpSession session = mSessions.get(connectionHandle);
        if (session != null) {
            byte[] addr = session.getPeerIdentityAddressIfPresent();
            if (addr != null) return addr;
        }
        // Fall back to cache (survives session cleanup)
        return mIdentityAddressCache.get(connectionHandle);
    }

    /**
     * Gets the identity address type for a connection.
     *
     * @param connectionHandle connection handle
     * @return identity address type or -1 if not available
     */
    public int getIdentityAddressType(int connectionHandle) {
        // First check active session
        SmpSession session = mSessions.get(connectionHandle);
        if (session != null) {
            byte[] addr = session.getPeerIdentityAddressIfPresent();
            if (addr != null) return session.peerIdentityAddressType;
        }
        // Fall back to cache (survives session cleanup)
        Integer type = mIdentityAddressTypeCache.get(connectionHandle);
        return type != null ? type : -1;
    }

    /**
     * Clears the identity address cache for a connection.
     * Should be called when connection is fully closed.
     *
     * @param connectionHandle connection handle
     */
    public void clearIdentityAddressCache(int connectionHandle) {
        mIdentityAddressCache.remove(connectionHandle);
        mIdentityAddressTypeCache.remove(connectionHandle);
    }

    // ==================== Pairing Operations ====================

    /**
     * Initiates pairing with a peer device (asynchronous).
     *
     * @param connectionHandle ACL connection handle
     * @param peerAddress peer Bluetooth address
     * @param peerAddressType peer address type
     * @param callback callback for pairing result (may be null)
     */
    public void initiatePairing(int connectionHandle, byte[] peerAddress, int peerAddressType,
                                ISmpCallback callback) {
        checkInitialized();
        Objects.requireNonNull(peerAddress, "peerAddress must not be null");

        String addrStr = formatAddress(peerAddress);
        mListener.onMessage("Initiating SMP pairing with " + addrStr);

        SmpSession session = new SmpSession(connectionHandle, peerAddress, peerAddressType, SmpRole.INITIATOR);
        session.localIoCap = mDefaultIoCap;
        session.localAuthReq = mDefaultAuthReq;
        session.localMaxKeySize = mDefaultMaxKeySize;
        session.callback = callback;
        mSessions.put(connectionHandle, session);

        mListener.onPairingStarted(connectionHandle, peerAddress);

        // Send pairing request asynchronously
        mExecutor.execute(() -> sendPairingRequest(session));
    }

    /**
     * Initiates pairing synchronously.
     *
     * @param connectionHandle ACL connection handle
     * @param peerAddress peer Bluetooth address
     * @param peerAddressType peer address type
     * @param timeoutMs timeout in milliseconds
     * @return true if pairing succeeded
     */
    public boolean initiatePairingSync(int connectionHandle, byte[] peerAddress, int peerAddressType,
                                       long timeoutMs) {
        checkInitialized();
        Objects.requireNonNull(peerAddress, "peerAddress must not be null");

        String addrStr = formatAddress(peerAddress);
        mListener.onMessage("Initiating SMP pairing (sync) with " + addrStr);

        SmpSession session = new SmpSession(connectionHandle, peerAddress, peerAddressType, SmpRole.INITIATOR);
        session.localIoCap = mDefaultIoCap;
        session.localAuthReq = mDefaultAuthReq;
        session.localMaxKeySize = mDefaultMaxKeySize;
        mSessions.put(connectionHandle, session);

        mListener.onPairingStarted(connectionHandle, peerAddress);
        sendPairingRequest(session);

        try {
            if (session.completionLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return session.success;
            } else {
                mListener.onMessage("SMP pairing timeout");
                failPairing(session, ERR_UNSPECIFIED_REASON, "Timeout");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Responds to a Security Request by initiating pairing or starting encryption.
     *
     * @param connectionHandle connection handle
     * @param peerAddress peer address
     * @param peerAddressType peer address type
     */
    public void respondToSecurityRequest(int connectionHandle, byte[] peerAddress, int peerAddressType) {
        checkInitialized();

        BondingInfo bondingInfo = getBondingInfo(peerAddress);
        if (bondingInfo != null && bondingInfo.hasLtk()) {
            mListener.onMessage("Have bonding info, starting encryption");
            startEncryption(connectionHandle, bondingInfo);
        } else {
            initiatePairing(connectionHandle, peerAddress, peerAddressType, null);
        }
    }

    /**
     * Confirms numeric comparison (SC only).
     *
     * @param connectionHandle connection handle
     * @param accept true if user confirmed values match
     */
    public void confirmNumericComparison(int connectionHandle, boolean accept) {
        SmpSession session = mSessions.get(connectionHandle);
        if (session == null) return;

        if (accept) {
            mListener.onMessage("User confirmed numeric comparison");
            if (session.isInitiator() && session.useSecureConnections) {
                sendDhKeyCheck(session);
            }
        } else {
            failPairing(session, ERR_NUMERIC_COMPARISON_FAILED, "User rejected");
        }
    }

    /**
     * Enters a passkey for pairing.
     *
     * @param connectionHandle connection handle
     * @param passkey 6-digit passkey (0-999999)
     */
    public void enterPasskey(int connectionHandle, int passkey) {
        SmpSession session = mSessions.get(connectionHandle);
        if (session == null) return;

        session.passkey = passkey;
        mListener.onMessage("Passkey entered: " + String.format("%06d", passkey));

        if (!session.useSecureConnections) {
            // Legacy: set TK from passkey
            byte[] tk = SmpCrypto.passkeyToTk(passkey);
            System.arraycopy(tk, 0, session.tk, 0, 16);
        }

        if (session.isInitiator()) {
            if (session.useSecureConnections) {
                session.passkeyBitIndex = 0;
                sendScPasskeyConfirm(session);
            } else {
                generateConfirmValue(session);
                sendPairingConfirm(session);
            }
        } else {
            if (!session.useSecureConnections) {
                generateConfirmValue(session);
            }
        }
    }

    /**
     * Gets the session for a connection handle.
     *
     * @param connectionHandle connection handle
     * @return session or null
     */
    public SmpSession getSession(int connectionHandle) {
        return mSessions.get(connectionHandle);
    }

    /**
     * Called when an ACL connection is lost during pairing.
     *
     * @param connectionHandle connection handle that was disconnected
     * @param reason HCI disconnect reason code
     */
    public void onConnectionLost(int connectionHandle, int reason) {
        SmpSession session = mSessions.get(connectionHandle);
        if (session != null && !session.getState().isTerminal()) {
            String reasonStr = String.format("Connection lost (reason=0x%02X)", reason);
            CourierLogger.w(TAG, "SMP session interrupted: " + reasonStr);

            session.setState(SmpState.FAILED);
            session.success = false;
            session.errorCode = ERR_UNSPECIFIED_REASON;
            session.errorMessage = reasonStr;

            mListener.onPairingFailed(session.connectionHandle, session.getPeerAddress(),
                    ERR_UNSPECIFIED_REASON, reasonStr);

            if (session.callback != null) {
                session.callback.onPairingComplete(session.connectionHandle, false, null);
            }

            session.completionLatch.countDown();
            mSessions.remove(connectionHandle);
        }
    }

    // ==================== SMP PDU Sending ====================

    private void sendPairingRequest(SmpSession session) {
        mListener.onMessage("Sending Pairing Request (AuthReq=0x" +
                Integer.toHexString(session.localAuthReq) + ")");
        session.setState(SmpState.WAIT_PAIRING_RSP);

        byte[] pdu = new byte[7];
        pdu[0] = (byte) PAIRING_REQUEST;
        pdu[1] = (byte) session.localIoCap;
        pdu[2] = (byte) session.localOobFlag;
        pdu[3] = (byte) session.localAuthReq;
        pdu[4] = (byte) session.localMaxKeySize;
        pdu[5] = (byte) session.localInitKeyDist;
        pdu[6] = (byte) session.localRespKeyDist;

        sendSmpPdu(session.connectionHandle, pdu);
    }

    private void sendPairingResponse(SmpSession session) {
        mListener.onMessage("Sending Pairing Response");

        byte[] pdu = new byte[7];
        pdu[0] = (byte) PAIRING_RESPONSE;
        pdu[1] = (byte) session.localIoCap;
        pdu[2] = (byte) session.localOobFlag;
        pdu[3] = (byte) session.localAuthReq;
        pdu[4] = (byte) session.localMaxKeySize;
        pdu[5] = (byte) session.negotiatedInitKeyDist;
        pdu[6] = (byte) session.negotiatedRespKeyDist;

        sendSmpPdu(session.connectionHandle, pdu);
    }

    private void sendPairingConfirm(SmpSession session) {
        mListener.onMessage("Sending Pairing Confirm");

        byte[] pdu = new byte[17];
        pdu[0] = (byte) PAIRING_CONFIRM;
        System.arraycopy(session.localConfirm, 0, pdu, 1, 16);

        sendSmpPdu(session.connectionHandle, pdu);
    }

    private void sendPairingRandom(SmpSession session) {
        mListener.onMessage("Sending Pairing Random");

        byte[] pdu = new byte[17];
        pdu[0] = (byte) PAIRING_RANDOM;
        System.arraycopy(session.localRandom, 0, pdu, 1, 16);

        sendSmpPdu(session.connectionHandle, pdu);
    }

    private void sendPairingFailed(SmpSession session, int reason) {
        mListener.onMessage("Sending Pairing Failed: 0x" + Integer.toHexString(reason));

        byte[] pdu = new byte[2];
        pdu[0] = (byte) PAIRING_FAILED;
        pdu[1] = (byte) reason;

        sendSmpPdu(session.connectionHandle, pdu);
    }

    private void sendPublicKey(SmpSession session) {
        mListener.onMessage("Sending Public Key");

        byte[] pdu = new byte[65];
        pdu[0] = (byte) PAIRING_PUBLIC_KEY;
        System.arraycopy(session.localPublicKeyX, 0, pdu, 1, 32);
        System.arraycopy(session.localPublicKeyY, 0, pdu, 33, 32);

        sendSmpPdu(session.connectionHandle, pdu);
    }

    private void sendDhKeyCheck(SmpSession session) {
        mListener.onMessage("Sending DHKey Check");

        byte[] pdu = new byte[17];
        pdu[0] = (byte) PAIRING_DHKEY_CHECK;
        System.arraycopy(session.localDhKeyCheck, 0, pdu, 1, 16);

        sendSmpPdu(session.connectionHandle, pdu);
    }

    private void sendEncryptionInformation(SmpSession session) {
        mListener.onMessage("Sending Encryption Information (LTK)");

        byte[] pdu = new byte[17];
        pdu[0] = (byte) ENCRYPTION_INFORMATION;
        System.arraycopy(session.ltk, 0, pdu, 1, 16);

        sendSmpPdu(session.connectionHandle, pdu);
    }

    private void sendMasterIdentification(SmpSession session) {
        mListener.onMessage("Sending Master Identification");

        byte[] pdu = new byte[11];
        pdu[0] = (byte) MASTER_IDENTIFICATION;
        pdu[1] = session.ediv[0];
        pdu[2] = session.ediv[1];
        System.arraycopy(session.rand, 0, pdu, 3, 8);

        sendSmpPdu(session.connectionHandle, pdu);
    }

    private void sendIdentityInformation(SmpSession session) {
        mListener.onMessage("Sending Identity Information (IRK)");

        byte[] pdu = new byte[17];
        pdu[0] = (byte) IDENTITY_INFORMATION;
        System.arraycopy(mLocalIrk, 0, pdu, 1, 16);

        sendSmpPdu(session.connectionHandle, pdu);
    }

    private void sendIdentityAddressInformation(SmpSession session) {
        mListener.onMessage("Sending Identity Address Information");

        byte[] pdu = new byte[8];
        pdu[0] = (byte) IDENTITY_ADDRESS_INFORMATION;
        pdu[1] = (byte) mLocalAddressType;
        System.arraycopy(mLocalAddress, 0, pdu, 2, 6);

        sendSmpPdu(session.connectionHandle, pdu);
    }

    private void sendSigningInformation(SmpSession session) {
        mListener.onMessage("Sending Signing Information (CSRK)");

        byte[] pdu = new byte[17];
        pdu[0] = (byte) SIGNING_INFORMATION;
        System.arraycopy(mLocalCsrk, 0, pdu, 1, 16);

        sendSmpPdu(session.connectionHandle, pdu);
    }

    private void sendScPasskeyConfirm(SmpSession session) {
        int bitValue = (session.passkey >> session.passkeyBitIndex) & 1;
        byte[] nonce = new byte[16];
        mSecureRandom.nextBytes(nonce);

        if (session.isInitiator()) {
            System.arraycopy(nonce, 0, session.na, 0, 16);
        } else {
            System.arraycopy(nonce, 0, session.nb, 0, 16);
        }

        byte[] pka = session.isInitiator() ? session.localPublicKeyX : session.peerPublicKeyX;
        byte[] pkb = session.isInitiator() ? session.peerPublicKeyX : session.localPublicKeyX;
        byte r = (byte) (0x80 | bitValue);

        byte[] confirm = SmpCrypto.f4(pka, pkb, nonce, r);
        System.arraycopy(confirm, 0, session.localConfirm, 0, 16);
        System.arraycopy(nonce, 0, session.localRandom, 0, 16);

        sendPairingConfirm(session);
    }

    private void sendSmpPdu(int connectionHandle, byte[] pdu) {
        CourierLogger.d(TAG, "SMP TX [" + pdu.length + "]: " + bytesToHex(pdu));
        mL2capManager.sendFixedChannelData(connectionHandle, SMP_CID, pdu);
    }

    // ==================== SMP PDU Processing ====================

    @Override
    public void onFixedChannelData(int connectionHandle, byte[] peerAddress, int peerAddressType, byte[] data) {
        processSmpData(connectionHandle, peerAddress, peerAddressType, data);
    }

    /**
     * Processes incoming SMP data.
     */
    public void processSmpData(int connectionHandle, byte[] peerAddress, int peerAddressType, byte[] data) {
        if (data == null || data.length < 1) {
            mListener.onMessage("Invalid SMP PDU (empty)");
            return;
        }

        int code = data[0] & 0xFF;
        CourierLogger.d(TAG, "SMP RX [" + data.length + "]: " + bytesToHex(data) +
                " (code=0x" + Integer.toHexString(code) + ")");

        SmpSession session = mSessions.get(connectionHandle);

        switch (code) {
            case PAIRING_REQUEST:
                handlePairingRequest(connectionHandle, peerAddress, peerAddressType, data);
                break;
            case PAIRING_RESPONSE:
                if (session != null) handlePairingResponse(session, data);
                break;
            case PAIRING_CONFIRM:
                if (session != null) handlePairingConfirm(session, data);
                break;
            case PAIRING_RANDOM:
                if (session != null) handlePairingRandom(session, data);
                break;
            case PAIRING_FAILED:
                if (session != null) handlePairingFailed(session, data);
                break;
            case ENCRYPTION_INFORMATION:
                if (session != null) handleEncryptionInformation(session, data);
                break;
            case MASTER_IDENTIFICATION:
                if (session != null) handleMasterIdentification(session, data);
                break;
            case IDENTITY_INFORMATION:
                if (session != null) handleIdentityInformation(session, data);
                break;
            case IDENTITY_ADDRESS_INFORMATION:
                if (session != null) handleIdentityAddressInformation(session, data);
                break;
            case SIGNING_INFORMATION:
                if (session != null) handleSigningInformation(session, data);
                break;
            case SECURITY_REQUEST:
                handleSecurityRequest(connectionHandle, peerAddress, peerAddressType, data);
                break;
            case PAIRING_PUBLIC_KEY:
                if (session != null) handlePublicKey(session, data);
                break;
            case PAIRING_DHKEY_CHECK:
                if (session != null) handleDhKeyCheck(session, data);
                break;
            case PAIRING_KEYPRESS_NOTIFICATION:
                if (session != null) handleKeypressNotification(session, data);
                break;
            default:
                mListener.onMessage("Unknown SMP code: 0x" + Integer.toHexString(code));
                if (session != null) {
                    sendPairingFailed(session, ERR_COMMAND_NOT_SUPPORTED);
                }
                break;
        }
    }

    private void handlePairingRequest(int connectionHandle, byte[] peerAddress,
                                      int peerAddressType, byte[] data) {
        if (data.length < 7) {
            mListener.onMessage("Invalid Pairing Request");
            return;
        }

        mListener.onMessage("Received Pairing Request from " + formatAddress(peerAddress));

        SmpSession session = new SmpSession(connectionHandle, peerAddress, peerAddressType, SmpRole.RESPONDER);
        session.localIoCap = mDefaultIoCap;
        session.localAuthReq = mDefaultAuthReq;
        session.localMaxKeySize = mDefaultMaxKeySize;
        mSessions.put(connectionHandle, session);

        mListener.onPairingStarted(connectionHandle, peerAddress);
        mListener.onPairingRequest(connectionHandle, peerAddress,
                data[1] & 0xFF, data[3] & 0xFF, (data[3] & AUTH_REQ_SC) != 0);

        // Parse peer parameters
        session.peerIoCap = data[1] & 0xFF;
        session.peerOobFlag = data[2] & 0xFF;
        session.peerAuthReq = data[3] & 0xFF;
        session.peerMaxKeySize = data[4] & 0xFF;
        session.peerInitKeyDist = data[5] & 0xFF;
        session.peerRespKeyDist = data[6] & 0xFF;

        mListener.onMessage(String.format("Peer: IoCap=%s, AuthReq=0x%02X, MaxKey=%d",
                getIoCapabilityString(session.peerIoCap), session.peerAuthReq, session.peerMaxKeySize));

        if (session.peerMaxKeySize < MIN_ENC_KEY_SIZE) {
            failPairing(session, ERR_ENCRYPTION_KEY_SIZE, "Key size too small");
            return;
        }

        // Determine SC vs Legacy
        boolean peerWantsSc = (session.peerAuthReq & AUTH_REQ_SC) != 0;
        boolean weWantSc = (session.localAuthReq & AUTH_REQ_SC) != 0;
        session.useSecureConnections = peerWantsSc && weWantSc;
        mListener.onMessage("Secure Connections: " + (session.useSecureConnections ? "Yes" : "No"));

        // Negotiate
        session.negotiatedKeySize = Math.min(session.localMaxKeySize, session.peerMaxKeySize);
        session.negotiatedInitKeyDist = session.peerInitKeyDist & session.localInitKeyDist;
        session.negotiatedRespKeyDist = session.peerRespKeyDist & session.localRespKeyDist;

        session.setMethod(determinePairingMethod(session));
        mListener.onMessage("Pairing method: " + session.getMethod());

        sendPairingResponse(session);

        if (session.useSecureConnections) {
            session.setState(SmpState.WAIT_PUBLIC_KEY);
        } else {
            session.setState(SmpState.WAIT_CONFIRM);
            if (session.getMethod() == SmpPairingMethod.JUST_WORKS) {
                Arrays.fill(session.tk, (byte) 0);
                generateConfirmValue(session);
            }
        }
    }

    private void handlePairingResponse(SmpSession session, byte[] data) {
        if (data.length < 7) {
            failPairing(session, ERR_INVALID_PARAMETERS, "Invalid Pairing Response");
            return;
        }

        if (session.getState() != SmpState.WAIT_PAIRING_RSP) {
            mListener.onMessage("Unexpected Pairing Response in state " + session.getState());
            return;
        }

        mListener.onMessage("Received Pairing Response");

        // Parse peer parameters
        session.peerIoCap = data[1] & 0xFF;
        session.peerOobFlag = data[2] & 0xFF;
        session.peerAuthReq = data[3] & 0xFF;
        session.peerMaxKeySize = data[4] & 0xFF;
        session.peerInitKeyDist = data[5] & 0xFF;
        session.peerRespKeyDist = data[6] & 0xFF;

        mListener.onMessage(String.format("Peer: IoCap=%s, AuthReq=0x%02X, MaxKey=%d",
                getIoCapabilityString(session.peerIoCap), session.peerAuthReq, session.peerMaxKeySize));

        if (session.peerMaxKeySize < MIN_ENC_KEY_SIZE) {
            failPairing(session, ERR_ENCRYPTION_KEY_SIZE, "Key size too small");
            return;
        }

        // Determine SC vs Legacy
        boolean peerWantsSc = (session.peerAuthReq & AUTH_REQ_SC) != 0;
        boolean weWantSc = (session.localAuthReq & AUTH_REQ_SC) != 0;
        session.useSecureConnections = peerWantsSc && weWantSc;
        mListener.onMessage("Secure Connections: " + (session.useSecureConnections ? "Yes (SC)" : "No (Legacy)"));

        session.negotiatedKeySize = Math.min(session.localMaxKeySize, session.peerMaxKeySize);
        session.negotiatedInitKeyDist = session.peerInitKeyDist & session.localInitKeyDist;
        session.negotiatedRespKeyDist = session.peerRespKeyDist & session.localRespKeyDist;

        session.setMethod(determinePairingMethod(session));
        mListener.onMessage("Pairing method: " + session.getMethod());

        if (session.useSecureConnections) {
            // SC pairing
            mExecutor.execute(() -> {
                if (generateLocalPublicKey(session)) {
                    sendPublicKey(session);
                    session.setState(SmpState.WAIT_PUBLIC_KEY);
                } else {
                    failPairing(session, ERR_UNSPECIFIED_REASON, "Failed to generate public key");
                }
            });
        } else {
            // Legacy pairing
            handleLegacyPairingMethod(session);
        }
    }

    private void handleLegacyPairingMethod(SmpSession session) {
        SmpPairingMethod method = session.getMethod();

        if (method == SmpPairingMethod.PASSKEY_ENTRY_RESPONDER_DISPLAYS) {
            if (session.callback != null) {
                session.callback.onPasskeyRequired(session.connectionHandle, false, 0);
            }
            mListener.onPasskeyRequired(session.connectionHandle, session.getPeerAddress(), false, 0);
            session.setState(SmpState.WAIT_CONFIRM);
        } else if (method == SmpPairingMethod.PASSKEY_ENTRY_INITIATOR_DISPLAYS) {
            session.passkey = mSecureRandom.nextInt(1000000);
            byte[] tk = SmpCrypto.passkeyToTk(session.passkey);
            System.arraycopy(tk, 0, session.tk, 0, 16);

            mListener.onMessage("Display passkey: " + String.format("%06d", session.passkey));
            if (session.callback != null) {
                session.callback.onPasskeyRequired(session.connectionHandle, true, session.passkey);
            }
            mListener.onPasskeyRequired(session.connectionHandle, session.getPeerAddress(), true, session.passkey);

            generateConfirmValue(session);
            sendPairingConfirm(session);
            session.setState(SmpState.WAIT_CONFIRM);
        } else {
            // Just Works
            Arrays.fill(session.tk, (byte) 0);
            generateConfirmValue(session);
            sendPairingConfirm(session);
            session.setState(SmpState.WAIT_CONFIRM);
        }
    }

    private void handlePairingConfirm(SmpSession session, byte[] data) {
        if (data.length < 17) {
            failPairing(session, ERR_INVALID_PARAMETERS, "Invalid Pairing Confirm");
            return;
        }

        mListener.onMessage("Received Pairing Confirm");
        System.arraycopy(data, 1, session.peerConfirm, 0, 16);

        if (session.useSecureConnections) {
            handleScPairingConfirm(session);
        } else {
            handleLegacyPairingConfirm(session);
        }
    }

    private void handleLegacyPairingConfirm(SmpSession session) {
        if (session.isInitiator()) {
            sendPairingRandom(session);
            session.setState(SmpState.WAIT_RANDOM);
        } else {
            if (session.localConfirm[0] != 0 || session.localConfirm[15] != 0) {
                sendPairingConfirm(session);
            }
            session.setState(SmpState.WAIT_RANDOM);
        }
    }

    private void handleScPairingConfirm(SmpSession session) {
        SmpPairingMethod method = session.getMethod();

        if (method == SmpPairingMethod.JUST_WORKS || method == SmpPairingMethod.NUMERIC_COMPARISON) {
            if (session.isInitiator()) {
                sendPairingRandom(session);
                session.setState(SmpState.WAIT_RANDOM);
            } else {
                session.setState(SmpState.WAIT_RANDOM);
            }
        } else {
            // Passkey entry
            if (session.isInitiator()) {
                sendPairingRandom(session);
                session.setState(SmpState.WAIT_RANDOM);
            } else {
                session.setState(SmpState.WAIT_RANDOM);
            }
        }
    }

    private void handlePairingRandom(SmpSession session, byte[] data) {
        if (data.length < 17) {
            failPairing(session, ERR_INVALID_PARAMETERS, "Invalid Pairing Random");
            return;
        }

        mListener.onMessage("Received Pairing Random");
        System.arraycopy(data, 1, session.peerRandom, 0, 16);

        if (session.useSecureConnections) {
            handleScPairingRandom(session);
        } else {
            handleLegacyPairingRandom(session);
        }
    }

    private void handleLegacyPairingRandom(SmpSession session) {
        // Verify confirm
        byte[] preq = buildPairingRequestPdu(session);
        byte[] pres = buildPairingResponsePdu(session);
        int iat = session.isInitiator() ? mLocalAddressType : session.peerAddressType;
        int rat = session.isInitiator() ? session.peerAddressType : mLocalAddressType;
        byte[] ia = session.isInitiator() ? mLocalAddress : session.getPeerAddress();
        byte[] ra = session.isInitiator() ? session.getPeerAddress() : mLocalAddress;

        byte[] expectedConfirm = SmpCrypto.c1(session.tk, session.peerRandom, preq, pres, iat, rat, ia, ra);

        if (!Arrays.equals(expectedConfirm, session.peerConfirm)) {
            mListener.onMessage("Confirm value mismatch!");
            CourierLogger.d(TAG, "Expected: " + bytesToHex(expectedConfirm));
            CourierLogger.d(TAG, "Received: " + bytesToHex(session.peerConfirm));
            failPairing(session, ERR_CONFIRM_VALUE_FAILED, "Confirm mismatch");
            return;
        }

        mListener.onMessage("Confirm value verified");

        if (session.isResponder()) {
            sendPairingRandom(session);
        }

        // Calculate STK
        byte[] r = new byte[16];
        if (session.isInitiator()) {
            System.arraycopy(session.localRandom, 0, r, 0, 8);
            System.arraycopy(session.peerRandom, 0, r, 8, 8);
        } else {
            System.arraycopy(session.peerRandom, 0, r, 0, 8);
            System.arraycopy(session.localRandom, 0, r, 8, 8);
        }

        byte[] stk = SmpCrypto.s1(session.tk, r);
        System.arraycopy(stk, 0, session.stk, 0, 16);
        mListener.onMessage("STK calculated");

        if (session.isInitiator()) {
            session.setState(SmpState.WAIT_ENCRYPTION);
            startEncryptionWithStk(session);
        } else {
            session.setState(SmpState.WAIT_LTK_REQUEST);
        }
    }

    private void handleScPairingRandom(SmpSession session) {
        if (session.isInitiator()) {
            System.arraycopy(session.peerRandom, 0, session.nb, 0, 16);
        } else {
            System.arraycopy(session.peerRandom, 0, session.na, 0, 16);
        }

        SmpPairingMethod method = session.getMethod();

        if (method == SmpPairingMethod.JUST_WORKS || method == SmpPairingMethod.NUMERIC_COMPARISON) {
            byte[] peerPkx = session.isInitiator() ? session.peerPublicKeyX : session.localPublicKeyX;
            byte[] expectedConfirm = SmpCrypto.f4(peerPkx, session.localPublicKeyX, session.peerRandom, (byte) 0);

            if (!Arrays.equals(expectedConfirm, session.peerConfirm)) {
                mListener.onMessage("SC Confirm value mismatch!");
                failPairing(session, ERR_CONFIRM_VALUE_FAILED, "Confirm mismatch");
                return;
            }

            if (session.isResponder()) {
                sendPairingRandom(session);
            }

            if (method == SmpPairingMethod.NUMERIC_COMPARISON) {
                int numericValue = SmpCrypto.g2(session.localPublicKeyX, session.peerPublicKeyX,
                        session.na, session.nb);
                mListener.onMessage("Numeric comparison: " + String.format("%06d", numericValue));
                if (session.callback != null) {
                    session.callback.onNumericComparisonRequired(session.connectionHandle, numericValue);
                }
                mListener.onNumericComparisonRequired(session.connectionHandle, session.getPeerAddress(), numericValue);
                return;
            }

            // Just Works - wait for DHKey if it's being generated async
            if (session.dhKeyLatch != null) {
                mListener.onMessage("Waiting for DH key generation to complete...");
                try {
                    if (!session.dhKeyLatch.await(ECDH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        failPairing(session, ERR_UNSPECIFIED_REASON, "DHKey generation timeout");
                        return;
                    }
                    if (!session.dhKeyGenerated) {
                        failPairing(session, ERR_UNSPECIFIED_REASON, "DHKey generation failed");
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failPairing(session, ERR_UNSPECIFIED_REASON, "DHKey wait interrupted");
                    return;
                }
            }

            // Just Works - continue
            calculateScKeys(session);

            if (session.isInitiator()) {
                sendDhKeyCheck(session);
                session.setState(SmpState.WAIT_DHKEY_CHECK);
            } else {
                session.setState(SmpState.WAIT_DHKEY_CHECK);
            }
        } else {
            handleScPasskeyRandom(session);
        }
    }

    private void handleScPasskeyRandom(SmpSession session) {
        int bitValue = (session.passkey >> session.passkeyBitIndex) & 1;
        byte r = (byte) (0x80 | bitValue);

        byte[] pka = session.isInitiator() ? session.localPublicKeyX : session.peerPublicKeyX;
        byte[] pkb = session.isInitiator() ? session.peerPublicKeyX : session.localPublicKeyX;
        byte[] expectedConfirm = SmpCrypto.f4(pka, pkb, session.peerRandom, r);

        if (!Arrays.equals(expectedConfirm, session.peerConfirm)) {
            failPairing(session, ERR_CONFIRM_VALUE_FAILED, "Passkey confirm mismatch");
            return;
        }

        if (session.isResponder()) {
            sendPairingRandom(session);
        }

        session.passkeyBitIndex++;

        if (session.passkeyBitIndex < 20) {
            if (session.isInitiator()) {
                sendScPasskeyConfirm(session);
            } else {
                session.setState(SmpState.WAIT_CONFIRM);
            }
        } else {
            calculateScKeys(session);

            if (session.isInitiator()) {
                sendDhKeyCheck(session);
                session.setState(SmpState.WAIT_DHKEY_CHECK);
            } else {
                session.setState(SmpState.WAIT_DHKEY_CHECK);
            }
        }
    }

    private void handlePairingFailed(SmpSession session, byte[] data) {
        int reason = data.length >= 2 ? (data[1] & 0xFF) : ERR_UNSPECIFIED_REASON;
        String reasonStr = getErrorString(reason);
        mListener.onMessage("Received Pairing Failed: " + reasonStr);

        session.setState(SmpState.FAILED);
        session.success = false;
        session.errorCode = reason;
        session.errorMessage = "Peer: " + reasonStr;

        mListener.onPairingFailed(session.connectionHandle, session.getPeerAddress(), reason, reasonStr);
        if (session.callback != null) {
            session.callback.onPairingComplete(session.connectionHandle, false, null);
        }
        session.completionLatch.countDown();
        mSessions.remove(session.connectionHandle);
    }

    private void handlePublicKey(SmpSession session, byte[] data) {
        if (data.length < 65) {
            failPairing(session, ERR_INVALID_PARAMETERS, "Invalid Public Key");
            return;
        }

        mListener.onMessage("Received Public Key");
        System.arraycopy(data, 1, session.peerPublicKeyX, 0, 32);
        System.arraycopy(data, 33, session.peerPublicKeyY, 0, 32);

        if (session.isResponder()) {
            mExecutor.execute(() -> {
                if (generateLocalPublicKey(session)) {
                    sendPublicKey(session);

                    if (generateDhKey(session)) {
                        SmpPairingMethod method = session.getMethod();
                        if (method == SmpPairingMethod.JUST_WORKS ||
                                method == SmpPairingMethod.NUMERIC_COMPARISON) {
                            session.setState(SmpState.WAIT_CONFIRM);
                        } else {
                            if (session.callback != null) {
                                boolean display = method == SmpPairingMethod.PASSKEY_ENTRY_RESPONDER_DISPLAYS;
                                if (display) {
                                    session.passkey = mSecureRandom.nextInt(1000000);
                                }
                                session.callback.onPasskeyRequired(session.connectionHandle, display, session.passkey);
                            }
                            session.setState(SmpState.WAIT_CONFIRM);
                        }
                    } else {
                        failPairing(session, ERR_UNSPECIFIED_REASON, "Failed to generate DH key");
                    }
                } else {
                    failPairing(session, ERR_UNSPECIFIED_REASON, "Failed to generate public key");
                }
            });
        } else {
            // Initiator received responder's public key
            SmpPairingMethod method = session.getMethod();

            if (method == SmpPairingMethod.JUST_WORKS ||
                    method == SmpPairingMethod.NUMERIC_COMPARISON) {
                // For SC Just Works/Numeric Comparison, generate Na and send confirm IMMEDIATELY
                // The f4 function only needs public keys and Na, NOT the DHKey
                // This prevents a race condition where peer's confirm arrives before DHKey generation completes
                mSecureRandom.nextBytes(session.na);
                System.arraycopy(session.na, 0, session.localRandom, 0, 16);
                byte[] confirm = SmpCrypto.f4(session.localPublicKeyX, session.peerPublicKeyX,
                        session.na, (byte) 0);
                System.arraycopy(confirm, 0, session.localConfirm, 0, 16);
                sendPairingConfirm(session);
                session.scInitiatorConfirmSent = true;
                session.setState(SmpState.WAIT_CONFIRM);

                // Create latch for DHKey completion tracking
                session.dhKeyLatch = new CountDownLatch(1);

                // Now start DHKey generation asynchronously (needed later for key calculation)
                mExecutor.execute(() -> {
                    mListener.onMessage("Generating DH key...");
                    if (generateDhKey(session)) {
                        mListener.onMessage("DH key generated");
                        session.dhKeyGenerated = true;
                    } else {
                        session.dhKeyGenerated = false;
                    }
                    // Signal that DHKey generation is complete (success or failure)
                    session.dhKeyLatch.countDown();
                });
            } else {
                // Passkey entry - need DHKey first for the multi-round protocol
                mExecutor.execute(() -> {
                    if (generateDhKey(session)) {
                        if (session.callback != null) {
                            boolean display = method == SmpPairingMethod.PASSKEY_ENTRY_INITIATOR_DISPLAYS;
                            if (display) {
                                session.passkey = mSecureRandom.nextInt(1000000);
                            }
                            session.callback.onPasskeyRequired(session.connectionHandle, display, session.passkey);
                        }
                    } else {
                        failPairing(session, ERR_UNSPECIFIED_REASON, "Failed to generate DH key");
                    }
                });
            }
        }
    }

    private void handleDhKeyCheck(SmpSession session, byte[] data) {
        if (data.length < 17) {
            failPairing(session, ERR_INVALID_PARAMETERS, "Invalid DHKey Check");
            return;
        }

        mListener.onMessage("Received DHKey Check");
        System.arraycopy(data, 1, session.peerDhKeyCheck, 0, 16);

        // Verify
        byte[] ioCap = new byte[3];
        byte[] expectedCheck;
        boolean isPasskeyMethod = session.getMethod().isPasskeyMethod();

        if (session.isInitiator()) {
            ioCap[0] = (byte) session.peerAuthReq;
            ioCap[1] = (byte) session.peerOobFlag;
            ioCap[2] = (byte) session.peerIoCap;
            byte[] r = SmpCrypto.buildScR(session.passkey, isPasskeyMethod);
            expectedCheck = SmpCrypto.f6(session.macKey, session.nb, session.na,
                    r, ioCap, session.getPeerAddress(), mLocalAddress);
        } else {
            ioCap[0] = (byte) session.peerAuthReq;
            ioCap[1] = (byte) session.peerOobFlag;
            ioCap[2] = (byte) session.peerIoCap;
            byte[] r = SmpCrypto.buildScR(session.passkey, isPasskeyMethod);
            expectedCheck = SmpCrypto.f6(session.macKey, session.na, session.nb,
                    r, ioCap, session.getPeerAddress(), mLocalAddress);
        }

        if (!Arrays.equals(expectedCheck, session.peerDhKeyCheck)) {
            mListener.onMessage("DHKey Check failed!");
            failPairing(session, ERR_DHKEY_CHECK_FAILED, "DHKey check mismatch");
            return;
        }

        mListener.onMessage("DHKey Check verified");

        if (session.isResponder()) {
            sendDhKeyCheck(session);
        }

        session.setState(SmpState.WAIT_ENCRYPTION);

        if (session.isInitiator()) {
            startEncryptionWithLtk(session);
        }
    }

    private void handleEncryptionInformation(SmpSession session, byte[] data) {
        if (data.length < 17) return;
        mListener.onMessage("Received Encryption Information (LTK)");
        System.arraycopy(data, 1, session.peerLtk, 0, 16);
        session.markKeyReceived(KEY_DIST_ENC_KEY);
        checkKeyDistributionComplete(session);
    }

    private void handleMasterIdentification(SmpSession session, byte[] data) {
        if (data.length < 11) return;
        mListener.onMessage("Received Master Identification");
        session.peerEdiv[0] = data[1];
        session.peerEdiv[1] = data[2];
        System.arraycopy(data, 3, session.peerRand, 0, 8);
        checkKeyDistributionComplete(session);
    }

    private void handleIdentityInformation(SmpSession session, byte[] data) {
        if (data.length < 17) return;
        mListener.onMessage("Received Identity Information (IRK)");
        System.arraycopy(data, 1, session.peerIrk, 0, 16);
        session.markKeyReceived(KEY_DIST_ID_KEY);
        checkKeyDistributionComplete(session);
    }

    private void handleIdentityAddressInformation(SmpSession session, byte[] data) {
        if (data.length < 8) return;
        mListener.onMessage("Received Identity Address Information");
        session.peerIdentityAddressType = data[1] & 0xFF;
        System.arraycopy(data, 2, session.peerIdentityAddress, 0, 6);
        mListener.onIdentityAddressReceived(session.connectionHandle, session.getPeerAddress(),
                Arrays.copyOf(session.peerIdentityAddress, 6), session.peerIdentityAddressType);
        checkKeyDistributionComplete(session);
    }

    private void handleSigningInformation(SmpSession session, byte[] data) {
        if (data.length < 17) return;
        mListener.onMessage("Received Signing Information (CSRK)");
        System.arraycopy(data, 1, session.peerCsrk, 0, 16);
        session.markKeyReceived(KEY_DIST_SIGN);
        checkKeyDistributionComplete(session);
    }

    private void handleSecurityRequest(int connectionHandle, byte[] peerAddress,
                                       int peerAddressType, byte[] data) {
        if (data.length < 2) return;
        int authReq = data[1] & 0xFF;
        mListener.onMessage("Received Security Request from " + formatAddress(peerAddress) +
                " authReq=0x" + Integer.toHexString(authReq));
        mListener.onSecurityRequest(connectionHandle, authReq);
    }

    private void handleKeypressNotification(SmpSession session, byte[] data) {
        if (data.length < 2) return;
        int type = data[1] & 0xFF;
        mListener.onMessage("Keypress notification: " + type);
    }

    // ==================== Key Distribution ====================

    private void checkKeyDistributionComplete(SmpSession session) {
        synchronized (session) {
            if (session.localKeysDistributionStarted) return;

            int expectedKeys = session.isInitiator() ?
                    session.negotiatedRespKeyDist : session.negotiatedInitKeyDist;

            boolean ltk = (expectedKeys & KEY_DIST_ENC_KEY) == 0 ||
                    session.hasReceivedKey(KEY_DIST_ENC_KEY);
            boolean irk = (expectedKeys & KEY_DIST_ID_KEY) == 0 ||
                    session.hasReceivedKey(KEY_DIST_ID_KEY);
            boolean csrk = (expectedKeys & KEY_DIST_SIGN) == 0 ||
                    session.hasReceivedKey(KEY_DIST_SIGN);

            if (ltk && irk && csrk) {
                mListener.onMessage("All keys received, sending our keys");
                session.localKeysDistributionStarted = true;
                sendOurKeys(session);
            }
        }
    }

    private void sendOurKeys(SmpSession session) {
        int keysToSend = session.isInitiator() ?
                session.negotiatedInitKeyDist : session.negotiatedRespKeyDist;

        if (session.useSecureConnections) {
            keysToSend &= ~KEY_DIST_ENC_KEY;
        }

        final int finalKeysToSend = keysToSend;

        mExecutor.execute(() -> {
            try {
                if (!session.useSecureConnections && (finalKeysToSend & KEY_DIST_ENC_KEY) != 0) {
                    mSecureRandom.nextBytes(session.ltk);
                    mSecureRandom.nextBytes(session.ediv);
                    mSecureRandom.nextBytes(session.rand);

                    sendEncryptionInformation(session);
                    Thread.sleep(KEY_DIST_DELAY_MS);
                    sendMasterIdentification(session);
                    Thread.sleep(KEY_DIST_DELAY_MS);
                }

                if ((finalKeysToSend & KEY_DIST_ID_KEY) != 0) {
                    sendIdentityInformation(session);
                    Thread.sleep(KEY_DIST_DELAY_MS);
                    sendIdentityAddressInformation(session);
                    Thread.sleep(KEY_DIST_DELAY_MS);
                }

                if ((finalKeysToSend & KEY_DIST_SIGN) != 0) {
                    sendSigningInformation(session);
                    Thread.sleep(KEY_DIST_DELAY_MS);
                }

                completePairing(session);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failPairing(session, ERR_UNSPECIFIED_REASON, "Interrupted");
            }
        });
    }

    // ==================== ECDH Operations ====================

    private boolean generateLocalPublicKey(SmpSession session) {
        mListener.onMessage("Generating local P-256 public key...");

        if (mLocalP256PublicKeyX != null && mLocalP256PublicKeyY != null) {
            System.arraycopy(mLocalP256PublicKeyX, 0, session.localPublicKeyX, 0, 32);
            System.arraycopy(mLocalP256PublicKeyY, 0, session.localPublicKeyY, 0, 32);
            return true;
        }

        mPublicKeyLatch = new CountDownLatch(1);

        byte[] cmd = HciCommands.leReadLocalP256PublicKey();
        mHciManager.sendCommand(cmd);

        try {
            if (mPublicKeyLatch.await(ECDH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                if (mLocalP256PublicKeyX != null) {
                    System.arraycopy(mLocalP256PublicKeyX, 0, session.localPublicKeyX, 0, 32);
                    System.arraycopy(mLocalP256PublicKeyY, 0, session.localPublicKeyY, 0, 32);
                    return true;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        mListener.onMessage("Failed to generate local public key");
        return false;
    }

    private boolean generateDhKey(SmpSession session) {
        mListener.onMessage("Generating DH key...");

        mDhKeyLatch = new CountDownLatch(1);
        mGeneratedDhKey = null;

        byte[] cmd = HciCommands.leGenerateDhKey(session.peerPublicKeyX, session.peerPublicKeyY);
        mHciManager.sendCommand(cmd);

        try {
            if (mDhKeyLatch.await(ECDH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                if (mGeneratedDhKey != null) {
                    System.arraycopy(mGeneratedDhKey, 0, session.dhKey, 0, 32);
                    mListener.onMessage("DH key generated");
                    return true;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        mListener.onMessage("Failed to generate DH key");
        return false;
    }

    private void calculateScKeys(SmpSession session) {
        byte[] a1 = new byte[7];
        byte[] a2 = new byte[7];

        a1[0] = (byte) mLocalAddressType;
        System.arraycopy(mLocalAddress, 0, a1, 1, 6);

        a2[0] = (byte) session.peerAddressType;
        System.arraycopy(session.getPeerAddress(), 0, a2, 1, 6);

        byte[][] keys = SmpCrypto.f5(session.dhKey, session.na, session.nb, a1, a2);
        System.arraycopy(keys[0], 0, session.macKey, 0, 16);
        System.arraycopy(keys[1], 0, session.ltk, 0, 16);

        // Calculate local DHKey check
        byte[] ioCap = new byte[3];
        ioCap[0] = (byte) session.localAuthReq;
        ioCap[1] = (byte) session.localOobFlag;
        ioCap[2] = (byte) session.localIoCap;

        boolean isPasskeyMethod = session.getMethod().isPasskeyMethod();
        byte[] r = SmpCrypto.buildScR(session.passkey, isPasskeyMethod);

        byte[] dhKeyCheck;
        if (session.isInitiator()) {
            dhKeyCheck = SmpCrypto.f6(session.macKey, session.na, session.nb,
                    r, ioCap, mLocalAddress, session.getPeerAddress());
        } else {
            dhKeyCheck = SmpCrypto.f6(session.macKey, session.nb, session.na,
                    r, ioCap, mLocalAddress, session.getPeerAddress());
        }
        System.arraycopy(dhKeyCheck, 0, session.localDhKeyCheck, 0, 16);

        mListener.onMessage("SC keys calculated");
    }

    // ==================== Encryption ====================

    private void startEncryptionWithStk(SmpSession session) {
        mListener.onMessage("Starting encryption with STK");

        ByteBuffer buffer = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) session.connectionHandle);
        buffer.put(new byte[8]); // Random (zeros for STK)
        buffer.putShort((short) 0); // EDIV (zeros for STK)
        buffer.put(session.stk);

        byte[] cmd = HciCommands.leStartEncryption(buffer.array());
        mHciManager.sendCommand(cmd);
    }

    private void startEncryptionWithLtk(SmpSession session) {
        mListener.onMessage("Starting encryption with LTK");

        ByteBuffer buffer = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) session.connectionHandle);

        if (session.useSecureConnections) {
            buffer.put(new byte[8]); // Random (zeros for SC)
            buffer.putShort((short) 0); // EDIV (zeros for SC)
        } else {
            buffer.put(session.rand);
            buffer.putShort((short) ((session.ediv[1] & 0xFF) << 8 | (session.ediv[0] & 0xFF)));
        }
        buffer.put(session.ltk);

        byte[] cmd = HciCommands.leStartEncryption(buffer.array());
        mHciManager.sendCommand(cmd);
    }

    /**
     * Starts encryption with stored bonding information.
     *
     * @param connectionHandle connection handle
     * @param bondingInfo stored bonding info
     */
    public void startEncryption(int connectionHandle, BondingInfo bondingInfo) {
        mListener.onMessage("Starting encryption with stored LTK");

        ByteBuffer buffer = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) connectionHandle);

        if (bondingInfo.isSecureConnections()) {
            buffer.put(new byte[8]);
            buffer.putShort((short) 0);
        } else {
            buffer.put(bondingInfo.getRand());
            byte[] ediv = bondingInfo.getEdiv();
            buffer.putShort((short) ((ediv[1] & 0xFF) << 8 | (ediv[0] & 0xFF)));
        }
        buffer.put(bondingInfo.getLtk());

        byte[] cmd = HciCommands.leStartEncryption(buffer.array());
        mHciManager.sendCommand(cmd);
    }

    // ==================== HCI Event Handling ====================

    @Override
    public void onEvent(byte[] event) {
        if (event == null || event.length < 2) return;

        int eventCode = event[0] & 0xFF;

        if (eventCode == HCI_LE_META_EVENT && event.length >= 3) {
            int subevent = event[2] & 0xFF;
            handleLeMetaEvent(subevent, event);
        } else if (eventCode == HCI_ENCRYPTION_CHANGE_EVENT) {
            handleEncryptionChangeEvent(event);
        } else if (eventCode == HCI_ENCRYPTION_KEY_REFRESH_COMPLETE) {
            handleEncryptionKeyRefreshEvent(event);
        }
    }

    @Override
    public void onAclData(byte[] data) {
        // ACL data is handled by L2capManager, no action needed here
    }

    @Override
    public void onScoData(byte[] data) {
        // SCO data not used in SMP
    }

    @Override
    public void onIsoData(byte[] data) {
        // ISO data not used in SMP
    }

    @Override
    public void onError(String message) {
        CourierLogger.e(TAG, "HCI Error: " + message);
        mListener.onError(message);
    }

    @Override
    public void onMessage(String message) {
        CourierLogger.d(TAG, "HCI: " + message);
    }

    private void handleLeMetaEvent(int subevent, byte[] event) {
        switch (subevent) {
            case HCI_LE_CONNECTION_COMPLETE:
            case HCI_LE_ENHANCED_CONNECTION_COMPLETE:
                if (event.length >= 21) {
                    int status = event[3] & 0xFF;
                    int handle = ((event[5] & 0xFF) << 8) | (event[4] & 0xFF);
                    int role = event[6] & 0xFF;
                    byte[] addr = new byte[6];
                    System.arraycopy(event, 8, addr, 0, 6);
                    CourierLogger.i(TAG, "LE Connection: handle=0x" + Integer.toHexString(handle) +
                            " role=" + (role == 0 ? "Central" : "Peripheral") +
                            " peer=" + formatAddress(addr) + " status=" + status);
                }
                break;
            case HCI_LE_LONG_TERM_KEY_REQUEST:
                handleLeLtkRequest(event);
                break;
            case HCI_LE_READ_LOCAL_P256_PUBLIC_KEY_COMPLETE:
                handleLeReadLocalP256PublicKeyComplete(event);
                break;
            case HCI_LE_GENERATE_DHKEY_COMPLETE:
            case HCI_LE_GENERATE_DHKEY_COMPLETE_V2:
                handleLeGenerateDhKeyComplete(event);
                break;
            default:
                CourierLogger.d(TAG, "LE Meta subevent 0x" + Integer.toHexString(subevent));
                break;
        }
    }

    private void handleLeLtkRequest(byte[] event) {
        if (event.length < 15) return;

        int handle = ((event[4] & 0xFF) << 8) | (event[3] & 0xFF);
        byte[] random = new byte[8];
        System.arraycopy(event, 5, random, 0, 8);
        int ediv = ((event[14] & 0xFF) << 8) | (event[13] & 0xFF);

        mListener.onMessage("LE LTK Request: handle=0x" + Integer.toHexString(handle) +
                " EDIV=0x" + Integer.toHexString(ediv));

        SmpSession session = mSessions.get(handle);
        byte[] ltk = null;

        if (session != null) {
            if (session.useSecureConnections) {
                ltk = Arrays.copyOf(session.ltk, 16);
            } else {
                ltk = Arrays.copyOf(session.stk, 16);
            }
        } else {
            // Check bonding database
            for (BondingInfo info : mBondingDatabase.values()) {
                if (info.hasLtk()) {
                    byte[] storedEdiv = info.getEdiv();
                    int storedEdivVal = ((storedEdiv[1] & 0xFF) << 8) | (storedEdiv[0] & 0xFF);
                    if (ediv == storedEdivVal || (ediv == 0 && info.isSecureConnections())) {
                        ltk = info.getLtk();
                        break;
                    }
                }
            }
        }

        if (ltk != null) {
            byte[] cmd = HciCommands.leLongTermKeyRequestReply(handle, ltk);
            mHciManager.sendCommand(cmd);
            mListener.onMessage("Sent LTK Reply");
        } else {
            byte[] cmd = HciCommands.leLongTermKeyRequestNegativeReply(handle);
            mHciManager.sendCommand(cmd);
            mListener.onMessage("Sent LTK Negative Reply");
        }
    }

    private void handleEncryptionChangeEvent(byte[] event) {
        if (event.length < 6) return;

        int status = event[2] & 0xFF;
        int handle = ((event[4] & 0xFF) << 8) | (event[3] & 0xFF);
        int enabled = event[5] & 0xFF;

        mListener.onMessage("Encryption Change: handle=0x" + Integer.toHexString(handle) +
                " status=0x" + Integer.toHexString(status) + " enabled=" + enabled);

        mListener.onEncryptionChanged(handle, enabled != 0 && status == 0);

        SmpSession session = mSessions.get(handle);
        if (session != null && session.getState() == SmpState.WAIT_ENCRYPTION) {
            if (status == 0 && enabled != 0) {
                mListener.onMessage("Encryption established, starting key distribution");
                session.setState(SmpState.KEY_DISTRIBUTION);

                if (session.isResponder()) {
                    sendOurKeys(session);
                } else {
                    checkKeyDistributionComplete(session);
                }
            } else {
                failPairing(session, ERR_UNSPECIFIED_REASON,
                        "Encryption failed: status=0x" + Integer.toHexString(status));
            }
        }
    }

    private void handleEncryptionKeyRefreshEvent(byte[] event) {
        if (event.length < 5) return;
        int status = event[2] & 0xFF;
        int handle = ((event[4] & 0xFF) << 8) | (event[3] & 0xFF);
        mListener.onMessage("Encryption Key Refresh: handle=0x" + Integer.toHexString(handle) +
                " status=0x" + Integer.toHexString(status));
    }

    private void handleLeReadLocalP256PublicKeyComplete(byte[] event) {
        if (event.length < 68) {
            mListener.onMessage("Invalid P256 Public Key Complete event");
            if (mPublicKeyLatch != null) mPublicKeyLatch.countDown();
            return;
        }

        int status = event[3] & 0xFF;
        if (status != 0) {
            mListener.onMessage("P256 Public Key generation failed: 0x" + Integer.toHexString(status));
            if (mPublicKeyLatch != null) mPublicKeyLatch.countDown();
            return;
        }

        mLocalP256PublicKeyX = new byte[32];
        mLocalP256PublicKeyY = new byte[32];
        System.arraycopy(event, 4, mLocalP256PublicKeyX, 0, 32);
        System.arraycopy(event, 36, mLocalP256PublicKeyY, 0, 32);

        mListener.onMessage("Local P-256 public key generated");
        if (mPublicKeyLatch != null) mPublicKeyLatch.countDown();
    }

    private void handleLeGenerateDhKeyComplete(byte[] event) {
        if (event.length < 36) {
            mListener.onMessage("Invalid DHKey Complete event");
            if (mDhKeyLatch != null) mDhKeyLatch.countDown();
            return;
        }

        int status = event[3] & 0xFF;
        if (status != 0) {
            mListener.onMessage("DHKey generation failed: 0x" + Integer.toHexString(status));
            if (mDhKeyLatch != null) mDhKeyLatch.countDown();
            return;
        }

        mGeneratedDhKey = new byte[32];
        System.arraycopy(event, 4, mGeneratedDhKey, 0, 32);

        mListener.onMessage("DHKey generated");
        if (mDhKeyLatch != null) mDhKeyLatch.countDown();
    }

    // ==================== Pairing Completion ====================

    private void completePairing(SmpSession session) {
        session.setState(SmpState.PAIRED);
        session.success = true;
        mListener.onMessage("Pairing complete!");

        // Cache identity address before removing session
        byte[] identityAddr = session.getPeerIdentityAddressIfPresent();
        if (identityAddr != null) {
            mIdentityAddressCache.put(session.connectionHandle, identityAddr);
            mIdentityAddressTypeCache.put(session.connectionHandle, session.peerIdentityAddressType);
            CourierLogger.d(TAG, "Cached identity address for handle 0x" +
                    Integer.toHexString(session.connectionHandle));
        }

        BondingInfo bondingInfo = buildBondingInfo(session);
        storeBondingInfo(bondingInfo);

        // Derive BR/EDR link key using CTKD if both sides support CT2
        boolean localCt2 = (session.localAuthReq & AUTH_REQ_CT2) != 0;
        boolean peerCt2 = (session.peerAuthReq & AUTH_REQ_CT2) != 0;

        if (localCt2 || peerCt2) {
            // Get the LTK for CTKD - use appropriate key based on pairing type
            byte[] ltkForCtkd = null;
            if (session.useSecureConnections) {
                ltkForCtkd = session.ltk;
            } else if (session.hasReceivedKey(KEY_DIST_ENC_KEY)) {
                ltkForCtkd = session.peerLtk;
            } else {
                // Use STK as LTK for very basic legacy pairing
                ltkForCtkd = session.stk;
            }

            if (ltkForCtkd != null && !isZeroKey(ltkForCtkd)) {
                try {
                    byte[] derivedLinkKey = SmpCrypto.deriveBrEdrLinkKey(ltkForCtkd, session.useSecureConnections);

                    // Store by identity address if available, otherwise by peer address
                    String addrKey;
                    if (identityAddr != null) {
                        addrKey = formatAddress(identityAddr);
                        mDerivedLinkKeys.put(addrKey, derivedLinkKey);
                        CourierLogger.i(TAG, "Derived BR/EDR link key via CTKD for identity: " + addrKey);
                    }
                    // Also store by current peer address
                    addrKey = session.getPeerAddressString();
                    mDerivedLinkKeys.put(addrKey, derivedLinkKey);
                    CourierLogger.i(TAG, "Derived BR/EDR link key via CTKD for: " + addrKey +
                            " (SC=" + session.useSecureConnections + ")");
                } catch (Exception e) {
                    CourierLogger.w(TAG, "Failed to derive BR/EDR link key: " + e.getMessage());
                }
            }
        }

        mListener.onPairingComplete(session.connectionHandle, session.getPeerAddress(), true, bondingInfo);
        if (session.callback != null) {
            session.callback.onPairingComplete(session.connectionHandle, true, bondingInfo);
        }
        session.completionLatch.countDown();
        mSessions.remove(session.connectionHandle);
    }

    /**
     * Checks if a key is all zeros.
     */
    private boolean isZeroKey(byte[] key) {
        for (byte b : key) {
            if (b != 0) return false;
        }
        return true;
    }

    private void failPairing(SmpSession session, int errorCode, String reason) {
        if (session.getState() == SmpState.FAILED) return;

        session.setState(SmpState.FAILED);
        session.success = false;
        session.errorCode = errorCode;
        session.errorMessage = reason;

        mListener.onMessage("Pairing failed: " + reason);
        sendPairingFailed(session, errorCode);

        mListener.onPairingFailed(session.connectionHandle, session.getPeerAddress(), errorCode, reason);
        if (session.callback != null) {
            session.callback.onPairingComplete(session.connectionHandle, false, null);
        }
        session.completionLatch.countDown();
        mSessions.remove(session.connectionHandle);
    }

    private BondingInfo buildBondingInfo(SmpSession session) {
        BondingInfo.Builder builder = BondingInfo.builder()
                .address(session.getPeerAddress())
                .addressType(session.peerAddressType)
                .keySize(session.negotiatedKeySize)
                .authenticated(session.getMethod().isMitmProtected())
                .secureConnections(session.useSecureConnections);

        if (session.useSecureConnections) {
            builder.ltk(session.ltk);
        } else if (session.hasReceivedKey(KEY_DIST_ENC_KEY)) {
            builder.ltk(session.peerLtk)
                    .ediv(session.peerEdiv)
                    .rand(session.peerRand);
        }

        if (session.hasReceivedKey(KEY_DIST_ID_KEY)) {
            builder.irk(session.peerIrk);
            byte[] identAddr = session.getPeerIdentityAddressIfPresent();
            if (identAddr != null) {
                builder.identityAddress(identAddr, session.peerIdentityAddressType);
            }
        }

        if (session.hasReceivedKey(KEY_DIST_SIGN)) {
            builder.csrk(session.peerCsrk);
        }

        return builder.build();
    }

    // ==================== Utility Methods ====================

    private SmpPairingMethod determinePairingMethod(SmpSession session) {
        boolean localMitm = (session.localAuthReq & AUTH_REQ_MITM) != 0;
        boolean peerMitm = (session.peerAuthReq & AUTH_REQ_MITM) != 0;
        boolean mitm = localMitm || peerMitm;

        boolean localOob = session.localOobFlag == OOB_AUTH_DATA_PRESENT;
        boolean peerOob = session.peerOobFlag == OOB_AUTH_DATA_PRESENT;

        if (localOob && peerOob) {
            return session.useSecureConnections ? SmpPairingMethod.OOB_SC : SmpPairingMethod.OOB_LEGACY;
        }

        if (!mitm) {
            return SmpPairingMethod.JUST_WORKS;
        }

        int initIoCap = session.isInitiator() ? session.localIoCap : session.peerIoCap;
        int respIoCap = session.isInitiator() ? session.peerIoCap : session.localIoCap;

        return getMethodFromIoCapabilities(initIoCap, respIoCap, session.useSecureConnections);
    }

    private SmpPairingMethod getMethodFromIoCapabilities(int initIoCap, int respIoCap, boolean sc) {
        // IO Capability mapping table per spec
        if (initIoCap == IO_CAP_NO_INPUT_NO_OUTPUT || respIoCap == IO_CAP_NO_INPUT_NO_OUTPUT) {
            return SmpPairingMethod.JUST_WORKS;
        }

        if (initIoCap == IO_CAP_DISPLAY_ONLY) {
            if (respIoCap == IO_CAP_DISPLAY_ONLY || respIoCap == IO_CAP_DISPLAY_YES_NO) {
                return SmpPairingMethod.JUST_WORKS;
            }
            return SmpPairingMethod.PASSKEY_ENTRY_INITIATOR_DISPLAYS;
        }

        if (initIoCap == IO_CAP_DISPLAY_YES_NO) {
            if (respIoCap == IO_CAP_DISPLAY_ONLY) {
                return SmpPairingMethod.JUST_WORKS;
            }
            if (respIoCap == IO_CAP_DISPLAY_YES_NO) {
                return sc ? SmpPairingMethod.NUMERIC_COMPARISON : SmpPairingMethod.JUST_WORKS;
            }
            if (respIoCap == IO_CAP_KEYBOARD_ONLY) {
                return SmpPairingMethod.PASSKEY_ENTRY_INITIATOR_DISPLAYS;
            }
            return sc ? SmpPairingMethod.NUMERIC_COMPARISON : SmpPairingMethod.PASSKEY_ENTRY_INITIATOR_DISPLAYS;
        }

        if (initIoCap == IO_CAP_KEYBOARD_ONLY) {
            if (respIoCap == IO_CAP_KEYBOARD_ONLY) {
                return SmpPairingMethod.PASSKEY_ENTRY_BOTH_INPUT;
            }
            return SmpPairingMethod.PASSKEY_ENTRY_RESPONDER_DISPLAYS;
        }

        if (initIoCap == IO_CAP_KEYBOARD_DISPLAY) {
            if (respIoCap == IO_CAP_DISPLAY_ONLY) {
                return SmpPairingMethod.PASSKEY_ENTRY_RESPONDER_DISPLAYS;
            }
            if (respIoCap == IO_CAP_DISPLAY_YES_NO) {
                return sc ? SmpPairingMethod.NUMERIC_COMPARISON : SmpPairingMethod.PASSKEY_ENTRY_RESPONDER_DISPLAYS;
            }
            if (respIoCap == IO_CAP_KEYBOARD_ONLY) {
                return SmpPairingMethod.PASSKEY_ENTRY_INITIATOR_DISPLAYS;
            }
            return sc ? SmpPairingMethod.NUMERIC_COMPARISON : SmpPairingMethod.PASSKEY_ENTRY_INITIATOR_DISPLAYS;
        }

        return SmpPairingMethod.JUST_WORKS;
    }

    private void generateConfirmValue(SmpSession session) {
        mSecureRandom.nextBytes(session.localRandom);

        byte[] preq = buildPairingRequestPdu(session);
        byte[] pres = buildPairingResponsePdu(session);
        int iat = session.isInitiator() ? mLocalAddressType : session.peerAddressType;
        int rat = session.isInitiator() ? session.peerAddressType : mLocalAddressType;
        byte[] ia = session.isInitiator() ? mLocalAddress : session.getPeerAddress();
        byte[] ra = session.isInitiator() ? session.getPeerAddress() : mLocalAddress;

        byte[] confirm = SmpCrypto.c1(session.tk, session.localRandom, preq, pres, iat, rat, ia, ra);
        System.arraycopy(confirm, 0, session.localConfirm, 0, 16);
    }

    private byte[] buildPairingRequestPdu(SmpSession session) {
        byte[] pdu = new byte[7];
        pdu[0] = SmpConstants.PAIRING_REQUEST;  // 0x01 - opcode must be included!
        if (session.isInitiator()) {
            pdu[1] = (byte) session.localIoCap;
            pdu[2] = (byte) session.localOobFlag;
            pdu[3] = (byte) session.localAuthReq;
            pdu[4] = (byte) session.localMaxKeySize;
            pdu[5] = (byte) session.localInitKeyDist;
            pdu[6] = (byte) session.localRespKeyDist;
        } else {
            pdu[1] = (byte) session.peerIoCap;
            pdu[2] = (byte) session.peerOobFlag;
            pdu[3] = (byte) session.peerAuthReq;
            pdu[4] = (byte) session.peerMaxKeySize;
            pdu[5] = (byte) session.peerInitKeyDist;
            pdu[6] = (byte) session.peerRespKeyDist;
        }
        return pdu;
    }

    private byte[] buildPairingResponsePdu(SmpSession session) {
        byte[] pdu = new byte[7];
        pdu[0] = SmpConstants.PAIRING_RESPONSE;  // 0x02 - opcode must be included!
        if (session.isResponder()) {
            pdu[1] = (byte) session.localIoCap;
            pdu[2] = (byte) session.localOobFlag;
            pdu[3] = (byte) session.localAuthReq;
            pdu[4] = (byte) session.localMaxKeySize;
            pdu[5] = (byte) session.negotiatedInitKeyDist;
            pdu[6] = (byte) session.negotiatedRespKeyDist;
        } else {
            // When we're Initiator, use the actual values the peer sent in their Response
            pdu[1] = (byte) session.peerIoCap;
            pdu[2] = (byte) session.peerOobFlag;
            pdu[3] = (byte) session.peerAuthReq;
            pdu[4] = (byte) session.peerMaxKeySize;
            pdu[5] = (byte) session.peerInitKeyDist;   // Use peer's value, not negotiated!
            pdu[6] = (byte) session.peerRespKeyDist;   // Use peer's value, not negotiated!
        }
        return pdu;
    }

    private static String formatAddress(byte[] address) {
        return SmpConstants.formatAddress(address);
    }

    private static String bytesToHex(byte[] bytes) {
        return SmpConstants.bytesToHex(bytes);
    }

    // ==================== Closeable ====================

    @Override
    public void close() {
        if (!mClosed.compareAndSet(false, true)) {
            return;
        }

        CourierLogger.i(TAG, "Closing SmpManager");

        // Fail any active sessions
        for (SmpSession session : mSessions.values()) {
            if (!session.getState().isTerminal()) {
                session.setState(SmpState.FAILED);
                session.success = false;
                session.errorCode = ERR_UNSPECIFIED_REASON;
                session.errorMessage = "SmpManager closed";
                session.completionLatch.countDown();
            }
        }
        mSessions.clear();

        // Unregister listeners
        if (mHciManager != null) {
            mHciManager.removeListener(this);
        }
        mL2capManager.unregisterFixedChannelListener(SMP_CID, this);

        // Shutdown executor
        mExecutor.shutdown();
        try {
            if (!mExecutor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                mExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            mExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        mInitialized.set(false);
        CourierLogger.i(TAG, "SmpManager closed");
    }

    /**
     * Returns whether the manager is closed.
     *
     * @return true if closed
     */
    public boolean isClosed() {
        return mClosed.get();
    }

    /**
     * Returns whether the manager is initialized.
     *
     * @return true if initialized
     */
    public boolean isInitialized() {
        return mInitialized.get() && !mClosed.get();
    }
}