package com.ypsopump.test.key

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.ypsopump.test.crypto.PumpCryptor

/**
 * Secure key storage using EncryptedSharedPreferences (AES-256-SIV/GCM).
 * Keys are protected by Android Keystore master key.
 */
class SecureKeyStore(context: Context) {

    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        "ypso_secure_crypto",
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun hasKey(): Boolean = prefs.getString("shared_key", null) != null

    fun getKeyHex(): String? = prefs.getString("shared_key", null)

    fun getKeyBytes(): ByteArray? {
        val hex = getKeyHex() ?: return null
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun storeKey(keyHex: String) {
        require(keyHex.length == 64) { "Shared key must be 64 hex chars (32 bytes)" }
        require(keyHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "Invalid hex characters in key"
        }
        prefs.edit()
            .putString("shared_key", keyHex.lowercase())
            .putLong("shared_key_stored_at", System.currentTimeMillis())
            .apply()
    }

    fun deleteKey() {
        prefs.edit().clear().apply()
    }

    fun getCryptor(): PumpCryptor? {
        return PumpCryptor.fromPrefs(prefs)
    }

    fun createCryptor(sharedKey: ByteArray): PumpCryptor {
        return PumpCryptor.create(prefs, sharedKey)
    }

    fun getStoredAt(): Long = prefs.getLong("shared_key_stored_at", 0)
}
