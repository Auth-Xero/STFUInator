package com.courierstack.hci;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Static factory methods for building HCI command packets.
 *
 * <p>This class provides methods to construct properly formatted HCI commands
 * according to the Bluetooth Core Specification v5.3.
 *
 * <p>Command packet format:
 * <pre>
 * +--------+--------+--------+-----------------+
 * | OpCode | OpCode | Param  |   Parameters    |
 * |  Low   |  High  | Length |                 |
 * +--------+--------+--------+-----------------+
 * </pre>
 *
 * <p>Usage example:
 * <pre>{@code
 * byte[] resetCmd = HciCommands.reset();
 * byte[] scanCmd = HciCommands.leSetScanEnable(true, false);
 * }</pre>
 */
public final class HciCommands {

    private HciCommands() {
        // Utility class - prevent instantiation
    }

    // ========== Command building ==========

    /**
     * Builds an HCI command packet with the specified opcode and parameters.
     *
     * @param opcode     command opcode (OGF << 10 | OCF)
     * @param parameters command parameters, may be null or empty
     * @return formatted command packet
     */
    public static byte[] buildCommand(int opcode, byte[] parameters) {
        int paramLen = (parameters != null) ? parameters.length : 0;
        byte[] command = new byte[3 + paramLen];
        command[0] = (byte) (opcode & 0xFF);
        command[1] = (byte) ((opcode >> 8) & 0xFF);
        command[2] = (byte) paramLen;
        if (parameters != null && paramLen > 0) {
            System.arraycopy(parameters, 0, command, 3, paramLen);
        }
        return command;
    }

    // ========== Controller & Baseband Commands (OGF 0x03) ==========

    /**
     * HCI_Reset (0x0C03) - Resets the controller.
     */
    public static byte[] reset() {
        return buildCommand(0x0C03, null);
    }

    /**
     * HCI_Set_Event_Mask (0x0C01) - Sets the event mask.
     *
     * @param mask 64-bit event mask
     */
    public static byte[] setEventMask(long mask) {
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(mask);
        return buildCommand(0x0C01, buf.array());
    }

    /**
     * HCI_Read_Local_Name (0x0C14) - Reads the local device name.
     */
    public static byte[] readLocalName() {
        return buildCommand(0x0C14, null);
    }

    /**
     * HCI_Write_Local_Name (0x0C13) - Sets the local device name.
     *
     * @param name device name (max 248 bytes UTF-8)
     */
    public static byte[] writeLocalName(String name) {
        Objects.requireNonNull(name, "name must not be null");
        byte[] nameBytes = new byte[248];
        byte[] src = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(src, 0, nameBytes, 0, Math.min(src.length, 247));
        return buildCommand(0x0C13, nameBytes);
    }

    /**
     * HCI_Write_Scan_Enable (0x0C1A) - Enables/disables page and inquiry scan.
     *
     * @param scanEnable scan enable bits (0=none, 1=inquiry, 2=page, 3=both)
     */
    public static byte[] writeScanEnable(int scanEnable) {
        return buildCommand(0x0C1A, new byte[]{(byte) scanEnable});
    }

    /**
     * HCI_Write_Inquiry_Mode (0x0C45) - Sets the inquiry mode.
     *
     * @param mode inquiry mode (0=standard, 1=RSSI, 2=EIR)
     */
    public static byte[] writeInquiryMode(int mode) {
        return buildCommand(0x0C45, new byte[]{(byte) mode});
    }

    /**
     * HCI_Write_Simple_Pairing_Mode (0x0C56) - Enables/disables SSP.
     *
     * @param enable true to enable SSP
     */
    public static byte[] writeSimplePairingMode(boolean enable) {
        return buildCommand(0x0C56, new byte[]{(byte) (enable ? 0x01 : 0x00)});
    }

    /**
     * HCI_Write_Authentication_Enable (0x0C20) - Enables/disables authentication.
     *
     * @param enable true to require authentication for connections
     */
    public static byte[] writeAuthenticationEnable(boolean enable) {
        return buildCommand(0x0C20, new byte[]{(byte) (enable ? 0x01 : 0x00)});
    }

    // ========== Informational Parameters (OGF 0x04) ==========

    /**
     * HCI_Read_Local_Version_Information (0x1001).
     */
    public static byte[] readLocalVersionInfo() {
        return buildCommand(0x1001, null);
    }

    /**
     * HCI_Read_Local_Supported_Commands (0x1002).
     */
    public static byte[] readLocalSupportedCommands() {
        return buildCommand(0x1002, null);
    }

    /**
     * HCI_Read_Local_Supported_Features (0x1003).
     */
    public static byte[] readLocalSupportedFeatures() {
        return buildCommand(0x1003, null);
    }

    /**
     * HCI_Read_Buffer_Size (0x1005).
     */
    public static byte[] readBufferSize() {
        return buildCommand(0x1005, null);
    }

    /**
     * HCI_Read_BD_ADDR (0x1009) - Reads the local Bluetooth address.
     */
    public static byte[] readBdAddr() {
        return buildCommand(0x1009, null);
    }

    // ========== Link Control Commands (OGF 0x01) ==========

    /**
     * HCI_Inquiry (0x0401) - Starts device discovery.
     *
     * @param lap          LAP (usually 0x9E8B33 for GIAC)
     * @param length       inquiry duration in 1.28s units (1-48)
     * @param numResponses max responses (0 = unlimited)
     */
    public static byte[] inquiry(int lap, int length, int numResponses) {
        ByteBuffer buf = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) (lap & 0xFF));
        buf.put((byte) ((lap >> 8) & 0xFF));
        buf.put((byte) ((lap >> 16) & 0xFF));
        buf.put((byte) length);
        buf.put((byte) numResponses);
        return buildCommand(0x0401, buf.array());
    }

    /**
     * HCI_Inquiry_Cancel (0x0402) - Cancels an ongoing inquiry.
     */
    public static byte[] inquiryCancel() {
        return buildCommand(0x0402, null);
    }

    /**
     * HCI_Create_Connection (0x0405) - Creates an ACL connection.
     *
     * @param bdAddr          6-byte Bluetooth address
     * @param packetType      allowed packet types
     * @param pageScanRepMode page scan repetition mode
     * @param reserved        reserved (set to 0)
     * @param clockOffset     clock offset
     * @param allowRoleSwitch allow role switch (0=no, 1=yes)
     */
    public static byte[] createConnection(byte[] bdAddr, int packetType, int pageScanRepMode,
                                          int reserved, int clockOffset, int allowRoleSwitch) {
        Objects.requireNonNull(bdAddr, "bdAddr must not be null");
        if (bdAddr.length != 6) {
            throw new IllegalArgumentException("bdAddr must be 6 bytes");
        }
        ByteBuffer buf = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(bdAddr);
        buf.putShort((short) packetType);
        buf.put((byte) pageScanRepMode);
        buf.put((byte) reserved);
        buf.putShort((short) clockOffset);
        buf.put((byte) allowRoleSwitch);
        return buildCommand(0x0405, buf.array());
    }

    /**
     * HCI_Disconnect (0x0406) - Disconnects a connection.
     *
     * @param handle connection handle
     * @param reason disconnect reason code
     */
    public static byte[] disconnect(int handle, int reason) {
        ByteBuffer buf = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) handle);
        buf.put((byte) reason);
        return buildCommand(0x0406, buf.array());
    }

    /**
     * HCI_Authentication_Requested (0x0411) - Requests authentication.
     *
     * @param handle connection handle
     */
    public static byte[] authenticationRequested(int handle) {
        ByteBuffer buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) handle);
        return buildCommand(0x0411, buf.array());
    }

    /**
     * HCI_Link_Key_Request_Reply (0x040B).
     *
     * @param bdAddr  6-byte Bluetooth address
     * @param linkKey 16-byte link key
     */
    public static byte[] linkKeyRequestReply(byte[] bdAddr, byte[] linkKey) {
        validateBdAddr(bdAddr);
        Objects.requireNonNull(linkKey, "linkKey must not be null");
        if (linkKey.length != 16) {
            throw new IllegalArgumentException("linkKey must be 16 bytes");
        }
        ByteBuffer buf = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(bdAddr);
        buf.put(linkKey);
        return buildCommand(0x040B, buf.array());
    }

    /**
     * HCI_Link_Key_Request_Negative_Reply (0x040C).
     *
     * @param bdAddr 6-byte Bluetooth address
     */
    public static byte[] linkKeyRequestNegativeReply(byte[] bdAddr) {
        validateBdAddr(bdAddr);
        return buildCommand(0x040C, bdAddr);
    }

    /**
     * HCI_PIN_Code_Request_Reply (0x040D).
     *
     * <p>Used for legacy PIN pairing.
     *
     * @param bdAddr  6-byte Bluetooth address
     * @param pinCode PIN code string (1-16 characters)
     */
    public static byte[] pinCodeRequestReply(byte[] bdAddr, String pinCode) {
        validateBdAddr(bdAddr);
        Objects.requireNonNull(pinCode, "pinCode must not be null");
        if (pinCode.isEmpty() || pinCode.length() > 16) {
            throw new IllegalArgumentException("PIN must be 1-16 characters");
        }
        byte[] pinBytes = pinCode.getBytes();
        ByteBuffer buf = ByteBuffer.allocate(23).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(bdAddr);
        buf.put((byte) pinBytes.length);
        buf.put(pinBytes);
        // Pad to 16 bytes
        for (int i = pinBytes.length; i < 16; i++) {
            buf.put((byte) 0);
        }
        return buildCommand(0x040D, buf.array());
    }

    /**
     * HCI_PIN_Code_Request_Negative_Reply (0x040E).
     *
     * @param bdAddr 6-byte Bluetooth address
     */
    public static byte[] pinCodeRequestNegativeReply(byte[] bdAddr) {
        validateBdAddr(bdAddr);
        return buildCommand(0x040E, bdAddr);
    }

    /**
     * HCI_IO_Capability_Request_Reply (0x042B).
     *
     * @param bdAddr     6-byte Bluetooth address
     * @param ioCap      IO capability
     * @param oobPresent OOB data present flag
     * @param authReq    authentication requirements
     */
    public static byte[] ioCapabilityRequestReply(byte[] bdAddr, byte ioCap,
                                                  byte oobPresent, byte authReq) {
        validateBdAddr(bdAddr);
        ByteBuffer buf = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(bdAddr);
        buf.put(ioCap);
        buf.put(oobPresent);
        buf.put(authReq);
        return buildCommand(0x042B, buf.array());
    }

    /**
     * HCI_User_Confirmation_Request_Reply (0x042C).
     *
     * @param bdAddr 6-byte Bluetooth address
     */
    public static byte[] userConfirmationRequestReply(byte[] bdAddr) {
        validateBdAddr(bdAddr);
        return buildCommand(0x042C, bdAddr);
    }

    /**
     * HCI_User_Confirmation_Request_Negative_Reply (0x042D).
     *
     * @param bdAddr 6-byte Bluetooth address
     */
    public static byte[] userConfirmationRequestNegativeReply(byte[] bdAddr) {
        validateBdAddr(bdAddr);
        return buildCommand(0x042D, bdAddr);
    }

    /**
     * HCI_User_Passkey_Request_Reply (0x042E).
     *
     * @param bdAddr  6-byte Bluetooth address
     * @param passkey 6-digit passkey
     */
    public static byte[] userPasskeyRequestReply(byte[] bdAddr, int passkey) {
        validateBdAddr(bdAddr);
        ByteBuffer buf = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(bdAddr);
        buf.putInt(passkey);
        return buildCommand(0x042E, buf.array());
    }

    /**
     * HCI_User_Passkey_Request_Negative_Reply (0x042F).
     *
     * @param bdAddr 6-byte Bluetooth address
     */
    public static byte[] userPasskeyRequestNegativeReply(byte[] bdAddr) {
        validateBdAddr(bdAddr);
        return buildCommand(0x042F, bdAddr);
    }

    /**
     * HCI_IO_Capability_Request_Negative_Reply (0x0434).
     *
     * @param bdAddr 6-byte Bluetooth address
     * @param reason error reason code
     */
    public static byte[] ioCapabilityRequestNegativeReply(byte[] bdAddr, byte reason) {
        validateBdAddr(bdAddr);
        ByteBuffer buf = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(bdAddr);
        buf.put(reason);
        return buildCommand(0x0434, buf.array());
    }

    // ========== LE Controller Commands (OGF 0x08) ==========

    /**
     * HCI_LE_Set_Event_Mask (0x2001).
     *
     * @param mask 64-bit LE event mask
     */
    public static byte[] leSetEventMask(long mask) {
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(mask);
        return buildCommand(0x2001, buf.array());
    }

    /**
     * HCI_LE_Set_Scan_Parameters (0x200B).
     *
     * @param scanType     scan type (0=passive, 1=active)
     * @param scanInterval scan interval (0x0004-0x4000, units of 0.625ms)
     * @param scanWindow   scan window (0x0004-0x4000, units of 0.625ms)
     * @param ownAddrType  own address type
     * @param filterPolicy filter policy
     */
    public static byte[] leSetScanParameters(int scanType, int scanInterval, int scanWindow,
                                             int ownAddrType, int filterPolicy) {
        ByteBuffer buf = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) scanType);
        buf.putShort((short) scanInterval);
        buf.putShort((short) scanWindow);
        buf.put((byte) ownAddrType);
        buf.put((byte) filterPolicy);
        return buildCommand(0x200B, buf.array());
    }

    /**
     * HCI_LE_Set_Scan_Enable (0x200C).
     *
     * @param enable           true to enable scanning
     * @param filterDuplicates true to filter duplicate advertisements
     */
    public static byte[] leSetScanEnable(boolean enable, boolean filterDuplicates) {
        return buildCommand(0x200C, new byte[]{
                (byte) (enable ? 1 : 0),
                (byte) (filterDuplicates ? 1 : 0)
        });
    }

    /**
     * HCI_LE_Create_Connection (0x200D).
     *
     * @param scanInterval       scan interval
     * @param scanWindow         scan window
     * @param filterPolicy       filter policy
     * @param peerAddrType       peer address type
     * @param peerAddr           6-byte peer address
     * @param ownAddrType        own address type
     * @param connIntervalMin    min connection interval
     * @param connIntervalMax    max connection interval
     * @param connLatency        connection latency
     * @param supervisionTimeout supervision timeout
     * @param minCeLen           min CE length
     * @param maxCeLen           max CE length
     */
    public static byte[] leCreateConnection(int scanInterval, int scanWindow, int filterPolicy,
                                            int peerAddrType, byte[] peerAddr, int ownAddrType,
                                            int connIntervalMin, int connIntervalMax,
                                            int connLatency, int supervisionTimeout,
                                            int minCeLen, int maxCeLen) {
        validateBdAddr(peerAddr);
        ByteBuffer buf = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) scanInterval);
        buf.putShort((short) scanWindow);
        buf.put((byte) filterPolicy);
        buf.put((byte) peerAddrType);
        buf.put(peerAddr);
        buf.put((byte) ownAddrType);
        buf.putShort((short) connIntervalMin);
        buf.putShort((short) connIntervalMax);
        buf.putShort((short) connLatency);
        buf.putShort((short) supervisionTimeout);
        buf.putShort((short) minCeLen);
        buf.putShort((short) maxCeLen);
        return buildCommand(0x200D, buf.array());
    }

    /**
     * HCI_LE_Create_Connection_Cancel (0x200E).
     */
    public static byte[] leCreateConnectionCancel() {
        return buildCommand(0x200E, null);
    }

    /**
     * HCI_LE_Start_Encryption (0x2019).
     *
     * <p>Starts encryption on an LE connection.
     *
     * @param handle connection handle
     * @param rand   random number (8 bytes)
     * @param ediv   encrypted diversifier
     * @param ltk    long term key (16 bytes)
     */
    public static byte[] leStartEncryption(int handle, byte[] rand, int ediv, byte[] ltk) {
        Objects.requireNonNull(rand, "rand must not be null");
        Objects.requireNonNull(ltk, "ltk must not be null");
        if (rand.length != 8) {
            throw new IllegalArgumentException("rand must be 8 bytes");
        }
        if (ltk.length != 16) {
            throw new IllegalArgumentException("ltk must be 16 bytes");
        }
        ByteBuffer buf = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) handle);
        buf.put(rand);
        buf.putShort((short) ediv);
        buf.put(ltk);
        return buildCommand(0x2019, buf.array());
    }

    /**
     * HCI_LE_Start_Encryption (0x2019).
     *
     * <p>Starts encryption using pre-built parameters.
     *
     * @param params pre-built parameter buffer (28 bytes: handle + rand + ediv + ltk)
     */
    public static byte[] leStartEncryption(byte[] params) {
        Objects.requireNonNull(params, "params must not be null");
        if (params.length != 28) {
            throw new IllegalArgumentException("params must be 28 bytes");
        }
        return buildCommand(0x2019, params);
    }

    /**
     * HCI_LE_Long_Term_Key_Request_Reply (0x201A).
     *
     * <p>Replies to an LTK request with the long term key.
     *
     * @param handle connection handle
     * @param ltk    long term key (16 bytes)
     */
    public static byte[] leLongTermKeyRequestReply(int handle, byte[] ltk) {
        Objects.requireNonNull(ltk, "ltk must not be null");
        if (ltk.length != 16) {
            throw new IllegalArgumentException("ltk must be 16 bytes");
        }
        ByteBuffer buf = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) handle);
        buf.put(ltk);
        return buildCommand(0x201A, buf.array());
    }

    /**
     * HCI_LE_Long_Term_Key_Request_Negative_Reply (0x201B).
     *
     * <p>Replies to an LTK request indicating no LTK is available.
     *
     * @param handle connection handle
     */
    public static byte[] leLongTermKeyRequestNegativeReply(int handle) {
        ByteBuffer buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) handle);
        return buildCommand(0x201B, buf.array());
    }

    /**
     * HCI_LE_Read_Local_P-256_Public_Key (0x2025).
     *
     * <p>Reads the local P-256 public key for LE Secure Connections.
     * The result is returned via the LE Read Local P-256 Public Key Complete event.
     */
    public static byte[] leReadLocalP256PublicKey() {
        return buildCommand(0x2025, null);
    }

    /**
     * HCI_LE_Generate_DHKey (0x2026).
     *
     * <p>Generates a Diffie-Hellman key from the peer's public key.
     * The result is returned via the LE Generate DHKey Complete event.
     *
     * @param peerPublicKeyX peer's P-256 public key X coordinate (32 bytes)
     * @param peerPublicKeyY peer's P-256 public key Y coordinate (32 bytes)
     */
    public static byte[] leGenerateDhKey(byte[] peerPublicKeyX, byte[] peerPublicKeyY) {
        Objects.requireNonNull(peerPublicKeyX, "peerPublicKeyX must not be null");
        Objects.requireNonNull(peerPublicKeyY, "peerPublicKeyY must not be null");
        if (peerPublicKeyX.length != 32) {
            throw new IllegalArgumentException("peerPublicKeyX must be 32 bytes");
        }
        if (peerPublicKeyY.length != 32) {
            throw new IllegalArgumentException("peerPublicKeyY must be 32 bytes");
        }
        ByteBuffer buf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(peerPublicKeyX);
        buf.put(peerPublicKeyY);
        return buildCommand(0x2026, buf.array());
    }

    /**
     * HCI_LE_Generate_DHKey_V2 (0x205E).
     *
     * <p>Generates a Diffie-Hellman key from the peer's public key (version 2).
     * Allows specifying the key type.
     *
     * @param peerPublicKeyX peer's P-256 public key X coordinate (32 bytes)
     * @param peerPublicKeyY peer's P-256 public key Y coordinate (32 bytes)
     * @param keyType        key type (0 = use generated private key, 1 = use debug private key)
     */
    public static byte[] leGenerateDhKeyV2(byte[] peerPublicKeyX, byte[] peerPublicKeyY, int keyType) {
        Objects.requireNonNull(peerPublicKeyX, "peerPublicKeyX must not be null");
        Objects.requireNonNull(peerPublicKeyY, "peerPublicKeyY must not be null");
        if (peerPublicKeyX.length != 32) {
            throw new IllegalArgumentException("peerPublicKeyX must be 32 bytes");
        }
        if (peerPublicKeyY.length != 32) {
            throw new IllegalArgumentException("peerPublicKeyY must be 32 bytes");
        }
        ByteBuffer buf = ByteBuffer.allocate(65).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(peerPublicKeyX);
        buf.put(peerPublicKeyY);
        buf.put((byte) keyType);
        return buildCommand(0x205E, buf.array());
    }

    // ========== Validation helpers ==========

    private static void validateBdAddr(byte[] bdAddr) {
        Objects.requireNonNull(bdAddr, "bdAddr must not be null");
        if (bdAddr.length != 6) {
            throw new IllegalArgumentException("bdAddr must be 6 bytes");
        }
    }
}