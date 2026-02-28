# 17 — mylife App: Komponenten & Dexcom Integration

## Android-Komponenten

### Activities (UI-Screens)

| Activity | Funktion |
|---|---|
| `cDexcomWizardMainActivity` | Dexcom CGM Setup-Wizard |
| `cDexcomDeviceSettingsActivity` | CGM Geräteeinstellungen |
| `cDexcomTransmitterSettingsActivity` | Transmitter-Konfiguration |
| `cDexcomTransmitterCalibrationActivity` | CGM Kalibrierung |
| `cDexcomDeviceAlertSettingsActivity` | Alarm-Einstellungen |
| `cDexcomAlertSettingsDetailsActivity` | Alarm-Details |
| `cDexcomAboutSettingsActivity` | Über den CGM-Sensor |
| `cDexcomDeviceHelpSettingsActivity` | CGM Hilfe |
| `cDexcomDeviceSafetyStatementsSettingsActivity` | Sicherheitshinweise |
| `cDexcomDeviceInstructionsSettingsActivity` | Anleitungen |
| `cDexcomDeviceHowToSettingsActivity` | How-To Guide |
| `cDexcomDeviceHowToVideoActivity` | Video-Anleitungen |
| `cDexcomDeviceShareStatusSettingsActivity` | Dexcom Share Status |
| `cDexcomDeviceShareFollowerSettingsActivity` | Share-Follower |
| `cDexcomDataConsentActivity` | Daten-Einwilligung |
| `cDexcomAccountSettingsActivity` | Dexcom-Konto |
| `cBolusDeliveryDoseActivity` | **mylife Dose Bolus-UI** |
| `cWebBrowserActivity` | In-App Browser |
| `WebAuthenticationCallbackActivity` | OAuth Callback |

### Services

| Service | Funktion |
|---|---|
| `cBluetoothAndroidAppService` | Haupt-BLE Service (Pump + CGM) |
| `TransmitterBleService` (Dexcom) | CGM Transmitter BLE |

### Receivers

| Receiver | Funktion |
|---|---|
| `AlarmReceiver` | Wecker/Erinnerungen |
| `cBroadcastReceiver` | App-Events |
| `cPlatformEgvNotificationService_EgvResetReceiver` | CGM Reset |
| `cPlatformEgvNotificationService_ExactAlarmPermissionChangedReceiver` | Alarm-Permission |

## Android Berechtigungen

```xml
BLUETOOTH_SCAN           <!-- BLE Scanning (Android 12+) -->
BLUETOOTH_CONNECT        <!-- BLE Verbindung (Android 12+) -->
BLUETOOTH_ADMIN          <!-- BLE Admin (bis Android 11) -->
BLUETOOTH                <!-- BLE Basis (bis Android 11) -->
BLUETOOTH_PRIVILEGED     <!-- BLE Privilegiert (bis Android 11) -->
INTERNET                 <!-- Netzwerk -->
ACCESS_NETWORK_STATE     <!-- Netzwerk-Status -->
CHANGE_NETWORK_STATE     <!-- Netzwerk ändern -->
CAMERA                   <!-- QR-Code Scan -->
ACCESS_COARSE_LOCATION   <!-- BLE Scanning Location -->
ACCESS_FINE_LOCATION     <!-- BLE Scanning Location -->
WAKE_LOCK                <!-- Hintergrund-Service -->
FOREGROUND_SERVICE       <!-- Foreground Service -->
FOREGROUND_SERVICE_CONNECTED_DEVICE  <!-- BLE Foreground -->
FLASHLIGHT               <!-- Taschenlampe -->
SCHEDULE_EXACT_ALARM     <!-- Exakte Alarme -->
POST_NOTIFICATIONS       <!-- Benachrichtigungen -->
VIBRATE                  <!-- Vibrationsalarme -->
CHANGE_WIFI_STATE        <!-- WiFi Steuerung -->
WRITE_EXTERNAL_STORAGE   <!-- Datei-Export (bis Android 9) -->
READ_EXTERNAL_STORAGE    <!-- Datei-Import -->
```

## Dexcom CGM Integration

Die mylife App enthält eine **vollständige Dexcom SDK-Integration** über mehrere Assemblies:

### Dexcom Assemblies

| Assembly | Funktion |
|---|---|
| `Dexcom.Android.Event.dll` | Events, Attestation, Integrity Manager |
| `Dexcom.Android.AppSupport.dll` | App-Support, BuildConfig |
| `Dexcom.Android.Transmitter.Libraries.dll` | BLE Transmitter-Kommunikation |
| `Dexcom.Android.CGM.System.dll` | CGM System-Management |
| `Dexcom.Android.Glucose.dll` | Glukose-Datenverarbeitung |
| `Dexcom.Android.GraphContainer.dll` | Glukose-Graphen |
| `Dexcom.Android.Insulin.dll` | Insulin-Daten (IOB etc.) |
| `Dexcom.Android.Cloud.Library.dll` | Dexcom Cloud/Share |
| `Dexcom.Android.Bulk.Core/Library.dll` | Bulk-Datenübertragung |
| `Dexcom.Android.Alert.Libraries.dll` | Alarm-System |
| `Dexcom.Android.Logging.Libraries.dll` | Dexcom Logging |
| `Dexcom.Cloud.Common.Service.dll` | Cloud API Service |

### Dexcom Share Features

- Remote Monitoring Sessions
- Follower Management
- Event Repository
- User Credentials Management
- Subscription Alert Settings

## Cloud-Integration

### Ypsomed Cloud

Die App nutzt eigene Ypsomed Cloud-Services:

```
Ypsomed.mylife.Apps.Services.Cloud.dll      — Cloud-Sync
Ypsomed.mylife.Apps.Services.RestCloud.dll   — REST API Client
```

### Libraries für Cloud-Kommunikation

| Library | Verwendung |
|---|---|
| `Square.Retrofit2.dll` | HTTP REST Client |
| `Square.OkHttp3.dll` | HTTP Transport |
| `Square.OkHttp3.LoggingInterceptor.dll` | HTTP Debug Logging |
| `Square.OkIO.dll` | IO Utilities |
| `Newtonsoft.Json.dll` | JSON Serialization |
| `RestSharp.dll` | Alternativer REST Client |
| `Refit.dll` | Type-safe REST Client |
| `Microsoft.IdentityModel.JsonWebTokens.dll` | JWT Handling |
| `System.IdentityModel.Tokens.Jwt.dll` | JWT Validation |

### Authentifizierung

- JWT-basiert (`Microsoft.IdentityModel.JsonWebTokens`)
- OAuth via WebAuthenticationCallbackActivity
- Dexcom Account Integration

## Datenspeicherung

### SQLite (verschlüsselt)

```
SQLite-net.dll          — ORM Layer
SQLitePCLRaw.core.dll   — Raw SQLite
libe_sqlite3.so         — Native SQLite (encrypted)
Sodium.Core.dll         — sqlite3_key / sqlite3_rekey
```

Die lokale Datenbank ist mit `sqlite3_key` verschlüsselt (SQLCipher-ähnlich).

### Encrypted Key Storage

```csharp
EncryptedKeyStore        — Generischer verschlüsselter Key Store
EncryptedAppKeyStore     — App-spezifischer Key Store
  → GetKeyPairAsync()        // X25519 Keypair
  → GetPumpPublicKeyAsync()  // Pump Curve25519 Key
  → GetSharedKeyPumpAppAsync() // Derived Shared Key
  → GetRebootCounter()
  → GetAttestationKeyId()
```

## MVVM-Architektur (MvvmCross)

Die App nutzt das MvvmCross Framework für MVVM-Pattern:

```
MvvmCross.dll                          — Core Framework
MvvmCross.DroidX.RecyclerView.dll      — Android RecyclerView
MvvmCross.Plugin.File.dll              — File I/O
MvvmCross.Plugin.Messenger.dll         — Event Messaging
MvvmCross.Plugin.PictureChooser.dll    — Bild-Auswahl
```

## Zusätzliche Libraries

| Library | Funktion |
|---|---|
| `NodaTime.dll` | Erweiterte Zeitzonenbehandlung |
| `FFImageLoading.dll` | Async Image Loading |
| `Lottie.Android.dll` | Animationen |
| `Telerik.Xamarin.Android.Chart.dll` | Glukose-Charts |
| `SkiaSharp.dll` | 2D-Grafik |
| `zxing.dll` / `ZXingNetMobile.dll` | QR/Barcode Scanning |
| `UrlBase64.dll` | URL-safe Base64 |
| `PCLCrypto.dll` | Portable Crypto Abstractions |
| `Google.Protobuf.dll` | Protocol Buffers |
| `Grpc.Core.dll` | gRPC Client |
