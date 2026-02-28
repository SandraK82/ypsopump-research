package com.ypsopump.test.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GLB safe variable encoding for YpsoPump protocol.
 * 8 bytes = value (u32 LE) + bitwise complement (u32 LE).
 * Integrity check: value XOR complement == 0xFFFFFFFF.
 */
object YpsoGlb {

    fun encode(value: Int): ByteArray {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(value)
        buf.putInt(value.inv())
        return buf.array()
    }

    fun decode(data: ByteArray): Int {
        require(data.size >= 8) { "GLB data must be at least 8 bytes, got ${data.size}" }
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val value = buf.int
        val check = buf.int
        require(value == check.inv()) {
            "GLB safe variable integrity check failed: $value vs ${check.inv()}"
        }
        return value
    }

    fun findInPayload(data: ByteArray): Int? {
        if (data.size < 8) return null
        for (start in 0..data.size - 8) {
            val buf = ByteBuffer.wrap(data, start, 8).order(ByteOrder.LITTLE_ENDIAN)
            val value = buf.int
            val check = buf.int
            if (value == check.inv()) return value
        }
        return null
    }
}
