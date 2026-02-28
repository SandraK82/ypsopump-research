package com.ypsopump.test.commands

import com.ypsopump.test.ble.YpsoBleManager
import com.ypsopump.test.ble.YpsoPumpUuids
import com.ypsopump.test.crypto.PumpCryptor
import com.ypsopump.test.data.*
import com.ypsopump.test.security.LogRedactor
import java.util.UUID

/**
 * Central command orchestrator. Wires BleManager + PumpCryptor.
 *
 * CRITICAL RULE: First command after connect MUST be a read (e.g. getSystemStatus())
 * to sync counters. Violating this causes pump error 138/139.
 */
class CommandExecutor(
    private val bleManager: YpsoBleManager
) {
    private val TAG = "CommandExecutor"

    var lastSystemStatus: SystemStatusData? = null
        private set
    var lastBolusStatus: BolusStatusData? = null
        private set

    fun setCryptor(cryptor: PumpCryptor) {
        bleManager.pumpCryptor = cryptor
    }

    // ==================== BASE COMMANDS (0-4, unencrypted) ====================

    fun readMasterVersion(callback: (String?) -> Unit) {
        bleManager.readCharByUuid(YpsoPumpUuids.CHAR_MASTER_VERSION) { data ->
            callback(data?.let { String(it).trim() })
        }
    }

    fun readBaseVersion(callback: (String?) -> Unit) {
        bleManager.readCharByUuid(YpsoPumpUuids.CHAR_BASE_VERSION) { data ->
            callback(data?.formatVersion())
        }
    }

    fun readSettingsVersion(callback: (String?) -> Unit) {
        bleManager.readCharByUuid(YpsoPumpUuids.CHAR_SETTINGS_VERSION) { data ->
            callback(data?.formatVersion())
        }
    }

    fun readHistoryVersion(callback: (String?) -> Unit) {
        bleManager.readCharByUuid(YpsoPumpUuids.CHAR_HISTORY_VERSION) { data ->
            callback(data?.formatVersion())
        }
    }

    fun authenticate(callback: (Boolean) -> Unit) = bleManager.authenticate(callback)

    // ==================== SETTINGS COMMANDS (5-9, encrypted) ====================

    fun readSetting(index: Int, callback: (Int?) -> Unit) = bleManager.readSetting(index, callback)
    fun writeSetting(index: Int, value: Int, callback: (Boolean) -> Unit) = bleManager.writeSetting(index, value, callback)

    fun readDate(callback: (ByteArray?) -> Unit) = bleManager.readCommand(YpsoPumpUuids.CHAR_SYSTEM_DATE, callback)
    fun readTime(callback: (ByteArray?) -> Unit) = bleManager.readCommand(YpsoPumpUuids.CHAR_SYSTEM_TIME, callback)
    fun syncDateTime(callback: (Boolean) -> Unit) = bleManager.syncTime(callback)

    // ==================== HISTORY COMMANDS (10-25, encrypted) ====================

    fun readEventsCount(callback: (Int?) -> Unit) = bleManager.readHistoryCount(YpsoPumpUuids.Events.COUNT, callback)
    fun readAlertsCount(callback: (Int?) -> Unit) = bleManager.readHistoryCount(YpsoPumpUuids.Alerts.COUNT, callback)
    fun readSystemCount(callback: (Int?) -> Unit) = bleManager.readHistoryCount(YpsoPumpUuids.System.COUNT, callback)

    fun readAllEvents(maxEntries: Int = 0, callback: (List<HistoryEntry>) -> Unit) =
        bleManager.readHistoryEvents(maxEntries, callback)

    fun readAllAlerts(maxEntries: Int = 0, callback: (List<HistoryEntry>) -> Unit) =
        bleManager.readHistoryAlerts(maxEntries, callback)

    fun readAllSystem(maxEntries: Int = 0, callback: (List<HistoryEntry>) -> Unit) =
        bleManager.readHistorySystem(maxEntries, callback)

    fun readBasalProfileA(callback: (List<Float>?) -> Unit) = bleManager.readBasalProfile('A', callback)
    fun readBasalProfileB(callback: (List<Float>?) -> Unit) = bleManager.readBasalProfile('B', callback)
    fun readActiveProgram(callback: (Char?) -> Unit) = bleManager.readActiveProgram(callback)

    fun readExtended(callback: (ByteArray?) -> Unit) =
        bleManager.readCommand(YpsoPumpUuids.CHAR_EXTENDED_READ, callback)

    // ==================== CONTROL COMMANDS (26-32, encrypted, critical) ====================

    fun getSystemStatus(callback: (SystemStatusData?) -> Unit) {
        bleManager.getSystemStatus { status ->
            lastSystemStatus = status
            callback(status)
        }
    }

    fun getBolusStatus(callback: (BolusStatusData?) -> Unit) {
        bleManager.getBolusStatus { status ->
            lastBolusStatus = status
            callback(status)
        }
    }

    fun startBolus(totalUnits: Float, durationMinutes: Int = 0, immediateUnits: Float = 0f, callback: (Boolean) -> Unit) {
        LogRedactor.i(TAG, "BOLUS: ${"%.2f".format(totalUnits)}U, dur=${durationMinutes}min")
        bleManager.startBolus(totalUnits, durationMinutes, immediateUnits, callback)
    }

    fun cancelBolus(kind: String = "fast", callback: (Boolean) -> Unit) {
        LogRedactor.i(TAG, "CANCEL BOLUS: $kind")
        bleManager.cancelBolus(kind, callback)
    }

    fun startTbr(percent: Int, durationMinutes: Int, callback: (Boolean) -> Unit) {
        LogRedactor.i(TAG, "TBR: ${percent}% for ${durationMinutes}min")
        bleManager.startTbr(percent, durationMinutes, callback)
    }

    fun cancelTbr(callback: (Boolean) -> Unit) {
        LogRedactor.i(TAG, "CANCEL TBR")
        bleManager.cancelTbr(callback)
    }

    fun readSecurityStatus(callback: (ByteArray?) -> Unit) = bleManager.readSecurityStatus(callback)

    // ==================== RAW COMMAND ====================

    fun sendRawCommand(uuid: UUID, payload: ByteArray, callback: (Boolean) -> Unit) =
        bleManager.sendCommand(uuid, payload, callback)

    fun readRawCommand(uuid: UUID, callback: (ByteArray?) -> Unit) =
        bleManager.readCommand(uuid, callback)

    // ==================== DEVICE INFO ====================

    fun readDeviceInfo(callback: (Map<String, String>) -> Unit) {
        val info = mutableMapOf<String, String>()
        var pending = 5

        fun done() { if (--pending == 0) callback(info) }

        bleManager.readCharByUuid(YpsoPumpUuids.CHAR_MANUFACTURER) { data ->
            data?.let { info["manufacturer"] = String(it).trim() }; done()
        }
        bleManager.readCharByUuid(YpsoPumpUuids.CHAR_MODEL) { data ->
            data?.let { info["model"] = String(it).trim() }; done()
        }
        bleManager.readCharByUuid(YpsoPumpUuids.CHAR_SERIAL) { data ->
            data?.let { info["serial"] = String(it).trim() }; done()
        }
        bleManager.readCharByUuid(YpsoPumpUuids.CHAR_FIRMWARE) { data ->
            data?.let { info["firmware"] = it.formatVersion() }; done()
        }
        bleManager.readCharByUuid(YpsoPumpUuids.CHAR_SOFTWARE) { data ->
            data?.let { info["software"] = it.formatVersion() }; done()
        }
    }

    private fun ByteArray.formatVersion(): String =
        joinToString(".") { (it.toInt() and 0xFF).toString() }
}
