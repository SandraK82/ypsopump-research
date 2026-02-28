package com.ypsopump.test.crypto

import android.content.SharedPreferences
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * Session crypto state manager for YpsoPump communication.
 *
 * Wire format: ciphertext+tag(16B) || nonce(24B)
 *
 * Before encrypting, 12 counter bytes are appended to plaintext:
 *   reboot_counter(4B LE) + write_counter(8B LE)
 *
 * CRITICAL: Must decrypt at least one pump response (e.g. getSystemStatus())
 * before sending any encrypted command. Otherwise reboot_counter may be wrong
 * and pump rejects with error 138/139.
 */
class PumpCryptor(
    val sharedKey: ByteArray,
    private val prefs: SharedPreferences
) {
    var readCounter: Long = prefs.getLong("read_counter", 0)
        private set
    var writeCounter: Long = prefs.getLong("write_counter", 0)
        private set
    var rebootCounter: Int = prefs.getInt("reboot_counter", 0)
        private set

    val isInitialized: Boolean get() = sharedKey.size == 32

    private fun persist() {
        prefs.edit()
            .putLong("read_counter", readCounter)
            .putLong("write_counter", writeCounter)
            .putInt("reboot_counter", rebootCounter)
            .apply()
    }

    fun encrypt(payload: ByteArray): ByteArray {
        val nonce = ByteArray(24).also { SecureRandom().nextBytes(it) }

        val buffer = ByteBuffer.allocate(payload.size + 12).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(payload)
        buffer.putInt(rebootCounter)
        writeCounter++
        buffer.putLong(writeCounter)

        val ciphertext = YpsoCrypto.xchacha20Poly1305Encrypt(
            buffer.array(), byteArrayOf(), nonce, sharedKey
        )
        persist()
        return ciphertext + nonce
    }

    fun decrypt(data: ByteArray): ByteArray {
        if (data.size < 24 + 16) throw IllegalArgumentException("Encrypted payload too short")

        val nonce = data.copyOfRange(data.size - 24, data.size)
        val ciphertext = data.copyOfRange(0, data.size - 24)

        val plaintext = YpsoCrypto.xchacha20Poly1305Decrypt(
            ciphertext, byteArrayOf(), nonce, sharedKey
        )

        val counters = ByteBuffer.wrap(plaintext, plaintext.size - 12, 12)
            .order(ByteOrder.LITTLE_ENDIAN)
        val peerRebootCounter = counters.int
        val numericCounter = counters.long

        if (peerRebootCounter != rebootCounter) {
            rebootCounter = peerRebootCounter
            writeCounter = 0
        }
        readCounter = numericCounter
        persist()

        return plaintext.copyOfRange(0, plaintext.size - 12)
    }

    companion object {
        fun fromPrefs(prefs: SharedPreferences): PumpCryptor? {
            val keyHex = prefs.getString("shared_key", null) ?: return null
            val expiresAt = prefs.getLong("shared_key_expires_at", Long.MAX_VALUE)
            if (System.currentTimeMillis() > expiresAt) return null
            return PumpCryptor(keyHex.hexToBytes(), prefs)
        }

        fun create(prefs: SharedPreferences, sharedKey: ByteArray): PumpCryptor {
            prefs.edit()
                .putString("shared_key", sharedKey.toHexString())
                .putLong("shared_key_expires_at", System.currentTimeMillis() + 3650L * 24 * 3600 * 1000)
                .putLong("read_counter", 0)
                .putLong("write_counter", 0)
                .putInt("reboot_counter", 0)
                .apply()
            return PumpCryptor(sharedKey, prefs)
        }

        private fun String.hexToBytes(): ByteArray =
            chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        private fun ByteArray.toHexString(): String =
            joinToString("") { "%02x".format(it) }
    }
}
