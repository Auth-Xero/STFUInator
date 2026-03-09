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
 * <li>Legacy Pairing with Just Works, Passkey Entry, and OOB</li>
 * <li>LE Secure Connections with Numeric Comparison</li>
 * <li>Automatic SC to Legacy fallback</li>
 * <li>Bonding database for reconnection</li>
 * <li>Full key distribution support</li>
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

    /**
     * Default timeout for synchronous pairing (ms).
     */
    private static final long DEFAULT_PAIRING_TIMEOUT_MS = 30000;

    /**
     * Delay between key distribution PDUs (ms).
     */
    private static final int KEY_DIST_DELAY_MS = 50;

    /**
     * Timeout for public key/DHKey generation (ms).
     */
    private static final int ECDH_TIMEOUT_MS = 5000;

    /**
     * Executor shutdown timeout (seconds).
     */
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

    /**
     * Active pairing sessions by connection handle.
     */
    private final Map<Integer, SmpSession> mSessions = new ConcurrentHashMap<>();

    /**
     * Bonding database by address string.
     */
    private final Map<String, BondingInfo> mBondingDatabase = new ConcurrentHashMap<>();

    /**
     * Cache of identity addresses by connection handle (survives session cleanup).
     */
    private final Map<Integer, byte[]> mIdentityAddressCache = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> mIdentityAddressTypeCache = new ConcurrentHashMap<>();

    /**
     * Derived BR/EDR link keys from CTKD by address string.
     */
    private final Map<String, byte[]> mDerivedLinkKeys = new ConcurrentHashMap<>();

    // ==================== Local Configuration ====================

    private volatile byte[] mLocalAddress = new byte[6];
    private volatile int mLocalAddressType = 0;
    private final byte[] mLocalIrk = new byte[16];
    private final byte[] mLocalCsrk = new byte[16];

    /**
     * Default IO capability.
     */
    private volatile int mDefaultIoCap = IO_CAP_NO_INPUT_NO_OUTPUT;

    /**
     * Default authentication requirements (bonding + CT2 for CTKD support).
     */
    private volatile int mDefaultAuthReq = AUTH_REQ_BONDING;

    /**
     * Default max encryption key size.
     */
    private volatile int mDefaultMaxKeySize = MAX_ENC_KEY_SIZE;

    /**
     * Whether to prefer Secure Connections.
     */
    private volatile boolean mPreferSecureConnections = false;

    // ==================== ECDH State ====================

    private volatile byte[] mLocalP256PublicKeyX = null;
    private volatile byte[] mLocalP256PublicKeyY = null;
    private volatile CountDownLatch mPublicKeyLatch = null;
    private volatile CountDownLatch mDhKeyLatch = null;
    private volatile byte[] mGeneratedDhKey = null;

    // Session-scoped ECDH tracking to prevent race conditions between
    // concurrent pairing attempts (e.g. auto-retry creating new sessions
    // while stale HCI completion events arrive from previous sessions).
    private volatile SmpSession mEcdhPendingSession = null;
    private volatile SmpSession mDhKeyPendingSession = null;
    private final Object mEcdhLock = new Object();
    private final Object mEncryptLock = new Object();

    // ==================== Constructor ====================

    /**
     * Creates a new SMP manager.
     *
     * @param l2capManager L2CAP manager for sending SMP PDUs
     * @param listener     listener for pairing events
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

        // Run crypto self-test to verify implementation
        if (!SmpCrypto.runSelfTest()) {
            CourierLogger.e(TAG, "CRITICAL: Crypto self-test failed!");
            mListener.onError("Crypto self-test failed - SMP pairing will not work correctly");
            // Continue anyway, but warn user
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

    public void setLocalAddress(byte[] address, int addressType) {
        Objects.requireNonNull(address, "address must not be null");
        if (address.length != 6) {
            throw new IllegalArgumentException("address must be 6 bytes");
        }
        mLocalAddress = Arrays.copyOf(address, 6);
        mLocalAddressType = addressType;
        CourierLogger.d(TAG, "Local address set: " + formatAddress(address) + " type=" + addressType);
    }

    public void setDefaultIoCapability(int ioCap) {
        mDefaultIoCap = ioCap;
    }

    public void setDefaultAuthReq(int authReq) {
        mDefaultAuthReq = authReq;
    }

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

    public void storeBondingInfo(BondingInfo info) {
        Objects.requireNonNull(info, "info must not be null");
        mBondingDatabase.put(info.getAddressString(), info);
        CourierLogger.i(TAG, "Stored bonding info for " + info.getAddressString());
    }

    public BondingInfo getBondingInfo(byte[] address) {
        return mBondingDatabase.get(formatAddress(address));
    }

    public boolean isBonded(byte[] address) {
        BondingInfo info = getBondingInfo(address);
        return info != null && info.hasLtk();
    }

    public Map<String, BondingInfo> getAllBondingInfo() {
        return Collections.unmodifiableMap(mBondingDatabase);
    }

    public BondingInfo removeBondingInfo(byte[] address) {
        return mBondingDatabase.remove(formatAddress(address));
    }

    public byte[] getDerivedLinkKey(byte[] address) {
        return mDerivedLinkKeys.get(formatAddress(address));
    }

    public byte[] getDerivedLinkKey(String addressString) {
        return mDerivedLinkKeys.get(addressString);
    }

    public boolean hasDerivedLinkKey(byte[] address) {
        return mDerivedLinkKeys.containsKey(formatAddress(address));
    }

    public void storeDerivedLinkKey(byte[] address, byte[] linkKey) {
        if (address != null && linkKey != null && linkKey.length == 16) {
            mDerivedLinkKeys.put(formatAddress(address), linkKey);
        }
    }

    public byte[] getIdentityAddress(int connectionHandle) {
        SmpSession session = mSessions.get(connectionHandle);
        if (session != null) {
            byte[] addr = session.getPeerIdentityAddressIfPresent();
            if (addr != null) return addr;
        }
        return mIdentityAddressCache.get(connectionHandle);
    }

    public int getIdentityAddressType(int connectionHandle) {
        SmpSession session = mSessions.get(connectionHandle);
        if (session != null) {
            byte[] addr = session.getPeerIdentityAddressIfPresent();
            if (addr != null) return session.peerIdentityAddressType;
        }
        Integer type = mIdentityAddressTypeCache.get(connectionHandle);
        return type != null ? type : -1;
    }

    public void clearIdentityAddressCache(int connectionHandle) {
        mIdentityAddressCache.remove(connectionHandle);
        mIdentityAddressTypeCache.remove(connectionHandle);
    }

    // ==================== Pairing Operations ====================

    public void initiatePairing(int connectionHandle, byte[] peerAddress, int peerAddressType,
                                ISmpCallback callback) {
        checkInitialized();
        Objects.requireNonNull(peerAddress, "peerAddress must not be null");

        String addrStr = formatAddress(peerAddress);
        mListener.onMessage("Initiating SMP pairing with " + addrStr);

        SmpSession existingSession = mSessions.get(connectionHandle);
        if (existingSession != null) {
            CourierLogger.w(TAG, "Cleaning up existing session for handle 0x" +
                    Integer.toHexString(connectionHandle));
            existingSession.completionLatch.countDown();
            mSessions.remove(connectionHandle);
        }

        SmpSession session = new SmpSession(connectionHandle, peerAddress, peerAddressType, SmpRole.INITIATOR);
        session.localIoCap = mDefaultIoCap;
        session.localAuthReq = mDefaultAuthReq;
        session.localMaxKeySize = mDefaultMaxKeySize;
        session.callback = callback;
        mSessions.put(connectionHandle, session);

        mListener.onPairingStarted(connectionHandle, peerAddress);
        mExecutor.execute(() -> sendPairingRequest(session));
    }

    public boolean initiatePairingSync(int connectionHandle, byte[] peerAddress, int peerAddressType,
                                       long timeoutMs) {
        checkInitialized();
        Objects.requireNonNull(peerAddress, "peerAddress must not be null");

        String addrStr = formatAddress(peerAddress);
        mListener.onMessage("Initiating SMP pairing (sync) with " + addrStr);

        SmpSession existingSession = mSessions.get(connectionHandle);
        if (existingSession != null) {
            CourierLogger.w(TAG, "Cleaning up existing session for handle 0x" +
                    Integer.toHexString(connectionHandle));
            existingSession.completionLatch.countDown();
            mSessions.remove(connectionHandle);
        }

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

    public void enterPasskey(int connectionHandle, int passkey) {
        SmpSession session = mSessions.get(connectionHandle);
        if (session == null) return;

        session.passkey = passkey;
        mListener.onMessage("Passkey entered: " + String.format("%06d", passkey));

        if (!session.useSecureConnections) {
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

    public SmpSession getSession(int connectionHandle) {
        return mSessions.get(connectionHandle);
    }

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
                session.callback.onPairingFailed(session.connectionHandle, ERR_UNSPECIFIED_REASON, reasonStr);
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

        // Update localRandom so sendPairingRandom() picks up the correct value
        System.arraycopy(nonce, 0, session.localRandom, 0, 16);

        byte r = (byte) (0x80 | bitValue);
        byte[] confirm;

        if (session.isInitiator()) {
            // Ca = f4(PKa, PKb, Na, r)
            confirm = SmpCrypto.f4(session.localPublicKeyX, session.peerPublicKeyX, session.na, r);
        } else {
            // Cb = f4(PKb, PKa, Nb, r)
            confirm = SmpCrypto.f4(session.localPublicKeyX, session.peerPublicKeyX, session.nb, r);
        }

        System.arraycopy(confirm, 0, session.localConfirm, 0, 16);
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

        boolean peerWantsSc = (session.peerAuthReq & AUTH_REQ_SC) != 0;
        boolean weWantSc = (session.localAuthReq & AUTH_REQ_SC) != 0;
        session.useSecureConnections = peerWantsSc && weWantSc;
        mListener.onMessage("Secure Connections: " + (session.useSecureConnections ? "Yes" : "No"));

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
            mExecutor.execute(() -> {
                if (generateLocalPublicKey(session)) {
                    sendPublicKey(session);
                    session.setState(SmpState.WAIT_PUBLIC_KEY);
                } else {
                    failPairing(session, ERR_UNSPECIFIED_REASON, "Failed to generate public key");
                }
            });
        } else {
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
                mListener.onMessage("Sending random (Na) immediately");
                sendPairingRandom(session);
                session.setState(SmpState.WAIT_RANDOM);
            } else {
                session.setState(SmpState.WAIT_RANDOM);
            }
        } else {
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
        byte[] preq = buildPairingRequestPdu(session);
        byte[] pres = buildPairingResponsePdu(session);
        int iat = session.isInitiator() ? mLocalAddressType : session.peerAddressType;
        int rat = session.isInitiator() ? session.peerAddressType : mLocalAddressType;
        byte[] ia = session.isInitiator() ? mLocalAddress : session.getPeerAddress();
        byte[] ra = session.isInitiator() ? session.getPeerAddress() : mLocalAddress;

        // SmpCrypto.c1 handles LE-to-BE conversion internally
        byte[] expectedConfirm = SmpCrypto.c1(session.tk, session.peerRandom, preq, pres, iat, rat, ia, ra);

        if (!Arrays.equals(expectedConfirm, session.peerConfirm)) {
            mListener.onMessage("Confirm value mismatch!");
            failPairing(session, ERR_CONFIRM_VALUE_FAILED, "Confirm mismatch");
            return;
        }

        mListener.onMessage("Confirm value verified");

        if (session.isResponder()) {
            sendPairingRandom(session);
        }

        byte[] r = new byte[16];
        if (session.isInitiator()) {
            System.arraycopy(session.peerRandom, 0, r, 0, 8);
            System.arraycopy(session.localRandom, 0, r, 8, 8);
        } else {
            System.arraycopy(session.localRandom, 0, r, 0, 8);
            System.arraycopy(session.peerRandom, 0, r, 8, 8);
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
            byte[] expectedConfirm;

            if (session.isInitiator()) {
                // Initiator verifies Cb = f4(PKb, PKa, Nb, 0)
                expectedConfirm = SmpCrypto.f4(session.peerPublicKeyX, session.localPublicKeyX, session.peerRandom, (byte) 0);
            } else {
                // Responder verifies Ca = f4(PKa, PKb, Na, 0)
                expectedConfirm = SmpCrypto.f4(session.peerPublicKeyX, session.localPublicKeyX, session.peerRandom, (byte) 0);
            }

            if (!Arrays.equals(expectedConfirm, session.peerConfirm)) {
                mListener.onMessage("SC Confirm value mismatch!");
                failPairing(session, ERR_CONFIRM_VALUE_FAILED, "Confirm mismatch");
                return;
            }

            if (session.isResponder()) {
                sendPairingRandom(session);
            }

            if (method == SmpPairingMethod.NUMERIC_COMPARISON) {
                // g2(PKa, PKb, Na, Nb)
                byte[] pkA = session.isInitiator() ? session.localPublicKeyX : session.peerPublicKeyX;
                byte[] pkB = session.isInitiator() ? session.peerPublicKeyX : session.localPublicKeyX;

                int numericValue = SmpCrypto.g2(pkA, pkB, session.na, session.nb);
                mListener.onMessage("Numeric comparison: " + String.format("%06d", numericValue));

                if (session.callback != null) {
                    session.callback.onNumericComparisonRequired(session.connectionHandle, numericValue);
                }
                mListener.onNumericComparisonRequired(session.connectionHandle, session.getPeerAddress(), numericValue);
                return;
            }

            if (session.dhKeyLatch != null) {
                try {
                    if (!session.dhKeyLatch.await(ECDH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        failPairing(session, ERR_UNSPECIFIED_REASON, "DHKey timeout");
                        return;
                    }
                    if (!session.dhKeyGenerated) {
                        failPairing(session, ERR_UNSPECIFIED_REASON, "DHKey generation failed");
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

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

        byte[] expectedConfirm;
        if (session.isInitiator()) {
            // Verify Cb = f4(PKb, PKa, Nb, r)
            expectedConfirm = SmpCrypto.f4(session.peerPublicKeyX, session.localPublicKeyX, session.peerRandom, r);
        } else {
            // Verify Ca = f4(PKa, PKb, Na, r)
            expectedConfirm = SmpCrypto.f4(session.peerPublicKeyX, session.localPublicKeyX, session.peerRandom, r);
        }

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

        // Mark session as failed FIRST so async operations can detect it
        session.setState(SmpState.FAILED);
        session.success = false;
        session.errorCode = reason;
        session.errorMessage = "Peer: " + reasonStr;

        // Cancel any pending ECDH operations for this session.
        // This prevents orphaned executor threads from writing stale results
        // into dead session state or signaling wrong latches.
        synchronized (mEcdhLock) {
            if (mEcdhPendingSession == session) {
                CourierLogger.d(TAG, "Cancelling pending public key generation for failed session");
                mEcdhPendingSession = null;
            }
            if (mDhKeyPendingSession == session) {
                CourierLogger.d(TAG, "Cancelling pending DH key generation for failed session");
                mDhKeyPendingSession = null;
            }
        }

        // Signal session-level DH key latch so async executor threads unblock
        CountDownLatch dhLatch = session.dhKeyLatch;
        if (dhLatch != null) {
            dhLatch.countDown();
        }

        mListener.onPairingFailed(session.connectionHandle, session.getPeerAddress(), reason, reasonStr);
        if (session.callback != null) {
            session.callback.onPairingFailed(session.connectionHandle, reason, "Peer: " + reasonStr);
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
                if (session.getState() == SmpState.FAILED) return;
                if (generateLocalPublicKey(session)) {
                    if (session.getState() == SmpState.FAILED) return;
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
            // Initiator path
            SmpPairingMethod method = session.getMethod();

            if (method == SmpPairingMethod.JUST_WORKS ||
                    method == SmpPairingMethod.NUMERIC_COMPARISON) {

                // Start DHKey generation asynchronously; we'll wait for it after
                // the confirm/random exchange when we actually need the result.
                session.dhKeyLatch = new CountDownLatch(1);
                session.dhKeyGenerated = false;
                mExecutor.execute(() -> {
                    if (session.getState() == SmpState.FAILED) {
                        session.dhKeyLatch.countDown();
                        return;
                    }
                    mListener.onMessage("Generating DH key (async)...");
                    if (generateDhKey(session)) {
                        if (session.getState() == SmpState.FAILED) {
                            session.dhKeyGenerated = false;
                        } else {
                            mListener.onMessage("DH key generated");
                            session.dhKeyGenerated = true;
                        }
                    } else {
                        mListener.onMessage("DH key generation failed");
                        session.dhKeyGenerated = false;
                    }
                    session.dhKeyLatch.countDown();
                });

                // Generate Na and compute confirm value immediately (don't wait for DHKey)
                mSecureRandom.nextBytes(session.na);
                System.arraycopy(session.na, 0, session.localRandom, 0, 16);

                // Ca = f4(PKax, PKbx, Na, 0)
                byte[] confirm = SmpCrypto.f4(session.localPublicKeyX, session.peerPublicKeyX, session.na, (byte) 0);
                System.arraycopy(confirm, 0, session.localConfirm, 0, 16);

                sendPairingConfirm(session);
                session.scInitiatorConfirmSent = true;
                session.setState(SmpState.WAIT_CONFIRM);
            } else {
                // Passkey entry - generate DH key first, then prompt user
                mExecutor.execute(() -> {
                    if (session.getState() == SmpState.FAILED) return;
                    if (generateDhKey(session)) {
                        if (session.getState() == SmpState.FAILED) return;
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

        byte[] a1 = new byte[7];
        byte[] a2 = new byte[7];
        a1[0] = (byte) mLocalAddressType;
        System.arraycopy(mLocalAddress, 0, a1, 1, 6);
        a2[0] = (byte) session.peerAddressType;
        System.arraycopy(session.getPeerAddress(), 0, a2, 1, 6);

        byte[] ioCap = new byte[3];
        ioCap[0] = (byte) session.peerAuthReq;
        ioCap[1] = (byte) session.peerOobFlag;
        ioCap[2] = (byte) session.peerIoCap;
        boolean isPasskeyMethod = session.getMethod().isPasskeyMethod();
        byte[] r = SmpCrypto.buildScR(session.passkey, isPasskeyMethod);

        // SmpCrypto.f6 handles LE-to-BE conversion internally
        byte[] expectedCheck;
        if (session.isInitiator()) {
            expectedCheck = SmpCrypto.f6(session.macKey, session.nb, session.na, r, ioCap, a2, a1);
        } else {
            expectedCheck = SmpCrypto.f6(session.macKey, session.na, session.nb, r, ioCap, a2, a1);
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
        byte[] identityAddr = Arrays.copyOf(session.peerIdentityAddress, 6);
        mIdentityAddressCache.put(session.connectionHandle, identityAddr);
        mIdentityAddressTypeCache.put(session.connectionHandle, session.peerIdentityAddressType);
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
            boolean ltk = (expectedKeys & KEY_DIST_ENC_KEY) == 0 || session.hasReceivedKey(KEY_DIST_ENC_KEY);
            boolean irk = (expectedKeys & KEY_DIST_ID_KEY) == 0 || session.hasReceivedKey(KEY_DIST_ID_KEY);
            boolean csrk = (expectedKeys & KEY_DIST_SIGN) == 0 || session.hasReceivedKey(KEY_DIST_SIGN);
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
            mListener.onMessage("Using cached P-256 public key");
            System.arraycopy(mLocalP256PublicKeyX, 0, session.localPublicKeyX, 0, 32);
            System.arraycopy(mLocalP256PublicKeyY, 0, session.localPublicKeyY, 0, 32);
            return true;
        }

        CourierLogger.d(TAG, "Requesting new public key from HCI");
        synchronized (mEcdhLock) {
            // Guard: abort if session was already torn down
            if (session.getState() == SmpState.FAILED) {
                CourierLogger.w(TAG, "generateLocalPublicKey: session already failed, aborting");
                return false;
            }
            mEcdhPendingSession = session;
            mPublicKeyLatch = new CountDownLatch(1);
        }
        byte[] cmd = HciCommands.leReadLocalP256PublicKey();
        mHciManager.sendCommand(cmd);
        try {
            if (mPublicKeyLatch.await(ECDH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                // Verify this completion was actually for our session
                if (session.getState() == SmpState.FAILED) {
                    CourierLogger.w(TAG, "generateLocalPublicKey: session failed while waiting");
                    return false;
                }
                if (mLocalP256PublicKeyX != null) {
                    System.arraycopy(mLocalP256PublicKeyX, 0, session.localPublicKeyX, 0, 32);
                    System.arraycopy(mLocalP256PublicKeyY, 0, session.localPublicKeyY, 0, 32);
                    return true;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (mEcdhLock) {
                if (mEcdhPendingSession == session) {
                    mEcdhPendingSession = null;
                }
            }
        }
        mListener.onMessage("Failed to generate local public key");
        return false;
    }

    private boolean generateDhKey(SmpSession session) {
        mListener.onMessage("Generating DH key...");
        synchronized (mEcdhLock) {
            if (session.getState() == SmpState.FAILED) {
                CourierLogger.w(TAG, "generateDhKey: session already failed, aborting");
                return false;
            }
            mDhKeyPendingSession = session;
            mDhKeyLatch = new CountDownLatch(1);
            mGeneratedDhKey = null;
        }
        // HCI expects Little Endian (as received from SMP)
        byte[] cmd = HciCommands.leGenerateDhKey(session.peerPublicKeyX, session.peerPublicKeyY);
        mHciManager.sendCommand(cmd);
        try {
            if (mDhKeyLatch.await(ECDH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                // Verify session is still valid
                if (session.getState() == SmpState.FAILED) {
                    CourierLogger.w(TAG, "generateDhKey: session failed while waiting");
                    return false;
                }
                if (mGeneratedDhKey != null) {
                    System.arraycopy(mGeneratedDhKey, 0, session.dhKey, 0, 32);
                    mListener.onMessage("DH key generated");
                    return true;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (mEcdhLock) {
                if (mDhKeyPendingSession == session) {
                    mDhKeyPendingSession = null;
                }
            }
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

        // f5(DHKey, Na, Nb, A1, A2) - SmpCrypto handles LE-to-BE conversion
        byte[][] keys = SmpCrypto.f5(session.dhKey, session.na, session.nb, a1, a2);
        System.arraycopy(keys[0], 0, session.macKey, 0, 16);
        System.arraycopy(keys[1], 0, session.ltk, 0, 16);

        byte[] ioCap = new byte[3];
        ioCap[0] = (byte) session.localAuthReq;
        ioCap[1] = (byte) session.localOobFlag;
        ioCap[2] = (byte) session.localIoCap;

        boolean isPasskeyMethod = session.getMethod().isPasskeyMethod();
        byte[] r = SmpCrypto.buildScR(session.passkey, isPasskeyMethod);

        // f6(MacKey, N1, N2, R, IOcap, A1, A2)
        byte[] dhKeyCheck;
        if (session.isInitiator()) {
            dhKeyCheck = SmpCrypto.f6(session.macKey, session.na, session.nb, r, ioCap, a1, a2);
        } else {
            dhKeyCheck = SmpCrypto.f6(session.macKey, session.nb, session.na, r, ioCap, a1, a2);
        }
        System.arraycopy(dhKeyCheck, 0, session.localDhKeyCheck, 0, 16);

        mListener.onMessage("SC keys calculated");
    }

    // ==================== Encryption ====================

    private volatile CountDownLatch mEncryptLatch = null;
    private volatile byte[] mEncryptedData = null;

    public byte[] hciLeEncrypt(byte[] key, byte[] plaintext) {
        if (key == null || key.length != 16 || plaintext == null || plaintext.length != 16) {
            CourierLogger.e(TAG, "hciLeEncrypt: Invalid parameters");
            return null;
        }
        // Serialize all encrypt operations: overlapping sessions must not
        // clobber each other's latch / result buffer.
        synchronized (mEncryptLock) {
            mEncryptLatch = new CountDownLatch(1);
            mEncryptedData = null;
            byte[] cmd = new byte[36];
            cmd[0] = 0x17;
            cmd[1] = 0x20;
            cmd[2] = 32;
            System.arraycopy(key, 0, cmd, 3, 16);
            System.arraycopy(plaintext, 0, cmd, 19, 16);
            mHciManager.sendCommand(cmd);
            try {
                if (mEncryptLatch.await(1000, TimeUnit.MILLISECONDS)) {
                    return mEncryptedData;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            CourierLogger.e(TAG, "hciLeEncrypt: Timeout waiting for response");
            return null;
        }
    }

    private void handleLeEncryptComplete(byte[] event) {
        if (event.length < 22) {
            if (mEncryptLatch != null) mEncryptLatch.countDown();
            return;
        }
        int status = event[5] & 0xFF;
        if (status != 0) {
            if (mEncryptLatch != null) mEncryptLatch.countDown();
            return;
        }
        mEncryptedData = new byte[16];
        System.arraycopy(event, 6, mEncryptedData, 0, 16);
        if (mEncryptLatch != null) mEncryptLatch.countDown();
    }

    private void startEncryptionWithStk(SmpSession session) {
        mListener.onMessage("Starting encryption with STK");
        ByteBuffer buffer = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) session.connectionHandle);
        buffer.put(new byte[8]);
        buffer.putShort((short) 0);
        buffer.put(session.stk);
        byte[] cmd = HciCommands.leStartEncryption(buffer.array());
        mHciManager.sendCommand(cmd);
    }

    private void startEncryptionWithLtk(SmpSession session) {
        mListener.onMessage("Starting encryption with LTK");
        ByteBuffer buffer = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) session.connectionHandle);
        if (session.useSecureConnections) {
            buffer.put(new byte[8]);
            buffer.putShort((short) 0);
        } else {
            buffer.put(session.rand);
            buffer.putShort((short) ((session.ediv[1] & 0xFF) << 8 | (session.ediv[0] & 0xFF)));
        }
        buffer.put(session.ltk);
        byte[] cmd = HciCommands.leStartEncryption(buffer.array());
        mHciManager.sendCommand(cmd);
    }

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
        } else if (eventCode == 0x0E && event.length >= 6) {
            int opcode = ((event[4] & 0xFF) << 8) | (event[3] & 0xFF);
            if (opcode == 0x2017) {
                handleLeEncryptComplete(event);
            }
        }
    }

    @Override public void onAclData(byte[] data) {}
    @Override public void onScoData(byte[] data) {}
    @Override public void onIsoData(byte[] data) {}
    @Override public void onError(String message) {
        CourierLogger.e(TAG, "HCI Error: " + message);
        mListener.onError(message);
    }
    @Override public void onMessage(String message) {
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
        int ediv = ((event[14] & 0xFF) << 8) | (event[13] & 0xFF);
        SmpSession session = mSessions.get(handle);
        byte[] ltk = null;
        if (session != null) {
            ltk = session.useSecureConnections ? Arrays.copyOf(session.ltk, 16) : Arrays.copyOf(session.stk, 16);
        } else {
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
        } else {
            byte[] cmd = HciCommands.leLongTermKeyRequestNegativeReply(handle);
            mHciManager.sendCommand(cmd);
        }
    }

    private void handleEncryptionChangeEvent(byte[] event) {
        if (event.length < 6) return;
        int status = event[2] & 0xFF;
        int handle = ((event[4] & 0xFF) << 8) | (event[3] & 0xFF);
        int enabled = event[5] & 0xFF;
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
                failPairing(session, ERR_UNSPECIFIED_REASON, "Encryption failed: status=0x" + Integer.toHexString(status));
            }
        }
    }

    private void handleEncryptionKeyRefreshEvent(byte[] event) {
        if (event.length < 5) return;
        int handle = ((event[4] & 0xFF) << 8) | (event[3] & 0xFF);
        mListener.onMessage("Encryption Key Refresh: handle=0x" + Integer.toHexString(handle));
    }

    private void handleLeReadLocalP256PublicKeyComplete(byte[] event) {
        if (event.length < 68) {
            CourierLogger.e(TAG, "Invalid P256 public key event length: " + event.length);
            if (mPublicKeyLatch != null) mPublicKeyLatch.countDown();
            return;
        }

        int status = event[3] & 0xFF;
        if (status != 0) {
            CourierLogger.e(TAG, "P256 public key generation failed: status=0x" + Integer.toHexString(status));
            if (mPublicKeyLatch != null) mPublicKeyLatch.countDown();
            return;
        }

        // Ignore stale completions from sessions that have already failed
        SmpSession pending = mEcdhPendingSession;
        if (pending != null && pending.getState() == SmpState.FAILED) {
            CourierLogger.w(TAG, "Ignoring stale public key for failed session handle=0x" +
                    Integer.toHexString(pending.connectionHandle));
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
            CourierLogger.w(TAG, "handleLeGenerateDhKeyComplete: invalid event length " + event.length);
            if (mDhKeyLatch != null) mDhKeyLatch.countDown();
            return;
        }
        int status = event[3] & 0xFF;
        if (status != 0) {
            CourierLogger.w(TAG, "handleLeGenerateDhKeyComplete: HCI status=0x" + Integer.toHexString(status));
            if (mDhKeyLatch != null) mDhKeyLatch.countDown();
            return;
        }

        // Check if there's a valid pending session for this completion
        SmpSession pending = mDhKeyPendingSession;
        if (pending != null && pending.getState() == SmpState.FAILED) {
            CourierLogger.w(TAG, "handleLeGenerateDhKeyComplete: ignoring stale DH key for " +
                    "failed session handle=0x" + Integer.toHexString(pending.connectionHandle));
            // Still signal latch so any blocked thread unblocks
            if (mDhKeyLatch != null) mDhKeyLatch.countDown();
            return;
        }

        mGeneratedDhKey = new byte[32];
        System.arraycopy(event, 4, mGeneratedDhKey, 0, 32);
        if (mDhKeyLatch != null) mDhKeyLatch.countDown();
    }

    private void completePairing(SmpSession session) {
        session.setState(SmpState.PAIRED);
        session.success = true;
        mListener.onMessage("Pairing complete!");
        byte[] identityAddr = session.getPeerIdentityAddressIfPresent();
        if (identityAddr != null) {
            mIdentityAddressCache.put(session.connectionHandle, identityAddr);
            mIdentityAddressTypeCache.put(session.connectionHandle, session.peerIdentityAddressType);
        }
        BondingInfo bondingInfo = buildBondingInfo(session);
        storeBondingInfo(bondingInfo);

        boolean localCt2 = (session.localAuthReq & AUTH_REQ_CT2) != 0;
        boolean peerCt2 = (session.peerAuthReq & AUTH_REQ_CT2) != 0;
        if (localCt2 || peerCt2) {
            byte[] ltkForCtkd = session.useSecureConnections ? session.ltk : (session.hasReceivedKey(KEY_DIST_ENC_KEY) ? session.peerLtk : session.stk);
            if (ltkForCtkd != null && !isZeroKey(ltkForCtkd)) {
                try {
                    byte[] derivedLinkKey = SmpCrypto.deriveBrEdrLinkKey(ltkForCtkd, session.useSecureConnections);
                    if (identityAddr != null) mDerivedLinkKeys.put(formatAddress(identityAddr), derivedLinkKey);
                    mDerivedLinkKeys.put(session.getPeerAddressString(), derivedLinkKey);
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

    private boolean isZeroKey(byte[] key) {
        for (byte b : key) if (b != 0) return false;
        return true;
    }

    private void failPairing(SmpSession session, int errorCode, String reason) {
        if (session.getState() == SmpState.FAILED) return;
        session.setState(SmpState.FAILED);
        session.success = false;
        session.errorCode = errorCode;
        session.errorMessage = reason;

        // Cancel any pending ECDH operations for this session
        synchronized (mEcdhLock) {
            if (mEcdhPendingSession == session) {
                mEcdhPendingSession = null;
            }
            if (mDhKeyPendingSession == session) {
                mDhKeyPendingSession = null;
            }
        }
        // Signal session-level DH key latch so async threads unblock
        CountDownLatch dhLatch = session.dhKeyLatch;
        if (dhLatch != null) {
            dhLatch.countDown();
        }

        mListener.onMessage("Pairing failed: " + reason);
        sendPairingFailed(session, errorCode);
        mListener.onPairingFailed(session.connectionHandle, session.getPeerAddress(), errorCode, reason);
        if (session.callback != null) {
            session.callback.onPairingFailed(session.connectionHandle, errorCode, reason);
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
            builder.ltk(session.peerLtk).ediv(session.peerEdiv).rand(session.peerRand);
        }
        if (session.hasReceivedKey(KEY_DIST_ID_KEY)) {
            builder.irk(session.peerIrk);
            byte[] identAddr = session.getPeerIdentityAddressIfPresent();
            if (identAddr != null) builder.identityAddress(identAddr, session.peerIdentityAddressType);
        }
        if (session.hasReceivedKey(KEY_DIST_SIGN)) {
            builder.csrk(session.peerCsrk);
        }
        return builder.build();
    }

    private SmpPairingMethod determinePairingMethod(SmpSession session) {
        boolean mitm = (session.localAuthReq & AUTH_REQ_MITM) != 0 || (session.peerAuthReq & AUTH_REQ_MITM) != 0;
        if (session.localOobFlag == OOB_AUTH_DATA_PRESENT && session.peerOobFlag == OOB_AUTH_DATA_PRESENT) {
            return session.useSecureConnections ? SmpPairingMethod.OOB_SC : SmpPairingMethod.OOB_LEGACY;
        }
        if (!mitm) return SmpPairingMethod.JUST_WORKS;
        int initIoCap = session.isInitiator() ? session.localIoCap : session.peerIoCap;
        int respIoCap = session.isInitiator() ? session.peerIoCap : session.localIoCap;
        return getMethodFromIoCapabilities(initIoCap, respIoCap, session.useSecureConnections);
    }

    private SmpPairingMethod getMethodFromIoCapabilities(int initIoCap, int respIoCap, boolean sc) {
        if (initIoCap == IO_CAP_NO_INPUT_NO_OUTPUT || respIoCap == IO_CAP_NO_INPUT_NO_OUTPUT) return SmpPairingMethod.JUST_WORKS;
        if (initIoCap == IO_CAP_DISPLAY_ONLY) return (respIoCap == IO_CAP_DISPLAY_ONLY || respIoCap == IO_CAP_DISPLAY_YES_NO) ? SmpPairingMethod.JUST_WORKS : SmpPairingMethod.PASSKEY_ENTRY_INITIATOR_DISPLAYS;
        if (initIoCap == IO_CAP_DISPLAY_YES_NO) {
            if (respIoCap == IO_CAP_DISPLAY_ONLY) return SmpPairingMethod.JUST_WORKS;
            if (respIoCap == IO_CAP_DISPLAY_YES_NO) return sc ? SmpPairingMethod.NUMERIC_COMPARISON : SmpPairingMethod.JUST_WORKS;
            return sc ? SmpPairingMethod.NUMERIC_COMPARISON : SmpPairingMethod.PASSKEY_ENTRY_INITIATOR_DISPLAYS;
        }
        if (initIoCap == IO_CAP_KEYBOARD_ONLY) return respIoCap == IO_CAP_KEYBOARD_ONLY ? SmpPairingMethod.PASSKEY_ENTRY_BOTH_INPUT : SmpPairingMethod.PASSKEY_ENTRY_RESPONDER_DISPLAYS;
        if (initIoCap == IO_CAP_KEYBOARD_DISPLAY) {
            if (respIoCap == IO_CAP_DISPLAY_ONLY) return SmpPairingMethod.PASSKEY_ENTRY_RESPONDER_DISPLAYS;
            if (respIoCap == IO_CAP_DISPLAY_YES_NO) return sc ? SmpPairingMethod.NUMERIC_COMPARISON : SmpPairingMethod.PASSKEY_ENTRY_RESPONDER_DISPLAYS;
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
        // SmpCrypto.c1 handles LE-to-BE conversion internally
        byte[] confirm = SmpCrypto.c1(session.tk, session.localRandom, preq, pres, iat, rat, ia, ra);
        System.arraycopy(confirm, 0, session.localConfirm, 0, 16);
    }

    private byte[] buildPairingRequestPdu(SmpSession session) {
        byte[] pdu = new byte[7];
        pdu[0] = SmpConstants.PAIRING_REQUEST;
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
        pdu[0] = SmpConstants.PAIRING_RESPONSE;
        if (session.isResponder()) {
            pdu[1] = (byte) session.localIoCap;
            pdu[2] = (byte) session.localOobFlag;
            pdu[3] = (byte) session.localAuthReq;
            pdu[4] = (byte) session.localMaxKeySize;
            pdu[5] = (byte) session.negotiatedInitKeyDist;
            pdu[6] = (byte) session.negotiatedRespKeyDist;
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

    private static String formatAddress(byte[] address) { return SmpConstants.formatAddress(address); }
    private static String bytesToHex(byte[] bytes) { return SmpConstants.bytesToHex(bytes); }

    @Override
    public void close() {
        if (!mClosed.compareAndSet(false, true)) return;
        CourierLogger.i(TAG, "Closing SmpManager");
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
        if (mHciManager != null) mHciManager.removeListener(this);
        mL2capManager.unregisterFixedChannelListener(SMP_CID, this);
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

    public boolean isClosed() { return mClosed.get(); }
    public int getDefaultAuthReq() { return mDefaultAuthReq; }
    public int getDefaultIoCapability() { return mDefaultIoCap; }
    public boolean isSecureConnectionsPreferred() { return mPreferSecureConnections; }
    public void clearEcdhCache() {
        synchronized (mEcdhLock) {
            mLocalP256PublicKeyX = null;
            mLocalP256PublicKeyY = null;
            mGeneratedDhKey = null;
            mEcdhPendingSession = null;
            mDhKeyPendingSession = null;
            // Signal any blocked threads so they can exit cleanly
            CountDownLatch pkLatch = mPublicKeyLatch;
            if (pkLatch != null) pkLatch.countDown();
            CountDownLatch dhLatch = mDhKeyLatch;
            if (dhLatch != null) dhLatch.countDown();
        }
    }
    public boolean isInitialized() { return mInitialized.get() && !mClosed.get(); }
}