# 13 — Play Integrity Bypass: Erfolgreiche Konfiguration (ohne Frida)

## Zusammenfassung

Am 27. Februar 2026 wurde CamAPS FX (`com.camdiab.fx_alert.mgdl` v1.4(190).111) erfolgreich auf einem gerooteten Samsung Galaxy A22 5G zum Laufen gebracht — **inklusive ProRegia Key Exchange und Pumpenverbindung**. Der Bypass erfolgt ausschließlich über Magisk-Module, ohne Frida.

**Ergebnis**: MEETS_STRONG_INTEGRITY + erfolgreicher ProRegia Key Exchange + YpsoPump BLE-Verbindung + Pumpenhistorie-Download.

## Testumgebung

| Komponente | Details |
|------------|---------|
| **Gerät** | Samsung SM-A226B (Galaxy A22 5G) |
| **Android** | 13 (SDK 33), One UI Core 5.1 |
| **Magisk** | 30.7 (Zygisk enabled, DenyList OFF, Enforce DenyList OFF) |
| **Root** | Magisk 30.7 |
| **Serial** | [REDACTED] |
| **Pumpe** | YpsoPump_[REDACTED] (Bluetooth LE) |

## Funktionierende Modulkonfiguration

### Nur 2 Module benötigt:

| Modul | Version | Module ID | Funktion |
|-------|---------|-----------|----------|
| **TrickyStore** | 1.4.1 | `tricky_store` | Keystore Attestation Hooking — fängt KeyMint/Keymaster-Aufrufe ab und injiziert gültige OEM-Zertifikatsketten |
| **Integrity Box** | V31 | `playintegrityfix` | All-in-One: Bundled PIF (Play Integrity Fix) + WebUI + Keybox-Management + Fingerprint-Spoofing |

### Nicht verwenden (Fehlerquellen):

| Modul | Problem |
|-------|---------|
| **Zygisk Assistant** | Veraltet seit Januar 2026 (XDA), verursacht Konflikte |
| **TEESimulator v3.1** | Keybox-Parsing-Fehler auf SM-A226B ("0 valid keys"), funktioniert nicht auf diesem Gerät |
| **PIF standalone** | Wird von Integrity Box mitgebracht, nicht separat installieren |

## Keybox-Konfiguration

### Kritisch: Keybox NUR auf dem Gerät holen!

Die Keybox darf **NICHT** manuell auf dem Mac/PC decodiert werden. Die Mac-basierte Decodierung (10x base64 → hex → ROT13) korrumpiert die ECDSA-Zertifikate (falsche base64-Padding, fehlerhafte Zeilenumbrüche).

**Korrekte Methode — Integrity Box key.sh auf dem Gerät ausführen:**

```bash
adb shell "su -c 'sh /data/adb/modules/playintegrityfix/webroot/common_scripts/key.sh'"
```

Erwartete Ausgabe:
```
Requesting valid keybox from GitHub...
Keybox has been updated
```

Die Keybox wird automatisch nach `/data/adb/tricky_store/keybox.xml` geschrieben.

### Keybox-Quelle

Das key.sh-Skript lädt eine encodierte Keybox von GitHub herunter (URL wird hier bewusst nicht veröffentlicht).
- Decodierung: 10x base64 → Hex-Decode (xxd) → ROT13
- Die Keybox enthält einen gültigen OEM-ECDSA-Schlüssel + RSA-Schlüssel
- **ECDSA ist zwingend erforderlich** — GMS nutzt SHA256withECDSA für Attestation
- AOSP Software Keybox ist von Google revoked → führt zu NO_INTEGRITY

## Konfigurationsdateien

### `/data/adb/tricky_store/target.txt`

```
com.google.android.gms!
com.android.vending
```

- `!` = Certificate Generation Mode (TrickyStore generiert neue Zertifikatskette basierend auf Keybox)
- GMS und Play Store werden als Targets gesetzt

### `/data/adb/modules/playintegrityfix/custom.pif.prop`

```properties
MANUFACTURER=Google
MODEL=Pixel 8a
FINGERPRINT=google/akita_beta/akita:CANARY/ZP11.260123.011/14822050:user/release-keys
BRAND=google
PRODUCT=akita_beta
DEVICE=akita
RELEASE=CANARY
ID=ZP11.260123.011
INCREMENTAL=14822050
TYPE=user
TAGS=release-keys
SECURITY_PATCH=2026-02-01
DEVICE_INITIAL_SDK_INT=32
*.build.id=ZP11.260123.011
*.security_patch=2026-02-01
*api_level=32
spoofBuild=1
spoofProps=1
spoofProvider=1
spoofSignature=0
spoofVendingFinger=1
spoofVendingSdk=0
```

**Wichtige Einstellungen:**
- `spoofProvider=1` — **MUSS 1 sein!** Bei 0 verliert PIF die Fähigkeit, den Keystore zu hooken → kein BASIC_INTEGRITY
- `spoofSignature=0` — Nicht nötig für CamAPS
- Pixel 8a Canary Fingerprint als Spoof-Ziel

### Magisk-Einstellungen

- **Zygisk**: Aktiviert
- **DenyList**: AUS (PIF braucht Zugriff auf GMS-Prozess)
- **Enforce DenyList**: AUS
- GMS (`com.google.android.gms`) ist **NICHT** in der DenyList

## Installationsanleitung (Schritt für Schritt)

### 1. Module installieren

```bash
# TrickyStore v1.4.1
adb push TrickyStore.zip /data/local/tmp/
adb shell "su -c 'magisk --install-module /data/local/tmp/TrickyStore.zip'"

# Integrity Box V31
adb push IntegrityBox.zip /data/local/tmp/
adb shell "su -c 'magisk --install-module /data/local/tmp/IntegrityBox.zip'"

# Reboot nach Modulinstallation
adb reboot
```

### 2. target.txt konfigurieren

```bash
adb shell "su -c 'printf \"com.google.android.gms!\ncom.android.vending\n\" > /data/adb/tricky_store/target.txt'"
```

### 3. Keybox holen (auf dem Gerät!)

```bash
adb shell "su -c 'sh /data/adb/modules/playintegrityfix/webroot/common_scripts/key.sh'"
```

### 4. PIF-Fingerprint konfigurieren

Die Integrity Box bringt eine Standard-PIF-Config mit (Pixel 8a Canary). Falls eine andere benötigt wird:

```bash
adb push custom.pif.prop /data/local/tmp/
adb shell "su -c 'cp /data/local/tmp/custom.pif.prop /data/adb/modules/playintegrityfix/custom.pif.prop'"
```

### 5. GMS-Cache leeren und Neustart

```bash
adb shell "su -c 'pm clear com.google.android.gms'"
adb reboot
```

### 6. CamAPS FX installieren

```bash
# Split-APK Installation mit Play Store Installer-Flag
adb install-multiple -i com.android.vending \
  com.camdiab.fx_alert.mgdl.apk \
  config.arm64_v8a.apk \
  config.en.apk \
  config.hdpi.apk
```

### 7. Testen

1. 2-3 Minuten nach Reboot warten (GMS-Initialisierung)
2. Play Integrity mit SPIC oder Play Integrity API Checker testen → STRONG_INTEGRITY erwartet
3. CamAPS FX öffnen, einloggen, mit Pumpe verbinden
4. Key Exchange sollte erfolgreich sein → Pumpenhistorie wird geladen

## Fehlerbehebung

### NO_INTEGRITY nach Keybox-Update
- GMS-Cache leeren: `adb shell "su -c 'pm clear com.google.android.gms'"`
- Neustart und 2-3 Minuten warten
- TrickyStore-Logs prüfen: `adb shell "su -c 'logcat -d -s TrickyStore'"`
- Keine Fehler wie "code 4" oder "Could not load keybox" → Keybox OK

### Nur BASIC_INTEGRITY
- `spoofProvider` in custom.pif.prop prüfen → muss 1 sein
- Keybox prüfen: wurde key.sh auf dem Gerät ausgeführt?
- AOSP Keybox (DeviceID="aosp") → revoked, durch OEM-Keybox ersetzen

### TrickyStore "0 valid keys"
- Keybox ist korrumpiert (typisch bei manueller Mac-Decodierung)
- Lösung: key.sh auf dem Gerät ausführen

### Key Exchange fehlgeschlagen trotz STRONG_INTEGRITY
- App mit `-i com.android.vending` installiert?
- Glooko-Login erfolgreich? (Logs prüfen: `okhttp.OkHttpClient`)
- Bluetooth-Pairing mit Pumpe abgeschlossen?

## Vergleich mit Frida-basiertem Ansatz (Dok. 12)

| Aspekt | Frida-Bypass (Dok. 12) | Magisk-Module (Dok. 13) |
|--------|----------------------|------------------------|
| **Ergebnis** | Key Exchange FEHLGESCHLAGEN | Key Exchange ERFOLGREICH |
| **Root-Detection** | Kein Schutz | TrickyStore + PIF |
| **Play Integrity** | PIF v16 allein → BASIC only | TrickyStore + IntegrityBox → STRONG |
| **Keybox** | Keine | OEM-Keybox via key.sh |
| **Komplexität** | 23 Frida-Layer, fragil | 2 Module, stabil |
| **Frida benötigt** | Ja (ständig) | Nein |
| **Stabilität** | Gering (Frida-Crash = App-Crash) | Hoch (System-Level Hooks) |

## Warum der Frida-Ansatz scheiterte

Der in Dokument 12 beschriebene Frida-Bypass scheiterte am Key Exchange, weil:

1. **Play Integrity Token-Verdict**: PIF v16 allein erreichte nur BASIC_INTEGRITY. Der ProRegia-Server verlangt mindestens DEVICE_INTEGRITY für den EncryptKey-Request.
2. **Kein Keybox-Support**: Ohne TrickyStore + OEM-Keybox konnte keine Hardware-Attestation simuliert werden.
3. **App-Verdict**: Das Play Integrity Token enthält auch `appRecognitionVerdict` und `appLicensingVerdict`. Ohne gültige Keybox werden diese Felder nicht korrekt gesetzt.

## Hinweise zur Langzeitstabilität

- **Keybox-Revocation**: OEM-Keyboxes werden regelmäßig von Google revoked. Wenn STRONG_INTEGRITY verloren geht, key.sh erneut ausführen (Integrity Box aktualisiert die Keybox-Quelle).
- **Integrity Box Updates**: Das Modul wird aktiv gepflegt (MeowDump). Bei neuen Versionen das Modul über Magisk aktualisieren.
- **CamAPS Updates**: Bei App-Updates erneut mit `-i com.android.vending` installieren.
- **Key-Erneuerung**: Der Pumpen-Key wird alle 28 Tage erneuert. Solange Play Integrity besteht, geschieht dies automatisch.

## Dateien und Downloads

| Datei | Beschreibung |
|-------|-------------|
| `TrickyStore.zip` | TrickyStore v1.4.1 Magisk-Modul |
| `v31-Integrity-Box-21-02-2026.zip` | Integrity Box V31 Magisk-Modul |
| CamAPS Split-APKs | `com.camdiab.fx_alert.mgdl.apk` + 3 Config-APKs (APKPure) |
