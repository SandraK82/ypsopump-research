# 06 — Closed-Loop Algorithm: Native Library Protection

## Overview

The CamAPS FX closed-loop insulin dosing algorithm is protected by a **two-stage native library encryption system**. The algorithm itself runs as native code, never exposed as Java/Kotlin bytecode, and is additionally encrypted at rest within the APK.

## Native Library Inventory

| Library | Size | Purpose |
|---------|------|---------|
| `liba532a9.so` | 554 KB | **Encrypted algorithm container** |
| `libe61d.so` | 1.3 MB | **Security runtime** (decrypts `liba532a9.so`) |
| `libsodium.so` | 267 KB | BLE crypto (libsodium 1.0.20) |
| `libd7c23b.so` | 5.7 MB | SQLCipher 4.9.0 + OpenSSL 3.0.16 |
| `libjnidispatch.so` | 166 KB | JNA 7.0.4 dispatch bridge |

## The Encrypted Container: `liba532a9.so`

### Static Analysis

- **No `.text` segment** — no executable code in the ELF file
- **No symbols** — completely stripped
- **No imports** — no dynamic library dependencies
- **Entropy**: 7.9998 / 8.0 — consistent with AES-256 encrypted data
- **Conclusion**: The entire file is an encrypted payload, not a valid shared library in its own right

### Runtime Decryption

At app startup, `libe61d.so` (the security runtime):
1. Performs integrity checks (root detection, anti-debug, anti-tamper)
2. If all checks pass, decrypts `liba532a9.so` in memory
3. Calls `RegisterNatives()` to bind the decrypted code to Java methods
4. The decrypted code never touches disk

## The Security Runtime: `libe61d.so`

### Exported Symbols

Only **3 exported symbols** — extremely minimal surface:
- `JNI_OnLoad` (39 KB of code — 9,776 ARM64 instructions)
- Two additional helper symbols

### Obfuscation Metrics

| Metric | Value |
|--------|-------|
| `JNI_OnLoad` size | 39 KB |
| Instructions | 9,776 |
| Indirect calls (BLR) | 986 |
| Obfuscated syscalls (SVC) | 476 |

### Anti-Tamper Capabilities

```
libe61d.so — Security Runtime
    │
    ├── Root Detection
    │   ├── __system_property_find_nth()
    │   ├── __system_property_foreach()
    │   ├── __system_property_get()
    │   ├── Check for /sbin/su, /system/xbin/su
    │   └── Check for Superuser.apk
    │
    ├── Anti-Debug
    │   ├── 476 obfuscated SVC syscalls
    │   ├── ptrace self-attach detection
    │   ├── /proc/self/status parsing
    │   └── Process monitoring via fork/execv
    │
    ├── Anti-Frida / Anti-Hook
    │   ├── dladdr() — check library origins
    │   ├── dlopen() — monitor loaded libraries
    │   ├── mprotect() — detect page permission changes
    │   └── Memory scanning for Frida signatures
    │
    └── Anti-Tamper
        ├── fork() + execv() self-monitoring
        ├── APK integrity verification
        └── kill() / killpg() for self-termination
```

### Imported Functions (Signature of Anti-Tamper)

```c
JNI_OnLoad, __system_property_find_nth, __system_property_foreach,
__system_property_get, __system_property_read, __system_property_read_callback,
fork, execv, kill, killpg, getppid, dladdr, dlerror, dlopen, dlsym,
mprotect, getpagesize, ioctl, fcntl, fopen, fclose, dup2, close, closedir,
basename, atoi, calloc, free, malloc, isupper, _exit
```

## JNI Interface: `GlobalFunctions.java`

The decrypted algorithm exposes three native methods:

```java
public static native synchronized String getRecommendation(
    String patientDataJson,    // Patient profile, settings
    String cgmDataJson,        // CGM readings array
    String pumpStatusJson,     // Current pump state
    byte[] licenseToken        // License/authentication token
);

public static native synchronized float getTDD(
    String patientData,
    String history,
    float f1,
    float f2,
    long timestamp,
    byte[] licenseToken
);

public static native synchronized int newPatient(String patientJson);
```

### The `byte[]` License Token

The `licenseToken` parameter acts as a runtime authentication mechanism:

- Without a valid token, the algorithm returns empty/error results
- The token is likely derived from the CamAPS FX subscription license
- This prevents extracted algorithm code from being used in third-party apps
- Additional licensing check via **PairIP** (`com/pairip/licensecheck/`) and Google Play

## 2-Stage Protection Flow

```
┌─────────────────────────────────────────────┐
│  APK on disk                                │
│                                             │
│  liba532a9.so  ── AES-256 encrypted blob   │
│  libe61d.so    ── security runtime          │
│                                             │
└──────────────────┬──────────────────────────┘
                   │ App launch
                   ▼
┌─────────────────────────────────────────────┐
│  libe61d.so::JNI_OnLoad()                   │
│                                             │
│  1. Root detection     ─── FAIL → crash     │
│  2. Anti-debug check   ─── FAIL → crash     │
│  3. Anti-Frida check   ─── FAIL → crash     │
│  4. APK integrity      ─── FAIL → crash     │
│  5. All passed                              │
│     └── Decrypt liba532a9.so in memory      │
│     └── RegisterNatives() → bind JNI        │
│                                             │
└──────────────────┬──────────────────────────┘
                   │
                   ▼
┌─────────────────────────────────────────────┐
│  Decrypted Algorithm (in memory only)       │
│                                             │
│  getRecommendation(patient, cgm, pump, lic) │
│  getTDD(patient, history, ..., lic)         │
│  newPatient(patient)                        │
│                                             │
│  License token validated per call           │
└─────────────────────────────────────────────┘
```

## Relevance for AAPS Driver

The closed-loop algorithm is **NOT needed** for an AAPS pump driver because:

1. AAPS has its own algorithm (oref1 / OpenAPS)
2. The driver only needs to send bolus/TBR commands to the pump
3. Algorithm decisions are made by AAPS, not the pump
4. The pump is a "dumb" executor — it delivers insulin as instructed

The security around the algorithm protects CamDiab's intellectual property (the Cambridge closed-loop algorithm), not the pump communication protocol.

## Bypass Considerations

For researchers who want to study the algorithm:

1. **Frida + anti-anti-debug**: Bypass `libe61d.so` checks, then dump decrypted memory
2. **Hardware debugger**: JTAG/SWD on the Android device to bypass all software checks
3. **Emulation**: Run the native code in a controlled environment (QEMU + Android linker)
4. **Hook `RegisterNatives()`**: Intercept the JNI binding to capture function pointers

Note: The algorithm is likely a variant of the Cambridge MPC (Model Predictive Control) algorithm published in academic papers, so the general approach is already known — only the specific tuning and implementation are protected.
