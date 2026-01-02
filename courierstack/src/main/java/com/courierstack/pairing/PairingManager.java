package com.courierstack.pairing;

import com.courierstack.core.CourierLogger;
import com.courierstack.hci.HciCommandManager;
import com.courierstack.hci.HciCommands;
import com.courierstack.hci.HciErrorCode;
import com.courierstack.hci.IHciCommandListener;
import com.courierstack.l2cap.AclConnection;
import com.courierstack.l2cap.IL2capConnectionCallback;
import com.courierstack.l2cap.IL2capListener;
import com.courierstack.l2cap.L2capChannel;
import com.courierstack.l2cap.L2capManager;
import com.courierstack.smp.SmpManager;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BR/EDR Secure Simple Pairing (SSP) Manager.
 *
 * <p>Implements authentication and pairing per Bluetooth Core Spec v5.3,
 * Vol 2, Part C (Link Manager Protocol), Section 4.2.
 *
 * <p>Supports: IO Capability Exchange, Numeric Comparison, Passkey Entry,
 * Out of Band (OOB), and Legacy PIN pairing.
 *
 * <p>Thread Safety: This class is thread-safe. All public methods can be called
 * from any thread. Callbacks are dispatched on the executor thread.
 *
 * @see IPairingListener
 * @see BondingInfo
 */
public class PairingManager implements IL2capListener, IHciCommandListener, Closeable {

    private static final String TAG = "PairingManager";

    /** HCI Event code for PIN_Code_Request. */
    private static final int EVT_PIN_CODE_REQUEST = 0x16;

    /** Executor shutdown timeout (seconds). */
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;

    // ==================== Dependencies ====================

    private final L2capManager mL2capManager;
    private final HciCommandManager mHciManager;
    private final IPairingListener mListener;
    private final ExecutorService mExecutor;
    private final SecureRandom mSecureRandom;
    private final CopyOnWriteArrayList<IPairingListener> mAdditionalListeners;

    /** SmpManager for CTKD-derived link keys. */
    private volatile SmpManager mSmpManager;

    // ==================== State ====================

    private final AtomicBoolean mInitialized = new AtomicBoolean(false);
    private final AtomicBoolean mClosed = new AtomicBoolean(false);

    /** Active pairing sessions by connection handle. */
    private final Map<Integer, PairingSession> mSessions = new ConcurrentHashMap<>();

    /** Bonding database by address string. */
    private final Map<String, BondingInfo> mBondingDatabase = new ConcurrentHashMap<>();

    // ==================== Configuration ====================

    private volatile int mDefaultIoCap = PairingConstants.IO_CAP_DISPLAY_YES_NO;
    private volatile int mDefaultAuthReq = PairingConstants.AUTH_REQ_MITM_GENERAL_BONDING;
    private volatile boolean mAutoAccept = false;

    /** Default PIN for legacy pairing. */
    private volatile String mDefaultPin = "0000";

    /** Force legacy PIN pairing by rejecting SSP. */
    private volatile boolean mForceLegacyPairing = false;

    // ==================== Constructor ====================

    /**
     * Creates a new pairing manager.
     *
     * @param l2capManager L2CAP manager for connections
     * @param listener     event listener
     * @throws NullPointerException if l2capManager or listener is null
     */
    public PairingManager(L2capManager l2capManager, IPairingListener listener) {
        mL2capManager = Objects.requireNonNull(l2capManager, "l2capManager must not be null");
        mListener = Objects.requireNonNull(listener, "listener must not be null");
        mHciManager = l2capManager.getHciManager();
        mExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "PairingManager-Worker");
            t.setDaemon(true);
            return t;
        });
        mSecureRandom = new SecureRandom();
        mAdditionalListeners = new CopyOnWriteArrayList<>();
    }

    // ==================== Initialization ====================

    /**
     * Initializes the pairing manager.
     *
     * @return true if initialization succeeded
     */
    public boolean initialize() {
        if (mClosed.get()) {
            mListener.onError("Cannot initialize - already closed");
            return false;
        }

        if (mInitialized.getAndSet(true)) {
            return true;
        }

        mL2capManager.addListener(this);
        if (mHciManager != null) {
            mHciManager.addListener(this);
        }

        mListener.onMessage("Pairing Manager initialized");
        return true;
    }

    private void checkInitialized() {
        if (!mInitialized.get()) {
            throw new IllegalStateException("PairingManager not initialized");
        }
        if (mClosed.get()) {
            throw new IllegalStateException("PairingManager is closed");
        }
    }

    // ==================== Configuration ====================

    /**
     * Enables Simple Secure Pairing on the controller.
     *
     * <p>Must be called for SSP to work.
     */
    public void enableSsp() {
        if (mHciManager == null) {
            mListener.onError("HCI manager not available for SSP");
            return;
        }

        // Log current settings
        String ioCap = PairingConstants.getIoCapabilityString(mDefaultIoCap);
        String authReq = PairingConstants.getAuthReqString(mDefaultAuthReq);
        mListener.onMessage("SSP config: IoCap=" + ioCap + ", AuthReq=" + authReq +
                (mAutoAccept ? " (AutoAccept=true)" : ""));

        // Set Event Mask to include all pairing events
        // Bits needed:
        //   Bit 17: Link Key Request (0x17)
        //   Bit 22: PIN Code Request (0x16)
        //   Bit 24: Link Key Notification (0x18)
        //   Bit 49: IO Capability Request (0x31)
        //   Bit 50: IO Capability Response (0x32)
        //   Bit 51: User Confirmation Request (0x33)
        //   Bit 52: User Passkey Request (0x34)
        //   Bit 53: Remote OOB Data Request (0x35)
        //   Bit 54: Simple Pairing Complete (0x36)
        //   Bit 55: User Passkey Notification (0x3B)
        // Use 0xFFFFFFFFFFFFFFFF to enable all events
        long eventMask = 0xFFFFFFFFFFFFFFFFL;
        byte[] maskCmd = HciCommands.setEventMask(eventMask);
        byte[] maskResp = mHciManager.sendCommandSync(maskCmd);
        if (maskResp != null && maskResp.length >= 6 && maskResp[5] == 0x00) {
            mListener.onMessage("Event mask configured for SSP events");
        } else {
            mListener.onError("Warning: Failed to set event mask");
        }

        // Skip SSP enable when forcing legacy PIN pairing
        if (mForceLegacyPairing) {
            mListener.onMessage("Legacy PIN pairing mode - SSP disabled");
            // Explicitly disable SSP to ensure PIN fallback
            byte[] cmd = HciCommands.writeSimplePairingMode(false);
            mHciManager.sendCommand(cmd);
        } else {
            // Send Write_Simple_Pairing_Mode command
            byte[] cmd = HciCommands.writeSimplePairingMode(true);
            byte[] resp = mHciManager.sendCommandSync(cmd);
            if (resp != null && resp.length >= 6 && resp[5] == 0x00) {
                mListener.onMessage("Simple Secure Pairing enabled on controller");
            } else {
                mListener.onError("Failed to enable SSP on controller");
            }
        }

        // Send Write_Authentication_Enable command
        byte[] authEnableCmd = HciCommands.writeAuthenticationEnable(true);
        mHciManager.sendCommand(authEnableCmd);
        mListener.onMessage("Authentication enable set");
    }

    /**
     * Sets the default IO capability for pairing.
     *
     * @param ioCap IO capability value
     */
    public void setIoCapability(int ioCap) {
        mDefaultIoCap = ioCap;
    }

    /**
     * Sets the default authentication requirements.
     *
     * @param authReq authentication requirements flags
     */
    public void setAuthRequirements(int authReq) {
        mDefaultAuthReq = authReq;
    }

    /**
     * Sets whether to auto-accept pairing requests.
     *
     * @param autoAccept true to auto-accept
     */
    public void setAutoAccept(boolean autoAccept) {
        mAutoAccept = autoAccept;
    }

    /**
     * Sets the SmpManager for CTKD-derived link key lookup.
     *
     * <p>When set, the PairingManager will check for CTKD-derived link keys
     * from LE pairing before falling back to SSP pairing.
     *
     * @param smpManager the SmpManager instance
     */
    public void setSmpManager(SmpManager smpManager) {
        mSmpManager = smpManager;
    }

    /**
     * Sets whether to force legacy PIN pairing by rejecting SSP.
     *
     * <p>When enabled, IO_Capability_Request will be rejected with a negative reply,
     * causing the controller to fall back to PIN pairing.
     *
     * @param force true to force legacy PIN pairing
     */
    public void setForceLegacyPairing(boolean force) {
        mForceLegacyPairing = force;
    }

    /**
     * Sets default pairing mode for auto-accept.
     *
     * @param mode pairing mode
     */
    public void setDefaultPairingMode(PairingMode mode) {
        switch (mode) {
            case JUST_WORKS:
                mDefaultIoCap = PairingConstants.IO_CAP_NO_INPUT_NO_OUTPUT;
                mDefaultAuthReq = PairingConstants.AUTH_REQ_DEDICATED_BONDING;
                mAutoAccept = true;
                break;
            case NUMERIC_COMPARISON:
                mDefaultIoCap = PairingConstants.IO_CAP_DISPLAY_YES_NO;
                mDefaultAuthReq = PairingConstants.AUTH_REQ_MITM_GENERAL_BONDING;
                mAutoAccept = false;
                break;
            case PASSKEY_ENTRY:
                mDefaultIoCap = PairingConstants.IO_CAP_KEYBOARD_ONLY;
                mDefaultAuthReq = PairingConstants.AUTH_REQ_MITM_GENERAL_BONDING;
                mAutoAccept = false;
                break;
            default:
                break;
        }
    }

    // ==================== Listener Management ====================

    /**
     * Adds a pairing event listener.
     *
     * @param listener listener to add
     */
    public void addListener(IPairingListener listener) {
        if (listener != null && !mAdditionalListeners.contains(listener)) {
            mAdditionalListeners.add(listener);
        }
    }

    /**
     * Removes a pairing event listener.
     *
     * @param listener listener to remove
     */
    public void removeListener(IPairingListener listener) {
        mAdditionalListeners.remove(listener);
    }

    // ==================== Link Key Management ====================

    /**
     * Stores a link key for a device (e.g., derived via CTKD from LE pairing).
     *
     * <p>This allows pre-storing link keys so that when the device requests
     * authentication, we can respond with the link key instead of triggering
     * a new pairing process.
     *
     * @param address peer Bluetooth address (6 bytes)
     * @param linkKey 16-byte link key
     */
    public void storeLinkKey(byte[] address, byte[] linkKey) {
        if (address == null || address.length != 6) return;
        if (linkKey == null || linkKey.length != 16) return;

        String addrStr = PairingConstants.formatAddress(address);
        BondingInfo info = BondingInfo.builder()
                .address(address)
                .linkKey(linkKey)
                .linkKeyType(PairingConstants.KEY_TYPE_UNAUTHENTICATED_P256)  // CTKD-derived key
                .authenticated(false)  // CTKD with Just Works = unauthenticated
                .build();
        mBondingDatabase.put(addrStr, info);
        mListener.onMessage("Stored CTKD-derived link key for " + addrStr);
    }

    /**
     * Gets the stored link key for a device.
     *
     * @param address peer Bluetooth address
     * @return link key or null if not stored
     */
    public byte[] getLinkKey(byte[] address) {
        if (address == null) return null;
        BondingInfo info = mBondingDatabase.get(PairingConstants.formatAddress(address));
        return info != null ? info.getLinkKey() : null;
    }

    /**
     * Checks if we have a link key for a device.
     *
     * @param address peer Bluetooth address
     * @return true if a link key is stored
     */
    public boolean hasLinkKey(byte[] address) {
        if (address == null) return false;
        return mBondingDatabase.containsKey(PairingConstants.formatAddress(address));
    }

    // ==================== Pairing Operations ====================

    /**
     * Initiates pairing with a remote device.
     *
     * @param address  peer Bluetooth address (6 bytes)
     * @param callback callback for pairing result
     */
    public void initiatePairing(byte[] address, IPairingCallback callback) {
        checkInitialized();
        Objects.requireNonNull(address, "address must not be null");

        String addrStr = PairingConstants.formatAddress(address);
        mListener.onMessage("Initiating pairing with " + addrStr);

        mL2capManager.createConnection(address, new IL2capConnectionCallback() {
            @Override
            public void onSuccess(L2capChannel channel) {
                int handle = channel.connection.handle;
                PairingSession session = new PairingSession(handle, address);
                session.callback = callback;
                session.localIoCap = mDefaultIoCap;
                session.localAuthReq = mDefaultAuthReq;
                mSessions.put(handle, session);

                if (mHciManager != null) {
                    byte[] cmd = HciCommands.authenticationRequested(handle);
                    mHciManager.sendCommand(cmd);
                }
                session.setState(PairingState.AUTHENTICATING);
                notifyPairingStarted(handle, address);
            }

            @Override
            public void onFailure(String reason) {
                if (callback != null) {
                    callback.onPairingComplete(false, null);
                }
                mListener.onError("Pairing connection failed: " + reason);
            }
        });
    }

    /**
     * Requests authentication on an existing ACL connection.
     *
     * <p>Use this when you already have an ACL connection and want to pair.
     *
     * @param address peer Bluetooth address (6 bytes)
     * @param connectionHandle existing ACL connection handle
     * @param callback callback for pairing result
     */
    public void requestAuthentication(byte[] address, int connectionHandle, IPairingCallback callback) {
        checkInitialized();
        Objects.requireNonNull(address, "address must not be null");

        String addrStr = PairingConstants.formatAddress(address);
        mListener.onMessage("Requesting authentication for " + addrStr + " on handle=0x" +
                Integer.toHexString(connectionHandle));

        // Create or update session
        PairingSession session = mSessions.computeIfAbsent(connectionHandle,
                h -> new PairingSession(h, address));
        session.callback = callback;
        session.localIoCap = mDefaultIoCap;
        session.localAuthReq = mDefaultAuthReq;
        session.setState(PairingState.AUTHENTICATING);

        // Send Authentication_Requested command
        if (mHciManager != null) {
            byte[] cmd = HciCommands.authenticationRequested(connectionHandle);
            mHciManager.sendCommand(cmd);
        }

        notifyPairingStarted(connectionHandle, address);
    }

    /**
     * Synchronously pairs with a device on an existing connection.
     *
     * <p>This method blocks until pairing completes or times out.
     * It is designed for use cases where you already have an ACL connection
     * and want to authenticate before accessing services.
     *
     * @param address peer Bluetooth address (6 bytes)
     * @param connectionHandle existing ACL connection handle
     * @param timeoutMs maximum time to wait for pairing (milliseconds)
     * @return true if pairing succeeded, false otherwise
     */
    public boolean pairDeviceSync(byte[] address, int connectionHandle, long timeoutMs) {
        checkInitialized();
        Objects.requireNonNull(address, "address must not be null");

        String addrStr = PairingConstants.formatAddress(address);
        mListener.onMessage("Starting sync pairing for " + addrStr + " on handle=0x" +
                Integer.toHexString(connectionHandle));

        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean success = new java.util.concurrent.atomic.AtomicBoolean(false);

        // Create callback for pairing result
        IPairingCallback callback = new IPairingCallback() {
            @Override
            public void onPairingComplete(boolean pairingSuccess, BondingInfo bondingInfo) {
                success.set(pairingSuccess);
                latch.countDown();
            }

            @Override
            public void onPairingProgress(byte[] addr, PairingState state, String message) {
                mListener.onMessage("Pairing progress: " + state + " - " + message);
            }
        };

        // Create or update session
        PairingSession session = mSessions.computeIfAbsent(connectionHandle,
                h -> new PairingSession(h, address));
        session.callback = callback;
        session.localIoCap = mDefaultIoCap;
        session.localAuthReq = mDefaultAuthReq;
        session.setState(PairingState.AUTHENTICATING);

        // Send Authentication_Requested command
        if (mHciManager != null) {
            byte[] cmd = HciCommands.authenticationRequested(connectionHandle);
            mHciManager.sendCommand(cmd);
            mListener.onMessage("Authentication_Requested sent");
        } else {
            mListener.onError("HCI manager not available for authentication");
            return false;
        }

        notifyPairingStarted(connectionHandle, address);

        // Wait for pairing to complete
        try {
            boolean completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!completed) {
                mListener.onError("Pairing timed out after " + timeoutMs + "ms");
                session.setState(PairingState.FAILED);
                mSessions.remove(connectionHandle);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            mListener.onError("Pairing interrupted");
            session.setState(PairingState.FAILED);
            mSessions.remove(connectionHandle);
            return false;
        }

        boolean result = success.get();
        mListener.onMessage("Sync pairing " + (result ? "succeeded" : "failed"));
        return result;
    }

    /**
     * Responds to IO capability request.
     *
     * @param handle connection handle
     * @param accept true to accept, false to reject
     */
    public void respondToIoCapability(int handle, boolean accept) {
        PairingSession session = mSessions.get(handle);
        if (session == null) return;

        if (!accept) {
            if (mHciManager != null) {
                byte[] cmd = HciCommands.ioCapabilityRequestNegativeReply(
                        session.getPeerAddress(), (byte) PairingConstants.ERR_PAIRING_NOT_ALLOWED);
                mHciManager.sendCommand(cmd);
            }
            session.setState(PairingState.FAILED);
            notifyPairingFailed(handle, session.getPeerAddress(),
                    PairingConstants.ERR_PAIRING_NOT_ALLOWED, "User rejected");
            return;
        }

        session.localIoCap = mDefaultIoCap;
        session.localAuthReq = mDefaultAuthReq;
        if (mHciManager != null) {
            byte[] cmd = HciCommands.ioCapabilityRequestReply(
                    session.getPeerAddress(),
                    (byte) session.localIoCap,
                    (byte) 0,
                    (byte) session.localAuthReq);
            mHciManager.sendCommand(cmd);
        }
        session.setState(PairingState.IO_CAP_EXCHANGED);
    }

    /**
     * Confirms or rejects numeric comparison value.
     *
     * @param handle connection handle
     * @param accept true to confirm, false to reject
     */
    public void confirmNumericComparison(int handle, boolean accept) {
        PairingSession session = mSessions.get(handle);
        if (session == null) return;

        if (mHciManager != null) {
            byte[] cmd;
            if (accept) {
                cmd = HciCommands.userConfirmationRequestReply(session.getPeerAddress());
            } else {
                cmd = HciCommands.userConfirmationRequestNegativeReply(session.getPeerAddress());
            }
            mHciManager.sendCommand(cmd);
        }
        session.setState(accept ? PairingState.CONFIRMED : PairingState.FAILED);
    }

    /**
     * Enters passkey for Passkey Entry model.
     *
     * @param handle  connection handle
     * @param passkey 6-digit passkey (0-999999)
     */
    public void enterPasskey(int handle, int passkey) {
        PairingSession session = mSessions.get(handle);
        if (session == null) return;

        session.passkey = passkey;
        if (mHciManager != null) {
            byte[] cmd = HciCommands.userPasskeyRequestReply(session.getPeerAddress(), passkey);
            mHciManager.sendCommand(cmd);
        }
        session.setState(PairingState.KEY_ENTERED);
    }

    // ==================== Bonding Database ====================

    /**
     * Returns stored bonding info for address, or null.
     *
     * @param address peer address
     * @return bonding info or null
     */
    public BondingInfo getBondingInfo(byte[] address) {
        return mBondingDatabase.get(PairingConstants.formatAddress(address));
    }

    /**
     * Stores bonding information.
     *
     * @param info bonding info to store
     */
    public void storeBondingInfo(BondingInfo info) {
        Objects.requireNonNull(info, "info must not be null");
        mBondingDatabase.put(info.getAddressString(), info);
    }

    /**
     * Removes stored bonding info for address.
     *
     * @param address peer address
     * @return removed bonding info or null
     */
    public BondingInfo removeBondingInfo(byte[] address) {
        return mBondingDatabase.remove(PairingConstants.formatAddress(address));
    }

    /**
     * Returns all stored bonds.
     *
     * @return unmodifiable map of bonding info
     */
    public Map<String, BondingInfo> getAllBonds() {
        return Collections.unmodifiableMap(mBondingDatabase);
    }

    /**
     * Gets the session for a connection handle.
     *
     * @param connectionHandle connection handle
     * @return session or null
     */
    public PairingSession getSession(int connectionHandle) {
        return mSessions.get(connectionHandle);
    }

    // ==================== HCI Event Handling ====================

    @Override
    public void onEvent(byte[] event) {
        if (event == null || event.length < 3) return;

        int eventCode = event[0] & 0xFF;
        byte[] data = new byte[event.length - 2];
        System.arraycopy(event, 2, data, 0, data.length);

        // Log all events for debugging (SSP events and Authentication/Encryption)
        if ((eventCode >= 0x06 && eventCode <= 0x08) ||  // Auth Complete, Change Conn Link Key, Encryption Change
                (eventCode >= 0x17 && eventCode <= 0x18) ||  // Link Key Request/Notification
                (eventCode >= 0x31 && eventCode <= 0x36) ||  // SSP events
                eventCode == 0x3B) {                          // User Passkey Notification
            CourierLogger.i(TAG, "Pairing Event: 0x" + Integer.toHexString(eventCode) +
                    " (" + getEventName(eventCode) + ") len=" + data.length);
        }

        switch (eventCode) {
            case PairingConstants.EVT_IO_CAPABILITY_REQUEST:
                handleIoCapabilityRequest(data);
                break;
            case PairingConstants.EVT_IO_CAPABILITY_RESPONSE:
                handleIoCapabilityResponse(data);
                break;
            case PairingConstants.EVT_USER_CONFIRMATION_REQUEST:
                handleUserConfirmationRequest(data);
                break;
            case PairingConstants.EVT_USER_PASSKEY_REQUEST:
                handleUserPasskeyRequest(data);
                break;
            case PairingConstants.EVT_USER_PASSKEY_NOTIFICATION:
                handleUserPasskeyNotification(data);
                break;
            case PairingConstants.EVT_AUTHENTICATION_COMPLETE:
                handleAuthenticationComplete(data);
                break;
            case PairingConstants.EVT_ENCRYPTION_CHANGE:
                handleEncryptionChange(data);
                break;
            case PairingConstants.EVT_LINK_KEY_REQUEST:
                handleLinkKeyRequest(data);
                break;
            case PairingConstants.EVT_LINK_KEY_NOTIFICATION:
                handleLinkKeyNotification(data);
                break;
            case PairingConstants.EVT_SIMPLE_PAIRING_COMPLETE:
                handleSimplePairingComplete(data);
                break;
            case EVT_PIN_CODE_REQUEST:
                handlePinCodeRequest(data);
                break;
        }
    }

    private void handleIoCapabilityRequest(byte[] data) {
        if (data.length < 6) return;
        byte[] addr = Arrays.copyOfRange(data, 0, 6);
        String addrStr = PairingConstants.formatAddress(addr);

        Integer handle = findHandleByAddress(addr);
        if (handle == null) {
            AclConnection conn = findConnectionByAddress(addr);
            if (conn == null) return;
            handle = conn.handle;
        }

        PairingSession session = mSessions.computeIfAbsent(handle,
                h -> new PairingSession(h, addr));
        session.setState(PairingState.IO_CAP_REQUEST);
        session.localIoCap = mDefaultIoCap;
        session.localAuthReq = mDefaultAuthReq;
        mListener.onMessage("IO Capability Request from " + addrStr);

        // Force legacy PIN pairing by rejecting SSP
        if (mForceLegacyPairing) {
            mListener.onMessage("Rejecting SSP to force legacy PIN pairing");
            if (mHciManager != null) {
                // Reason 0x18 = Pairing Not Allowed (will trigger PIN fallback)
                byte[] cmd = HciCommands.ioCapabilityRequestNegativeReply(addr, (byte) 0x18);
                mHciManager.sendCommand(cmd);
            }
            return;
        }

        // Auto-accept for NoInputNoOutput or if autoAccept is enabled
        if (mAutoAccept || mDefaultIoCap == PairingConstants.IO_CAP_NO_INPUT_NO_OUTPUT) {
            mListener.onMessage("Auto-responding to IO Capability Request");
            if (mHciManager != null) {
                byte[] cmd = HciCommands.ioCapabilityRequestReply(
                        addr, (byte) mDefaultIoCap, (byte) 0, (byte) mDefaultAuthReq);
                mHciManager.sendCommand(cmd);
            }
            session.setState(PairingState.IO_CAP_EXCHANGED);
            return;
        }

        notifyIoCapabilityRequest(handle, addr);
    }

    private void handleIoCapabilityResponse(byte[] data) {
        if (data.length < 9) return;
        byte[] addr = Arrays.copyOfRange(data, 0, 6);
        int ioCap = data[6] & 0xFF;
        int oobPresent = data[7] & 0xFF;
        int authReq = data[8] & 0xFF;

        // Find handle - with fallback to connection lookup
        Integer handle = findHandleByAddress(addr);
        if (handle == null) {
            AclConnection conn = findConnectionByAddress(addr);
            if (conn == null) {
                CourierLogger.w(TAG, "IO Capability Response: No connection found for " +
                        PairingConstants.formatAddress(addr));
                return;
            }
            handle = conn.handle;
        }

        // Get or create session
        final int finalHandle = handle;
        final byte[] finalAddr = addr;
        PairingSession session = mSessions.computeIfAbsent(handle,
                h -> new PairingSession(h, finalAddr));

        session.peerIoCap = ioCap;
        session.peerOobPresent = oobPresent;
        session.peerAuthReq = authReq;
        session.setState(PairingState.IO_CAP_EXCHANGED);
        determinePairingMode(session);
        mListener.onMessage("IO Cap Response: ioCap=" + PairingConstants.getIoCapabilityString(ioCap) +
                " authReq=" + PairingConstants.getAuthReqString(authReq) +
                " mode=" + session.getMode());
    }

    private void handleUserConfirmationRequest(byte[] data) {
        if (data.length < 10) return;
        byte[] addr = Arrays.copyOfRange(data, 0, 6);
        int numericValue = ByteBuffer.wrap(data, 6, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();

        // Find handle - with fallback to connection lookup
        Integer handle = findHandleByAddress(addr);
        if (handle == null) {
            AclConnection conn = findConnectionByAddress(addr);
            if (conn == null) {
                CourierLogger.w(TAG, "User Confirmation Request: No connection found for " +
                        PairingConstants.formatAddress(addr));
                // Still try to respond to avoid timeout - use address directly
                if (mAutoAccept || mDefaultIoCap == PairingConstants.IO_CAP_NO_INPUT_NO_OUTPUT) {
                    CourierLogger.i(TAG, "Auto-accepting User Confirmation (no session)");
                    if (mHciManager != null) {
                        byte[] cmd = HciCommands.userConfirmationRequestReply(addr);
                        mHciManager.sendCommand(cmd);
                    }
                }
                return;
            }
            handle = conn.handle;
        }

        // Get or create session
        final int finalHandle = handle;
        final byte[] finalAddr = addr;
        PairingSession session = mSessions.computeIfAbsent(handle,
                h -> new PairingSession(h, finalAddr));

        session.numericValue = numericValue;
        session.setMode(PairingMode.NUMERIC_COMPARISON);
        session.setState(PairingState.USER_CONFIRM);
        mListener.onMessage("User Confirmation Request: " + String.format("%06d", numericValue));

        // Auto-accept for NoInputNoOutput or if autoAccept is enabled
        if (mAutoAccept || mDefaultIoCap == PairingConstants.IO_CAP_NO_INPUT_NO_OUTPUT) {
            mListener.onMessage("Auto-accepting User Confirmation");
            if (mHciManager != null) {
                byte[] cmd = HciCommands.userConfirmationRequestReply(addr);
                mHciManager.sendCommand(cmd);
            }
            session.setState(PairingState.CONFIRMED);
            return;
        }

        notifyNumericComparison(handle, addr, numericValue);
    }

    private void handleUserPasskeyRequest(byte[] data) {
        if (data.length < 6) return;
        byte[] addr = Arrays.copyOfRange(data, 0, 6);

        // Find handle - with fallback to connection lookup
        Integer handle = findHandleByAddress(addr);
        if (handle == null) {
            AclConnection conn = findConnectionByAddress(addr);
            if (conn == null) {
                CourierLogger.w(TAG, "User Passkey Request: No connection found for " +
                        PairingConstants.formatAddress(addr));
                return;
            }
            handle = conn.handle;
        }

        // Get or create session
        final int finalHandle = handle;
        final byte[] finalAddr = addr;
        PairingSession session = mSessions.computeIfAbsent(handle,
                h -> new PairingSession(h, finalAddr));

        session.setMode(PairingMode.PASSKEY_ENTRY);
        session.setState(PairingState.PASSKEY_REQUEST);
        mListener.onMessage("Passkey request");
        notifyPasskeyRequest(handle, addr, false, 0);
    }

    private void handleUserPasskeyNotification(byte[] data) {
        if (data.length < 10) return;
        byte[] addr = Arrays.copyOfRange(data, 0, 6);
        int passkey = ByteBuffer.wrap(data, 6, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();

        // Find handle - with fallback to connection lookup
        Integer handle = findHandleByAddress(addr);
        if (handle == null) {
            AclConnection conn = findConnectionByAddress(addr);
            if (conn == null) {
                CourierLogger.w(TAG, "User Passkey Notification: No connection found for " +
                        PairingConstants.formatAddress(addr));
                return;
            }
            handle = conn.handle;
        }

        // Get or create session
        final int finalHandle = handle;
        final byte[] finalAddr = addr;
        PairingSession session = mSessions.computeIfAbsent(handle,
                h -> new PairingSession(h, finalAddr));

        session.passkey = passkey;
        session.setMode(PairingMode.PASSKEY_ENTRY);
        session.setState(PairingState.PASSKEY_DISPLAY);
        mListener.onMessage("Display passkey: " + String.format("%06d", passkey));
        notifyPasskeyRequest(handle, addr, true, passkey);
    }

    private void handleAuthenticationComplete(byte[] data) {
        if (data.length < 3) return;
        int status = data[0] & 0xFF;
        int handle = ((data[2] & 0xFF) << 8) | (data[1] & 0xFF);

        PairingSession session = mSessions.get(handle);
        if (session == null) {
            // This can happen if Simple_Pairing_Complete already cleaned up
            CourierLogger.w(TAG, "Authentication Complete: No session for handle=0x" +
                    Integer.toHexString(handle) + ", status=0x" + Integer.toHexString(status));
            return;
        }

        if (status == 0) {
            // For legacy PIN pairing, authentication success = pairing complete
            // (encryption may not be enabled for audio profiles)
            if (mForceLegacyPairing) {
                session.setState(PairingState.PAIRED);
                mListener.onMessage("Authentication complete - legacy pairing successful");

                // Get bonding info from database (link key was stored earlier)
                BondingInfo info = mBondingDatabase.get(
                        PairingConstants.formatAddress(session.getPeerAddress()));

                // Trigger callback for sync pairing
                if (session.callback != null) {
                    IPairingCallback cb = session.callback;
                    session.callback = null;
                    cb.onPairingComplete(true, info);
                }

                notifyPairingComplete(handle, session.getPeerAddress(), true, info);
            } else {
                session.setState(PairingState.AUTHENTICATED);
                mListener.onMessage("Authentication complete - waiting for encryption");
            }
        } else {
            // Authentication failed
            mListener.onMessage("Authentication failed: " + HciErrorCode.getDescription(status));

            // Only trigger callback if not already triggered by Simple_Pairing_Complete
            if (session.getState() != PairingState.FAILED) {
                session.setState(PairingState.FAILED);

                if (session.callback != null) {
                    IPairingCallback cb = session.callback;
                    session.callback = null;  // Prevent double-callback
                    cb.onPairingComplete(false, null);
                }

                notifyPairingFailed(handle, session.getPeerAddress(), status,
                        "Authentication failed: " + HciErrorCode.getDescription(status));
            }

            // Clean up session on failure
            mSessions.remove(handle);
        }
    }

    private void handleEncryptionChange(byte[] data) {
        if (data.length < 4) return;
        int status = data[0] & 0xFF;
        int handle = ((data[2] & 0xFF) << 8) | (data[1] & 0xFF);
        int encryptionEnabled = data[3] & 0xFF;

        mListener.onMessage("Encryption Change: handle=0x" + Integer.toHexString(handle) +
                " status=0x" + Integer.toHexString(status) +
                " encrypted=" + (encryptionEnabled != 0));

        PairingSession session = mSessions.get(handle);
        if (session == null) return;

        if (status == 0x00 && encryptionEnabled != 0) {
            // Success!
            session.setState(PairingState.PAIRED);
            session.encrypted = true;
            mListener.onMessage("Pairing complete - encryption enabled");

            BondingInfo info = mBondingDatabase.get(PairingConstants.formatAddress(session.getPeerAddress()));
            if (info == null) {
                info = BondingInfo.builder()
                        .address(session.getPeerAddress())
                        .linkKey(session.linkKey)
                        .linkKeyType(session.linkKeyType)
                        .authenticated(session.authenticated)
                        .build();
            }

            // Trigger callback for sync pairing
            if (session.callback != null) {
                IPairingCallback cb = session.callback;
                session.callback = null;  // Prevent double-callback
                cb.onPairingComplete(true, info);
            }

            notifyPairingComplete(handle, session.getPeerAddress(), true, info);

            // Keep session for potential re-authentication, but clear callback
        } else if (status != 0x00) {
            // Encryption failed
            mListener.onMessage("Encryption failed: " + HciErrorCode.getDescription(status));

            // Only trigger callback if not already triggered
            if (session.getState() != PairingState.FAILED) {
                session.setState(PairingState.FAILED);

                if (session.callback != null) {
                    IPairingCallback cb = session.callback;
                    session.callback = null;
                    cb.onPairingComplete(false, null);
                }

                notifyPairingFailed(handle, session.getPeerAddress(), status,
                        "Encryption failed: " + HciErrorCode.getDescription(status));
            }

            // Clean up session on failure
            mSessions.remove(handle);
        }
    }

    private void handleLinkKeyRequest(byte[] data) {
        if (data.length < 6) return;
        byte[] addr = Arrays.copyOfRange(data, 0, 6);
        String addrStr = PairingConstants.formatAddress(addr);

        BondingInfo info = mBondingDatabase.get(addrStr);
        byte[] linkKey = (info != null) ? info.getLinkKey() : null;

        // Check SmpManager for CTKD-derived link key if not in bonding database
        // Skip if forcing legacy pairing (CTKD keys may not work with all devices)
        if (linkKey == null && mSmpManager != null && !mForceLegacyPairing) {
            linkKey = mSmpManager.getDerivedLinkKey(addrStr);
            if (linkKey != null) {
                CourierLogger.i(TAG, "Using CTKD-derived link key for " + addrStr);
            }
        }

        if (mHciManager != null) {
            byte[] cmd;
            if (linkKey != null) {
                mListener.onMessage("Sending link key for " + addrStr);
                cmd = HciCommands.linkKeyRequestReply(addr, linkKey);
            } else {
                mListener.onMessage("No link key for " + addrStr + " - sending negative reply (will trigger pairing)");
                cmd = HciCommands.linkKeyRequestNegativeReply(addr);
            }
            mHciManager.sendCommand(cmd);
        }
    }

    /**
     * Handles PIN_Code_Request event (legacy pairing).
     *
     * <p>Automatically responds with the configured default PIN.
     */
    private void handlePinCodeRequest(byte[] data) {
        if (data.length < 6) return;
        byte[] addr = Arrays.copyOfRange(data, 0, 6);
        String addrStr = PairingConstants.formatAddress(addr);

        mListener.onMessage("PIN Code Request from " + addrStr);

        if (mHciManager != null) {
            mListener.onMessage("Replied with PIN: " + mDefaultPin);
            byte[] cmd = HciCommands.pinCodeRequestReply(addr, mDefaultPin);
            mHciManager.sendCommand(cmd);
        }
    }

    private void handleLinkKeyNotification(byte[] data) {
        if (data.length < 23) return;
        byte[] addr = Arrays.copyOfRange(data, 0, 6);
        byte[] linkKey = Arrays.copyOfRange(data, 6, 22);
        int keyType = data[22] & 0xFF;

        // Find handle - with fallback to connection lookup
        Integer handle = findHandleByAddress(addr);
        if (handle == null) {
            AclConnection conn = findConnectionByAddress(addr);
            if (conn == null) {
                CourierLogger.w(TAG, "Link Key Notification: No connection found for " +
                        PairingConstants.formatAddress(addr) + ", storing key anyway");
                // Still store the link key even without a session
                BondingInfo info = BondingInfo.builder()
                        .address(addr)
                        .linkKey(linkKey)
                        .linkKeyType(keyType)
                        .authenticated(PairingConstants.isMitmProtected(keyType))
                        .build();
                mBondingDatabase.put(PairingConstants.formatAddress(addr), info);
                mListener.onMessage("Link key received (no session), type=" + PairingConstants.getLinkKeyTypeString(keyType));
                return;
            }
            handle = conn.handle;
        }

        // Get or create session
        final int finalHandle = handle;
        final byte[] finalAddr = addr;
        PairingSession session = mSessions.computeIfAbsent(handle,
                h -> new PairingSession(h, finalAddr));

        System.arraycopy(linkKey, 0, session.linkKey, 0, 16);
        session.linkKeyType = keyType;
        session.authenticated = PairingConstants.isMitmProtected(keyType);

        BondingInfo info = BondingInfo.builder()
                .address(addr)
                .linkKey(linkKey)
                .linkKeyType(keyType)
                .authenticated(session.authenticated)
                .build();
        mBondingDatabase.put(PairingConstants.formatAddress(addr), info);

        mListener.onMessage("Link key received, type=" + PairingConstants.getLinkKeyTypeString(keyType));
    }

    private void handleSimplePairingComplete(byte[] data) {
        if (data.length < 7) return;
        int status = data[0] & 0xFF;
        byte[] addr = Arrays.copyOfRange(data, 1, 7);

        // Find handle - with fallback to connection lookup
        Integer handle = findHandleByAddress(addr);
        if (handle == null) {
            AclConnection conn = findConnectionByAddress(addr);
            if (conn == null) {
                CourierLogger.w(TAG, "Simple Pairing Complete: No connection found for " +
                        PairingConstants.formatAddress(addr) + ", status=" + status);
                return;
            }
            handle = conn.handle;
        }

        PairingSession session = mSessions.get(handle);
        if (session == null) {
            CourierLogger.w(TAG, "Simple Pairing Complete: No session for handle=" + handle +
                    ", status=" + status);
            return;
        }

        if (status == 0) {
            // Success - wait for Link Key Notification and Encryption Change
            session.setState(PairingState.WAITING_LINK_KEY);
            mListener.onMessage("Simple Pairing Complete - waiting for link key");
        } else {
            // Failed - mark as failed but DON'T remove session yet
            // (Authentication_Complete event will also arrive and needs the session)
            session.setState(PairingState.FAILED);
            mListener.onMessage("Simple Pairing Complete failed: " + HciErrorCode.getDescription(status));

            // Trigger callback only if not already triggered
            if (session.callback != null) {
                IPairingCallback cb = session.callback;
                session.callback = null;  // Prevent double-callback
                cb.onPairingComplete(false, null);
            }

            notifyPairingFailed(handle, addr, status,
                    "Pairing failed: " + HciErrorCode.getDescription(status));

            // Don't remove session here - let handleAuthenticationComplete or handleEncryptionChange do it
        }
    }

    private void determinePairingMode(PairingSession session) {
        boolean localMitm = (session.localAuthReq & 0x04) != 0;
        boolean peerMitm = (session.peerAuthReq & 0x04) != 0;
        boolean mitm = localMitm || peerMitm;

        if (!mitm) {
            session.setMode(PairingMode.JUST_WORKS);
            return;
        }

        int localIo = session.localIoCap;
        int peerIo = session.peerIoCap;

        if ((localIo == PairingConstants.IO_CAP_DISPLAY_YES_NO ||
                localIo == PairingConstants.IO_CAP_KEYBOARD_DISPLAY) &&
                (peerIo == PairingConstants.IO_CAP_DISPLAY_YES_NO ||
                        peerIo == PairingConstants.IO_CAP_KEYBOARD_DISPLAY)) {
            session.setMode(PairingMode.NUMERIC_COMPARISON);
        } else if (localIo == PairingConstants.IO_CAP_KEYBOARD_ONLY ||
                peerIo == PairingConstants.IO_CAP_KEYBOARD_ONLY) {
            session.setMode(PairingMode.PASSKEY_ENTRY);
        } else {
            session.setMode(PairingMode.JUST_WORKS);
        }
    }

    // ==================== Helper Methods ====================

    private String getEventName(int eventCode) {
        switch (eventCode) {
            case 0x06: return "Authentication_Complete";
            case 0x07: return "Remote_Name_Request_Complete";
            case 0x08: return "Encryption_Change";
            case 0x16: return "PIN_Code_Request";
            case 0x17: return "Link_Key_Request";
            case 0x18: return "Link_Key_Notification";
            case 0x31: return "IO_Capability_Request";
            case 0x32: return "IO_Capability_Response";
            case 0x33: return "User_Confirmation_Request";
            case 0x34: return "User_Passkey_Request";
            case 0x35: return "Remote_OOB_Data_Request";
            case 0x36: return "Simple_Pairing_Complete";
            case 0x3B: return "User_Passkey_Notification";
            default: return "Unknown_0x" + Integer.toHexString(eventCode);
        }
    }

    private Integer findHandleByAddress(byte[] addr) {
        for (Map.Entry<Integer, PairingSession> entry : mSessions.entrySet()) {
            if (Arrays.equals(entry.getValue().getPeerAddress(), addr)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private AclConnection findConnectionByAddress(byte[] addr) {
        for (AclConnection conn : mL2capManager.getConnections().values()) {
            if (conn.matchesAddress(addr)) {
                return conn;
            }
        }
        return null;
    }

    // ==================== Notification Helpers ====================

    private void notifyPairingStarted(int handle, byte[] addr) {
        mListener.onPairingStarted(handle, addr);
        for (IPairingListener l : mAdditionalListeners) {
            try {
                l.onPairingStarted(handle, addr);
            } catch (Exception e) {
                CourierLogger.e(TAG, "Listener exception", e);
            }
        }
    }

    private void notifyIoCapabilityRequest(int handle, byte[] addr) {
        mListener.onIoCapabilityRequest(handle, addr);
        for (IPairingListener l : mAdditionalListeners) {
            try {
                l.onIoCapabilityRequest(handle, addr);
            } catch (Exception e) {
                CourierLogger.e(TAG, "Listener exception", e);
            }
        }
    }

    private void notifyNumericComparison(int handle, byte[] addr, int value) {
        mListener.onNumericComparison(handle, addr, value);
        for (IPairingListener l : mAdditionalListeners) {
            try {
                l.onNumericComparison(handle, addr, value);
            } catch (Exception e) {
                CourierLogger.e(TAG, "Listener exception", e);
            }
        }
    }

    private void notifyPasskeyRequest(int handle, byte[] addr, boolean display, int passkey) {
        mListener.onPasskeyRequest(handle, addr, display, passkey);
        for (IPairingListener l : mAdditionalListeners) {
            try {
                l.onPasskeyRequest(handle, addr, display, passkey);
            } catch (Exception e) {
                CourierLogger.e(TAG, "Listener exception", e);
            }
        }
    }

    private void notifyPairingComplete(int handle, byte[] addr, boolean success, BondingInfo info) {
        mListener.onPairingComplete(handle, addr, success, info);
        for (IPairingListener l : mAdditionalListeners) {
            try {
                l.onPairingComplete(handle, addr, success, info);
            } catch (Exception e) {
                CourierLogger.e(TAG, "Listener exception", e);
            }
        }
    }

    private void notifyPairingFailed(int handle, byte[] addr, int errorCode, String reason) {
        mListener.onPairingFailed(handle, addr, errorCode, reason);
        for (IPairingListener l : mAdditionalListeners) {
            try {
                l.onPairingFailed(handle, addr, errorCode, reason);
            } catch (Exception e) {
                CourierLogger.e(TAG, "Listener exception", e);
            }
        }
    }

    // ==================== IL2capListener Implementation ====================

    @Override
    public void onConnectionComplete(AclConnection conn) {
        // No action needed
    }

    @Override
    public void onDisconnectionComplete(int handle, int reason) {
        mSessions.remove(handle);
    }

    @Override
    public void onChannelOpened(L2capChannel channel) {
        // No action needed
    }

    @Override
    public void onChannelClosed(L2capChannel channel) {
        // No action needed
    }

    @Override
    public void onDataReceived(L2capChannel channel, byte[] data) {
        // No action needed
    }

    @Override
    public void onConnectionRequest(int handle, int psm, int sourceCid) {
        // No action needed
    }

    // ==================== IHciCommandListener Implementation ====================

    @Override
    public void onAclData(byte[] data) {
        // Handled by L2capManager
    }

    @Override
    public void onScoData(byte[] data) {
        // Not used
    }

    @Override
    public void onIsoData(byte[] data) {
        // Not used
    }

    @Override
    public void onError(String message) {
        mListener.onError(message);
    }

    @Override
    public void onMessage(String message) {
        // Forward HCI messages to listener
    }

    // ==================== Closeable Implementation ====================

    @Override
    public void close() {
        if (!mClosed.compareAndSet(false, true)) {
            return;
        }

        CourierLogger.i(TAG, "Closing PairingManager");

        // Clean up sessions
        mSessions.clear();

        // Unregister listeners
        mL2capManager.removeListener(this);
        if (mHciManager != null) {
            mHciManager.removeListener(this);
        }

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
        CourierLogger.i(TAG, "PairingManager closed");
    }

    /**
     * @deprecated Use {@link #close()} instead
     */
    @Deprecated
    public void shutdown() {
        close();
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