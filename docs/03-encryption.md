# 03 — Encryption: Multi-Layer Security Architecture

## Overview

CamAPS FX employs **five distinct encryption layers**, ranging from BLE transport to app hardening. This document details each layer, focusing on the BLE transport encryption that must be replicated for any third-party driver.

## Layer 1: BLE Transport Encryption (XChaCha20-Poly1305)

This is the primary encryption layer for pump ↔ app communication.

### Algorithm

| Property | Value |
|----------|-------|
| **Cipher** | XChaCha20-Poly1305 AEAD |
| **Library** | libsodium 1.0.20 via Lazysodium (JNA) |
| **Key size** | 256 bits (32 bytes) |
| **Nonce size** | 192 bits (24 bytes) — randomly generated per message |
| **Auth tag** | 128 bits (16 bytes) — Poly1305 MAC |
| **Functions** | `crypto_aead_xchacha20poly1305_ietf_encrypt` / `_decrypt` |

### Message Structure

**Important**: The nonce is **appended** (not prepended) in the BLE payload.

```
Encrypted BLE Payload:
┌──────────────────────────────────┬──────────────┐
│  Ciphertext + Tag (var + 16B)    │  Nonce (24B) │
└──────────────────────────────────┴──────────────┘

Plaintext before encryption:
┌──────────────────────┬──────────────────────────────┐
│  Command Data (var)  │  Counter Data (12B)          │
│                      │  [rebootCounter 4B, big-end] │
│                      │  [writeCounter 8B, big-end]  │
└──────────────────────┴──────────────────────────────┘
```

### XChaCha20 vs ChaCha20

XChaCha20 uses a 24-byte nonce instead of the standard 12-byte nonce. This is critical because:

1. **Random nonce safety** — with 192 bits, random nonce collisions are astronomically unlikely (birthday bound at ~2^96 messages)
2. **No nonce counter needed** — each message generates a fresh random nonce
3. **HChaCha20 subkey derivation** — internally, XChaCha20 uses the first 16 bytes of the nonce with HChaCha20 to derive a subkey, then uses the remaining 8 bytes as a standard ChaCha20 nonce

### Implementation (from decompiled `write.java`)

```
// Encryption (from decompiled write.java, method "read()", line 121)
nonce = random_bytes(24)
counter_data = rebootCounter(4 bytes, big-endian) || writeCounter(8 bytes, big-endian)
plaintext = command_data || counter_data
ciphertext = crypto_aead_xchacha20poly1305_ietf_encrypt(
    plaintext,
    associated_data=null,
    nonce,
    shared_key
)
ble_payload = ciphertext || nonce  // NOTE: nonce APPENDED, not prepended!

// Decryption (from write.java, method "RemoteActionCompatParcelizer()", line 492)
nonce = ble_payload[-24:]                    // last 24 bytes
ciphertext = ble_payload[:-24]               // everything before nonce
plaintext = crypto_aead_xchacha20poly1305_ietf_decrypt(
    ciphertext,
    associated_data=null,
    nonce,
    shared_key
)
command_data = plaintext[:-12]
reboot_counter = plaintext[-12:-8]           // 4 bytes, big-endian int
read_counter = plaintext[-8:]                // 8 bytes, big-endian long
```

## Layer 2: Key Derivation (Shared Key via Curve25519)

The 32-byte shared key used for XChaCha20-Poly1305 is derived from an ECDH exchange:

```
Step 1: raw_shared = crypto_scalarmult(app_private_key, pump_public_key)
        // Curve25519 ECDH — produces 32-byte raw shared secret

Step 2: shared_key = crypto_core_hchacha20(raw_shared, zero_nonce)
        // HChaCha20 KDF — converts raw ECDH output to a proper key
        // zero_nonce = 16 bytes of 0x00
```

### Key Properties

| Key | Size | Storage | Lifetime |
|-----|------|---------|----------|
| App private key | 32 bytes | EncryptedSharedPreferences | Per key exchange |
| App public key | 32 bytes | Sent to backend in Step 5 | Per key exchange |
| Pump public key | 32 bytes | EncryptedSharedPreferences | Static (factory-burned) |
| Shared key | 32 bytes | EncryptedSharedPreferences | 28 days (app-enforced) |

### Critical Finding: App-Side Expiry Only

The **28-day key expiration is enforced APP-SIDE ONLY**. The pump itself does not check the age of the shared key. This means:

- Manipulating `sharedKeyDate` in SharedPreferences extends key validity indefinitely
- Once a shared key is extracted, it works until the next key exchange occurs
- The pump accepts encrypted commands with a valid shared key regardless of age

## Layer 3: Backend / Cloud Sync Encryption

Data synchronized to the cloud uses separate encryption:

| Algorithm | Purpose | Details |
|-----------|---------|---------|
| RSA/ECB/PKCS1Padding | Asymmetric key wrapping | Found in `getMaximumBolusSuggestion` class |
| AES/CBC/PKCS5Padding | Symmetric data encryption | General data at rest |
| PBKDF2WithHmacSHA256 | Key derivation from password | Cloud authentication |
| AES-128-CBC (no auth!) | Account encryption | SHA-256 truncated to 16 bytes — **weak** |

### Cloud Sync Flow

```
SyncDecryptedDTO → encrypt → SyncEncryptedDTO → upload to MyLife Cloud
```

**Vulnerability**: AES-128-CBC without authentication (no HMAC/AEAD). SHA-256 hash truncated to 16 bytes for key material. This is susceptible to padding oracle attacks.

## Layer 4: Local Database (SQLCipher)

| Property | Value |
|----------|-------|
| **Engine** | SQLCipher 4.9.0 Commercial |
| **Native lib** | `libd7c23b.so` (5.7 MB) with embedded OpenSSL 3.0.16 |
| **Encryption** | AES-256 per-page encryption |
| **Integrity** | HMAC per page |
| **KDF** | PBKDF2 (configurable iterations) |
| **Hash** | SHA-256 / SHA-512 for HMAC |

The SQLCipher database stores all local pump data, CGM readings, and algorithm state. The database key is derived from user credentials and stored in the Android Keystore.

## Layer 5: App Hardening (DexGuard)

See [07-obfuscation.md](07-obfuscation.md) for full details. Key crypto-relevant points:

- All crypto method names are obfuscated
- String constants (including algorithm names) are encrypted at runtime
- Native security library (`libe61d.so`) performs anti-tampering checks
- The closed-loop algorithm is stored in an AES-256 encrypted native library (`liba532a9.so`)

## Encryption Stack Summary

```
┌─────────────────────────────────────────────┐
│  Layer 5: DexGuard (code obfuscation)       │
├─────────────────────────────────────────────┤
│  Layer 4: SQLCipher (local DB encryption)   │
├─────────────────────────────────────────────┤
│  Layer 3: AES-CBC / RSA (cloud sync)        │
├─────────────────────────────────────────────┤
│  Layer 2: Curve25519 ECDH + HChaCha20 KDF  │
├─────────────────────────────────────────────┤
│  Layer 1: XChaCha20-Poly1305 (BLE AEAD)    │  ← MUST REPLICATE
└─────────────────────────────────────────────┘
```

For building a third-party driver, only **Layers 1 and 2** need to be replicated. Layers 3–5 are specific to the CamAPS FX app.

## Practical Implementation

### Using Lazysodium-Android (Java/Kotlin)

```kotlin
// Dependency: com.goterl:lazysodium-android:5.1.0
// Dependency: net.java.dev.jna:jna:5.14.0@aar

val lazySodium = LazySodiumAndroid(SodiumAndroid())

// Key generation
val keyPair = lazySodium.cryptoBoxKeypair()
val appPrivateKey = keyPair.secretKey  // 32 bytes
val appPublicKey = keyPair.publicKey   // 32 bytes

// Shared key computation (after receiving pumpPublicKey)
val rawShared = ByteArray(32)
lazySodium.cryptoScalarmult(rawShared, appPrivateKey, pumpPublicKey)

val sharedKey = ByteArray(32)
val zeroNonce = ByteArray(16)  // 16 zero bytes
lazySodium.cryptoCoreHChaCha20(sharedKey, zeroNonce, rawShared, null)

// Encrypt
val nonce = lazySodium.randomBytesBuf(24)
val plaintext = commandData + writeCounterBytes + rebootCounterBytes
val ciphertext = ByteArray(plaintext.size + 16)  // +16 for tag
lazySodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
    ciphertext, null, plaintext, plaintext.size.toLong(),
    null, 0, null, nonce, sharedKey
)
val blePayload = nonce + ciphertext

// Decrypt
val nonce = blePayload.sliceArray(0 until 24)
val ct = blePayload.sliceArray(24 until blePayload.size)
val decrypted = ByteArray(ct.size - 16)
lazySodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
    decrypted, null, null, ct, ct.size.toLong(),
    null, 0, nonce, sharedKey
)
```

## Counter Management

See [02-ble-protocol.md](02-ble-protocol.md) for counter details. Key points:

- `writeCounter` must be monotonically increasing per session
- `readCounter` validates incoming messages
- `rebootCounter` detects pump restarts — shared key survives reboots
- Counter errors return `COUNTER_ERROR` (status code 139)
