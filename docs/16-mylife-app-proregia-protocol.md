# 16 — mylife App: ProRegia Protokoll & Key Exchange

## Übersicht

Die mylife App kommuniziert über **gRPC** mit dem ProRegia Backend für den kryptographischen Key Exchange mit der YpsoPump. Das Protokoll ist nahezu identisch mit CamAPS FX, nutzt aber einen eigenen Endpoint.

## ProRegia Backend

### Endpoint-Konfiguration

Die Konfiguration erfolgt über das `IProConfig`-Interface:

```csharp
// CryptoLib.Abstraction.dll / Proregia.Bluetooth.Contracts
interface IProConfig {
    string Host { get; }           // ProRegia Server Hostname
    int Port { get; }              // ProRegia Server Port
    string ApiKey { get; }         // App-spezifischer API Key
    Dictionary CharMap { get; }     // BLE Characteristic Mapping
    Guid GuidVirtualService { get; }
    Guid GuidVirtualCharacteristic { get; }
    ILogger Logger { get; }
    int TimeoutInSeconds { get; }
}
```

| App | Host | Port |
|---|---|---|
| mylife App | `connect.ml.pr.sec01.proregia.io` | 8090 |
| CamAPS FX | `connect.cam.pr.sec01.proregia.io` | 443 |

### gRPC Service Definitionen

Aus den Protobuf-Marshallern in `Ypsomed.mylife.Apps.Services.Validation.dll`:

```protobuf
// Rekonstruierte Proto-Messages
message ServerNonce { bytes server_nonce = 1; }
message DeviceIdentifier { ... }

message EncryptKeyRequest {
    bytes cloud_challenge = 1;
    // + pump public key, app public key, device id, etc.
}
message EncryptKeyResponse { ... }
message EncryptCommanderKeyRequest { ... }  // Für mylife Dose (Bolus)

message KeyAttestation { ... }
message KeyAttestationForJwt { ... }
message KeyAttestationResponse { ... }

message SignJwtRequest { bytes jwt_token = 1; bytes nonce = 2; }
message SignJwtResponse { bytes signature = 1; }

message JwtKeyIdentifier { ... }

message GetFirmwareRequest { ... }
message FirmwareImage { ... }
message FirmwareMetadata { ... }
message FirmwareUpdatedResponse { ... }
```

### gRPC RPCs

```protobuf
// Rekonstruierte Service Definition
service ProregiaService {
    // Key Exchange für BLE-Verschlüsselung
    rpc NonceRequest(DeviceIdentifier) returns (ServerNonce);
    rpc EncryptKey(EncryptKeyRequest) returns (EncryptKeyResponse);

    // Bolus-Verschlüsselung (mylife Dose)
    rpc EncryptCommanderKey(EncryptCommanderKeyRequest) returns (EncryptKeyResponse);

    // Key Attestation (Hardware-basiert)
    rpc NonceForKeyAttestation(DeviceIdentifier) returns (ServerNonce);
    rpc NonceForKeyAttestationJwt(JwtKeyIdentifier) returns (ServerNonce);
    rpc ValidateKeyAttestation(KeyAttestation) returns (KeyAttestationResponse);
    rpc ValidateKeyAttestationJwt(KeyAttestationForJwt) returns (KeyAttestationResponse);

    // JWT Signing
    rpc SignJwt(SignJwtRequest) returns (SignJwtResponse);

    // Firmware Updates
    rpc GetFirmwareForPump(GetFirmwareRequest) returns (FirmwareImage);
}
```

## Key Exchange Ablauf

### Schritt-für-Schritt

```
┌──────────┐     ┌──────────────┐     ┌──────────┐
│  App     │     │  ProRegia    │     │ YpsoPump │
│ (Phone)  │     │  Backend     │     │  (BLE)   │
└────┬─────┘     └──────┬───────┘     └────┬─────┘
     │                   │                  │
     │ 1. BLE Connect    │                  │
     │ ──────────────────────────────────►  │
     │                   │                  │
     │ 2. Get Pump Public Key               │
     │ ◄────────────────────────────────── │
     │                   │                  │
     │ 3. NonceRequest   │                  │
     │ ─────────────────►│                  │
     │ ◄─────────────────│ ServerNonce      │
     │                   │                  │
     │ 4. Play Integrity Token Request      │
     │ ──────► Google ──►│                  │
     │ ◄────── Play ◄────│                  │
     │                   │                  │
     │ 5. Key Attestation│                  │
     │ (Android Keystore)│                  │
     │ ─────────────────►│                  │
     │ ◄─────────────────│ Attestation OK   │
     │                   │                  │
     │ 6. EncryptKey     │                  │
     │ (challenge + keys)│                  │
     │ ─────────────────►│                  │
     │ ◄─────────────────│ EncryptKeyResp   │
     │                   │                  │
     │ 7. Key Exchange Part 1 (BLE)         │
     │ ──────────────────────────────────►  │
     │                   │                  │
     │ 8. Key Exchange Part 2 (BLE)         │
     │ ◄────────────────────────────────── │
     │                   │                  │
     │ 9. EnableCrypto   │                  │
     │ ──────────────────────────────────►  │
     │                   │                  │
     │ ═══ Verschlüsselte BLE Kommunikation │
```

### Implementierung im Code

```csharp
// Proregia.Bluetooth.Backend.RealServer
class RealServer : IServer {
    async Task<ServerNonce> GetServerNonceAsync();
    async Task<EncryptKeyResponse> EncryptKeyAsync();
    async Task<ServerNonce> GetServerNonceForKeyAttestationAsync();
    async Task<KeyAttestationResponse> ValidateKeyAttestationAsync();
}

// Proregia.Bluetooth.ProDevice
class ProDevice {
    async Task KeyExchangeAsync();
    async Task EnableCryptoAsync();
    async Task DisableCryptoAsync();
    async Task DeleteAttestationKey();
    async Task<DateTime> GetEncryptionKeyExpirationDateAsync();
}

// Proregia.Bluetooth.ProCharacteristic
class ProCharacteristic {
    async Task KeyExchangeAsync();    // Part 1
    async Task KeyExchangePart2();     // Part 2
    async Task ReadExtendedChar();
    async Task WriteExtendedChar();
}
```

## Kryptographie

### Verschlüsselung

Die App nutzt **libsodium** (NaCl) für die BLE-Verschlüsselung:

```csharp
// Proregia.Bluetooth.Crypto.CryptoWrapper
class CryptoWrapper {
    async Task InitAsync();
    async Task<byte[]> EncryptAsync();
    async Task<byte[]> DecryptAsync();
    async Task<byte[]> GetPublicKeyAsync();
    async Task SetPumpPublicKeyAsync();
    async Task<byte[]> GenerateOrGetDeviceIdAsync();
    async Task ResetAsync();
}
```

**Algorithmen** (identisch zu CamAPS FX):
- **Key Agreement**: X25519 (Curve25519 ECDH)
- **Encryption**: XChaCha20-Poly1305 (AEAD)
- **Key Derivation**: HChaCha20
- **Hashing**: Blake2b (`Blake2bHashCall.GetBlake2bHash`)

### Schlüsselspeicherung

```csharp
// Proregia.Bluetooth.Crypto.EncryptedAppKeyStore
class EncryptedAppKeyStore {
    async Task<KeyPair> GetKeyPairAsync();        // App X25519 Keypair
    async Task PutKeyPairAsync(KeyPair pair);
    async Task<byte[]> GetPumpPublicKeyAsync();    // Pump Public Key
    async Task PutPumpPublicKeyAsync(byte[] key);
    async Task<byte[]> GetSharedKeyPumpAppAsync(); // Derived Shared Key
    async Task<DateTime> GetSharedKeyPumpAppDateAsync(); // Key Expiry
    async Task<int> GetRebootCounter();
    async Task<int> GetNumericReadCounterFromPump();
    async Task<int> GetNumericWriteCounterFromApp();
    async Task<string> GetAttestationKeyId();
    async Task DeleteAttestationKeyId();
}
```

## mylife Dose (Bolus-Steuerung)

Die mylife App enthält eine spezielle Funktion für Bolus-Abgabe vom Smartphone:

```
EncryptCommanderKey — Separater gRPC Call für Commander-Keys
EncryptCommanderKeyRequest — Enthält zusätzliche Autorisierung
GetBolusStatus — Abfrage des Bolus-Delivery-Status
cBolusDeliveryDoseActivity — Android Activity für Bolus-UI
```

Dies ist der **Commander-Modus**, bei dem die App als "Commander" fungiert und Bolus-Befehle an die Pumpe sendet. Der Commander-Key ist ein **separater kryptographischer Schlüssel**, der über einen eigenen gRPC-Call (`EncryptCommanderKey`) bezogen wird.

**Wichtig**: CamAPS FX hat **denselben `EncryptCommanderKey`-Service** (siehe Doc 05, Service #1). Beide Apps nutzen das identische ProRegia-Protokoll mit denselben gRPC-Services:
- `EncryptKey` → Normaler BLE-Verschlüsselungs-Key (Lesen, Status)
- `EncryptCommanderKey` → Commander-Key für Bolus-Befehle (Schreiben)

Über den BLE Control Service (Index 27: `START_STOP_BOLUS`) werden dann die eigentlichen Bolus-Befehle verschlüsselt an die Pumpe gesendet. Beide Apps können also Bolus-Befehle absetzen — CamAPS FX nutzt dies sowohl für Micro-Boluses im Closed-Loop als auch für manuell berechnete Boluses.

## Key Lifecycle & Counter-Management

Siehe ausführliche Analyse in [19-key-lifecycle-pump-rotation.md](19-key-lifecycle-pump-rotation.md).

### Zusammenfassung

- **Pump Public Key**: Statisch (factory-burned), rotiert **NIEMALS**
- **Shared Key**: Bleibt gültig bis die App einen neuen Key Exchange durchführt
- **28-Tage-Expiry**: Nur app-seitig (`GetEncryptionKeyExpirationDateAsync()`), Pumpe prüft nicht
- **Reboot Counter**: Pumpe zählt Neustarts; nach Neustart muss App erst lesen (Counter-Sync), dann schreiben
- **Commander Key**: Kein separater Lifecycle — nutzt denselben Shared Key wie normale BLE-Verschlüsselung

### Counter in verschlüsselten Nachrichten

Jede verschlüsselte BLE-Nachricht enthält am Ende 12 Bytes:

```
[rebootCounter 4B big-endian] [writeCounter 8B big-endian]
```

| Counter | Richtung | Funktion |
|---|---|---|
| `rebootCounter` | Pump → App | Erkennung von Pumpen-Neustarts |
| `writeCounter` | App → Pump | Monoton steigend pro Befehl |
| `readCounter` | Pump → App | Monoton steigend pro Antwort |

Fehler 138/139 = Counter-Mismatch (nicht Key-Invalidierung).

## Firmware Updates

ProRegia unterstützt auch Pump-Firmware-Updates:

```
GetFirmwareForPump / GetFirmwareForPumpClient
FirmwareImage / FirmwareMetadata
FirmwareUpdatedResponse
IntegrityCheckFailed (Dexcom.Android.Transmitter.Libraries)
```

## Referenz: vicktor/ypsomed-pump Repo

Das GitHub-Repo [vicktor/ypsomed-pump](https://github.com/vicktor/ypsomed-pump) enthält eine vollständige Kotlin-Reimplementation des ProRegia-Protokolls. Dieses Repo nutzt einen **Relay-Proxy**, weil es kein Play Integrity Token generieren kann (Token ist an Package Name + Signing Certificate gebunden). Wir brauchen den Proxy NICHT, da wir STRONG_INTEGRITY über TrickyStore erreichen.
