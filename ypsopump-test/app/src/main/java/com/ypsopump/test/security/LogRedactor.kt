package com.ypsopump.test.security

import android.util.Log

/**
 * Utility for logging that redacts sensitive data (keys, nonces, tokens).
 * Replaces 64-char hex strings (32 bytes = shared keys) with [REDACTED].
 */
object LogRedactor {

    private val HEX_KEY_PATTERN = Regex("[0-9a-fA-F]{64}")
    private val HEX_NONCE_PATTERN = Regex("[0-9a-fA-F]{48}") // 24 bytes = nonces

    fun redact(message: String): String {
        return message
            .replace(HEX_KEY_PATTERN, "[KEY_REDACTED]")
            .replace(HEX_NONCE_PATTERN, "[NONCE_REDACTED]")
    }

    fun d(tag: String, message: String) {
        Log.d(tag, redact(message))
    }

    fun i(tag: String, message: String) {
        Log.i(tag, redact(message))
    }

    fun w(tag: String, message: String) {
        Log.w(tag, redact(message))
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, redact(message), throwable)
        } else {
            Log.e(tag, redact(message))
        }
    }
}
