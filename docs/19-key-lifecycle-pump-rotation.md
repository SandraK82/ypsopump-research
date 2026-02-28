# 19 — Key Lifecycle: Pump-seitige Rotation & Counter-Management

## Übersicht

Dieses Dokument analysiert, was die Pumpe zum kryptographischen Key beiträgt, ob sie eigenständig Schlüssel rotiert, und wie das Counter-Management funktioniert. Die Erkenntnisse stammen aus der Analyse von:
- CamAPS FX Decompilation (Docs 03, 04)
- mylife App .NET Assembly-Analyse (Docs 14-18)
- vicktor/ypsomed-pump Kotlin-Reimplementation (`PumpCryptor.kt`)

## Pump-Beiträge zum Key Exchange

### Was die Pumpe liefert

| Beitrag | Größe | Statisch/Dynamisch | Fließt in Key-Ableitung ein? |
|---|---|---|---|
| **Curve25519 Public Key** | 32 Bytes | **Statisch** (factory-burned) | **JA** — ECDH: `scalarmult(app_private, pump_public)` |
| **Random Challenge** | 32 Bytes | Dynamisch (pro Verbindung) | **NEIN** — wird nur an ProRegia gesendet |
| **BT MAC Adresse** | 6 Bytes | Statisch | **NEIN** — wird nur an ProRegia gesendet |
| **Reboot Counter** | 4 Bytes | Dynamisch (nach Neustart) | **NEIN** — nur Counter-Sync in verschlüsselten Nachrichten |

### Key-Ableitung im Detail

```
Shared Key = hchacha20(scalarmult(app_private_key, pump_public_key), zero_nonce)
                        ^^^^^^^^^^^^^^^^^^^^^^^^  ^^^^^^^^^^^^^^^^^
                        App-seitig generiert      Factory-burned, STATISCH
```

- Der **Pump Public Key** ist ab Werk in den STM32F051 Flash eingebrannt und ändert sich **NIEMALS**
- Der **Random Challenge** (32 Bytes pro Verbindung) dient nur zur Backend-Validierung — er fließt NICHT in die Key-Ableitung ein
- Die **BT MAC Adresse** wird an ProRegia gesendet, fließt aber ebenfalls NICHT in die Key-Ableitung ein
- Der Shared Key ändert sich nur, wenn die App einen neuen Private Key generiert (= neuer Key Exchange)

## Pump-seitige Key-Rotation

### Rotiert die Pumpe ihren Schlüssel?

**NEIN.** Die Pumpe rotiert weder ihren Public Key noch invalidiert sie bestehende Shared Keys:

1. **Kein key-seitiges Timeout**: Die Pumpe prüft NICHT, wie alt der Shared Key ist
2. **Kein erzwungener Key-Wechsel**: Die Pumpe fordert niemals einen neuen Key Exchange an
3. **Statischer Public Key**: Der Curve25519 Public Key ist ab Werk eingebrannt
4. **28-Tage-Expiry ist app-seitig**: Nur `GetEncryptionKeyExpirationDateAsync()` in der App prüft das Alter

### Quelle: Doc 03

> "The 28-day key expiration is enforced APP-SIDE ONLY. The pump itself does not check the age of the shared key."
> — Manipulating `sharedKeyDate` in SharedPreferences extends key validity indefinitely.

### Quelle: vicktor/ypsomed-pump (`PumpCryptor.kt`)

```kotlin
// Key wird mit 3650 Tagen Expiry gespeichert, aber:
// "la validez real del protocolo es de 28 días"
// (die tatsächliche Protokoll-Gültigkeit beträgt 28 Tage)
System.currentTimeMillis() + 3650L * 24 * 3600 * 1000
```

Der 3650-Tage-Wert (10 Jahre) ist ein Platzhalter — die eigentliche 28-Tage-Prüfung wird in der offiziellen App separat durchgeführt.

## Counter-Management

### Drei Counter im verschlüsselten Protokoll

Jede verschlüsselte BLE-Nachricht enthält am Ende 12 Bytes Counter-Daten:

```
Plaintext vor Verschlüsselung:
┌──────────────────────┬──────────────────────────────┐
│  Command Data (var)  │  Counter Data (12B)          │
│                      │  [rebootCounter 4B, big-end] │
│                      │  [writeCounter 8B, big-end]  │
└──────────────────────┴──────────────────────────────┘
```

| Counter | Größe | Richtung | Beschreibung |
|---|---|---|---|
| **rebootCounter** | 4 Bytes (Big-Endian) | Pump → App | Zählt Pumpen-Neustarts seit Key Exchange |
| **writeCounter** | 8 Bytes (Big-Endian) | App → Pump | Monoton steigend pro gesendeter Nachricht |
| **readCounter** | 8 Bytes (Big-Endian) | Pump → App | Monoton steigend pro empfangener Nachricht |

### Reboot Counter — Das einzige dynamische Element

Aus `PumpCryptor.kt` (vicktor/ypsomed-pump):

```kotlin
// Beim Entschlüsseln einer Pump-Nachricht:
val pumpRebootCounter = // aus den letzten 12 Bytes der entschlüsselten Nachricht

if (pumpRebootCounter != localRebootCounter) {
    // Pumpe wurde neu gestartet!
    localRebootCounter = pumpRebootCounter
    writeCounter = 0  // Write-Counter auf 0 zurücksetzen
}
```

**Verhalten nach Pumpen-Neustart**:
1. Pumpe erhöht ihren internen `rebootCounter`
2. App empfängt eine verschlüsselte Nachricht der Pumpe
3. App liest den neuen `rebootCounter` aus der Nachricht
4. App setzt `writeCounter` auf 0 zurück
5. **Shared Key bleibt gültig** — nur Counter werden zurückgesetzt

### Kritische Regel: Erst lesen, dann schreiben

Aus `PumpCryptor.kt`:

> "Es ist imprescindible descifrar al menos una respuesta de la bomba antes de enviar cualquier comando cifrado"
> (Es ist zwingend notwendig, mindestens eine Antwort der Pumpe zu entschlüsseln, bevor verschlüsselte Befehle gesendet werden)

Dies liegt daran, dass nach einem Pumpen-Neustart der `rebootCounter` sich ändert. Die App MUSS erst den neuen Counter lesen, bevor sie Befehle senden kann.

### Counter-Fehler

| Fehlercode | Bezeichnung | Ursache |
|---|---|---|
| 138 | `COUNTER_MISMATCH` | Write-Counter stimmt nicht mit Pumpen-Erwartung überein |
| 139 | `COUNTER_ERROR` | Allgemeiner Counter-Fehler (falscher rebootCounter) |

**Wichtig**: Counter-Fehler invalidieren den Shared Key NICHT. Sie erfordern lediglich eine Counter-Resynchronisation.

## Speicherung in der App

### mylife App (EncryptedAppKeyStore)

```csharp
class EncryptedAppKeyStore {
    GetKeyPairAsync()                  // App X25519 Keypair
    GetPumpPublicKeyAsync()            // Pump Public Key (statisch)
    GetSharedKeyPumpAppAsync()         // Abgeleiteter Shared Key
    GetSharedKeyPumpAppDateAsync()     // Key-Erstellungszeitpunkt (28-Tage-Check)
    GetRebootCounter()                 // Letzter bekannter Reboot Counter
    GetNumericReadCounterFromPump()    // Lese-Counter
    GetNumericWriteCounterFromApp()    // Schreib-Counter
    GetAttestationKeyId()              // Hardware Key Attestation ID
}
```

### CamAPS FX (EncryptedSharedPreferences)

```
sharedKey       → 32-Byte Shared Key
privateKey      → 32-Byte App Private Key
pumpPublicKey   → 32-Byte Pump Public Key
sharedKeyDate   → Timestamp für 28-Tage-Expiry
writeCounter    → Aktueller Write Counter
readCounter     → Aktueller Read Counter
```

## Implikationen für den Bypass

### Gute Nachrichten

1. **Ein erfolgreicher Key Exchange reicht**: Der Shared Key funktioniert unbegrenzt, solange die App ihn nicht verwirft
2. **Kein pump-seitiges Timeout**: Die Pumpe erzwingt keinen Key-Wechsel
3. **28-Tage-Expiry umgehbar**: Nur die App prüft das Alter — `sharedKeyDate` manipulieren oder Prüfung per Frida/Patch umgehen
4. **Commander Key hat keinen separaten Lifecycle**: Derselbe Shared Key wird für normale und Commander-Operationen verwendet
5. **Reboot überlebt**: Auch nach Pumpen-Neustart bleibt der Shared Key gültig

### Vorsichtsmaßnahmen

1. **Nach Pumpen-Neustart**: Erst eine Pump-Nachricht lesen (Counter-Sync), dann erst Befehle senden
2. **Write Counter monoton**: Muss bei jedem Befehl erhöht werden, sonst Fehler 138/139
3. **Counter lokal persistieren**: Bei App-Neustart müssen die Counter aus dem Storage gelesen werden
4. **Kein paralleler Zugriff**: Nur ein Gerät kann gleichzeitig verschlüsselt mit der Pumpe kommunizieren

### Szenario: Was passiert wenn...

| Ereignis | Shared Key | Counter | Aktion nötig? |
|---|---|---|---|
| Pumpe wird neu gestartet | Bleibt gültig | rebootCounter ändert sich | Ja: erst lesen, dann schreiben |
| App wird neu gestartet | Bleibt gültig (aus Storage) | Aus Storage laden | Nein (automatisch) |
| 28 Tage vergehen | App verwirft ihn | Reset auf 0 | Neuer Key Exchange nötig |
| `sharedKeyDate` manipuliert | Bleibt gültig | Unverändert | Nein |
| Zweites Gerät macht Key Exchange | **Wird überschrieben** | Reset auf 0 | Erstes Gerät verliert Zugriff |

## Zusammenfassung

```
┌─────────────────────────────────────────────────────────────────┐
│                    KEY LIFECYCLE                                 │
│                                                                 │
│  Pump Public Key:    STATISCH (factory-burned, nie rotiert)     │
│  Shared Key:         STABIL (bis App neuen Key Exchange macht)  │
│  28-Tage-Expiry:     NUR APP-SEITIG (Pump prüft nicht)         │
│  Reboot Counter:     DYNAMISCH (aber Key bleibt gültig)         │
│  Write/Read Counter: MONOTON STEIGEND (Reset bei Reboot/KE)    │
│  Commander Key:      KEIN separater Lifecycle                   │
│                                                                 │
│  → Einmaliger Key Exchange = unbegrenzter verschlüsselter       │
│    Kanal zur Pumpe (wenn App-seitige Prüfung umgangen wird)    │
└─────────────────────────────────────────────────────────────────┘
```
