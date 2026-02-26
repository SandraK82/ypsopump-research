# 09 — Bypass Options: Practical Approaches

## Overview

This document describes practical approaches to establish independent BLE communication with the YpsoPump. The primary challenge is obtaining a valid shared key, as the 9-step key exchange requires backend cooperation. We present four strategies, from simplest to most complex.

## Strategy Comparison

| Strategy | Difficulty | Requires Root | Requires CamAPS | Offline Capable |
|----------|-----------|---------------|------------------|-----------------|
| **A: Pump Faking (Frida)** | Low | Yes | Yes (running) | After key extract |
| **B: Key Extraction (Frida)** | Low | Yes | Yes (one-time) | Yes |
| **C: gRPC MITM** | Medium | Yes | Yes (one-time) | Yes |
| **D: SWD Flash Extraction** | High | No | No | Yes |

## Strategy A: Pump Faking via Frida (Recommended)

**Concept**: Use Frida to fake a virtual YpsoPump towards CamAPS FX, intercept the 116-byte key exchange payload, then relay it to the real pump from a custom driver app.

This is the **most elegant approach** because:
1. CamAPS handles all backend complexity (gRPC, Play Integrity, nonce request)
2. No need to reimplement the key exchange protocol
3. CamAPS becomes a "key exchange proxy"
4. After key exchange, the custom driver communicates directly with the real pump

### Architecture

```
┌─────────────────────────────────────────────────────┐
│  Phase 1: Key Exchange via CamAPS Proxy             │
│                                                     │
│  ┌──────────┐  Frida   ┌──────────┐    ┌────────┐  │
│  │  CamAPS  │◄────────►│  Fake    │    │  Real  │  │
│  │  FX App  │  hooks   │  Pump    │    │  Pump  │  │
│  └────┬─────┘          │ (BLE     │    │(YpsoPump│  │
│       │                │  Periph.)│    │        │  │
│       │ gRPC           └──────────┘    └────────┘  │
│       ▼                                            │
│  ┌──────────┐                                      │
│  │ ProRegia │                                      │
│  │ Backend  │                                      │
│  └──────────┘                                      │
│                                                     │
│  → CamAPS reads challenge from fake pump            │
│  → CamAPS does gRPC key exchange with backend       │
│  → CamAPS writes 116 bytes to fake pump             │
│  → Frida intercepts the 116 bytes + all keys        │
│                                                     │
└─────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────┐
│  Phase 2: Direct Communication                      │
│                                                     │
│  ┌──────────┐         ┌────────┐                   │
│  │  Custom   │◄──BLE──►│  Real  │                   │
│  │  Driver   │         │  Pump  │                   │
│  │  (AAPS)   │         │        │                   │
│  └──────────┘         └────────┘                   │
│                                                     │
│  → Driver has shared key from Phase 1               │
│  → XChaCha20-Poly1305 encrypted communication      │
│  → No CamAPS needed anymore                        │
│                                                     │
└─────────────────────────────────────────────────────┘
```

### Implementation Steps

1. **Create BLE Peripheral**: Android app that advertises as YpsoPump
   - Same service UUID: `669a0c20-0008-969e-e211-ffffffffffff`
   - Same device name pattern: `mylife YpsoPump XXXXXX`
   - Same GATT service structure

2. **Serve Challenge + Public Key**: When CamAPS reads the key exchange characteristic
   - Forward the real pump's challenge (32 bytes) + public key (32 bytes)
   - Or generate own Curve25519 keypair for the challenge

3. **Intercept 116-Byte Payload**: When CamAPS writes the key exchange response
   - Capture the 116 bytes via Frida hook on `writeCharacteristic`
   - Forward to the real pump

4. **Extract Shared Key**: Hook `write.b(byte[])` (the shared key computation)
   - Captures `privateKey`, `pumpPublicKey`, and derived `sharedKey`
   - Store for use in custom driver

5. **Hand Off to Custom Driver**: The custom app now has everything needed
   - 32-byte shared key
   - Counter values (write=0, read=0 after key exchange)
   - Full command set knowledge

### Frida Hook Points

```javascript
// Hook the BLE write to capture 116-byte payload
Java.perform(function() {
    var BluetoothGatt = Java.use("android.bluetooth.BluetoothGatt");
    BluetoothGatt.writeCharacteristic.overload(
        "android.bluetooth.BluetoothGattCharacteristic",
        "int"
    ).implementation = function(char, writeType) {
        var value = char.getValue();
        if (value && value.length === 116) {
            console.log("[*] 116-byte key exchange payload captured!");
            console.log(bytesToHex(value));
            // Forward to real pump or save
        }
        return this.writeCharacteristic(char, writeType);
    };
});
```

### Advantages

- CamAPS does all the heavy lifting (backend auth, Play Integrity, gRPC)
- No need to reverse-engineer the 116-byte payload structure
- Works with any future backend protocol changes
- Key exchange happens through legitimate channels

### Limitations

- Requires a rooted Android phone running CamAPS
- CamAPS must be installed and logged in
- Timing-sensitive (BLE connection management)

## Strategy B: Post-Exchange Key Extraction (Frida)

**Concept**: Let CamAPS perform a normal key exchange with the real pump, then extract the shared key via Frida hooks.

### Hook Target: `write.b(byte[])`

The `write.b()` method computes the shared key:

```javascript
Java.perform(function() {
    var writeClass = Java.use("write");
    writeClass.b.overload("[B").implementation = function(pumpPublicKey) {
        console.log("[*] Shared key computation triggered!");
        console.log("[*] Pump Public Key: " + bytesToHex(pumpPublicKey));

        var result = this.b(pumpPublicKey);

        // The shared key is now stored internally
        // Read it from the object fields
        console.log("[*] Shared Key: " + bytesToHex(result));
        return result;
    };
});
```

### Hook Target: EncryptedSharedPreferences

```javascript
// Hook SharedPreferences.getString to capture stored keys
Java.perform(function() {
    var SharedPrefs = Java.use("android.content.SharedPreferences");
    // ... hook implementation in guides/frida-key-extraction.md
});
```

### Process

1. Install CamAPS + Frida on rooted phone
2. Start Frida script before opening CamAPS
3. Let CamAPS connect to pump normally
4. Key exchange happens → Frida captures shared key
5. Save key for custom driver
6. Disconnect CamAPS, connect custom driver

## Strategy C: gRPC Man-in-the-Middle

**Concept**: Since there is no certificate pinning, intercept the gRPC `EncryptKey` response to capture the 116-byte payload.

### Setup

1. Install mitmproxy/Burp on computer
2. Install custom root CA on rooted Android device
3. Route traffic through proxy (iptables or Wi-Fi proxy)
4. Let CamAPS perform key exchange
5. Capture `EncryptKeyResponse.encrypted_bytes` (116 bytes)

### gRPC Interception

```bash
# Using mitmproxy with gRPC support
mitmproxy --mode transparent \
  --set grpc_protobuf_parser=true \
  --flow-filter "~d connect.cam.pr.sec01.proregia.io"
```

The response contains:
```protobuf
EncryptKeyResponse {
    encrypted_bytes: <116 bytes, Base64-encoded>
}
```

### Advantages

- Captures the full protocol exchange
- Can be repeated for different pumps
- Also captures firmware update traffic

### Limitations

- Requires rooted phone with custom CA
- gRPC binary encoding needs decoding
- Only captures backend traffic, still need to extract local shared key

## Strategy D: SWD Flash Extraction (Hardware)

**Concept**: Extract the Pre-Shared Key (PSK) from the STM32F051 via Serial Wire Debug, enabling completely offline key exchanges.

### Requirements

- SWD debugger (ST-Link, J-Link, or Black Magic Probe)
- Physical access to YpsoPump PCB
- STM32F051 RDP (Read-Out Protection) must NOT be Level 2

### SWD Pinout (STM32F051C8T6 LQFP-48)

| Signal | GPIO | Pin |
|--------|------|-----|
| SWDIO | PA13 | 34 |
| SWCLK | PA14 | 37 |
| NRST | — | 7 |
| GND | VSS | 8, 23, 35, 47 |

### Process

1. Open pump housing (carefully — medical device)
2. Connect SWD debugger to test pads
3. Check RDP level: `st-info --probe`
4. If RDP Level 0 or 1: dump entire flash
   ```bash
   st-flash read firmware.bin 0x08000000 0x10000  # 64 KB
   ```
5. Analyse firmware for:
   - PSK (256-bit, likely at fixed address)
   - Pump public key (32 bytes Curve25519)
   - BLE protocol state machine
   - Crypto implementation

### If Successful

With the PSK, you can:
1. Generate 116-byte payloads yourself (no backend needed)
2. Perform unlimited key exchanges
3. Fully emulate the backend
4. Analyse the pump's crypto implementation

### Risks

- **Destructive**: Opening the pump voids warranty
- **RDP Level 2**: If set, flash is permanently locked (reads return zero)
- **Single pump only**: Each pump has a unique PSK

## Strategy Recommendation

For the AAPS driver project, we recommend **Strategy A (Pump Faking)** combined with **Strategy B (Key Extraction)** as fallback:

1. **First attempt**: Strategy A — use CamAPS as key exchange proxy
2. **Fallback**: Strategy B — let CamAPS do normal key exchange, extract key via Frida
3. **One-time setup**: After shared key is obtained, driver operates independently
4. **Key renewal**: Repeat every 28 days (or disable expiry check via Frida)

Since the pump doesn't enforce key expiration, a single key extraction could theoretically last until the next firmware update or pump replacement.

## Brute-Force Analysis

For completeness: brute-forcing the 256-bit shared key is computationally infeasible (2^256 combinations). However, the Curve25519 key space is also 256 bits, and the PSK is 256 bits. There are no shortcuts — the cryptography is sound. The weaknesses are in the implementation and protocol, not the algorithms.
