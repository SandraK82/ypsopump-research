# 07 — Obfuscation: DexGuard Analysis

## Overview

CamAPS FX uses **DexGuard** (not standard ProGuard/R8) for extremely aggressive code obfuscation. This document details the obfuscation techniques discovered during reverse engineering and provides a partial class deobfuscation mapping.

## Obfuscation Tool: DexGuard

DexGuard is a commercial Android obfuscation tool by Guardsquare, going far beyond ProGuard:

| Feature | ProGuard | DexGuard |
|---------|----------|----------|
| Class/method renaming | Yes | Yes |
| String encryption | No | **Yes** |
| Control-flow obfuscation | No | **Yes** |
| Reflection wrapping | No | **Yes** |
| Native code protection | No | **Yes** |
| Anti-tamper checks | No | **Yes** |
| Resource encryption | No | **Yes** |

## Technique 1: Class Renaming

Real classes are renamed to Android framework class names, making static analysis extremely confusing:

### Deobfuscated Class Mapping

| Obfuscated Name | Actual Purpose | Key Evidence |
|-----------------|----------------|-------------|
| `KmsAeadKeyOrBuilder` | **PumpBleHandler** | BLE read/writeCharacteristic, key exchange orchestration |
| `KmsEnvelopeAeadKeyBuilder` | **ProBluetoothConfig** | BLE configuration, Parcelable implementation |
| `MediaDescriptionCompat` | **BackendService** | gRPC client, `connect.cam.pr.sec01.proregia.io` reference |
| `write` | **CryptoWrapper** | Lazysodium calls, XChaCha20-Poly1305 encrypt/decrypt |
| `ensureEntryIsMutable` | **EncryptedAppKeyStore** | EncryptedSharedPreferences wrapper |
| `clearDekTemplate` | **KeyExchangeStateListener** | Step update interface/callback |
| `HmacKey1` | **LazySodium-Wrapper** | Abstraction over Sodium JNA calls |
| `a.setCheckable` | **MainActivity** | Main activity lifecycle |
| `a.setSupportButtonTintMode` | **SelectCGM Activity** | CGM selection screen |
| `getMaximumBolusSuggestion` | **CryptoHelper** | RSA/AES/PBKDF2 implementations |
| `S3ExecutionContext` | **StringDecryptor** | Central string decryption dispatch |
| `setAlertVersion` | **OkHttp Client Setup** | HTTP client configuration |
| `setPumpType` | **OkHttp Client Setup** | HTTP client configuration (variant) |

## Technique 2: String Encryption

All string constants are encrypted and decrypted at runtime. Four distinct methods were identified:

### Method 1: Byte-Array XOR (`$$c` method)

Each class has a private static byte array seed and a decryption method:

```java
private static final byte[] $$a = {0x12, 0x34, ...};  // per-class seed
private static final int $$b = 42;                      // offset

private static String $$c(int start, int length) {
    // XOR-based stream cipher using $$a as key
    // Parameters control starting position and length
    // Returns decrypted string
}
```

Each class has its own `$$a` seed and `$$b` offset. The `$$c` method signature varies per class.

### Method 2: Char-Array Reflection

```java
// Encrypted char arrays
static final char[] data = {36299, 8591, 54642, 35014, ...};

// Decrypted via reflection through S3ExecutionContext
String result = S3ExecutionContext.write(index);
```

### Method 3: S3ExecutionContext Central Dispatch

The `S3ExecutionContext` class serves as the central string decryption hub:

```java
// Simple form
public static String write(int index);

// Complex form with type dispatch
public static Object b(int index, int subIndex, char type,
                        int arg, boolean flag, String str,
                        Class[] classes);
```

This class uses reflection internally, reading encrypted data from the APK's resources and decrypting on demand.

### Method 4: XTEA-based Encryption

Found in `setAlertVersion.java`:

```java
// 16-round XTEA (eXtended Tiny Encryption Algorithm)
// Used for string constants in OkHttp configuration classes
```

## Technique 3: Control-Flow Obfuscation

Fake branches are inserted throughout the code:

```java
// Original:
doSomething();

// Obfuscated:
int i = someValue;
if (i % 2 == 0) {
    throw null;  // Never reached — dead code
}
doSomething();
if (i % 3 == 0) {
    throw null;  // Never reached
}
```

These branches:
- Never execute (conditions are always false)
- Confuse decompiler control-flow analysis
- Make automated pattern matching unreliable
- Force manual analysis of each method

## Technique 4: Reflection Wrapping

Standard Java method calls are replaced with reflection:

```java
// Original:
String result = obj.toString();

// Obfuscated:
Object result = Class.forName("java.lang.Object")
    .getMethod("toString")
    .invoke(obj);
```

This affects:
- All standard library calls
- Internal method calls between classes
- Constructor invocations
- Field access

## Technique 5: Native Code Protection

The native security library `libe61d.so` (see [06-closed-loop-algorithm.md](06-closed-loop-algorithm.md)) adds:

- Root detection
- Anti-debug (ptrace, process monitoring)
- Anti-Frida / anti-hook (dladdr, mprotect monitoring)
- Anti-tamper (fork/execv self-monitoring)
- APK integrity verification

## Technique 6: PairIP License Check

Package `com/pairip/licensecheck/` implements Google Play license verification, adding another layer of app authenticity checks.

## Impact on Reverse Engineering

### What Makes Analysis Hard

1. Class names give no hints about functionality
2. String constants are not visible in static analysis
3. Control flow is polluted with dead branches
4. Method calls are hidden behind reflection
5. Native code is encrypted and anti-debug protected

### What Makes Analysis Possible

1. **BLE UUIDs are not encrypted** — they exist as byte arrays that DexGuard doesn't recognise as strings
2. **libsodium function signatures** are identifiable by parameter patterns (key sizes, nonce sizes)
3. **gRPC endpoint** `connect.cam.pr.sec01.proregia.io` is in cleartext in `MediaDescriptionCompat.java`
4. **Protobuf message classes** retain their field structure even when renamed
5. **JNI method signatures** in `GlobalFunctions.java` are readable
6. **Firebase/strings.xml** values are in cleartext
7. **Dexcom OAuth credentials** are in cleartext

### Key Source Files (Decompiled Paths)

| File | Purpose |
|------|---------|
| `defpackage/KmsAeadKeyOrBuilder.java` | PumpBleHandler |
| `defpackage/MediaDescriptionCompat.java` | BackendService |
| `defpackage/write.java` | CryptoWrapper |
| `defpackage/ensureEntryIsMutable.java` | EncryptedAppKeyStore |
| `defpackage/S3ExecutionContext.java` | StringDecryptor |
| `defpackage/getMaximumBolusSuggestion.java` | CryptoHelper |
| `com/proregia/probluetooth/enums/ProStatusCodeEnum.java` | Error codes |
| `uk/ac/cam/ap/ypsomed_15x/ypsomed/YpsoCommandChars.java` | BLE commands |
| `uk/ac/cam/ap/ypsomed_15x/data/DataRepositoryImp.java` | Pump data |

## Deobfuscation Methodology

The approach used for this analysis:

1. **Identify entry points**: AndroidManifest.xml activity/service declarations
2. **Follow BLE callbacks**: `onCharacteristicChanged` leads to crypto code
3. **Pattern match libsodium**: 32-byte keys + 24-byte nonces = XChaCha20
4. **Trace gRPC calls**: Protobuf serialization patterns are distinctive
5. **Hook with Frida**: Runtime string decryption reveals actual values
6. **Cross-reference error codes**: `ProStatusCodeEnum` provides semantic context
