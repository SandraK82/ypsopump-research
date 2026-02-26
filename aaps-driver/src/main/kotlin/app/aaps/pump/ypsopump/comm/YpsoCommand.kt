package app.aaps.pump.ypsopump.comm

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Base class for YpsoPump BLE commands.
 * Each command encapsulates encoding of the request and decoding of the response.
 */
abstract class YpsoCommand(val commandCode: YpsoCommandCodes) {

    /** Encode the command request data (before encryption). */
    abstract fun encode(): ByteArray

    /** Decode the response data (after decryption). */
    abstract fun decode(data: ByteArray)

    /** Whether this command was successfully executed. */
    var success: Boolean = false
        protected set

    /** Error code from pump response, if any. */
    var errorCode: Int = 0
        protected set

    companion object {
        fun ByteArray.getUInt16(offset: Int): Int =
            ByteBuffer.wrap(this, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

        fun ByteArray.getUInt32(offset: Int): Long =
            ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

        fun ByteArray.getInt32(offset: Int): Int =
            ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

        fun Int.toLeBytes(size: Int = 4): ByteArray =
            ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).putInt(this).array()
                .sliceArray(0 until size)
    }
}
