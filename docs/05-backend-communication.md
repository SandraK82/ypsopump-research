# 05 — Backend Communication: gRPC, Cloud Services & APIs

## Overview

CamAPS FX communicates with multiple backend systems for key exchange, data synchronisation, CGM integration and firmware updates. This document maps all discovered endpoints, protocols and credentials.

## ProRegia gRPC Backend (Pump Key Exchange)

### Endpoint

```
connect.cam.pr.sec01.proregia.io:443
```

**No certificate pinning** — standard TLS via `ManagedChannelBuilder`.

### gRPC Services (10 discovered)

Full namespace: `Proregia.Bluetooth.Contracts.Proto.*`

| # | Service | gRPC Class | Purpose |
|---|---------|-----------|---------|
| 1 | `EncryptCommanderKey` | `EncryptCommanderKeyGrpc` | Commander key exchange (secondary app role) |
| 2 | `EncryptKey` | `EncryptKeyGrpc` | **Primary key exchange** (Step 5 of 9-step protocol) |
| 3 | `ValidateKeyAttestation` | `ValidateKeyAttestationGrpc` | Key attestation validation |
| 4 | `ValidateKeyAttestationJwt` | `ValidateKeyAttestationJwtGrpc` | JWT-based key attestation |
| 5 | `NonceForKeyAttestation` | `NonceForKeyAttestationJwtGrpc` | Nonce request (Step 3) |
| 6 | `SignJwt` | `SignJwtGrpc` | Server-side JWT signing |
| 7 | `FirmwareUpdateCheck` | `FirmwareUpdateCheckGrpc` | Check for firmware updates |
| 8 | `GetFirmwareForPump` | `GetFirmwareForPumpGrpc` | Download firmware image |
| 9 | `FirmwareUpdateConfirmation` | `FirmwareUpdateConfirmationGrpc` | Confirm update installation |
| 10 | `LogEvent` | `LogEventGrpc` | Remote event logging |

### Key Protobuf Messages

```protobuf
// Key Exchange (corrected — 8 fields including Metrics)
message EncryptKeyRequest {
    string challenge = 1;                    // 32 bytes (uppercase hex via extractServiceName.write)
    string pump_public_key = 2;              // 32 bytes Curve25519 (uppercase hex)
    string app_public_key = 3;               // 32 bytes Curve25519 (uppercase hex)
    string bt_address = 4;                   // BT MAC address (uppercase hex)
    string message_attestation_object = 5;   // Google Play Integrity token (JWS string)
    string nonce = 6;                        // 24 bytes server nonce (uppercase hex)
    Metrics metrics = 7;                     // Device and app metadata (see below)
    string device_id = 8;                    // UUID string from SharedPreferences "proregia_prefs"
}

message Metrics {
    string platform = 1;              // "Android" (hardcoded via obfuscated string)
    string model = 2;                 // Build.MODEL (e.g. "SM-A226B")
    string os_type = 3;               // Build.VERSION.SDK_INT as string (e.g. "33")
    string os_version = 4;            // Build.VERSION.RELEASE (e.g. "13")
    string manufacturer = 5;          // Build.MANUFACTURER (e.g. "samsung")
    string device_serial = 6;         // Hardcoded "no Serial"
    string application_name = 7;      // context.getApplicationInfo().loadLabel() (e.g. "mylife CamAPS FX")
    string application_package = 8;   // context.getApplicationInfo().packageName
    string library_version = 9;       // ProBluetooth SDK version (KmsEnvelopeAeadKeyBuilder.write())
    bool xamarin = 10;                // false (Android native, not Xamarin)
}

message EncryptKeyResponse {
    bytes encrypted_bytes = 1;  // 116 bytes
}

// Firmware
message GetFirmwareRequest {
    string pump_serial = 1;
    string current_version = 2;
}

message FirmwareImage {
    bytes data = 1;
    FirmwareMetadata metadata = 2;
}

message FirmwareMetadata {
    string version = 1;
    string checksum = 2;
    int64 size = 3;
}

message FirmwareUpdatedResponse {
    bool success = 1;
}
```

## AWS Backend (Data Storage)

### DynamoDB

| Table | Hash Key | Notable Fields |
|-------|----------|---------------|
| `StoredSettings` | `userId` | `alertSettingsEncrypted`, `algorithmDataBlock`, `dataKey`, `userEmail`, `ttl` |

### AWS Services Used

| Service | Purpose |
|---------|---------|
| **Cognito** | Identity Provider / User Authentication (`CognitoUser25` class) |
| **STS** | Temporary credential generation |
| **S3** | Data storage (CGM data, logs, exports) |
| **DynamoDB** | Settings persistence |

### Authentication Flow

```
1. User enters email + password
2. SRP (Secure Remote Password) handshake with Cognito
3. Cognito returns JWT tokens (id, access, refresh)
4. STS exchanges JWT for temporary AWS credentials
5. App accesses DynamoDB / S3 with temp credentials
```

## MyLife Cloud API (Azure API Management)

### Required HTTP Headers

| Header | Min Length | Purpose |
|--------|-----------|---------|
| `Authorization` | 10 chars | `Bearer <JWT token>` |
| `Gateway-JWT-Header` | — | Separate gateway JWT |
| `Subscription-Key` | 32 chars | Azure APIM subscription key |
| `Public-Key-Header` | 32 chars | E2E encryption public key |
| `Target-Country-Header` | 2 chars | Regional routing (e.g. "DE", "GB") |

### Data Sync Protocol

```
SyncDecryptedDTO
    → Encrypt with Public-Key-Header
    → SyncEncryptedDTO
    → Upload to MyLife Cloud
    → Stored in patient's account
```

## Dexcom Integration (OAuth2 / OIDC)

### Hardcoded Configuration (in cleartext!)

All Dexcom OAuth2 configuration is hardcoded in cleartext in `UAMTokenActivity.java` (DexGuard-obfuscated class name varies by version):

- **Authorization, Token & UserInfo endpoints** — point to `.dexcomdev.com` (development/staging domain, not production!)
- **Client ID** — hardcoded UUID in cleartext
- **Client Secret** — trivial hardcoded string in cleartext (literally the word "secret")
- **Redirect URI** — `camaps://redirect_uri`
- **Scopes** — `AccountManagement phone profile DataShare openid offline_access`

### Security Issues

1. **`.dexcomdev.com`** — Development/staging environment URLs shipped in production app (visible in decompiled `UAMTokenActivity.java`)
2. **Client secret** — Trivially exploitable hardcoded cleartext value
3. **No dynamic configuration** — hardcoded values cannot be rotated without app update

### Supported CGM Types

| Enum | CGM | ID |
|------|-----|-----|
| `DEXCOM_G6` | Dexcom G6 | 140 |
| `DEXCOM_G7` | Dexcom G7 | 141 |
| `LIBRE_3` | FreeStyle Libre 3 | 142 |
| `LIBRE_3_PLUS` | FreeStyle Libre 3+ | 143 |

## Glooko / Diasend Integration

### OAuth2 Authentication

```
client_id:     (obfuscated via DexGuard)
client_secret: (obfuscated via DexGuard)
login:         email + password
```

### Device Information Sent

```json
{
    "applicationVersion": "1.4",
    "buildNumber": "190",
    "device": "Pixel 7",
    "deviceId": "...",
    "gitHash": "...",
    "os": "Android 14"
}
```

### Security Check

The Glooko authentication class performs root detection:
- Checks for `/system/xbin/su`
- Checks for `Superuser.apk`

## Firebase Configuration

All Firebase configuration values are stored in cleartext in `res/values/strings.xml` within the APK:

| Key | Location |
|-----|----------|
| Google API Key | `strings.xml` → `google_api_key` (cleartext) |
| Firebase DB URL | `strings.xml` → `firebase_database_url` (cleartext) |
| GCP Project | Derivable from Firebase DB URL |
| App ID | `strings.xml` → `google_app_id` (cleartext) |
| GCM Sender ID | `strings.xml` → `gcm_defaultSenderId` (cleartext) |
| Storage Bucket | `strings.xml` → `google_storage_bucket` (cleartext) |

### Firebase Services Used

| Service | Purpose |
|---------|---------|
| Crashlytics | Crash reporting |
| Performance Monitoring | App performance telemetry |
| Remote Config | Dynamic feature flags |
| Cloud Messaging (FCM) | Push notifications |
| Analytics | Usage analytics |
| Sessions | Session tracking |

## Network Architecture Diagram

```
┌──────────────┐
│  CamAPS FX   │
│   (Android)  │
└──────┬───────┘
       │
       ├──── BLE ──── YpsoPump
       │
       ├──── gRPC/TLS ──── ProRegia (connect.cam.pr.sec01.proregia.io)
       │                         └── Utimaco Cloud HSM
       │
       ├──── HTTPS ──── MyLife Cloud (Azure APIM)
       │
       ├──── HTTPS ──── AWS (Cognito, DynamoDB, S3, STS)
       │
       ├──── OAuth2 ──── Dexcom (.dexcomdev.com)
       │
       ├──── OAuth2 ──── Glooko / Diasend
       │
       └──── HTTPS ──── Firebase (crashlytics, analytics, etc.)
```

## Relevance for Third-Party Driver

For building a driver app, only the **ProRegia gRPC** backend is relevant (for key exchange). All other backends handle data sync, CGM, and analytics — none are required for pump communication.

**Alternative**: Using the Frida pump-faking approach, even the ProRegia backend can be avoided entirely — CamAPS performs the key exchange, and the extracted shared key is used by the custom driver.
