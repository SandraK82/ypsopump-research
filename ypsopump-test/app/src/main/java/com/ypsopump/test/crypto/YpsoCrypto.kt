package com.ypsopump.test.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic operations for YpsoPump protocol.
 *
 * - X25519 for Diffie-Hellman key exchange (API 33+)
 * - ChaCha20-Poly1305 for authenticated encryption (API 28+)
 * - HChaCha20 pure Kotlin for subkey derivation
 *
 * Wire format: ciphertext+tag(16B) || nonce(24B) -- nonce at END
 */
object YpsoCrypto {

    private val X25519_PUBKEY_DER_HEADER = byteArrayOf(
        0x30, 0x2A,
        0x30, 0x05,
        0x06, 0x03, 0x2B, 0x65, 0x6E,
        0x03, 0x21, 0x00
    )

    // ==================== X25519 Key Exchange ====================

    data class X25519KeyPair(
        val privateKey: PrivateKey,
        val publicKey: PublicKey,
        val rawPublicKey: ByteArray
    )

    fun generateX25519KeyPair(): X25519KeyPair {
        val kpg = KeyPairGenerator.getInstance("X25519")
        val keyPair = kpg.generateKeyPair()
        val rawPub = publicKeyToRawBytes(keyPair.public)
        return X25519KeyPair(keyPair.private, keyPair.public, rawPub)
    }

    fun publicKeyToRawBytes(publicKey: PublicKey): ByteArray {
        val encoded = publicKey.encoded
        return encoded.copyOfRange(encoded.size - 32, encoded.size)
    }

    fun rawBytesToPublicKey(rawBytes: ByteArray): PublicKey {
        require(rawBytes.size == 32) { "X25519 public key must be 32 bytes" }
        val derEncoded = X25519_PUBKEY_DER_HEADER + rawBytes
        val keyFactory = KeyFactory.getInstance("X25519")
        return keyFactory.generatePublic(X509EncodedKeySpec(derEncoded))
    }

    fun x25519SharedSecret(myPrivateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("X25519")
        ka.init(myPrivateKey)
        ka.doPhase(peerPublicKey, true)
        return ka.generateSecret()
    }

    fun deriveSharedKey(myPrivateKey: PrivateKey, pumpPublicKeyRaw: ByteArray): ByteArray {
        val peerPubKey = rawBytesToPublicKey(pumpPublicKeyRaw)
        val secret = x25519SharedSecret(myPrivateKey, peerPubKey)
        return hchacha20(secret, ByteArray(16))
    }

    // ==================== HChaCha20 (pure Kotlin) ====================

    private fun rotl32(v: Int, n: Int): Int = (v shl n) or (v ushr (32 - n))

    private fun quarterRound(state: IntArray, a: Int, b: Int, c: Int, d: Int) {
        state[a] = state[a] + state[b]; state[d] = rotl32(state[d] xor state[a], 16)
        state[c] = state[c] + state[d]; state[b] = rotl32(state[b] xor state[c], 12)
        state[a] = state[a] + state[b]; state[d] = rotl32(state[d] xor state[a], 8)
        state[c] = state[c] + state[d]; state[b] = rotl32(state[b] xor state[c], 7)
    }

    fun hchacha20(
        key: ByteArray,
        nonce: ByteArray,
        constant: ByteArray = "expand 32-byte k".toByteArray(Charsets.US_ASCII)
    ): ByteArray {
        require(key.size == 32) { "Key must be 32 bytes" }
        require(nonce.size == 16) { "Nonce must be 16 bytes" }
        require(constant.size == 16) { "Constant must be 16 bytes" }

        val buf = ByteBuffer.wrap(constant + key + nonce).order(ByteOrder.LITTLE_ENDIAN)
        val state = IntArray(16) { buf.int }

        repeat(10) {
            quarterRound(state, 0, 4, 8, 12)
            quarterRound(state, 1, 5, 9, 13)
            quarterRound(state, 2, 6, 10, 14)
            quarterRound(state, 3, 7, 11, 15)
            quarterRound(state, 0, 5, 10, 15)
            quarterRound(state, 1, 6, 11, 12)
            quarterRound(state, 2, 7, 8, 13)
            quarterRound(state, 3, 4, 9, 14)
        }

        val output = intArrayOf(
            state[0], state[1], state[2], state[3],
            state[12], state[13], state[14], state[15]
        )
        val result = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        output.forEach { result.putInt(it) }
        return result.array()
    }

    // ==================== XChaCha20-Poly1305 ====================

    fun xchacha20Poly1305Encrypt(
        plaintext: ByteArray,
        aad: ByteArray,
        nonce: ByteArray,
        key: ByteArray
    ): ByteArray {
        require(nonce.size == 24) { "XChaCha20 nonce must be 24 bytes" }
        require(key.size == 32) { "Key must be 32 bytes" }

        val subkey = hchacha20(key, nonce.copyOfRange(0, 16))
        val subnonce = ByteArray(12)
        System.arraycopy(nonce, 16, subnonce, 4, 8)

        val cipher = Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(subkey, "ChaCha20"), IvParameterSpec(subnonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    fun xchacha20Poly1305Decrypt(
        ciphertext: ByteArray,
        aad: ByteArray,
        nonce: ByteArray,
        key: ByteArray
    ): ByteArray {
        require(nonce.size == 24) { "XChaCha20 nonce must be 24 bytes" }
        require(key.size == 32) { "Key must be 32 bytes" }

        val subkey = hchacha20(key, nonce.copyOfRange(0, 16))
        val subnonce = ByteArray(12)
        System.arraycopy(nonce, 16, subnonce, 4, 8)

        val cipher = Cipher.getInstance("ChaCha20/Poly1305/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(subkey, "ChaCha20"), IvParameterSpec(subnonce))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    // ==================== Serial / Address Helpers ====================

    fun serialToBtAddress(serial: String): ByteArray {
        val num = serial.toLong() % 10000000
        val little = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(num.toInt()).array()
        return byteArrayOf(0xEC.toByte(), 0x2A, 0xF0.toByte(), little[2], little[1], little[0])
    }

    fun serialToMac(serial: String): String {
        val num = serial.toLong().let { if (it > 10000000) it - 10000000 else it }
        val hex = "%06X".format(num)
        return "EC:2A:F0:${hex.substring(0, 2)}:${hex.substring(2, 4)}:${hex.substring(4, 6)}"
    }
}
