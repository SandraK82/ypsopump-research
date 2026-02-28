# 08 — Security Findings

## Overview

This document summarises all security vulnerabilities and noteworthy findings discovered during the reverse engineering of CamAPS FX v1.4(190).111. Findings are categorised by severity and include both weaknesses and strong security practices.

## Critical Findings

### 1. No Certificate Pinning (gRPC + REST)

**Severity**: High
**Impact**: Full MITM of all backend communication on rooted devices

The gRPC channel to `connect.cam.pr.sec01.proregia.io:443` uses standard TLS without certificate pinning:

```java
// Decompiled (MediaDescriptionCompat.java)
ManagedChannelBuilder.forAddress("connect.cam.pr.sec01.proregia.io", 443)
    .build();
// No CertificatePinner, no custom TrustManager
```

Similarly, OkHttp REST clients use standard `OkHttpClient.Builder()` without `CertificatePinner`.

**Exploitation**: Install a custom root CA on a rooted Android device, then intercept all traffic including the 116-byte key exchange response.

### 2. Hardcoded Dexcom OAuth Client Secret

**Severity**: High
**Impact**: Unauthorised access to Dexcom APIs

Both the Client ID (a UUID) and Client Secret are hardcoded in cleartext in `UAMTokenActivity.java` (DexGuard-obfuscated class name varies by version). The client secret is a trivially guessable string — literally the word "secret".

### 3. Dexcom Development Server URLs in Production

**Severity**: Medium
**Impact**: Potential data leakage to non-production environment

The Authorization and Token endpoints (found in cleartext in `UAMTokenActivity.java`) point to a `.dexcomdev.com` domain — a development/staging environment, not production. Production CamAPS FX is configured to authenticate against Dexcom's dev servers.

### 4. AES-128-CBC Without Authentication

**Severity**: Medium
**Impact**: Potential padding oracle attacks on account data

```java
// Account encryption in AccountsEntity.java
byte[] key = Arrays.copyOf(
    MessageDigest.getInstance("SHA-256").digest(passwordBytes),
    16  // SHA-256 truncated to 16 bytes = AES-128!
);
// AES/CBC/PKCS5Padding — no HMAC, no AEAD
```

The `emailAddressEncrypted` and `passwordEncrypted` fields use AES-128-CBC with a key derived from a truncated SHA-256 hash. No authentication (HMAC/AEAD) is applied.

### 5. App-Side-Only Key Expiration

**Severity**: Medium
**Impact**: Indefinite shared key validity

The 28-day key expiration is enforced only in the app:

```java
// Decompiled key expiry check
calendar.add(Calendar.DAY_OF_YEAR, 28);
if (now.after(calendar)) {
    // Force new key exchange
}
```

The **pump does not check key age**. Manipulating `sharedKeyDate` in EncryptedSharedPreferences (via Frida or Keystore hook) extends validity indefinitely.

### 6. Firebase API Keys in Cleartext

**Severity**: Low (standard for Firebase, but noteworthy)

All Firebase configuration values (API Key, Database URL, GCM Sender ID, App ID, Storage Bucket) are stored in cleartext in `res/values/strings.xml` within the APK. They can be extracted by any standard APK decompilation tool (e.g., `apktool d`, `jadx`).

### 7. IV Position Inconsistency

**Severity**: Low
**Impact**: Implementation fragility

AES-CBC IV is prepended in `AppSettings` but appended in Follow Alerts. This inconsistency suggests different developers or iterations.

### 8. Step 2 Skipped in Key Exchange

**Severity**: Informational
**Impact**: Reduced security posture

Step 2 of the 9-step key exchange protocol is marked as "obsolete" on Android and skipped entirely. This may have been a key attestation step.

## Known CVEs (Pre-v1.7.5, 2021)

Discovered by ManiMed/ERNW security researchers:

| CVE | Description | CVSS |
|-----|------------|------|
| CVE-2021-27491 | Password hashes exposed during registration | 5.8 |
| CVE-2021-27495 | Password visible on HTTPS → HTTP redirect | 6.3 |
| CVE-2021-27499 | Non-random IVs in CBC encryption | 5.4 |
| CVE-2021-27503 | Hardcoded secrets for app-layer encryption | 5.4 |

The current version (v1.4(190).111) uses ProBluetooth SDK 2.0.13 with the 9-step key exchange — this is the response to these CVEs.

## Strong Security Practices

Credit where due — CamAPS FX implements several strong security measures:

| Practice | Details |
|----------|---------|
| **XChaCha20-Poly1305 AEAD** | Modern, secure authenticated encryption for BLE |
| **Curve25519 ECDH** | Strong key agreement with forward secrecy per key exchange |
| **HChaCha20 KDF** | Proper key derivation from raw ECDH output |
| **9-step key exchange** | Multi-party trust model (app, backend, pump) |
| **Google Play Integrity** | Attests app and device integrity |
| **Utimaco Cloud HSM** | Hardware security module for backend key material |
| **Counter-based replay protection** | Triple counter system (write, read, reboot) |
| **EncryptedSharedPreferences** | AES256-SIV + AES256-GCM for local key storage |
| **SQLCipher 4.9.0 Commercial** | AES-256 per-page database encryption |
| **DexGuard obfuscation** | Aggressive code protection |
| **Native anti-tamper** | Root detection, anti-debug, anti-Frida in `libe61d.so` |
| **Encrypted algorithm** | Closed-loop algorithm encrypted at rest in `liba532a9.so` |
| **24-byte random nonces** | Safe for random generation (192-bit birthday bound) |
| **No cleartext traffic** | `usesCleartextTraffic="false"` in manifest |

## Update (2026-02-27): Play Integrity Bypass erfolgreich

**Die Play Integrity-Prüfung kann vollständig umgangen werden.** Mit zwei Magisk-Modulen (TrickyStore v1.4.1 + Integrity Box V31) wird MEETS_STRONG_INTEGRITY erreicht — das höchste Integritätslevel. Der ProRegia Key Exchange und die Pumpenverbindung funktionieren auf einem gerooteten Gerät.

Dieses Ergebnis hat Auswirkungen auf die Sicherheitsbewertung:

| Schutzmaßnahme | Bewertung (aktualisiert) |
|----------------|--------------------------|
| **Google Play Integrity** | UMGANGEN — OEM-Keybox-Leak ermöglicht STRONG_INTEGRITY auf gerooteten Geräten |
| **Native anti-tamper** | IRRELEVANT — Kein Frida nötig, App läuft unmodifiziert |
| **DexGuard obfuscation** | IRRELEVANT — App wird nicht gepatcht oder gehookt |

Die Schwäche liegt nicht bei CamAPS/Ypsomed, sondern im **Play Integrity Ökosystem selbst**: Geleakte OEM-Keyboxes ermöglichen es gerooteten Geräten, als nicht-gerootet zu erscheinen. Google revoked diese Keyboxes regelmäßig, aber neue tauchen zeitnah auf.

Siehe [13-play-integrity-bypass-success.md](13-play-integrity-bypass-success.md) für die vollständige Dokumentation.

## Attack Surface Summary

```
┌──────────────────────────────────────────────────────────┐
│  ATTACK SURFACE                                          │
├──────────────────────────────────────────────────────────┤
│                                                          │
│  BLE Layer (strong)                                      │
│  ├── XChaCha20-Poly1305 AEAD ......... SECURE           │
│  ├── Curve25519 key exchange .......... SECURE           │
│  ├── Counter replay protection ........ SECURE           │
│  └── 28-day key expiry ............... APP-SIDE ONLY ⚠️  │
│                                                          │
│  Backend Layer (mixed)                                   │
│  ├── gRPC TLS ........................ NO PINNING ❌     │
│  ├── Utimaco HSM ..................... SECURE            │
│  ├── Google Play Integrity ........... SECURE            │
│  └── Key attestation Step 2 ......... SKIPPED ⚠️        │
│                                                          │
│  Cloud Layer (weak)                                      │
│  ├── Dexcom OAuth ................... HARDCODED SECRET ❌│
│  ├── Account encryption ............. AES-128-CBC ❌     │
│  ├── Firebase keys .................. CLEARTEXT ⚠️       │
│  └── MyLife Cloud ................... AZURE APIM ✓       │
│                                                          │
│  App Layer (strong)                                      │
│  ├── DexGuard obfuscation ........... STRONG            │
│  ├── Native anti-tamper .............. STRONG            │
│  ├── SQLCipher database .............. SECURE            │
│  └── EncryptedSharedPreferences ...... SECURE            │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

## Responsible Disclosure

These findings were obtained through static analysis of a publicly available APK from APKPure. No active exploitation of production systems was performed. The research is conducted under the #WeAreNotWaiting movement for diabetes device interoperability and the EHDS (European Health Data Space) right to data portability.

We recommend CamDiab/Ypsomed:
1. Implement certificate pinning on gRPC and REST channels
2. Rotate the Dexcom OAuth client secret and use dynamic configuration
3. Switch to AEAD encryption (e.g. AES-GCM) for account data
4. Re-enable Step 2 key attestation
5. Enforce key expiration server-side or on-pump
