# 02 — BLE Protocol: YpsoPump Command Set

## Overview

The YpsoPump exposes a BLE GATT server with custom services and characteristics. Communication is encrypted at the application layer using XChaCha20-Poly1305 (see [03-encryption.md](03-encryption.md)). This document describes all 33 commands, the GATT structure and the communication patterns.

## GATT Service Structure

The pump advertises with the device name pattern `mylife YpsoPump XXXXXX` where `XXXXXX` is derived from the serial number.

### BLE UUIDs

| UUID | Role |
|------|------|
| `669a0c20-0008-969e-e211-ffffffffffff` | **Scan Filter** (Advertised Service UUID) |
| `669a0c20-0008-969e-e211-eeeeeeeeeeee` | **General Purpose Service** |
| `669a0c20-0008-969e-e211-fcff0000000a` | **Data Characteristic A** (Read) |
| `669a0c20-0008-969e-e211-fcff0000000b` | **Data Characteristic B** (Read) |
| `669a0c20-0008-969e-e211-fcff000000ff` | **Control Characteristic** |
| `669a0c20-0008-969e-e211-fcbeb0147bc5` | **Pump-specific UUID** |
| `fb349b5f-8000-0080-0010-0000adde0000` | **Secondary Scan Filter** |
| `fb349b5f-8000-0080-0010-0000feda0002` | **Secondary Data** |

The `669a0c20` base UUID family belongs to YpsoPump; `fb349b5f` is a secondary service.

### BLE Middleware

**ProRegia ProBluetooth SDK v2.0.13** (commercial BLE middleware by ProRegia GmbH, package: `com.proregia.probluetooth`).

### Services

| Service | Command Indices | Purpose |
|---------|----------------|---------|
| **Base Service** | 0 – 4 | Versions, authentication, country config |
| **Settings Service** | 5 – 9 | Settings, date/time management |
| **History Service** | 10 – 25 | Alarm/event/system logs, counters |
| **Control Service** | 26 – 32 | Bolus, TBR, status, notifications |

### Encrypted Payload Structure

All data written to characteristics is encrypted with XChaCha20-Poly1305:

```
BLE Output = Ciphertext || Nonce[24 bytes]     ← nonce APPENDED
BLE Input  = Ciphertext || Nonce[24 bytes]     ← nonce at END
```

Counter data (12 bytes) is appended to plaintext before encryption:
- `rebootCounter` (4 bytes, big-endian int)
- `writeCounter` (8 bytes, big-endian long)

## Complete Command Set (33 Commands)

Source: `YpsoCommandChars.java` (854 lines, `uk.ac.cam.ap.ypsomed_15x.ypsomed`)

### Base Service (Indices 0–4)

| Index | Command Name | Description |
|-------|-------------|-------------|
| 0 | `PUMP_BASE_SERVICE_VERSION` | Pump firmware / base service version |
| 1 | `MASTER_SOFTWARE_VERSION` | Master software version |
| 2 | `SUPERVISOR_SOFTWARE_VERSION` | Supervisor software version |
| 3 | `AUTHORIZATION_PASSWORD` | Authentication / pairing password |
| 4 | `PUMP_COUNTRY_CODE` | Country configuration |

### Settings Service (Indices 5–9)

| Index | Command Name | Description |
|-------|-------------|-------------|
| 5 | `SETTINGS_SERVICE_VERSION` | Settings service version |
| 6 | `SETTING_ID` | Setting identifier for read/write |
| 7 | `SETTING_VALUE` | Setting value for read/write |
| 8 | `SYSTEM_DATE` | System date (read/write) |
| 9 | `SYSTEM_TIME` | System time (read/write) |

### History Service (Indices 10–25)

| Index | Command Name | Description |
|-------|-------------|-------------|
| 10 | `HISTORY_SERVICE_VERSION` | History service version |
| 11 | `ALARM_ENTRY_COUNT` | Number of alarm log entries |
| 12 | `ALARM_ENTRY_INDEX` | Alarm entry index selector |
| 13 | `ALARM_ENTRY_VALUE` | Alarm entry value at current index |
| 14 | `EVENT_ENTRY_COUNT` | Number of event log entries |
| 15 | `EVENT_ENTRY_INDEX` | Event entry index selector |
| 16 | `EVENT_ENTRY_VALUE` | Event entry value at current index |
| 17 | `SYSTEM_ENTRY_COUNT` | Number of system log entries |
| 18 | `COMPLAINT_ENTRY_COUNT` | Number of complaint log entries |
| 19 | `SYSTEM_ENTRY_INDEX` | System entry index selector |
| 20 | `COMPLAINT_ENTRY_INDEX` | Complaint entry index selector |
| 21 | `SYSTEM_ENTRY_VALUE` | System entry value at current index |
| 22 | `COMPLAINT_ENTRY_VALUE` | Complaint entry value at current index |
| 23 | `COUNTER_ID` | Counter identifier selector |
| 24 | `COUNTER_VALUE` | Counter value at current ID |
| 25 | `CLEAR_HISTORY_PASSWORD` | Password to clear history |

**History Read Pattern**: COUNT → INDEX → VALUE iteration:
1. Read `*_ENTRY_COUNT` to get total entries
2. Write `*_ENTRY_INDEX` from 0 to count-1
3. Read `*_ENTRY_VALUE` at each index

### Control Service (Indices 26–32)

| Index | Command Name | Description |
|-------|-------------|-------------|
| 26 | `CONTROL_SERVICE_VERSION` | Control service version |
| 27 | `START_STOP_BOLUS` | Start or stop bolus delivery |
| 28 | `GET_BOLUS_STATUS` | Current bolus delivery status |
| 29 | `START_STOP_TBR` | Start or stop Temporary Basal Rate |
| 30 | `GET_SYSTEM_STATUS` | Battery, reservoir, pump state |
| 31 | `BOLUS_STATUS_NOTIFICATION` | Bolus status change notification |
| 32 | `VIRTUAL_CHAR` | Virtual characteristic (internal) |

## Communication Pattern

### Connection Sequence

```
1. BLE Scan (filter by service UUID or device name)
2. Connect GATT
3. Discover Services
4. Key Exchange (if no valid shared key)
   a. Read pump public key + challenge (64 bytes)
   b. Request server nonce via gRPC
   c. Google Play Integrity check
   d. Request 116-byte encrypted payload via gRPC
   e. Write 116 bytes to pump
   f. Compute shared key locally
5. Enable notifications on desired characteristics
6. Read/Write commands as needed
```

### Read Command Flow

```
App                                    Pump
 |                                      |
 |  writeCharacteristic(cmd_uuid,       |
 |    encrypted(command_request))        |
 |------------------------------------->|
 |                                      |
 |  onCharacteristicChanged(cmd_uuid,   |
 |    encrypted(response_data))         |
 |<-------------------------------------|
 |                                      |
 |  decrypt(response_data) → plaintext  |
```

### Write Command Flow

```
App                                    Pump
 |                                      |
 |  writeCharacteristic(cmd_uuid,       |
 |    encrypted(command_payload))        |
 |------------------------------------->|
 |                                      |
 |  onCharacteristicChanged(cmd_uuid,   |
 |    encrypted(ack/status))            |
 |<-------------------------------------|
 |                                      |
 |  decrypt(ack) → ProStatusCodeEnum    |
```

### Fragmentation

BLE MTU is negotiated after connection (typically 23 bytes default, up to 517 bytes). Payloads exceeding MTU are fragmented across multiple write operations. The pump reassembles fragments before decryption.

Error code `FRAGMENTATION_ERROR` (status 141) indicates reassembly failure.

## Counter System

Every encrypted message includes counter data for replay protection:

| Counter | Size | Purpose |
|---------|------|---------|
| `writeCounter` | 8 bytes | Incremented per write, appended to plaintext before encryption |
| `readCounter` | 8 bytes | Validated per read, must be monotonically increasing |
| `rebootCounter` | 4 bytes | Incremented on pump reboot |

### Reboot Handling

```
if (rebootCounter_pump == rebootCounter_app):
    → Normal operation
if (rebootCounter_pump > rebootCounter_app):
    → Pump rebooted; reset writeCounter to 0
    → Store new rebootCounter
    → NO new key exchange required (shared key survives reboot)
if (rebootCounter_pump < 0):
    → IllegalArgumentException
```

## Data Repository Fields

The app maintains a `DataRepositoryImp` (1614 lines in decompiled source) with these pump-related fields:

```
alarmLog:               ArrayList<byte[]>
eventLog:               ArrayList<byte[]>
systemLog:              ArrayList<byte[]>
bootTimeToFactoryTime:  long
commProtocol:           int
serialNumber:           String
model:                  int
passkey:                short
password:               int
profile_A:              float[24]   // 24 hourly basal rates
profile_B:              float[24]   // alternate profile
isProfileA_active:      boolean
factoryTimeKeyExchange: long
lastPumpDisplayTime:    long
```

## Timing

Factory time is used internally by the pump. Conversions:
- `factoryTimeToYpsoDisplayTime` — offset from factory epoch to display time
- `bootTimeToFactoryTime` — offset from boot time to factory time
- All log entries use factory time; the app converts for display

## Supported Device Types

| Device | Encryption | Backend Validation |
|--------|-----------|-------------------|
| **YpsoPump** | XChaCha20-Poly1305 + Curve25519 | gRPC 9-step key exchange |
| Dana-i / Dana RS | Password-based | None |
| Dexcom G6/G7 | EC-Crypto | None |
| FreeStyle Libre 3/3+ | Proprietary | None |

The YpsoPump has by far the most sophisticated BLE security of any insulin pump on the market.
