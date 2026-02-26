package app.aaps.pump.ypsopump.comm.commands

import app.aaps.pump.ypsopump.comm.YpsoCommand
import app.aaps.pump.ypsopump.comm.YpsoCommandCodes

/**
 * GET_SYSTEM_STATUS (index 30) — reads battery, reservoir, pump state.
 */
class StatusCommand : YpsoCommand(YpsoCommandCodes.GET_SYSTEM_STATUS) {

    var batteryPercent: Int = 0; private set
    var reservoirUnits: Double = 0.0; private set
    var pumpState: Int = 0; private set
    var isSuspended: Boolean = false; private set
    var activeTbrPercent: Int = 100; private set
    var activeTbrRemainingMinutes: Int = 0; private set

    override fun encode(): ByteArray = byteArrayOf(0x00) // simple read request

    override fun decode(data: ByteArray) {
        if (data.isEmpty()) {
            success = false
            return
        }
        // Response parsing — exact field offsets to be confirmed via BLE sniffing
        // Preliminary structure based on DataRepositoryImp field analysis:
        if (data.size >= 12) {
            batteryPercent = data[0].toInt() and 0xFF
            reservoirUnits = (data.getUInt16(1)).toDouble() / 10.0
            pumpState = data[3].toInt() and 0xFF
            isSuspended = pumpState == 0x02
            activeTbrPercent = data.getUInt16(4)
            activeTbrRemainingMinutes = data.getUInt16(6)
            success = true
        } else {
            errorCode = if (data.isNotEmpty()) data[0].toInt() and 0xFF else -1
            success = false
        }
    }
}
