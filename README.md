# YpsoPump Research

**Reverse engineering documentation and AndroidAPS driver for the Ypsomed YpsoPump insulin pump.**

> **#WeAreNotWaiting** — Because patients shouldn't have to wait for interoperability.

## What is this?

This repository contains the findings of a comprehensive security and protocol analysis of the **mylife YpsoPump** insulin pump and its companion app **CamAPS FX** (v1.4(190).111). The goal is to enable the diabetes community to build open-source drivers for the YpsoPump, particularly for [AndroidAPS](https://github.com/nightscout/AndroidAPS).

**This repository does NOT contain decompiled source code.** All findings were obtained through static analysis of a publicly available APK and documented in a clean-room manner.

## Key Findings

| Topic | Finding |
|-------|---------|
| **BLE Encryption** | XChaCha20-Poly1305 AEAD via libsodium 1.0.20 |
| **Key Exchange** | 9-step protocol involving app, backend (gRPC), and pump |
| **Key Derivation** | Curve25519 ECDH + HChaCha20 KDF |
| **Commands** | 33 BLE commands (indices 0-32) across 4 services |
| **Backend** | `connect.cam.pr.sec01.proregia.io:443` — **no certificate pinning** |
| **Key Expiry** | 28-day expiry enforced **app-side only** — pump doesn't check |
| **Hardware** | STM32F051 (MCU) + EM9301 (BLE radio) |
| **Bypass** | Shared key extractable via Frida after single legitimate key exchange |

## Documentation

| Document | Description |
|----------|-------------|
| [01 — Hardware](docs/01-hardware.md) | PCB analysis, STM32F051, EM9301, FCC filings |
| [02 — BLE Protocol](docs/02-ble-protocol.md) | All 33 commands, GATT UUIDs, service structure |
| [03 — Encryption](docs/03-encryption.md) | XChaCha20-Poly1305, 5-layer encryption stack |
| [04 — Key Exchange](docs/04-key-exchange.md) | 9-step protocol, 116-byte payload, Curve25519 |
| [05 — Backend](docs/05-backend-communication.md) | gRPC services, AWS, Azure, Dexcom, Firebase |
| [06 — Algorithm](docs/06-closed-loop-algorithm.md) | Encrypted native library, 2-stage protection |
| [07 — Obfuscation](docs/07-obfuscation.md) | DexGuard techniques, deobfuscated class map |
| [08 — Security](docs/08-security-findings.md) | Vulnerabilities, CVEs, attack surface |
| [09 — Bypass](docs/09-bypass-options.md) | Frida pump faking, key extraction, gRPC MITM |
| [10 — Legal](docs/10-legal-analysis.md) | EHDS, GDPR, DMCA, right to interoperability |
| [11 — CamAPS Algorithm](docs/11-camaps-algorithm-analysis.md) | Cambridge Algorithm analysis |
| [12 — CamAPS Sideload](docs/12-camaps-sideload-bypass.md) | Sideload bypass attempt |
| [13 — Play Integrity](docs/13-play-integrity-bypass-success.md) | STRONG_INTEGRITY bypass via TrickyStore |
| [14 — mylife Overview](docs/14-mylife-app-overview.md) | mylife App architecture, Xamarin/.NET stack |
| [15 — mylife Security](docs/15-mylife-app-security.md) | No root detection, no anti-tamper, no obfuscation |
| [16 — mylife Protocol](docs/16-mylife-app-proregia-protocol.md) | ProRegia gRPC, Key Exchange, Commander Key |
| [17 — mylife Components](docs/17-mylife-app-components.md) | Activities, Dexcom SDK, Cloud integration |
| [18 — mylife Bypass](docs/18-mylife-app-bypass-plan.md) | Bypass-Plan für gerootetes A22 |
| [19 — Key Lifecycle](docs/19-key-lifecycle-pump-rotation.md) | Pump-seitige Rotation, Counter-Management |

## Practical Guides

| Guide | Description |
|-------|-------------|
| [Building a Driver App](guides/building-a-driver-app.md) | Complete walkthrough: BLE, crypto, commands |
| [Frida Key Extraction](guides/frida-key-extraction.md) | Scripts for shared key capture + pump faking |
| [BLE Sniffing Setup](guides/ble-sniffing-setup.md) | nRF Sniffer + Wireshark + decryption |

## AAPS Driver

The [`aaps-driver/`](aaps-driver/) directory contains a work-in-progress AndroidAPS pump driver module:

- Full XChaCha20-Poly1305 encryption implementation
- Curve25519 key exchange (local steps)
- All 33 command codes
- PumpType.YPSOPUMP integration (already exists in AAPS)
- Dagger DI module
- Status, bolus, and TBR command classes

**Status**: The driver structure is complete, but requires a real YpsoPump for BLE payload verification and integration testing. The key exchange must be bootstrapped via Frida/CamAPS proxy (see [Strategy A: Pump Faking](docs/09-bypass-options.md#strategy-a-pump-faking-via-frida-recommended)).

## The Pump Faking Approach

The recommended strategy for bootstrapping the driver:

```
Phase 1: Use Frida to fake a virtual YpsoPump towards CamAPS FX
         → CamAPS performs the key exchange with the backend
         → Capture the 116-byte payload and shared key
         → No need to reimplement backend communication!

Phase 2: Custom driver communicates directly with real pump
         → Uses the captured shared key
         → XChaCha20-Poly1305 encrypted BLE
         → Independent of CamAPS
```

This approach is documented in detail in [docs/09-bypass-options.md](docs/09-bypass-options.md).

## Responsible Disclosure

These findings were obtained through **static analysis of a publicly available APK**. No active exploitation of production systems was performed. Security vulnerabilities have been documented to enable the vendor to improve their product.

We believe in responsible disclosure and recommend Ypsomed/CamDiab:
- Implement certificate pinning on gRPC and REST channels
- Enforce key expiration server-side
- Rotate hardcoded credentials

## Legal Basis

This research is conducted under:
- **EU Software Directive 2009/24/EC** Art. 6 (decompilation for interoperability)
- **EU EHDS Regulation 2025/327** (right to health data access and portability)
- **GDPR** Art. 15/20 (right of access and data portability)
- **US DMCA §1201(f)** (reverse engineering for interoperability)
- **EU Cyber Resilience Act 2024/2847** Art. 13(6) (security research)

See [docs/10-legal-analysis.md](docs/10-legal-analysis.md) for full analysis.

## Disclaimer

**THIS SOFTWARE IS PROVIDED FOR RESEARCH AND EDUCATIONAL PURPOSES ONLY.**

- This is NOT a medical device and is NOT approved for therapeutic use
- Do NOT use this to control insulin delivery without understanding the risks
- Incorrect insulin dosing can cause severe hypoglycemia or death
- Always have a fallback to the official CamAPS FX app
- The authors are not responsible for any harm resulting from use of this information

**If you choose to use this with a real insulin pump, you do so entirely at your own risk.**

## Credits & References

- [AndroidAPS](https://github.com/nightscout/AndroidAPS) — Open-source AID system
- [OpenAPS](https://openaps.org/) — #WeAreNotWaiting movement
- [Nightscout](https://nightscout.github.io/) — Open-source CGM in the cloud
- [Lazysodium](https://github.com/nicholasgasior/lazysodium-android) — libsodium for Android
- [libsodium](https://libsodium.org/) — Cryptographic library
- [Frida](https://frida.re/) — Dynamic instrumentation toolkit
- [nRF Sniffer](https://www.nordicsemi.com/Products/Development-tools/nrf-sniffer-for-bluetooth-le) — BLE packet capture

## Contributing

Contributions are welcome! Areas where help is needed:
- Verifying BLE command payload structures with a real YpsoPump
- Completing the AAPS driver integration
- Testing the Frida pump faking approach
- Documenting per-command request/response formats
- Adding support for extended bolus and basal profile commands

## License

[MIT License](LICENSE)
