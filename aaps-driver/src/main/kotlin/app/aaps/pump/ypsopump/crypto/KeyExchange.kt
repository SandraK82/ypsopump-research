package app.aaps.pump.ypsopump.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Curve25519 key exchange + HChaCha20 key derivation for YpsoPump.
 *
 * Implements the local part of the 9-step key exchange protocol:
 * - Step 1: Parse pump challenge + public key (64 bytes)
 * - Step 8: Compute shared key from ECDH + HChaCha20
 *
 * Steps 3-6 (backend/gRPC) are handled externally —
 * either via CamAPS proxy (Frida pump faking) or direct gRPC MITM.
 */
@Singleton
class KeyExchange @Inject constructor() {

    private val lazySodium = LazySodiumAndroid(SodiumAndroid())

    private var appPrivateKey: ByteArray? = null
    private var appPublicKey: ByteArray? = null

    /**
     * Generate a new Curve25519 keypair for this key exchange.
     * @return the app's public key (32 bytes) to send to the backend
     */
    fun generateKeyPair(): ByteArray {
        val publicKey = ByteArray(KEY_SIZE)
        val privateKey = ByteArray(KEY_SIZE)
        lazySodium.cryptoBoxKeypair(publicKey, privateKey)
        appPublicKey = publicKey
        appPrivateKey = privateKey
        return publicKey
    }

    /**
     * Parse the 64-byte response from the pump's key exchange characteristic.
     * @return Pair(challenge: 32 bytes, pumpPublicKey: 32 bytes)
     */
    fun parsePumpChallenge(data: ByteArray): Pair<ByteArray, ByteArray> {
        require(data.size == CHALLENGE_AND_KEY_SIZE) {
            "Expected $CHALLENGE_AND_KEY_SIZE bytes, got ${data.size}"
        }
        val challenge = data.sliceArray(0 until KEY_SIZE)
        val pumpPublicKey = data.sliceArray(KEY_SIZE until CHALLENGE_AND_KEY_SIZE)
        return Pair(challenge, pumpPublicKey)
    }

    /**
     * Compute the shared key from the pump's public key (Step 8).
     *
     * Step 1: raw = crypto_scalarmult(appPrivateKey, pumpPublicKey)   // ECDH
     * Step 2: sharedKey = crypto_core_hchacha20(raw, zeroNonce)       // KDF
     */
    fun computeSharedKey(pumpPublicKey: ByteArray): ByteArray {
        val privateKey = appPrivateKey
            ?: throw IllegalStateException("No keypair generated — call generateKeyPair() first")

        require(pumpPublicKey.size == KEY_SIZE) {
            "Pump public key must be $KEY_SIZE bytes, got ${pumpPublicKey.size}"
        }

        // Step 1: Curve25519 ECDH scalar multiplication
        val rawShared = ByteArray(KEY_SIZE)
        val scalarResult = lazySodium.cryptoScalarmult(rawShared, privateKey, pumpPublicKey)
        if (!scalarResult) throw SecurityException("ECDH scalar multiplication failed")

        // Step 2: HChaCha20 key derivation
        val sharedKey = ByteArray(KEY_SIZE)
        val zeroNonce = ByteArray(16) // 16 bytes of 0x00
        val hchachaResult = lazySodium.cryptoCoreHChaCha20(sharedKey, zeroNonce, rawShared, null)
        if (!hchachaResult) throw SecurityException("HChaCha20 key derivation failed")

        return sharedKey
    }

    /**
     * Import an externally extracted shared key (e.g., from Frida).
     * Bypasses the full key exchange when key was obtained via CamAPS proxy.
     */
    fun importSharedKey(sharedKeyHex: String): ByteArray {
        val bytes = sharedKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        require(bytes.size == KEY_SIZE) { "Shared key must be $KEY_SIZE bytes" }
        return bytes
    }

    companion object {
        const val KEY_SIZE = 32
        const val CHALLENGE_AND_KEY_SIZE = 64  // challenge(32) + pumpPubKey(32)
        const val KEY_EXCHANGE_PAYLOAD_SIZE = 116
    }
}
