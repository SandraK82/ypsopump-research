# 04 — Key Exchange: 9-Step Protocol

## Overview

The YpsoPump uses a sophisticated 9-step key exchange protocol involving the app, a backend server (ProRegia gRPC) and the pump hardware. The backend generates a cryptographic 116-byte payload that only the pump can validate, ensuring that only authorised apps can establish a shared key.

## Sequence Diagram

```
App                          Backend (gRPC)                    Pump
 │                               │                               │
 │  STEP 1: BLE Read (64 bytes) │                               │
 │◄──────────────────────────────────────────────────────────────│
 │  [Challenge 32B] + [Pump Public Key 32B (Curve25519)]         │
 │                               │                               │
 │  STEP 2: (OBSOLETE — skipped on Android)                      │
 │                               │                               │
 │  STEP 3: gRPC NonceRequest    │                               │
 │──────────────────────────────►│                               │
 │  DeviceIdentifier(btAddr, id) │                               │
 │◄──────────────────────────────│                               │
 │  ServerNonce (24 bytes)       │                               │
 │                               │                               │
 │  STEP 4: Google Play Integrity API (up to 30s timeout)        │
 │  integrityToken = PlayIntegrity(cloudProjectNumber, nonce)    │
 │                               │                               │
 │  STEP 5: gRPC EncryptKey      │                               │
 │──────────────────────────────►│                               │
 │  challenge, pumpPubKey,       │                               │
 │  appPubKey, btAddr, nonce,    │                               │
 │  integrityToken, deviceId     │                               │
 │◄──────────────────────────────│                               │
 │  encryptedBytes (116 bytes)   │                               │
 │                               │                               │
 │  STEP 6: BLE Write (116 bytes)│                               │
 │──────────────────────────────────────────────────────────────►│
 │  Pump validates 116 bytes cryptographically                   │
 │                               │                               │
 │  STEP 7: Counter Reset (local)│                               │
 │  writeCounter = 0             │                               │
 │  readCounter = 0              │                               │
 │                               │                               │
 │  STEP 8: Shared Key (local)   │                               │
 │  raw = scalarmult(priv, pub)  │                               │
 │  key = hchacha20(raw, 0x00…)  │                               │
 │  → Stored in EncryptedSharedPreferences                       │
 │                               │                               │
 │  STEP 9: COMPLETED            │                               │
```

## Step-by-Step Details

### Step 1: Read Pump Challenge + Public Key

The app reads 64 bytes from a dedicated key exchange characteristic:

| Offset | Size | Content |
|--------|------|---------|
| 0 | 32 bytes | Random challenge (generated per connection) |
| 32 | 32 bytes | Pump's Curve25519 public key (factory-burned, static) |

The pump public key **never changes** — it is burned into the STM32F051 flash at manufacturing.

### Step 2: (Obsolete)

This step was part of an older protocol version and is skipped on current Android builds. It may have involved local key attestation.

### Step 3: Request Server Nonce

The app sends a `NonceRequest` to the ProRegia gRPC backend:

```protobuf
message NonceRequest {
    DeviceIdentifier device = 1;
}

message DeviceIdentifier {
    string bluetooth_address = 1;  // e.g. "AA:BB:CC:DD:EE:FF"
    string device_id = 2;          // Android device ID
}

// Response
message NonceResponse {
    bytes server_nonce = 1;  // 24 bytes
}
```

**Endpoint**: `connect.cam.pr.sec01.proregia.io:443`

### Step 4: Google Play Integrity Check

The app requests an integrity token from the Google Play Integrity API:

```kotlin
val integrityToken = PlayIntegrity.getToken(
    cloudProjectNumber,
    serverNonce
)
// Timeout: up to 30 seconds
```

This token proves to the backend that:
- The app is the genuine CamAPS FX from Play Store
- The device passes integrity checks (not rooted, verified boot)
- The request is fresh (bound to server nonce)

### Step 5: Encrypt Key Request

The app sends all collected data to the backend:

```protobuf
message EncryptKeyRequest {
    bytes challenge = 1;          // 32 bytes from pump
    bytes pump_public_key = 2;    // 32 bytes from pump
    bytes app_public_key = 3;     // 32 bytes (app-generated Curve25519)
    string bluetooth_address = 4;
    bytes server_nonce = 5;       // 24 bytes from Step 3
    string integrity_token = 6;   // Google Play Integrity token
    string device_id = 7;
}

message EncryptKeyResponse {
    bytes encrypted_bytes = 1;    // 116 bytes
}
```

### The 116-Byte Payload (Reconstructed)

```
116 bytes = Cryptographic payload for pump validation
┌──────────────────────────────────────┐
│ App Public Key        (32 bytes)     │  ← So pump can compute shared key
├──────────────────────────────────────┤
│ Challenge-Response    (32 bytes)     │  ← Proves backend knows the challenge
├──────────────────────────────────────┤
│ Nonce / IV            (24 bytes)     │  ← For encryption
├──────────────────────────────────────┤
│ Auth Tag              (16 bytes)     │  ← AEAD MAC (Poly1305 or AES-GCM)
├──────────────────────────────────────┤
│ Metadata              (12 bytes)     │  ← Timestamp, flags, version
└──────────────────────────────────────┘
  = 116 bytes total
```

The payload is encrypted/signed using the pump's **Pre-Shared Key (PSK)** — a 256-bit secret known to the Utimaco Cloud HSM and burned into the pump at manufacturing. **Only the HSM and the pump know this PSK.**

### Step 6: Write to Pump

The 116 bytes are written to the key exchange characteristic. The pump:

1. Decrypts/verifies using its PSK
2. Extracts the app's public key
3. Computes its own copy of the shared key via ECDH
4. Stores the shared key internally
5. Returns success or error via notification

### Step 7: Counter Reset

On successful key exchange, the app resets:
- `writeCounter = 0`
- `readCounter = 0`
- `rebootCounter` is read from the pump

### Step 8: Local Shared Key Computation

```
raw = crypto_scalarmult(appPrivateKey, pumpPublicKey)  // Curve25519
sharedKey = crypto_core_hchacha20(raw, zeroNonce)       // HChaCha20
```

Both the app and pump independently compute the same shared key.

### Step 9: Complete

Keys are stored in `EncryptedSharedPreferences`:

| Key | Value |
|-----|-------|
| `sharedKey` | 32-byte derived key |
| `privateKey` | 32-byte app private key |
| `pumpPublicKey` | 32-byte pump public key |
| `pumpPublicKeyDate` | Timestamp of pump key read |
| `sharedKeyDate` | Timestamp for 28-day expiry check |
| `writeCounter` | Current write counter |
| `readCounter` | Current read counter |

## Backend Infrastructure

### Utimaco Cloud HSM

The backend uses a **Utimaco Cloud HSM** for all cryptographic operations. Each pump's PSK is stored in the HSM, never exposed to application servers. The `EncryptKey` gRPC handler:

1. Verifies the Google Play Integrity token
2. Looks up the pump's PSK by device identifier
3. Constructs and encrypts the 116-byte payload using the PSK
4. Returns it to the app

### No Certificate Pinning

The gRPC channel to `connect.cam.pr.sec01.proregia.io:443` uses standard TLS **without certificate pinning** (`ManagedChannelBuilder` without pinning configuration). This is a significant security finding — see [08-security-findings.md](08-security-findings.md).

## Bypass Strategies

### Strategy 1: Frida Key Extraction (Post Key Exchange)

After a legitimate key exchange completes, extract the shared key via Frida hooks. See [../guides/frida-key-extraction.md](../guides/frida-key-extraction.md).

### Strategy 2: Pump Faking with CamAPS Hijack

Use Frida to fake the pump towards CamAPS, intercept the 116-byte payload, then relay it to the real pump from a custom app. This allows using CamAPS as a "key exchange proxy" without reimplementing the backend communication. See [09-bypass-options.md](09-bypass-options.md).

### Strategy 3: gRPC MITM

Since there is no certificate pinning, a MITM proxy on a rooted phone can intercept the full key exchange. See [08-security-findings.md](08-security-findings.md).

### Strategy 4: SWD Flash Extraction

If the STM32F051 RDP is not enabled, the PSK can be extracted via SWD, enabling completely offline key exchanges without any backend.

## Error Handling

| Error Code | Status | Description |
|-----------|--------|-------------|
| `KEY_EXCHANGE_ERROR_BLOCKED_OR_BUSY` | 136 | Pump temporarily blocked |
| `KEY_EXCHANGE_ERROR_PUMP_NOT_READY` | 137 | Pump not in key exchange mode |
| `KEY_EXCHANGE_ERROR_INVALID_SERVER_NONCE` | 160 | Nonce NULL or wrong length |
| `KEY_EXCHANGE_ERROR_GENERAL` | 161 | General key exchange error |
| `KEY_EXCHANGE_ERROR_NULL_AUTH_TOKEN` | 162 | Integrity token is null |
| `KEY_EXCHANGE_ERROR_INVALID_AUTH_TOKEN` | 163 | Integrity token invalid |
| `KEY_EXCHANGE_RUNNING` | 178 | Key exchange already in progress |
