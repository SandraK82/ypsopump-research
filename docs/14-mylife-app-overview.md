# 14 — mylife App: Überblick & Architektur

## Allgemein

| Eigenschaft | Wert |
|---|---|
| **App-Name** | mylife App |
| **Package** | `net.sinovo.mylife.app` |
| **Version** | 2.4.3_001 (versionCode 204030019) |
| **Hersteller** | Sinovo / Ypsomed AG |
| **Framework** | Xamarin.Forms (.NET/C#, Mono Runtime) |
| **Min SDK** | 25 (Android 7.1) |
| **Target SDK** | 34 (Android 14) |
| **APK-Größe** | ~130 MB (base) + 124 MB (native arm64) |
| **Play Store** | [Google Play](https://play.google.com/store/apps/details?id=net.sinovo.mylife.app) |

## Funktionsumfang

Die mylife App ist die offizielle Companion-App für die **mylife YpsoPump**. Im Vergleich zu CamAPS FX (das nur Closed-Loop-Steuerung bietet) hat die mylife App einen breiteren Funktionsumfang:

1. **mylife Dose** — Bolus-Abgabe direkt vom Smartphone (EncryptCommanderKey-Mechanismus)
2. **Pump-Statusanzeige** — Reservoir, Batterie, aktuelle Basalrate
3. **Dexcom CGM Integration** — Vollständige Dexcom G6/G7 Transmitter-Anbindung
4. **Blutzucker-Logbuch** — Manuelle BZ-Eingabe und CGM-Verlauf
5. **Berichte** — PDF-Reports (SinovoPdfReports.dll)
6. **Cloud-Sync** — Ypsomed Cloud + Dexcom Share
7. **Widget** — Homescreen-Widget mit aktuellem Status

## Architektur

### Framework-Stack

```
┌─────────────────────────────────────────┐
│  Xamarin.Forms UI (MVVM via MvvmCross)  │
├─────────────────────────────────────────┤
│  Ypsomed.mylife.Apps.Services.*         │
│  (Validation, Cloud, Config, Storage)   │
├─────────────────────────────────────────┤
│  CryptoLib.Abstraction (Proregia BLE)   │
│  CryptoLib (Native Crypto Wrapper)      │
├──────────────┬──────────────────────────┤
│  Plugin.BLE  │  Dexcom.Android.*        │
│  (BLE Layer) │  (CGM Integration)       │
├──────────────┴──────────────────────────┤
│  Mono Runtime (libmonodroid.so)         │
│  Native Libs: libsodium.so, libgrpc_*  │
└─────────────────────────────────────────┘
```

### Kern-Assemblies

| Assembly | Funktion | Größe |
|---|---|---|
| `mylife App.dll` | App-Entry, AuditApp | 23 KB |
| `Ypsomed.mylife.Apps.Services.Validation.dll` | ProRegia-Client, gRPC, Key Exchange, Crypto | 214 KB |
| `CryptoLib.Abstraction.dll` | Proregia.Bluetooth.* Namespace, BLE-Protokoll | 376 KB |
| `CryptoLib.dll` | Native Crypto Wrapper (libsodium P/Invoke) | 127 KB |
| `nexus.core.dll` | Kern-Business-Logic | 155 KB |
| `Sodium.Core.dll` | SQLite Crypto (sqlite3_key) | 51 KB |
| `Plugin.BLE.dll` | BLE Abstraction Layer | 235 KB |
| `Dexcom.Android.Event.dll` | Play Integrity + Device Attestation | 120 KB |
| `Dexcom.Android.Transmitter.Libraries.dll` | Dexcom CGM Transmitter BLE | N/A |

### Native Libraries

| Library | Funktion |
|---|---|
| `libsodium.so` | NaCl Crypto (XChaCha20-Poly1305, Curve25519) |
| `libgrpc_csharp_ext.so` | gRPC C-Extension für ProRegia Backend |
| `libmonodroid.so` | Xamarin/Mono Android Runtime |
| `libmonosgen-2.0.so` | Mono GC (SGen) |
| `libSkiaSharp.so` | 2D-Grafik (Charts) |
| `libe_sqlite3.so` | SQLite (verschlüsselt via sqlite3_key) |
| `libmono-btls-shared.so` | Mono BoringTLS |

## Vergleich: mylife App vs CamAPS FX

| Aspekt | mylife App | CamAPS FX |
|---|---|---|
| **Framework** | Xamarin/.NET (C#) | Native Android (Java/Kotlin) |
| **Obfuscation** | Keine (Klartext-Symbole) | DexGuard (verschlüsselte Klassen) |
| **Anti-Tamper** | Keine | libe61d.so (PairIP) |
| **Root Detection** | Keine | DexGuard Root Checks |
| **Play Integrity** | Ja (für Key Exchange) | Ja (für Key Exchange) |
| **Key Exchange** | ProRegia gRPC (`ml.pr`) | ProRegia gRPC (`cam.pr`) |
| **Bolus Control** | Ja (mylife Dose, separater Commander-Key) | Ja (über Closed Loop + manuelle Bolus-Berechnung) |
| **CGM** | Dexcom G6/G7 integriert | Dexcom G6/G7 + Libre |
| **Pump Connection** | YpsoPump BLE | YpsoPump BLE |
| **Backend** | Ypsomed Cloud | Glooko/Diasend |
| **Crypto** | libsodium (NaCl) | Custom Java (in libe61d.so) |
| **Decompilierbarkeit** | Einfach (monodis/ILSpy) | Schwer (DexGuard) |

## Build-Informationen

```
Build-Server: /Users/iosbuild/builds/uVNqL2da/0/customers/proregia/
Projekt-Pfad: proregia-xamarin-lib/CryptoLibNG/
CI/CD: /Users/runner/work/1/s/Ypsomed.mylife.Apps/UI/Ypsomed.mylife.Apps.UI.Droid/
Target Framework: MonoAndroid v11.0 (Xamarin.Android v9.0 Support)
Build-Typ: EU-Android-Release
```
