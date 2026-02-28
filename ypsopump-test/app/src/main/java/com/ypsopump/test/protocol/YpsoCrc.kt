package com.ypsopump.test.protocol

/**
 * CRC16 implementation for YpsoPump protocol.
 * Uses CRC-32 polynomial 0x04C11DB7 with bitstuffing,
 * returns lower 16 bits as 2 bytes LE.
 */
object YpsoCrc {

    private const val CRC_POLY = 0x04C11DB7L

    private val CRC_TABLE = LongArray(256).also { table ->
        for (idx in 0 until 256) {
            var v = idx.toLong() shl 24
            for (bit in 0 until 8) {
                v = if (v and 0x80000000L != 0L) {
                    ((v shl 1) and 0xFFFFFFFFL) xor CRC_POLY
                } else {
                    (v shl 1) and 0xFFFFFFFFL
                }
            }
            table[idx] = v
        }
    }

    private fun bitstuff(data: ByteArray): ByteArray {
        if (data.isEmpty()) return byteArrayOf()
        val blockCount = (data.size + 3) / 4
        val stuffed = ByteArray(blockCount * 4)
        for (block in 0 until blockCount) {
            val base = block * 4
            for (idx in 0 until 4) {
                val src = base + idx
                stuffed[base + 3 - idx] = if (src < data.size) data[src] else 0
            }
        }
        return stuffed
    }

    fun crc16(payload: ByteArray): ByteArray {
        var crc = 0xFFFFFFFFL
        for (byte in bitstuff(payload)) {
            val tableIdx = ((crc shr 24) xor (byte.toLong() and 0xFF)) and 0xFF
            crc = ((crc shl 8) and 0xFFFFFFFFL) xor CRC_TABLE[tableIdx.toInt()]
        }
        val result = (crc and 0xFFFFL).toInt()
        return byteArrayOf(
            (result and 0xFF).toByte(),
            ((result shr 8) and 0xFF).toByte()
        )
    }

    fun isValid(payload: ByteArray): Boolean {
        if (payload.size < 2) return false
        val data = payload.copyOfRange(0, payload.size - 2)
        val crcBytes = payload.copyOfRange(payload.size - 2, payload.size)
        return crc16(data).contentEquals(crcBytes)
    }

    fun appendCrc(payload: ByteArray): ByteArray {
        return payload + crc16(payload)
    }

    fun stripIfValid(payload: ByteArray): ByteArray {
        return if (isValid(payload)) payload.copyOfRange(0, payload.size - 2) else payload
    }
}
