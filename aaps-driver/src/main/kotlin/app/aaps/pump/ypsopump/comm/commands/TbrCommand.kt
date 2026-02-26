package app.aaps.pump.ypsopump.comm.commands

import app.aaps.pump.ypsopump.comm.YpsoCommand
import app.aaps.pump.ypsopump.comm.YpsoCommandCodes
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * START_STOP_TBR (index 29) — set or cancel a Temporary Basal Rate.
 *
 * TBR is percentage-based (PumpType.YPSOPUMP.tempBasalType = Percent).
 * Duration in 15-minute steps (specialBasalDurations = [15, 30]).
 */
class TbrCommand(
    private val percentage: Int,
    private val durationMinutes: Int,
    private val start: Boolean = true
) : YpsoCommand(YpsoCommandCodes.START_STOP_TBR) {

    var tbrState: Int = 0; private set
    var activePercent: Int = 100; private set
    var remainingMinutes: Int = 0; private set

    override fun encode(): ByteArray {
        return if (start) {
            ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(0x01.toByte()) // start
                .putShort(percentage.toShort())
                .putShort(durationMinutes.toShort())
                .array()
                .sliceArray(0 until 5)
        } else {
            byteArrayOf(0x00) // stop TBR
        }
    }

    override fun decode(data: ByteArray) {
        if (data.size >= 5) {
            tbrState = data[0].toInt() and 0xFF
            activePercent = data.getUInt16(1)
            remainingMinutes = data.getUInt16(3)
            success = tbrState == 0x01 // accepted
        } else {
            errorCode = if (data.isNotEmpty()) data[0].toInt() and 0xFF else -1
            success = false
        }
    }
}
