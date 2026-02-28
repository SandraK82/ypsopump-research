package com.ypsopump.test.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.util.Log
import com.ypsopump.test.crypto.PumpCryptor
import com.ypsopump.test.data.*
import com.ypsopump.test.protocol.YpsoCrc
import com.ypsopump.test.protocol.YpsoFraming
import com.ypsopump.test.protocol.YpsoGlb
import com.ypsopump.test.security.LogRedactor
import com.ypsopump.test.ui.screens.BleLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.observer.ConnectionObserver
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

/**
 * BLE communication layer for YpsoPump, based on Nordic BLE library.
 * Adapted from vicktor/ypsomed-pump with StateFlow-based state management.
 *
 * Manages: connection, auth, encryption, multi-frame, all pump commands.
 */
class YpsoBleManager(context: Context) : BleManager(context) {

    companion object {
        private const val TAG = "YpsoBleManager"
        const val YPSOPUMP_NAME_PREFIX = "YpsoPump_"

        val AUTH_SALT = byteArrayOf(
            0x4F, 0xC2.toByte(), 0x45, 0x4D, 0x9B.toByte(),
            0x81.toByte(), 0x59, 0xA4.toByte(), 0x93.toByte(), 0xBB.toByte()
        )
    }

    init {
        connectionObserver = object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) {
                log("Connecting to ${device.name}...")
                _connectionState.value = ConnectionState.CONNECTING
            }
            override fun onDeviceConnected(device: BluetoothDevice) {
                log("Connected to ${device.name}")
                _connectionState.value = ConnectionState.CONNECTED
            }
            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                log("Failed to connect: reason=$reason")
                _connectionState.value = ConnectionState.DISCONNECTED
            }
            override fun onDeviceReady(device: BluetoothDevice) {
                log("Device ready: ${device.name}")
            }
            override fun onDeviceDisconnecting(device: BluetoothDevice) {
                log("Disconnecting from ${device.name}")
            }
            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                log("Disconnected: reason=$reason")
                _connectionState.value = ConnectionState.DISCONNECTED
                _isAuthenticated.value = false
            }
        }
    }

    override fun shouldClearCacheWhenDisconnected(): Boolean = true

    // ==================== STATE ====================

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, AUTHENTICATED }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated

    private val _logEvents = MutableSharedFlow<LogEvent>(replay = 100)
    val logEvents: SharedFlow<LogEvent> = _logEvents

    // ==================== CHARACTERISTICS ====================

    private var charDeviceName: BluetoothGattCharacteristic? = null
    private var charManufacturer: BluetoothGattCharacteristic? = null
    private var charSoftwareRevision: BluetoothGattCharacteristic? = null
    private var charAuthPassword: BluetoothGattCharacteristic? = null
    private var charMasterVersion: BluetoothGattCharacteristic? = null
    private var charBolusStartStop: BluetoothGattCharacteristic? = null
    private var charBolusStatus: BluetoothGattCharacteristic? = null
    private var charTbrStartStop: BluetoothGattCharacteristic? = null
    private var charSystemStatus: BluetoothGattCharacteristic? = null
    private var charBolusNotification: BluetoothGattCharacteristic? = null
    private var charSystemDate: BluetoothGattCharacteristic? = null
    private var charSystemTime: BluetoothGattCharacteristic? = null
    private var charExtendedRead: BluetoothGattCharacteristic? = null
    private var charSettingId: BluetoothGattCharacteristic? = null
    private var charSettingValue: BluetoothGattCharacteristic? = null
    private var charHistoryEventsCount: BluetoothGattCharacteristic? = null
    private var charHistoryEventsIndex: BluetoothGattCharacteristic? = null
    private var charHistoryEventsValue: BluetoothGattCharacteristic? = null
    private var charHistoryAlertsCount: BluetoothGattCharacteristic? = null
    private var charHistoryAlertsIndex: BluetoothGattCharacteristic? = null
    private var charHistoryAlertsValue: BluetoothGattCharacteristic? = null
    private var charHistorySystemCount: BluetoothGattCharacteristic? = null
    private var charHistorySystemIndex: BluetoothGattCharacteristic? = null
    private var charHistorySystemValue: BluetoothGattCharacteristic? = null
    private var charSecurityStatus: BluetoothGattCharacteristic? = null

    private val discoveredServices = mutableListOf<BluetoothGattService>()
    private var connectedDeviceMac: String? = null

    private var _countersSynced = true
    var pumpCryptor: PumpCryptor? = null
        set(value) {
            field = value
            _countersSynced = (value == null)
        }

    // ==================== SERVICE DISCOVERY ====================

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        discoveredServices.clear()
        discoveredServices.addAll(gatt.services)
        connectedDeviceMac = gatt.device.address

        log("Discovered ${gatt.services.size} services")

        gatt.services.forEach { service ->
            service.characteristics.forEach { char ->
                when (char.uuid) {
                    YpsoPumpUuids.CHAR_DEVICE_NAME -> charDeviceName = char
                    YpsoPumpUuids.CHAR_MANUFACTURER -> charManufacturer = char
                    YpsoPumpUuids.CHAR_SOFTWARE -> charSoftwareRevision = char
                    YpsoPumpUuids.CHAR_AUTH_PASSWORD -> charAuthPassword = char
                    YpsoPumpUuids.CHAR_MASTER_VERSION -> charMasterVersion = char
                    YpsoPumpUuids.CHAR_BOLUS_START_STOP -> charBolusStartStop = char
                    YpsoPumpUuids.CHAR_BOLUS_STATUS -> charBolusStatus = char
                    YpsoPumpUuids.CHAR_TBR_START_STOP -> charTbrStartStop = char
                    YpsoPumpUuids.CHAR_SYSTEM_STATUS -> charSystemStatus = char
                    YpsoPumpUuids.CHAR_BOLUS_NOTIFICATION -> charBolusNotification = char
                    YpsoPumpUuids.CHAR_SYSTEM_DATE -> charSystemDate = char
                    YpsoPumpUuids.CHAR_SYSTEM_TIME -> charSystemTime = char
                    YpsoPumpUuids.CHAR_EXTENDED_READ -> charExtendedRead = char
                    YpsoPumpUuids.CHAR_SETTING_ID -> charSettingId = char
                    YpsoPumpUuids.CHAR_SETTING_VALUE -> charSettingValue = char
                    YpsoPumpUuids.Events.COUNT -> charHistoryEventsCount = char
                    YpsoPumpUuids.Events.INDEX -> charHistoryEventsIndex = char
                    YpsoPumpUuids.Events.VALUE -> charHistoryEventsValue = char
                    YpsoPumpUuids.Alerts.COUNT -> charHistoryAlertsCount = char
                    YpsoPumpUuids.Alerts.INDEX -> charHistoryAlertsIndex = char
                    YpsoPumpUuids.Alerts.VALUE -> charHistoryAlertsValue = char
                    YpsoPumpUuids.System.COUNT -> charHistorySystemCount = char
                    YpsoPumpUuids.System.INDEX -> charHistorySystemIndex = char
                    YpsoPumpUuids.System.VALUE -> charHistorySystemValue = char
                    YpsoPumpUuids.CHAR_SEC_STATUS -> charSecurityStatus = char
                }
            }
        }

        return gatt.getService(YpsoPumpUuids.SERVICE_SECURITY) != null ||
               gatt.getService(YpsoPumpUuids.SERVICE_WRITE) != null
    }

    override fun onServicesInvalidated() {
        charDeviceName = null; charManufacturer = null; charSoftwareRevision = null
        charAuthPassword = null; charMasterVersion = null
        charBolusStartStop = null; charBolusStatus = null; charTbrStartStop = null
        charSystemStatus = null; charBolusNotification = null
        charSystemDate = null; charSystemTime = null; charExtendedRead = null
        charSettingId = null; charSettingValue = null
        charHistoryEventsCount = null; charHistoryEventsIndex = null; charHistoryEventsValue = null
        charHistoryAlertsCount = null; charHistoryAlertsIndex = null; charHistoryAlertsValue = null
        charHistorySystemCount = null; charHistorySystemIndex = null; charHistorySystemValue = null
        charSecurityStatus = null; discoveredServices.clear(); connectedDeviceMac = null
    }

    override fun initialize() {
        log("Initializing connection...")
        enableNotificationOnChar(charBolusStatus, "BolusStatus")
        enableNotificationOnChar(charBolusNotification, "BolusNotification")
        enableNotificationOnChar(charSystemStatus, "SystemStatus")
        _connectionState.value = ConnectionState.CONNECTED
        log("Connection initialized")
    }

    private fun enableNotificationOnChar(char: BluetoothGattCharacteristic?, name: String) {
        char?.let {
            if (it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                setNotificationCallback(it).with { _, data ->
                    val bytes = data.value ?: return@with
                    log("$name notification: ${bytes.toHex()}")
                    BleLog.add("NOTIFY", name, bytes.toHex())
                }
                enableNotifications(it).enqueue()
                log("Enabled $name notifications")
            } else if (it.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                setNotificationCallback(it).with { _, data ->
                    val bytes = data.value ?: return@with
                    log("$name indication: ${bytes.toHex()}")
                    BleLog.add("NOTIFY", name, bytes.toHex())
                }
                enableIndications(it).enqueue()
            }
        }
    }

    // ==================== AUTHENTICATION ====================

    fun computeAuthPassword(macAddress: String): ByteArray {
        val macBytes = macAddress.replace(":", "")
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return MessageDigest.getInstance("MD5").digest(macBytes + AUTH_SALT)
    }

    fun authenticate(callback: (Boolean) -> Unit) {
        val char = charAuthPassword ?: run { callback(false); return }
        val mac = connectedDeviceMac ?: run { callback(false); return }

        val password = computeAuthPassword(mac)
        log("Authenticating with MD5 password for MAC $mac")
        BleLog.add("TX", "AUTH", password.toHex(), "MD5(MAC+SALT)")

        writeCharacteristic(char, password, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            .with { _, _ ->
                log("Auth password written successfully")
                _isAuthenticated.value = true
                _connectionState.value = ConnectionState.AUTHENTICATED
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    callback(true)
                }, 200)
            }
            .fail { _, status ->
                log("Auth write failed: status=$status")
                callback(false)
            }
            .enqueue()
    }

    // ==================== READ/WRITE PRIMITIVES ====================

    fun readCharByUuid(uuid: UUID, callback: (ByteArray?) -> Unit) {
        val char = findCharacteristic(uuid) ?: run { callback(null); return }
        readCharacteristic(char).with { _, data ->
            val bytes = data.value
            BleLog.add("RX", uuid.toString().takeLast(4), bytes?.toHex() ?: "null")
            callback(bytes)
        }.fail { _, _ -> callback(null) }.enqueue()
    }

    fun readExtended(firstUuid: UUID, callback: (ByteArray?) -> Unit) {
        val firstChar = findCharacteristic(firstUuid) ?: run { callback(null); return }
        readCharacteristic(firstChar).with { _, data ->
            val firstFrame = data.value
            if (firstFrame == null || firstFrame.isEmpty()) { callback(null); return@with }
            BleLog.add("RX", firstUuid.toString().takeLast(4), firstFrame.toHex(), "frame 1")

            val totalFrames = YpsoFraming.getTotalFrames(firstFrame[0])
            if (totalFrames <= 1) {
                callback(if (firstFrame.size > 1) firstFrame.copyOfRange(1, firstFrame.size) else byteArrayOf())
                return@with
            }

            val extChar = charExtendedRead
            if (extChar == null) {
                callback(if (firstFrame.size > 1) firstFrame.copyOfRange(1, firstFrame.size) else byteArrayOf())
                return@with
            }

            val frames = mutableListOf(firstFrame)
            readRemainingFrames(extChar, totalFrames - 1, frames) { allFrames ->
                if (allFrames == null) callback(null)
                else callback(YpsoFraming.parseMultiFrameRead(allFrames))
            }
        }.fail { _, _ -> callback(null) }.enqueue()
    }

    private fun readRemainingFrames(
        char: BluetoothGattCharacteristic, remaining: Int,
        frames: MutableList<ByteArray>, callback: (List<ByteArray>?) -> Unit
    ) {
        if (remaining <= 0) { callback(frames); return }
        readCharacteristic(char).with { _, data ->
            val frame = data.value
            if (frame != null) {
                frames.add(frame)
                BleLog.add("RX", "EXT", frame.toHex(), "frame ${frames.size}")
            }
            readRemainingFrames(char, remaining - 1, frames, callback)
        }.fail { _, _ -> callback(null) }.enqueue()
    }

    fun writeMultiFrame(uuid: UUID, payload: ByteArray, callback: (Boolean) -> Unit) {
        val char = findCharacteristic(uuid) ?: run { callback(false); return }
        val frames = YpsoFraming.chunkPayload(payload)
        log("Writing ${payload.size} bytes in ${frames.size} frames")
        BleLog.add("TX", uuid.toString().takeLast(4), payload.toHex(), "${frames.size} frames")
        writeFramesSequentially(char, frames, 0, callback)
    }

    private fun writeFramesSequentially(
        char: BluetoothGattCharacteristic, frames: List<ByteArray>,
        index: Int, callback: (Boolean) -> Unit
    ) {
        if (index >= frames.size) { callback(true); return }
        writeCharacteristic(char, frames[index], BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            .with { _, _ -> writeFramesSequentially(char, frames, index + 1, callback) }
            .fail { _, status ->
                log("Frame ${index + 1} write failed: $status")
                callback(false)
            }.enqueue()
    }

    // ==================== ENCRYPTION HELPERS ====================

    private fun encryptIfNeeded(data: ByteArray): ByteArray {
        val cryptor = pumpCryptor ?: return data
        return cryptor.encrypt(data)
    }

    private fun decryptIfNeeded(data: ByteArray): ByteArray? {
        val cryptor = pumpCryptor ?: return data
        return try {
            val decrypted = cryptor.decrypt(data)
            if (!_countersSynced) {
                _countersSynced = true
                log("Counter sync OK (reboot=${cryptor.rebootCounter}, read=${cryptor.readCounter}, write=${cryptor.writeCounter})")
            }
            decrypted
        } catch (e: Exception) {
            log("Decryption FAILED: ${e.message}")
            null
        }
    }

    private fun ensureCountersSynced(callback: () -> Unit) {
        if (_countersSynced || pumpCryptor == null) { callback(); return }
        log("Auto-syncing counters before encrypted write...")
        getSystemStatus { _ -> _countersSynced = true; callback() }
    }

    // ==================== PUMP COMMANDS ====================

    fun sendCommand(uuid: UUID, payload: ByteArray, callback: (Boolean) -> Unit) {
        val char = findCharacteristic(uuid) ?: run { callback(false); return }
        ensureCountersSynced {
            val withCrc = YpsoCrc.appendCrc(payload)
            val dataToSend = encryptIfNeeded(withCrc)
            val frames = YpsoFraming.chunkPayload(dataToSend)
            BleLog.add("TX", uuid.toString().takeLast(4), payload.toHex(), "cmd+crc+enc")
            writeFramesSequentially(char, frames, 0, callback)
        }
    }

    fun readCommand(uuid: UUID, callback: (ByteArray?) -> Unit) {
        readExtended(uuid) { rawData ->
            if (rawData == null) { callback(null); return@readExtended }
            val data = decryptIfNeeded(rawData)
            if (data == null) { callback(null); return@readExtended }
            callback(if (YpsoCrc.isValid(data)) data.copyOfRange(0, data.size - 2) else data)
        }
    }

    fun getSystemStatus(callback: (SystemStatusData?) -> Unit) {
        readExtended(YpsoPumpUuids.CHAR_SYSTEM_STATUS) { rawData ->
            if (rawData == null) { callback(null); return@readExtended }
            val data = decryptIfNeeded(rawData) ?: run { callback(null); return@readExtended }
            if (!YpsoCrc.isValid(data)) { callback(null); return@readExtended }

            val payload = data.copyOfRange(0, data.size - 2)
            if (payload.size < 6) { callback(null); return@readExtended }

            val mode = payload[0].toInt() and 0xFF
            val insulinRaw = ByteBuffer.wrap(payload, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val battery = payload[5].toInt() and 0xFF
            val status = SystemStatusData(mode, DeliveryMode.name(mode), insulinRaw / 100f, battery)
            log("Status: ${status.deliveryModeName}, Insulin: ${status.insulinRemaining}U, Battery: ${status.batteryPercent}%")
            BleLog.add("RX", "STAT", payload.toHex(),
                "${status.deliveryModeName} ${status.insulinRemaining}U ${status.batteryPercent}%")
            callback(status)
        }
    }

    fun getBolusStatus(callback: (BolusStatusData?) -> Unit) {
        readExtended(YpsoPumpUuids.CHAR_BOLUS_STATUS) { rawData ->
            if (rawData == null) { callback(null); return@readExtended }
            val data = decryptIfNeeded(rawData) ?: run { callback(null); return@readExtended }
            if (!YpsoCrc.isValid(data)) { callback(null); return@readExtended }

            val payload = data.copyOfRange(0, data.size - 2)
            if (payload.size < 13) { callback(null); return@readExtended }

            val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val fastStatus = buf.get().toInt() and 0xFF
            val fastSeq = buf.int.toLong() and 0xFFFFFFFFL
            val fastInj = buf.int / 100f
            val fastTot = buf.int / 100f

            var slowStatus = 0; var slowSeq = 0L; var slowInj = 0f; var slowTot = 0f
            var slowFastInj = 0f; var slowFastTot = 0f; var actDur = 0; var totDur = 0
            if (payload.size >= 14) {
                slowStatus = buf.get().toInt() and 0xFF
                if (slowStatus != 0 && payload.size >= 42) {
                    slowSeq = buf.int.toLong() and 0xFFFFFFFFL
                    slowInj = buf.int / 100f; slowTot = buf.int / 100f
                    slowFastInj = buf.int / 100f; slowFastTot = buf.int / 100f
                    actDur = buf.int; totDur = buf.int
                }
            }
            callback(BolusStatusData(fastStatus, fastSeq, fastInj, fastTot,
                slowStatus, slowSeq, slowInj, slowTot, slowFastInj, slowFastTot, actDur, totDur))
        }
    }

    fun startBolus(totalUnits: Float, durationMinutes: Int = 0, immediateUnits: Float = 0f, callback: (Boolean) -> Unit) {
        val totalScaled = Math.round(totalUnits * 100).coerceIn(1, 2500)
        val immediateScaled = Math.round(immediateUnits * 100).coerceIn(0, totalScaled)
        val bolusType: Byte = if (durationMinutes == 0) 1 else 2

        val payload = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(totalScaled).putInt(durationMinutes).putInt(immediateScaled).put(bolusType).array()

        log("Starting bolus: ${"%.2f".format(totalUnits)}U, type=$bolusType")
        BleLog.add("TX", "BOLU", payload.toHex(), "${"%.2f".format(totalUnits)}U")

        val char = charBolusStartStop ?: run { callback(false); return }
        ensureCountersSynced {
            val withCrc = YpsoCrc.appendCrc(payload)
            val encrypted = encryptIfNeeded(withCrc)
            val frames = YpsoFraming.chunkPayload(encrypted)
            writeFramesSequentially(char, frames, 0, callback)
        }
    }

    fun cancelBolus(kind: String = "fast", callback: (Boolean) -> Unit) {
        val bolusType = if (kind == "fast") 1 else 2
        val payload = ByteArray(13).also { it[12] = bolusType.toByte() }
        val char = charBolusStartStop ?: run { callback(false); return }
        ensureCountersSynced {
            val withCrc = YpsoCrc.appendCrc(payload)
            val encrypted = encryptIfNeeded(withCrc)
            val frames = YpsoFraming.chunkPayload(encrypted)
            writeFramesSequentially(char, frames, 0, callback)
        }
    }

    fun startTbr(percent: Int, durationMinutes: Int, callback: (Boolean) -> Unit) {
        val char = charTbrStartStop ?: run { callback(false); return }
        val payload = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(percent).putInt(percent.inv()).putInt(durationMinutes).putInt(durationMinutes.inv()).array()

        log("Starting TBR: ${percent}% for ${durationMinutes}min")
        ensureCountersSynced {
            val encrypted = encryptIfNeeded(payload)
            val frames = YpsoFraming.chunkPayload(encrypted)
            writeFramesSequentially(char, frames, 0, callback)
        }
    }

    fun cancelTbr(callback: (Boolean) -> Unit) = startTbr(100, 0, callback)

    fun syncTime(callback: (Boolean) -> Unit) {
        val now = java.util.Calendar.getInstance()
        val datePayload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(now.get(java.util.Calendar.YEAR).toShort())
            .put((now.get(java.util.Calendar.MONTH) + 1).toByte())
            .put(now.get(java.util.Calendar.DAY_OF_MONTH).toByte()).array()

        val timePayload = byteArrayOf(
            now.get(java.util.Calendar.HOUR_OF_DAY).toByte(),
            now.get(java.util.Calendar.MINUTE).toByte(),
            now.get(java.util.Calendar.SECOND).toByte()
        )

        val dateChar = findCharacteristic(YpsoPumpUuids.CHAR_SYSTEM_DATE) ?: run { callback(false); return }
        ensureCountersSynced {
            val dateEnc = encryptIfNeeded(YpsoCrc.appendCrc(datePayload))
            writeFramesSequentially(dateChar, YpsoFraming.chunkPayload(dateEnc), 0) { dateOk ->
                if (!dateOk) { callback(false); return@writeFramesSequentially }
                val timeChar = findCharacteristic(YpsoPumpUuids.CHAR_SYSTEM_TIME) ?: run { callback(false); return@writeFramesSequentially }
                val timeEnc = encryptIfNeeded(YpsoCrc.appendCrc(timePayload))
                writeFramesSequentially(timeChar, YpsoFraming.chunkPayload(timeEnc), 0, callback)
            }
        }
    }

    // ==================== SETTINGS ====================

    fun readSetting(index: Int, callback: (Int?) -> Unit) {
        val idChar = charSettingId; val valChar = charSettingValue
        if (idChar == null || valChar == null) { callback(null); return }

        ensureCountersSynced {
            val data = encryptIfNeeded(YpsoGlb.encode(index))
            writeFramesSequentially(idChar, YpsoFraming.chunkPayload(data), 0) { ok ->
                if (!ok) { callback(null); return@writeFramesSequentially }
                readExtended(YpsoPumpUuids.CHAR_SETTING_VALUE) { raw ->
                    if (raw == null) { callback(null); return@readExtended }
                    val dec = decryptIfNeeded(raw) ?: run { callback(null); return@readExtended }
                    callback(YpsoGlb.findInPayload(dec))
                }
            }
        }
    }

    fun writeSetting(index: Int, value: Int, callback: (Boolean) -> Unit) {
        val idChar = charSettingId; val valChar = charSettingValue
        if (idChar == null || valChar == null) { callback(false); return }

        ensureCountersSynced {
            val idData = encryptIfNeeded(YpsoGlb.encode(index))
            writeFramesSequentially(idChar, YpsoFraming.chunkPayload(idData), 0) { ok ->
                if (!ok) { callback(false); return@writeFramesSequentially }
                val valData = encryptIfNeeded(YpsoGlb.encode(value))
                writeFramesSequentially(valChar, YpsoFraming.chunkPayload(valData), 0, callback)
            }
        }
    }

    fun readBasalProfile(program: Char, callback: (List<Float>?) -> Unit) {
        val start = when (program) {
            'A' -> SettingsIndex.PROGRAM_A_START; 'B' -> SettingsIndex.PROGRAM_B_START
            else -> { callback(null); return }
        }
        val rates = mutableListOf<Float>()
        readBasalRateSeq(start, start + 23, rates, callback)
    }

    private fun readBasalRateSeq(cur: Int, end: Int, rates: MutableList<Float>, cb: (List<Float>?) -> Unit) {
        if (cur > end) { cb(rates); return }
        readSetting(cur) { v ->
            if (v != null) {
                rates.add(if (v == -1) 0f else v / 100f)
                readBasalRateSeq(cur + 1, end, rates, cb)
            } else cb(null)
        }
    }

    fun readActiveProgram(callback: (Char?) -> Unit) {
        readSetting(SettingsIndex.ACTIVE_PROGRAM) { v ->
            when (v) {
                SettingsIndex.PROGRAM_A_VALUE -> callback('A')
                SettingsIndex.PROGRAM_B_VALUE -> callback('B')
                else -> callback(null)
            }
        }
    }

    // ==================== HISTORY ====================

    fun readHistoryCount(countUuid: UUID, callback: (Int?) -> Unit) {
        readExtended(countUuid) { raw ->
            if (raw == null) { callback(null); return@readExtended }
            val data = decryptIfNeeded(raw) ?: run { callback(null); return@readExtended }
            callback(YpsoGlb.findInPayload(data))
        }
    }

    fun readHistory(
        countUuid: UUID, indexChar: BluetoothGattCharacteristic?, valueUuid: UUID,
        maxEntries: Int = 50, callback: (List<HistoryEntry>) -> Unit
    ) {
        if (indexChar == null) { callback(emptyList()); return }
        readHistoryCount(countUuid) { count ->
            if (count == null || count == 0) { callback(emptyList()); return@readHistoryCount }
            val start = if (maxEntries > 0) maxOf(0, count - maxEntries) else 0
            val entries = mutableListOf<HistoryEntry>()
            readHistorySeq(indexChar, valueUuid, start, count - 1, entries, callback)
        }
    }

    private fun readHistorySeq(
        idxChar: BluetoothGattCharacteristic, valUuid: UUID,
        cur: Int, last: Int, entries: MutableList<HistoryEntry>, cb: (List<HistoryEntry>) -> Unit
    ) {
        if (cur > last) { cb(entries.reversed()); return }
        readHistoryEntry(idxChar, valUuid, cur) { entry ->
            if (entry != null) entries.add(entry)
            readHistorySeq(idxChar, valUuid, cur + 1, last, entries, cb)
        }
    }

    private fun readHistoryEntry(
        idxChar: BluetoothGattCharacteristic, valUuid: UUID, idx: Int, cb: (HistoryEntry?) -> Unit
    ) {
        val data = encryptIfNeeded(YpsoGlb.encode(idx))
        writeFramesSequentially(idxChar, YpsoFraming.chunkPayload(data), 0) { ok ->
            if (!ok) { cb(null); return@writeFramesSequentially }
            readExtended(valUuid) { raw ->
                if (raw == null) { cb(null); return@readExtended }
                val dec = decryptIfNeeded(raw) ?: run { cb(null); return@readExtended }
                val payload = if (YpsoCrc.isValid(dec)) dec.copyOfRange(0, dec.size - 2) else dec
                if (payload.size >= 17) {
                    try { cb(HistoryEntry.parse(payload)) } catch (_: Exception) { cb(null) }
                } else cb(null)
            }
        }
    }

    fun readHistoryEvents(max: Int = 50, cb: (List<HistoryEntry>) -> Unit) =
        readHistory(YpsoPumpUuids.Events.COUNT, charHistoryEventsIndex, YpsoPumpUuids.Events.VALUE, max, cb)

    fun readHistoryAlerts(max: Int = 50, cb: (List<HistoryEntry>) -> Unit) =
        readHistory(YpsoPumpUuids.Alerts.COUNT, charHistoryAlertsIndex, YpsoPumpUuids.Alerts.VALUE, max, cb)

    fun readHistorySystem(max: Int = 50, cb: (List<HistoryEntry>) -> Unit) =
        readHistory(YpsoPumpUuids.System.COUNT, charHistorySystemIndex, YpsoPumpUuids.System.VALUE, max, cb)

    fun readSecurityStatus(callback: (ByteArray?) -> Unit) {
        readExtended(YpsoPumpUuids.CHAR_SEC_STATUS) { raw ->
            callback(if (raw != null) decryptIfNeeded(raw) else null)
        }
    }

    // ==================== HELPERS ====================

    private fun findCharacteristic(uuid: UUID): BluetoothGattCharacteristic? {
        discoveredServices.forEach { svc ->
            svc.getCharacteristic(uuid)?.let { return it }
        }
        return null
    }

    private fun log(message: String) {
        LogRedactor.d(TAG, message)
        _logEvents.tryEmit(LogEvent(System.currentTimeMillis(), message))
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

    override fun close() {
        _connectionState.value = ConnectionState.DISCONNECTED
        _isAuthenticated.value = false
        pumpCryptor = null
        super.close()
    }
}

data class LogEvent(val timestamp: Long, val message: String)
