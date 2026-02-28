package com.ypsopump.test.key

/**
 * Validates and imports shared keys from various sources:
 * - Hex string input (64 chars = 32 bytes)
 * - File import (raw hex content)
 */
object KeyImporter {

    sealed class ImportResult {
        data class Success(val keyHex: String) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }

    fun fromHexString(input: String): ImportResult {
        val cleaned = input.trim()
            .replace(" ", "")
            .replace(":", "")
            .replace("-", "")
            .replace("\n", "")
            .replace("0x", "")
            .lowercase()

        if (cleaned.isEmpty()) {
            return ImportResult.Error("Key is empty")
        }

        if (!cleaned.all { it in '0'..'9' || it in 'a'..'f' }) {
            return ImportResult.Error("Invalid hex characters found")
        }

        if (cleaned.length != 64) {
            return ImportResult.Error(
                "Key must be exactly 32 bytes (64 hex chars), got ${cleaned.length / 2} bytes (${cleaned.length} chars)"
            )
        }

        // Additional validation: reject all-zeros key
        if (cleaned.all { it == '0' }) {
            return ImportResult.Error("All-zero key is not valid")
        }

        return ImportResult.Success(cleaned)
    }

    fun fromFileContent(content: String): ImportResult {
        // Try to find 64 consecutive hex chars in file content
        val lines = content.lines()

        for (line in lines) {
            val trimmed = line.trim()
            // Skip comments and empty lines
            if (trimmed.startsWith("#") || trimmed.startsWith("//") || trimmed.isEmpty()) continue

            // If line has a key=value pattern, take the value
            val value = if (trimmed.contains("=")) {
                trimmed.substringAfter("=").trim()
            } else {
                trimmed
            }

            val result = fromHexString(value)
            if (result is ImportResult.Success) return result
        }

        return ImportResult.Error("No valid 32-byte hex key found in file content")
    }

    fun formatKeyForDisplay(keyHex: String): String {
        return keyHex.chunked(4).joinToString(" ").chunked(25).joinToString("\n")
    }
}
