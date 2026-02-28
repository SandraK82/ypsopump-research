package com.ypsopump.test.data

import com.ypsopump.test.protocol.YpsoCrc
import java.nio.ByteBuffer
import java.nio.ByteOrder

// ==================== Delivery Mode ====================

object DeliveryMode {
    const val STOPPED = 0
    const val BASAL = 1
    const val TBR = 2
    const val BOLUS_FAST = 3
    const val BOLUS_EXTENDED = 4
    const val BOLUS_AND_BASAL = 5
    const val PRIMING = 6
    const val PAUSED = 7

    fun name(mode: Int): String = when (mode) {
        STOPPED -> "Stopped"
        BASAL -> "Basal"
        TBR -> "TBR Active"
        BOLUS_FAST -> "Fast Bolus"
        BOLUS_EXTENDED -> "Extended Bolus"
        BOLUS_AND_BASAL -> "Bolus + Basal"
        PRIMING -> "Priming"
        PAUSED -> "Paused"
        else -> "Unknown($mode)"
    }
}

// ==================== Event Types ====================

object EventType {
    const val BOLUS_DELAYED_RUNNING = 1
    const val BOLUS_IMMEDIATE = 2
    const val BOLUS_DELAYED = 3
    const val PRIMING_FINISHED = 4
    const val BOLUS_STEP_CHANGED = 5
    const val BASAL_PROFILE_CHANGED = 6
    const val BASAL_PROFILE_A_CHANGED = 7
    const val BASAL_PROFILE_B_CHANGED = 8
    const val BASAL_PROFILE_TEMP_RUNNING = 9
    const val BASAL_PROFILE_TEMP = 10
    const val DATE_CHANGED = 12
    const val TIME_CHANGED = 13
    const val PUMP_MODE_CHANGED = 14
    const val REWIND_FINISHED = 16
    const val BOLUS_COMBINED_RUNNING = 17
    const val BOLUS_COMBINED = 18
    const val BOLUS_IMMEDIATE_RUNNING = 19
    const val BOLUS_DELAYED_BACKUP = 20
    const val BOLUS_COMBINED_BACKUP = 21
    const val BASAL_PROFILE_TEMP_BACKUP = 22
    const val DAILY_TOTAL_INSULIN = 23
    const val BATTERY_REMOVED = 24
    const val CANNULA_PRIMING_FINISHED = 25
    const val BOLUS_BLIND = 26
    const val BOLUS_BLIND_RUNNING = 27
    const val BOLUS_BLIND_ABORT = 28
    const val BOLUS_IMMEDIATE_ABORT = 29
    const val BOLUS_DELAYED_ABORT = 30
    const val BOLUS_COMBINED_ABORT = 31
    const val BASAL_PROFILE_TEMP_ABORT = 32
    const val BOLUS_AMOUNT_CAP_CHANGED = 33
    const val BASAL_RATE_CAP_CHANGED = 34
    const val DELIVERY_STATUS_CHANGED = 150

    // Alert events
    const val A_BATTERY_REMOVED = 100
    const val A_BATTERY_EMPTY = 101
    const val A_REUSABLE_ERROR = 102
    const val A_NO_CARTRIDGE = 103
    const val A_CARTRIDGE_EMPTY = 104
    const val A_OCCLUSION = 105
    const val A_AUTO_STOP = 106
    const val A_LIPO_DISCHARGED = 107
    const val A_BATTERY_REJECTED = 108

    fun name(type: Int): String = when (type) {
        BOLUS_DELAYED_RUNNING -> "Bolus Delayed Running"
        BOLUS_IMMEDIATE -> "Bolus Immediate"
        BOLUS_DELAYED -> "Bolus Delayed"
        PRIMING_FINISHED -> "Priming Finished"
        BOLUS_STEP_CHANGED -> "Bolus Step Changed"
        BASAL_PROFILE_CHANGED -> "Basal Profile Changed"
        BASAL_PROFILE_A_CHANGED -> "Basal Profile A Changed"
        BASAL_PROFILE_B_CHANGED -> "Basal Profile B Changed"
        BASAL_PROFILE_TEMP_RUNNING -> "TBR Running"
        BASAL_PROFILE_TEMP -> "TBR Completed"
        DATE_CHANGED -> "Date Changed"
        TIME_CHANGED -> "Time Changed"
        PUMP_MODE_CHANGED -> "Pump Mode Changed"
        REWIND_FINISHED -> "Rewind Finished"
        BOLUS_COMBINED_RUNNING -> "Bolus Combined Running"
        BOLUS_COMBINED -> "Bolus Combined"
        BOLUS_IMMEDIATE_RUNNING -> "Bolus Immediate Running"
        BOLUS_DELAYED_BACKUP -> "Bolus Delayed Backup"
        BOLUS_COMBINED_BACKUP -> "Bolus Combined Backup"
        BASAL_PROFILE_TEMP_BACKUP -> "TBR Backup"
        DAILY_TOTAL_INSULIN -> "Daily Total Insulin"
        BATTERY_REMOVED -> "Battery Removed"
        CANNULA_PRIMING_FINISHED -> "Cannula Priming Finished"
        BOLUS_BLIND -> "Bolus Blind"
        BOLUS_BLIND_RUNNING -> "Bolus Blind Running"
        BOLUS_BLIND_ABORT -> "Bolus Blind Abort"
        BOLUS_IMMEDIATE_ABORT -> "Bolus Immediate Abort"
        BOLUS_DELAYED_ABORT -> "Bolus Delayed Abort"
        BOLUS_COMBINED_ABORT -> "Bolus Combined Abort"
        BASAL_PROFILE_TEMP_ABORT -> "TBR Abort"
        BOLUS_AMOUNT_CAP_CHANGED -> "Bolus Amount Cap Changed"
        BASAL_RATE_CAP_CHANGED -> "Basal Rate Cap Changed"
        A_BATTERY_REMOVED -> "Alert: Battery Removed"
        A_BATTERY_EMPTY -> "Alert: Battery Empty"
        A_REUSABLE_ERROR -> "Alert: Reusable Error"
        A_NO_CARTRIDGE -> "Alert: No Cartridge"
        A_CARTRIDGE_EMPTY -> "Alert: Cartridge Empty"
        A_OCCLUSION -> "Alert: Occlusion"
        A_AUTO_STOP -> "Alert: Auto Stop"
        A_LIPO_DISCHARGED -> "Alert: LiPo Discharged"
        A_BATTERY_REJECTED -> "Alert: Battery Rejected"
        DELIVERY_STATUS_CHANGED -> "Delivery Status Changed"
        else -> "Unknown($type)"
    }

    fun isBolusEvent(type: Int): Boolean = type in listOf(
        BOLUS_IMMEDIATE, BOLUS_DELAYED, BOLUS_COMBINED,
        BOLUS_IMMEDIATE_RUNNING, BOLUS_DELAYED_RUNNING, BOLUS_COMBINED_RUNNING,
        BOLUS_IMMEDIATE_ABORT, BOLUS_DELAYED_ABORT, BOLUS_COMBINED_ABORT,
        BOLUS_BLIND, BOLUS_BLIND_RUNNING, BOLUS_BLIND_ABORT,
        BOLUS_STEP_CHANGED, BOLUS_AMOUNT_CAP_CHANGED
    )

    fun isTbrEvent(type: Int): Boolean = type in listOf(
        BASAL_PROFILE_TEMP, BASAL_PROFILE_TEMP_RUNNING,
        BASAL_PROFILE_TEMP_BACKUP, BASAL_PROFILE_TEMP_ABORT
    )

    fun isAlertEvent(type: Int): Boolean = type in 100..199
}

// ==================== Bolus Notification Status ====================

object BolusNotificationStatus {
    const val IDLE = 0
    const val DELIVERING = 1
    const val CANCELLED = 3
    const val COMPLETED = 4

    fun name(status: Int): String = when (status) {
        IDLE -> "Idle"
        DELIVERING -> "Delivering"
        CANCELLED -> "Cancelled"
        COMPLETED -> "Completed"
        else -> "Unknown($status)"
    }

    fun isTerminal(status: Int): Boolean = status != IDLE && status != DELIVERING
}

// ==================== Settings Index ====================

object SettingsIndex {
    const val ACTIVE_PROGRAM = 1
    const val PROGRAM_A_START = 14
    const val PROGRAM_A_END = 37
    const val PROGRAM_B_START = 38
    const val PROGRAM_B_END = 61
    const val PROGRAM_A_VALUE = 3
    const val PROGRAM_B_VALUE = 10
}

// ==================== Data Classes ====================

data class SystemStatusData(
    val deliveryMode: Int,
    val deliveryModeName: String,
    val insulinRemaining: Float,
    val batteryPercent: Int
)

data class BolusStatusData(
    val fastStatus: Int,
    val fastSequence: Long,
    val fastInjected: Float,
    val fastTotal: Float,
    val slowStatus: Int,
    val slowSequence: Long,
    val slowInjected: Float,
    val slowTotal: Float,
    val slowFastPartInjected: Float,
    val slowFastPartTotal: Float,
    val actualDuration: Int,
    val totalDuration: Int
)

data class HistoryEntry(
    val timestamp: Long,
    val entryType: Int,
    val entryTypeName: String,
    val value1: Int,
    val value2: Int,
    val value3: Int,
    val sequence: Long,
    val index: Int
) {
    companion object {
        private const val YPSO_EPOCH_OFFSET_SEC = 946684800L

        fun parse(data: ByteArray): HistoryEntry {
            require(data.size >= 17) { "History entry must be at least 17 bytes, got ${data.size}" }
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val pumpSeconds = buf.int.toLong() and 0xFFFFFFFFL
            val timestamp = pumpSeconds + YPSO_EPOCH_OFFSET_SEC
            val entryType = buf.get().toInt() and 0xFF
            val value1 = buf.short.toInt() and 0xFFFF
            val value2 = buf.short.toInt() and 0xFFFF
            val value3 = buf.short.toInt() and 0xFFFF
            val sequence = buf.int.toLong() and 0xFFFFFFFFL
            val index = buf.short.toInt() and 0xFFFF
            return HistoryEntry(
                timestamp = timestamp,
                entryType = entryType,
                entryTypeName = EventType.name(entryType),
                value1 = value1,
                value2 = value2,
                value3 = value3,
                sequence = sequence,
                index = index
            )
        }
    }
}

data class BolusNotification(
    val fastStatus: Int,
    val fastStatusName: String,
    val fastSequence: Long,
    val slowStatus: Int,
    val slowStatusName: String,
    val slowSequence: Long
) {
    companion object {
        fun parse(data: ByteArray): BolusNotification? {
            if (data.size < 10) return null
            val payload = if (data.size >= 12 && YpsoCrc.isValid(data)) {
                data.copyOfRange(0, data.size - 2)
            } else {
                data
            }
            if (payload.size < 10) return null
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val fastStatus = buf.get().toInt() and 0xFF
            val fastSeq = buf.int.toLong() and 0xFFFFFFFFL
            val slowStatus = buf.get().toInt() and 0xFF
            val slowSeq = buf.int.toLong() and 0xFFFFFFFFL
            return BolusNotification(
                fastStatus = fastStatus,
                fastStatusName = BolusNotificationStatus.name(fastStatus),
                fastSequence = fastSeq,
                slowStatus = slowStatus,
                slowStatusName = BolusNotificationStatus.name(slowStatus),
                slowSequence = slowSeq
            )
        }
    }
}
