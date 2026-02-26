package app.aaps.pump.ypsopump.comm.commands

import app.aaps.pump.ypsopump.comm.YpsoCommand
import app.aaps.pump.ypsopump.comm.YpsoCommandCodes
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * START_STOP_BOLUS (index 27) — deliver or cancel a bolus.
 *
 * Units are encoded as integer × 10 (e.g., 1.5U → 15).
 * Minimum step: 0.1 U (PumpType.YPSOPUMP.bolusSize).
 */
class BolusCommand(
    private val units: Double,
    private val start: Boolean = true
) : YpsoCommand(YpsoCommandCodes.START_STOP_BOLUS) {

    var deliveredUnits: Double = 0.0; private set
    var bolusState: Int = 0; private set

    override fun encode(): ByteArray {
        val unitsX10 = (units * 10).toInt()
        return ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(if (start) 0x01.toByte() else 0x00.toByte()) // start=1, stop=0
            .putShort(unitsX10.toShort())
            .array()
            .sliceArray(0 until 3)
    }

    override fun decode(data: ByteArray) {
        if (data.size >= 4) {
            bolusState = data[0].toInt() and 0xFF
            deliveredUnits = (data.getUInt16(1)).toDouble() / 10.0
            success = bolusState == 0x01 // accepted
        } else {
            errorCode = if (data.isNotEmpty()) data[0].toInt() and 0xFF else -1
            success = false
        }
    }
}
