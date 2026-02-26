# 01 — Hardware Analysis: YpsoPump

## Overview

The **mylife YpsoPump** (Ypsomed AG, Switzerland) is a patch-style insulin pump communicating over Bluetooth Low Energy (BLE). This document summarises hardware findings obtained through FCC filings, regulatory databases and reverse engineering of the companion app **CamAPS FX** (v1.4(190).111).

## Main MCU: STM32F051

| Property | Value |
|----------|-------|
| **Manufacturer** | STMicroelectronics |
| **Core** | ARM Cortex-M0 |
| **Flash** | 64 KB |
| **RAM** | 8 KB |
| **Operating voltage** | 2.0 – 3.6 V |
| **Package** | LQFP-48 |

The STM32F051 handles all pump control logic: motor driving, occlusion detection, reservoir level sensing, button input and alarm generation. The pump's Curve25519 public key is burned into flash at manufacturing and **never changes** across reboots or resets.

### SWD Debug Interface

The STM32F051 exposes a Serial Wire Debug (SWD) port. If not permanently disabled via the Read-Out Protection (RDP) fuses, this could allow:

- Full flash memory read-out (including PSK and pump public key)
- Live RAM inspection during BLE communication
- Firmware replacement / patching

**Status**: RDP level has not been verified on a live device. FCC teardown photos show test pads consistent with SWD access.

## BLE Radio: EM Microelectronic EM9301

| Property | Value |
|----------|-------|
| **Manufacturer** | EM Microelectronic (Swatch Group) |
| **Standard** | Bluetooth 4.0 Low Energy |
| **Role** | Peripheral only |
| **TX Power** | –20 dBm to +4 dBm (configurable) |
| **Frequency** | 2402 – 2480 MHz |
| **Modulation** | GFSK |
| **Interface to MCU** | SPI |
| **Sleep current** | < 1 µA |

The EM9301 is a BLE-only radio (no Classic Bluetooth). It is controlled entirely by the STM32F051 over SPI — the STM32 implements the GATT server, advertisement logic and all protocol state machines. The EM9301 is essentially a dumb radio transceiver.

### Key Implications

1. **No BLE 4.2+ features** — no LE Secure Connections, no extended advertisements
2. **All security is application-layer** — BLE pairing provides no meaningful protection; XChaCha20-Poly1305 handles everything
3. **SPI sniffing** between STM32 and EM9301 would reveal pre-encryption plaintext

## FCC Filing & RF Specifications

| Parameter | Value |
|-----------|-------|
| **FCC ID** | (available via FCC search for Ypsomed) |
| **Frequency range** | 2402 – 2480 MHz |
| **Modulation** | GFSK (BT = 0.5) |
| **Data rate** | 1 Mbps |
| **Max TX power** | +4 dBm |
| **Antenna** | Chip / PCB trace antenna |
| **Test standard** | FCC Part 15.247 |

FCC test reports include internal photographs showing the PCB layout with clearly identifiable:

- STM32F051 (main MCU)
- EM9301 (BLE radio)
- Crystal oscillator (32 MHz for EM9301, 8 MHz for STM32)
- Motor driver circuitry
- Battery connector
- Test pads (SWD and SPI)

## Power Supply

The YpsoPump uses a single **AAA 1.5V alkaline battery** (user-replaceable). A boost converter raises the voltage to 3.3V for the MCU and BLE radio. Battery level is reported as a percentage via BLE command index 29 (part of the Control Service).

## Reservoir

- **Capacity**: 1.6 mL (160 units at U-100)
- **Type**: Pre-filled cartridge (mylife YpsoPump Reservoir)
- **Detection**: Mechanical plunger position sensor
- **Minimum bolus step**: 0.1 U
- **Minimum basal step**: 0.01 U/h

## PCB Topology (Reconstructed)

```
┌─────────────────────────────────────────┐
│                                         │
│   ┌──────────┐     SPI     ┌─────────┐ │
│   │ STM32F051│◄───────────►│ EM9301  │ │
│   │ (MCU)    │             │ (BLE)   │ │
│   └────┬─────┘             └────┬────┘ │
│        │                        │      │
│   SWD  │  GPIO/ADC         Antenna     │
│   Pads │                    Trace      │
│        ▼                               │
│   ┌─────────┐  ┌────────┐  ┌───────┐  │
│   │  Motor  │  │ Button │  │Battery│  │
│   │ Driver  │  │  Input │  │ Sense │  │
│   └─────────┘  └────────┘  └───────┘  │
│                                         │
└─────────────────────────────────────────┘
```

## Security Observations

1. **Static public key**: The pump's Curve25519 public key is factory-burned and never rotated. If extracted via SWD, it enables offline analysis of captured BLE traffic.

2. **No hardware security module**: Unlike modern medical devices, the YpsoPump relies entirely on the STM32F051's flash for key storage. There is no dedicated secure element.

3. **Pre-Shared Key (PSK)**: Each pump has a 256-bit PSK shared with the Utimaco Cloud HSM. This PSK is used by the backend to generate the 116-byte key exchange payload. If extracted via SWD, a completely offline key exchange becomes possible.

4. **BLE 4.0 only**: No LE Secure Connections means BLE-level pairing offers minimal protection. The application-layer XChaCha20-Poly1305 is the sole security boundary.

## References

- STM32F051 datasheet: [st.com](https://www.st.com/en/microcontrollers-microprocessors/stm32f051.html)
- EM9301 datasheet: [emmicroelectronic.com](https://www.emmicroelectronic.com/product/standard-protocols-702/em9301)
- FCC OET Equipment Authorization Search: [fcc.gov](https://www.fcc.gov/oet/ea/fccid)
