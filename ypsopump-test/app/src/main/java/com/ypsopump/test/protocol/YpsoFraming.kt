package com.ypsopump.test.protocol

/**
 * Multi-frame BLE protocol for YpsoPump.
 * Each BLE write is max 20 bytes: 1 byte header + 19 bytes payload.
 * Header format: ((frame_idx+1) << 4 & 0xF0) | (total_frames & 0x0F)
 */
object YpsoFraming {

    private const val MAX_PAYLOAD_PER_FRAME = 19

    fun chunkPayload(data: ByteArray): List<ByteArray> {
        if (data.isEmpty()) {
            return listOf(byteArrayOf(0x10))
        }

        val totalFrames = maxOf(1, (data.size + MAX_PAYLOAD_PER_FRAME - 1) / MAX_PAYLOAD_PER_FRAME)
        val frames = mutableListOf<ByteArray>()

        for (idx in 0 until totalFrames) {
            val start = idx * MAX_PAYLOAD_PER_FRAME
            val end = minOf(start + MAX_PAYLOAD_PER_FRAME, data.size)
            val chunk = data.copyOfRange(start, end)

            val header = (((idx + 1) shl 4) and 0xF0) or (totalFrames and 0x0F)
            frames.add(byteArrayOf(header.toByte()) + chunk)
        }

        return frames
    }

    fun parseMultiFrameRead(frames: List<ByteArray>): ByteArray {
        val merged = mutableListOf<Byte>()
        for (frame in frames) {
            if (frame.size > 1) {
                merged.addAll(frame.drop(1))
            }
        }
        return merged.toByteArray()
    }

    fun getTotalFrames(firstByte: Byte): Int {
        return (firstByte.toInt() and 0x0F).let { if (it == 0) 1 else it }
    }
}
