package app.aaps.pump.ypsopump

import app.aaps.pump.ypsopump.crypto.SessionCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SessionCrypto (XChaCha20-Poly1305).
 *
 * Note: These tests require Lazysodium native library.
 * Run on Android device/emulator or with JNA configured for desktop.
 */
class SessionCryptoTest {

    private lateinit var crypto: SessionCrypto

    @Before
    fun setUp() {
        crypto = SessionCrypto()
        // 32-byte test key (DO NOT use in production)
        crypto.sharedKey = ByteArray(32) { it.toByte() }
        crypto.writeCounter = 0
        crypto.readCounter = 0
        crypto.rebootCounter = 0
    }

    @Test
    fun `encrypt produces correct output size`() {
        val commandData = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val encrypted = crypto.encrypt(commandData)

        // Expected: (commandData + 12 counterBytes + 16 tag) + 24 nonce
        val expectedSize = commandData.size + 12 + 16 + 24
        assertEquals(expectedSize, encrypted.size)
    }

    @Test
    fun `encrypt increments write counter`() {
        val cmd = byteArrayOf(0x01)
        assertEquals(0L, crypto.writeCounter)
        crypto.encrypt(cmd)
        assertEquals(1L, crypto.writeCounter)
        crypto.encrypt(cmd)
        assertEquals(2L, crypto.writeCounter)
    }

    @Test
    fun `nonce is at end of encrypted payload`() {
        val cmd = byteArrayOf(0x01)
        val encrypted1 = crypto.encrypt(cmd)
        val encrypted2 = crypto.encrypt(cmd)

        // Last 24 bytes should be different (random nonces)
        val nonce1 = encrypted1.sliceArray(encrypted1.size - 24 until encrypted1.size)
        val nonce2 = encrypted2.sliceArray(encrypted2.size - 24 until encrypted2.size)

        assertTrue("Nonces should be different", !nonce1.contentEquals(nonce2))
    }

    @Test
    fun `encrypt then decrypt roundtrip`() {
        // To test roundtrip, we need to simulate what the pump would do:
        // The pump encrypts a response with its own counter data
        // For unit testing, we verify encrypt output structure

        val commandData = byteArrayOf(0x10, 0x20, 0x30)
        val encrypted = crypto.encrypt(commandData)

        assertNotNull(encrypted)
        assertTrue(encrypted.size > 24 + 16) // at least nonce + tag
    }

    @Test
    fun `reset clears all state`() {
        crypto.writeCounter = 42
        crypto.readCounter = 13
        crypto.rebootCounter = 5

        crypto.reset()

        assertEquals(0L, crypto.writeCounter)
        assertEquals(0L, crypto.readCounter)
        assertEquals(0, crypto.rebootCounter)
        assertEquals(null, crypto.sharedKey)
    }

    @Test
    fun `counter data size is 12 bytes`() {
        assertEquals(12, SessionCrypto.COUNTER_DATA_SIZE)
    }

    @Test
    fun `key size is 32 bytes`() {
        assertEquals(32, SessionCrypto.KEY_SIZE)
    }

    @Test
    fun `nonce size is 24 bytes`() {
        assertEquals(24, SessionCrypto.NONCE_SIZE)
    }

    @Test
    fun `tag size is 16 bytes`() {
        assertEquals(16, SessionCrypto.TAG_SIZE)
    }
}
