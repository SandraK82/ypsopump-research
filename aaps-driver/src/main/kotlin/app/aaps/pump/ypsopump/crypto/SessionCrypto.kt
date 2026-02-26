package app.aaps.pump.ypsopump.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * XChaCha20-Poly1305 encryption/decryption for YpsoPump BLE communication.
 *
 * Message format (from decompiled write.java):
 *   Encrypt output:  ciphertext || nonce[24]     (nonce APPENDED)
 *   Decrypt input:   ciphertext || nonce[24]      (nonce at END)
 *   Plaintext:       commandData || rebootCounter[4] || writeCounter[8]
 */
@Singleton
class SessionCrypto @Inject constructor() {

    private val lazySodium = LazySodiumAndroid(SodiumAndroid())

    var sharedKey: ByteArray? = null
    var writeCounter: Long = 0L
    var readCounter: Long = 0L
    var rebootCounter: Int = 0

    val isInitialized: Boolean
        get() = sharedKey != null

    /**
     * Encrypt command data for writing to the pump.
     * @return BLE payload: ciphertext+tag || nonce
     */
    fun encrypt(commandData: ByteArray): ByteArray {
        val key = sharedKey ?: throw IllegalStateException("No shared key set")

        // Build plaintext: command + rebootCounter(4B big-endian) + writeCounter(8B big-endian)
        val counterData = ByteBuffer.allocate(COUNTER_DATA_SIZE)
            .putInt(rebootCounter)
            .putLong(writeCounter)
            .array()
        val plaintext = commandData + counterData

        // Random 24-byte nonce
        val nonce = ByteArray(NONCE_SIZE)
        lazySodium.randombytes_buf(nonce, NONCE_SIZE)

        // Encrypt: output = ciphertext + 16-byte tag
        val ciphertext = ByteArray(plaintext.size + TAG_SIZE)
        val ciphertextLen = longArrayOf(0)
        val result = lazySodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
            ciphertext, ciphertextLen,
            plaintext, plaintext.size.toLong(),
            null, 0, null,
            nonce, key
        )
        if (!result) throw SecurityException("Encryption failed")

        writeCounter++

        // Return ciphertext || nonce (nonce APPENDED, not prepended!)
        return ciphertext + nonce
    }

    /**
     * Decrypt data received from pump.
     * @param blePayload BLE payload: ciphertext+tag || nonce
     * @return decrypted command data (without counters)
     */
    fun decrypt(blePayload: ByteArray): ByteArray {
        val key = sharedKey ?: throw IllegalStateException("No shared key set")

        if (blePayload.size < NONCE_SIZE + TAG_SIZE) {
            throw IllegalArgumentException("Payload too short: ${blePayload.size} bytes")
        }

        // Nonce is the LAST 24 bytes
        val nonce = blePayload.sliceArray(blePayload.size - NONCE_SIZE until blePayload.size)
        val ciphertext = blePayload.sliceArray(0 until blePayload.size - NONCE_SIZE)

        // Decrypt
        val plaintext = ByteArray(ciphertext.size - TAG_SIZE)
        val plaintextLen = longArrayOf(0)
        val result = lazySodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
            plaintext, plaintextLen, null,
            ciphertext, ciphertext.size.toLong(),
            null, 0, nonce, key
        )
        if (!result) throw SecurityException("Decryption failed — invalid key or tampered data")

        // Parse counters from end of plaintext
        if (plaintext.size >= COUNTER_DATA_SIZE) {
            val buf = ByteBuffer.wrap(plaintext, plaintext.size - COUNTER_DATA_SIZE, COUNTER_DATA_SIZE)
            val pumpRebootCounter = buf.getInt()
            val pumpCounter = buf.getLong()

            // Handle reboot detection
            if (pumpRebootCounter > rebootCounter) {
                rebootCounter = pumpRebootCounter
                writeCounter = 0L
            } else if (pumpRebootCounter < 0) {
                throw IllegalArgumentException("Invalid reboot counter: $pumpRebootCounter")
            }

            // Validate read counter (must be monotonically increasing)
            if (readCounter > 0 && pumpCounter <= readCounter) {
                throw SecurityException("Read counter not increasing: $pumpCounter <= $readCounter")
            }
            readCounter = pumpCounter

            return plaintext.sliceArray(0 until plaintext.size - COUNTER_DATA_SIZE)
        }

        return plaintext
    }

    fun reset() {
        sharedKey = null
        writeCounter = 0L
        readCounter = 0L
        rebootCounter = 0
    }

    companion object {
        const val KEY_SIZE = 32
        const val NONCE_SIZE = 24
        const val TAG_SIZE = 16
        const val COUNTER_DATA_SIZE = 12 // rebootCounter(4) + writeCounter(8)
    }
}
