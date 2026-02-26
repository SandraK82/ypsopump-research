package app.aaps.pump.ypsopump.comm.commands

import app.aaps.pump.ypsopump.comm.YpsoCommand
import app.aaps.pump.ypsopump.comm.YpsoCommandCodes

/**
 * History entry read commands following the COUNT/INDEX/VALUE pattern.
 *
 * Usage:
 *   1. CountCommand(ALARM_ENTRY_COUNT) → entryCount
 *   2. For i in 0..<entryCount:
 *      a. IndexCommand(ALARM_ENTRY_INDEX, i)
 *      b. ValueCommand(ALARM_ENTRY_VALUE) → raw bytes
 */
class CountCommand(commandCode: YpsoCommandCodes) : YpsoCommand(commandCode) {
    var entryCount: Int = 0; private set

    override fun encode(): ByteArray = byteArrayOf(0x00)

    override fun decode(data: ByteArray) {
        if (data.size >= 4) {
            entryCount = data.getInt32(0)
            success = true
        } else if (data.size >= 2) {
            entryCount = data.getUInt16(0)
            success = true
        } else {
            success = false
        }
    }
}

class IndexCommand(commandCode: YpsoCommandCodes, private val index: Int) : YpsoCommand(commandCode) {
    override fun encode(): ByteArray = index.toLeBytes()

    override fun decode(data: ByteArray) {
        // Index write acknowledgment
        success = data.isEmpty() || (data.isNotEmpty() && data[0].toInt() == 0)
    }
}

class ValueCommand(commandCode: YpsoCommandCodes) : YpsoCommand(commandCode) {
    var rawValue: ByteArray = byteArrayOf(); private set

    override fun encode(): ByteArray = byteArrayOf(0x00)

    override fun decode(data: ByteArray) {
        rawValue = data
        success = data.isNotEmpty()
    }
}
