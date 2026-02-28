# 12 — CamAPS FX Sideload Bypass: Frida-basierter Ansatz (ÜBERHOLT)

> **HINWEIS**: Dieser Frida-basierte Ansatz ist **überholt**. Der Key Exchange scheiterte an unzureichendem Play Integrity Level. Eine funktionierende Magisk-basierte Lösung (ohne Frida) ist in [13-play-integrity-bypass-success.md](13-play-integrity-bypass-success.md) dokumentiert.

## Zusammenfassung

Dieses Dokument beschreibt den Versuch, eine sideloaded CamAPS FX APK (von APKPure) auf einem gerooteten Samsung Galaxy A22 5G vollständig funktionsfähig zu machen. Der Bypass umfasst PairIP License Check, Dexcom Error 65, Google Play Integrity und ProBluetooth Key Exchange. **Der Key Exchange schlug fehl** — die Lösung wurde stattdessen über Magisk-Module (TrickyStore + Integrity Box) erreicht.

## Testumgebung

| Komponente | Details |
|------------|---------|
| **Gerät** | Samsung SM-A226B (Galaxy A22 5G) |
| **Android** | 13 (SDK 33) |
| **Root** | Magisk 28.1 |
| **PIF** | PlayIntegrityFork v16 (osm0sis & chiteroman) |
| **PIF-Fingerprint** | Pixel 7a Canary (`google/lynx_beta/lynx:CANARY/ZP11.260123.011/14822050:user/release-keys`) |
| **Frida** | 17.7.3 (CLI-basiert, NOT Python API!) |
| **App** | `com.camdiab.fx_alert.mgdl` v1.4(190).111 (APKPure) |
| **Frida Server** | `/data/local/tmp/frida-server` |

## Wichtige technische Erkenntnisse

### Frida 17.x CLI-Modus
Die Java Bridge funktioniert nur im CLI-Modus (`frida -f ... -l script.js`), NICHT über die Python API (`session.create_script()`). Der `cat /dev/zero | frida` Pattern hält stdin offen für non-interaktive Sessions.

### jadx Decompilation
Der `defpackage.` Prefix ist ein jadx-Platzhalter. Runtime-Klassennamen haben KEINEN Package-Prefix: `Java.use("getUninterpretedOptionCount")` statt `Java.use("defpackage.getUninterpretedOptionCount")`.

### Binary Encoding
`extractServiceName.write(byte[])` konvertiert zu **UPPERCASE HEX** (`"0123456789ABCDEF"`). Dies stimmt mit dem ypsomed-pump Repository überein. Keine Base64-Encoding für Proto-Felder.

### Device ID
Die ProRegia Device ID ist eine UUID, gespeichert in `SharedPreferences("proregia_prefs", "device_id")`. Wird beim ersten Aufruf generiert. Spoofbar via Frida-Hook auf `addEntry.read(Context)`.

## Bypass-Layer (V8 — aktuell)

### Layer 1-8: PairIP License Check Bypass (GELÖST)

8 Klassen in `com.pairip.licensecheck.*` werden gehookt:

| Layer | Klasse/Methode | Aktion |
|-------|---------------|--------|
| 1 | `LicenseContentProvider.onCreate()` | return true |
| 2 | `LicenseClient.initializeLicenseCheck()` | set FULL_CHECK_OK |
| 3 | `LicenseResponseHelper.validateResponse()` | übersprungen |
| 4 | `LicenseClient.handleError()` | ignoriert |
| 5 | `System.exit()` | selektiv blockiert (nur PairIP) |
| 6 | `LicenseActivity.onStart()` | finish() |
| 7 | `LicenseClient.performLocalInstallerCheck()` | return true |
| 8 | `LicenseClient.connectToLicensingService()` | blockiert |

**Status**: Vollständig gelöst. App startet ohne License Check.

### Layer 9-10: Dexcom Error 65 Bypass (GELÖST)

Error 65 = `DX_MSG_RESTART_APP` (ordinal 53 in `HandleErrorMessage.NotificationCodes`).

| Layer | Klasse/Methode | Aktion |
|-------|---------------|--------|
| 9 | `getUninterpretedOptionCount.setContentView()` | Trigger übersprungen |
| 10 | `setAllowAlias.read(NotificationCodes)` | ordinal 53 gefiltert |

**Status**: Gelöst. Nach `pm clear` der App-Daten keine Error 65 mehr.

### Layer 11-13: Google Play Integrity für Dexcom Cloud (GELÖST)

| Layer | Klasse/Methode | Aktion |
|-------|---------------|--------|
| 11 | `getAlgorithmDataBlock.write()` | "IntegrityCheckFailed" unterdrückt |
| 12 | `SSEResultBase.IconCompatParcelizer(bool)` | forced true |
| 13 | Periodischer Enforcer (10s) | MediaBrowserCompatItemReceiver = true |

**Status**: Gelöst. Dexcom Cloud-Fehler werden abgefangen.

### Layer 14-18: Key Exchange Diagnostics (AKTIV)

| Layer | Zweck |
|-------|-------|
| 14 | ProBluetoothException Trace (alle 3 Konstruktoren + Stack Trace) |
| 15 | IntegrityServiceException Trace (mit Error Code Mapping) |
| 16 | Phonesky (Play Store) Detection Trace |
| 17 | Endpoint.read() — Play Integrity API Call Trace |
| 18 | Dummy Token Injection bei Play Integrity Failure |

### Layer 19-20: Metrics Spoofing + Request Logging (AKTIV)

| Layer | Zweck |
|-------|-------|
| 19 | Metrics.setManufacturer: "samsung" → "Google", setModel: "SM-A226B" → "Pixel 7a" |
| 20 | EncryptKeyRequest Logging (alle Feldlängen, Token-Preview) |

### Layer 21-23: Throttle Bypass + Extended Diagnostics (V8 NEU)

| Layer | Zweck |
|-------|-------|
| 21 | Device ID Spoofing: Neue UUID bei jedem Start (Throttle-Umgehung) |
| 22 | Vollständiges Metrics Logging (alle 10 Felder) |
| 23 | gRPC StatusRuntimeException Logger (Code + Description + Metadata) |

## Bisherige Ergebnisse der Key Exchange Diagnostik

### Was funktioniert
- Phonesky (Play Store) wird erkannt: **GEFUNDEN**
- IIntegrityService Binding: **VORHANDEN** (keine IntegrityServiceException)
- Play Integrity Tokens werden generiert (kein Fehler)
- Cloud Project Number: `256051217936`
- Metrics Spoofing wird ausgeführt (Samsung → Pixel 7a im Request)

### Was NICHT funktioniert
- **EncryptKeyRequest wird vom Server abgelehnt**
- Erster Fehler: `INVALID_ARGUMENT: Invalid EncryptKeyRequest`
- Nach mehreren Versuchen: `INVALID_ARGUMENT: Device is blocked due to throttling!`
- BLE-Fehler: `Extended Read Failed, Status Code: 136` auf Characteristic `669a0c20-0008-969e-e211-fcff0000000a`

### Analyse des Fehlers

Der `INVALID_ARGUMENT: Invalid EncryptKeyRequest` trat auf, bevor das Gerät gedrosselt wurde. Mögliche Ursachen:

1. **Play Integrity Token Verdict (wahrscheinlichste Ursache)**
   - Sideloaded APK → `appRecognitionVerdict` = `UNRECOGNIZED_VERSION`
   - Sideloaded APK → `appLicensingVerdict` = `UNLICENSED`
   - PIF spooft `deviceRecognitionVerdict` korrekt, aber App-Verdict ist unabhängig
   - Server validiert Token gegen Google Play Console → Rejection

2. **APK-Signatur-Mismatch**
   - APKPure könnte das APK re-signiert haben
   - Play Integrity Token enthält Signing-Certificate SHA256
   - Server vergleicht gegen registriertes Certificate → Mismatch

3. **Metrics-Mismatch** (weniger wahrscheinlich)
   - Bereits adressiert mit Layer 19 → kein Erfolg
   - Könnte weitere Felder benötigen (applicationName, packageName)

4. **Server-seitige Device-Validierung** (möglich)
   - Server kennt registrierte Geräte
   - Unbekannte UUID → Rejection

## Offene Fragen

1. Welchen `appRecognitionVerdict` enthält das Play Integrity Token?
2. Hat APKPure das APK re-signiert? (Certificate-Vergleich nötig)
3. Existiert ein Relay-Ansatz wie im ypsomed-pump Repository?
4. Ist der MITM-Ansatz (kein Cert Pinning!) praktikabel für Token-Analyse?

## Ergebnis: Frida-Ansatz ÜBERHOLT

**Der Frida-basierte Bypass ist nicht mehr nötig.** Am 27. Februar 2026 wurde eine rein Magisk-basierte Lösung gefunden, die ohne Frida auskommt und den Key Exchange erfolgreich durchführt.

### Warum Frida scheiterte

Der Frida-Ansatz konnte den Key Exchange nicht erfolgreich abschließen, weil:

1. **Unzureichendes Play Integrity Level**: PIF v16 allein erreichte nur BASIC_INTEGRITY. Der ProRegia-Server verlangt mindestens DEVICE_INTEGRITY.
2. **Keine Hardware-Attestation**: Ohne TrickyStore + gültige OEM-Keybox konnte keine Hardware-Attestation simuliert werden.
3. **Server-seitige Validierung**: Der ProRegia-Server prüft das Play Integrity Token serverseitig und lehnt Anfragen mit unzureichendem Verdict ab (`INVALID_ARGUMENT: Invalid EncryptKeyRequest`).

### Funktionierende Alternative

Siehe **[13-play-integrity-bypass-success.md](13-play-integrity-bypass-success.md)** für die vollständige Lösung:

- **TrickyStore v1.4.1** + **Integrity Box V31** (2 Magisk-Module, kein Frida)
- Ergebnis: **MEETS_STRONG_INTEGRITY** + erfolgreicher ProRegia Key Exchange + Pumpenverbindung
- Stabil, kein App-Crash bei Frida-Disconnect, kein ständiges Script-Management

### Frida bleibt relevant für

- Key Extraction (Strategie B aus Dok. 09) — nach erfolgreichem Key Exchange den Shared Key für einen custom AAPS-Driver extrahieren
- Protokollanalyse — BLE-Kommunikation mit der Pumpe mitlesen
- Algorithmus-Reverse-Engineering — Closed-Loop-Algorithmus verstehen

## Code-Referenz

### Obfuskierte Klassennamen (jadx → Runtime)

| jadx Name | Tatsächliche Funktion |
|-----------|----------------------|
| `MediaDescriptionCompat` | gRPC Client (EncryptKey + NonceRequest) |
| `KmsAeadKeyOrBuilder` | ProBluetoothGatt (BLE + Key Exchange) |
| `extractServiceName` | Hex-Encoding Utility |
| `addEntry` | Device ID Provider (UUID aus SharedPrefs) |
| `Endpoint` | Play Integrity API Orchestrator |
| `getFeatures` | Phonesky (Play Store) Detection |
| `clearExemplars` | Future/Promise Wrapper (30s Timeout) |
| `getPagesOrBuilderList` | Play Integrity Token Result (abstract) |
| `clearAliases` | IntegrityTokenRequest Builder |
| `addFeaturesBytes` | IntegrityTokenRequest DTO |
| `getUninterpretedOptionCount` | Dexcom Error 65 Trigger |
| `setAllowAlias` | Dexcom Error Dispatch |
| `getAlgorithmDataBlock` | Dexcom Cloud Error Handler |
| `SSEResultBase` | Dexcom Integrity Flag |
| `KmsEnvelopeAeadKeyBuilder` | ProBluetooth Configuration |
| `KmsEnvelopeAeadKeyFormat` | BLE GATT Helper |
| `setAppVersion` | ProBluetooth Manager |

### ProRegia Server Endpunkte

| App | Server | Port |
|-----|--------|------|
| CamAPS FX | `connect.cam.pr.sec01.proregia.io` | 443 |
| mylife App | `connect.ml.pr.sec01.proregia.io` | 8090 |

### Launcher Script

```bash
#!/bin/bash
# launcher.sh — Auto-respawning Frida launcher
SCRIPT="bypass_pairip.js"
PKG="com.camdiab.fx_alert.mgdl"

while true; do
    echo "[$(date +%H:%M:%S)] Starte CamAPS FX mit Bypass..."
    cat /dev/zero | frida -U -f "$PKG" -l "$SCRIPT" 2>&1
    echo "[$(date +%H:%M:%S)] Frida beendet (exit=$?), re-spawne in 3s..."
    sleep 3
done
```
