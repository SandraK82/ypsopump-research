# 15 — mylife App: Security Analysis

## Übersicht

Die mylife App (`net.sinovo.mylife.app` v2.4.3_001) hat ein **signifikant schwächeres Sicherheitsprofil** als CamAPS FX. Es gibt keine Obfuscation, kein Anti-Tamper und keine Root-Detection. Die einzige relevante Sicherheitsbarriere ist die Play Integrity API für den ProRegia Key Exchange.

## Sicherheitsmechanismen

### 1. Play Integrity API

**Status**: VORHANDEN, aktiv für Key Exchange

Die App nutzt die Google Play Integrity API v1.1.0 (sowie das ältere SafetyNet v17.0.1 als Fallback).

**Implementierung** (in `Dexcom.Android.Event.dll`):
```
IntegrityManagerFactory
IntegrityTokenRequest
IntegrityTokenResponse
RequestIntegrityToken
IIntegrityManager
IDeviceAttestation
IAttestationKeyStore
```

Der Integrity Token wird im Rahmen des ProRegia Key Exchange an den Server gesendet. Der Server validiert:
- Package Name = `net.sinovo.mylife.app`
- Signing Certificate Hash
- Device Integrity Verdict (MEETS_DEVICE_INTEGRITY oder STRONG_INTEGRITY)

**Bypass**: Über TrickyStore v1.4.1 + Integrity Box V31 + OEM Keybox → STRONG_INTEGRITY erreicht (siehe Doc 13).

### 2. Key Attestation (Hardware-basiert)

**Status**: VORHANDEN (neu gegenüber CamAPS FX)

Die App nutzt **Android Key Attestation** zusätzlich zur Play Integrity:

```
ValidateKeyAttestationAsync
GetServerNonceForKeyAttestationAsync
DeleteAttestationKey
KeyAttestation (Protobuf Message)
KeyAttestationForJwt
KeyAttestationResponse
IAttestationKeyStore
```

Dies bedeutet:
1. App generiert einen Schlüssel im Android Keystore
2. Attestation Certificate Chain wird an ProRegia gesendet
3. Server validiert die Zertifikatskette

**Auswirkung**: Hardware Key Attestation ist schwieriger zu umgehen als Play Integrity allein. TrickyStore kann TEE-signierte Attestation Certificates fälschen, wenn der Keybox korrekt installiert ist.

### 3. JWT-basierte Authentifizierung

```
SignJwt / SignJwtClient
JwtKeyIdentifier
JwtToken / JwtTokenFieldNumber
SignatureFieldNumber
```

ProRegia verwendet JWT-Token für die Server-Authentifizierung. Diese werden nach dem Key Attestation Schritt ausgestellt.

## Fehlende Sicherheitsmechanismen

### Root Detection — NICHT VORHANDEN

**Ergebnis**: Keine Root-Detection gefunden.

Durchsucht in allen 228 .NET Assemblies und Java DEX-Dateien. Kein Suche nach:
- `/system/xbin/su`, `/sbin/su`
- `com.topjohnwu.magisk`
- `de.robv.android.xposed`
- Frida-Ports oder -Libraries
- `test-keys` Build Tags
- Su-Binary Execution

### Anti-Tamper — NICHT VORHANDEN

**Ergebnis**: Keine Anti-Tamper-Mechanismen gefunden.

- Kein DexGuard
- Kein PairIP / libe61d.so
- Keine Signatur-Verifikation bei Start
- Keine Installer-Verifikation
- Kein nativer Anti-Debug

### Code Obfuscation — NICHT VORHANDEN

**Ergebnis**: Alle Symbole im Klartext.

- Klassen: `Proregia.Bluetooth.Backend.RealServer`
- Methoden: `EncryptKeyAsync`, `GetServerNonceAsync`, `KeyExchangeAsync`
- Namespaces: `Proregia.Bluetooth.Crypto`, `Proregia.Bluetooth.Contracts.Proto`
- PDB-Pfade: `/Users/iosbuild/builds/.../proregia-xamarin-lib/CryptoLibNG/`

Dies erlaubt vollständige Rekonstruktion der App-Logik durch IL-Decompilation.

### Certificate Pinning — MINIMAL

OkHttp `CertificatePinner` Klasse ist vorhanden (in `Square.Retrofit2.ConverterGson.dll`), aber:
- Keine SHA256-Pin-Hashes gefunden in den Assemblies
- Keine Custom TrustManager Konfiguration
- gRPC Channel zu ProRegia nutzt Standard-TLS (wie bei CamAPS FX)

**Einschätzung**: Pinning ist wahrscheinlich nicht aktiv konfiguriert oder kann trivial über Frida/Magisk umgangen werden.

## Vergleich Sicherheitsprofil

| Mechanismus | CamAPS FX | mylife App |
|---|---|---|
| Play Integrity | Ja | Ja |
| Key Attestation | Nein (nur Nonce) | Ja (Hardware) |
| Root Detection | DexGuard Checks | **KEINE** |
| Anti-Tamper | PairIP (libe61d.so) | **KEINE** |
| Code Obfuscation | DexGuard (verschlüsselt) | **KEINE** |
| Certificate Pinning | Nicht aktiv | Nicht aktiv |
| Delayed Kill | Ja (Storage Wipe) | **NEIN** |

## Bekannte CVEs (historisch)

Ypsomed hatte in der Vergangenheit Sicherheitslücken:

| CVE | CVSS | Beschreibung |
|---|---|---|
| CVE-2021-27491 | 5.4 | Cleartext Transmission |
| CVE-2021-27495 | 5.6 | Weak Authentication |
| CVE-2021-27499 | 5.4 | Missing Encryption |
| CVE-2021-27503 | 5.6 | Weak Pairing |

Diese betrafen die erste Generation des Pump-Protokolls. Die aktuelle App nutzt ProRegia mit XChaCha20-Poly1305 Verschlüsselung, was die meisten dieser CVEs adressiert.

## Fazit

Die mylife App ist ein **deutlich leichteres Ziel** als CamAPS FX:

1. **Kein Selbstzerstörungs-Mechanismus**: CamAPS FX löscht sich selbst nach Root-Detection. Die mylife App hat keine solche Funktion.
2. **Volle Decompilierbarkeit**: Alle Klassen, Methoden und Strings im Klartext.
3. **Keine Root-Blockade**: App startet normal auf gerooteten Geräten.
4. **Play Integrity ist die einzige Hürde**: Bereits umgangen (STRONG_INTEGRITY via TrickyStore).
5. **Key Attestation als zusätzliche Hürde**: Wird ebenfalls von TrickyStore gefälscht.

**Nächster Schritt**: App auf dem gerooteten A22 testen — wenn Play Integrity + Key Attestation durchgehen, sollte der Key Exchange funktionieren.
