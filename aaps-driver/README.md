# AAPS YpsoPump Driver

AndroidAPS pump driver module for the Ypsomed YpsoPump insulin pump.

## Status: Work in Progress

This driver implements the basic structure for YpsoPump communication but requires:

1. **Key exchange** before first use (via Frida/CamAPS proxy — see [docs/09-bypass-options.md](../docs/09-bypass-options.md))
2. **BLE payload structure verification** via actual pump communication
3. **Integration testing** with a real YpsoPump

## Architecture

```
YpsoPumpPlugin (Pump interface)
    │
    ├── YpsoBleManager (BLE scan, connect, GATT)
    │   └── GattAttributes (UUID mapping)
    │
    ├── SessionCrypto (XChaCha20-Poly1305 encrypt/decrypt)
    │   └── KeyExchange (Curve25519 ECDH + HChaCha20 KDF)
    │
    ├── Commands (YpsoCommand base + 33 command implementations)
    │   ├── StatusCommand (index 30)
    │   ├── BolusCommand (index 27)
    │   ├── TbrCommand (index 29)
    │   └── HistoryCommand (COUNT/INDEX/VALUE pattern)
    │
    └── YpsoPumpState (pump status holder)
```

## Key Exchange Options

The YpsoPump requires a 9-step key exchange involving a backend server. Three approaches:

### Option A: CamAPS Proxy (Recommended)
Use Frida to fake a virtual pump towards CamAPS, capture the 116-byte key exchange payload and shared key. See [guides/frida-key-extraction.md](../guides/frida-key-extraction.md).

### Option B: Post-Exchange Key Extraction
Let CamAPS do a normal key exchange, then extract the shared key via Frida hooks on `write.b()`.

### Option C: Import Shared Key
If you already have a shared key (hex string), configure it in AAPS settings.

## Integration with AAPS

To integrate into the AndroidAPS build:

1. Copy `aaps-driver/` to `AndroidAPS/pump/ypsopump/`
2. Add to `settings.gradle`: `include ':pump:ypsopump'`
3. Add to `PluginsListModule.kt`:
   ```kotlin
   @Binds @PumpDriver @IntoMap @IntKey(175)
   abstract fun bindYpsoPumpPlugin(plugin: YpsoPumpPlugin): PluginBase
   ```
4. Build and install

## Dependencies

- `com.goterl:lazysodium-android:5.1.0` — XChaCha20-Poly1305 + Curve25519
- `net.java.dev.jna:jna:5.14.0@aar` — JNA for native libsodium
- AAPS core modules (interfaces, data, utils)

## Encryption Details

| Property | Value |
|----------|-------|
| Algorithm | XChaCha20-Poly1305 AEAD |
| Key size | 256 bits (32 bytes) |
| Nonce | 192 bits (24 bytes, random, appended to payload) |
| Auth tag | 128 bits (16 bytes, Poly1305) |
| Counter | 12 bytes (rebootCounter[4] + writeCounter[8]) |
| Key derivation | Curve25519 ECDH + HChaCha20 |
