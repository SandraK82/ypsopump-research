# BLE Sniffing Setup Guide

## Overview

This guide explains how to capture and decrypt YpsoPump BLE traffic using an nRF Sniffer and Wireshark. Since the pump uses application-layer encryption (XChaCha20-Poly1305), raw BLE sniffing shows encrypted payloads — but combined with extracted keys, full decryption is possible.

## Hardware Requirements

| Component | Purpose | Approximate Cost |
|-----------|---------|-----------------|
| **nRF52840 Dongle** | BLE sniffer hardware | ~10 EUR |
| **nRF Sniffer firmware** | Packet capture firmware | Free |
| **Computer** | Running Wireshark | — |
| **Android phone** | Running CamAPS or custom driver | — |

Alternative sniffers:
- Ubertooth One (more capable, more expensive)
- TI CC2540 USB Dongle (older, BLE 4.0 only)
- Ellisys Bluetooth Tracker (professional, expensive)

## Software Setup

### 1. Flash nRF Sniffer Firmware

```bash
# Download nRF Sniffer from Nordic
# https://www.nordicsemi.com/Products/Development-tools/nrf-sniffer-for-bluetooth-le

# Flash using nrfutil
nrfutil dfu usb-serial -p /dev/ttyACM0 -pkg sniffer_nrf52840dongle_nrf52840_x.x.x.zip
```

### 2. Install Wireshark Plugin

```bash
# Copy the nRF Sniffer Wireshark plugin
cp nrf_sniffer_for_bluetooth_le/extcap/* ~/.config/wireshark/extcap/
chmod +x ~/.config/wireshark/extcap/nrf_sniffer_for_bluetooth_le.py

# Install Python dependencies
pip install pyserial
```

### 3. Configure Wireshark

1. Open Wireshark
2. The nRF Sniffer should appear as a capture interface
3. Double-click to configure:
   - **Device**: Select your nRF52840 dongle
   - **Follow**: Set to the pump's BLE address (if known)
   - **RSSI filter**: Optional, helps in noisy environments

## Capture Process

### Step 1: Identify the Pump

```
1. Start Wireshark capture on nRF Sniffer interface
2. Power on the YpsoPump
3. Look for advertisements from "mylife YpsoPump XXXXXX"
4. Note the BLE MAC address
5. Set the sniffer to follow this address
```

### Step 2: Capture Connection

```
1. Restart capture, following the pump's address
2. Open CamAPS (or custom driver) on the phone
3. Let the app connect to the pump
4. Capture the full connection sequence:
   - Connection request
   - MTU negotiation
   - Service discovery
   - Characteristic reads/writes (encrypted)
```

### Step 3: Identify Key Exchange

Look for these patterns in the capture:

| Packet | Size | Direction | Content |
|--------|------|-----------|---------|
| Read response | 64 bytes | Pump → App | Challenge (32B) + Public Key (32B) |
| Write request | 116 bytes | App → Pump | Key exchange payload |
| Subsequent writes | Variable | App → Pump | Encrypted commands |
| Notifications | Variable | Pump → App | Encrypted responses |

## Wireshark Display Filters

```
# Show only BLE ATT protocol
btatt

# Show only writes to pump
btatt.opcode == 0x12 || btatt.opcode == 0x52

# Show only notifications from pump
btatt.opcode == 0x1b

# Show packets > 50 bytes (likely encrypted data)
btatt && frame.len > 50

# Show specific UUID
btatt.handle == 0x0012

# Filter by connection handle
btle.connection_handle == 0x0040
```

## Decryption (Post-Capture)

### Prerequisites

You need the extracted shared key (see [frida-key-extraction.md](frida-key-extraction.md)):
- `sharedKey` (32 bytes)
- Initial `writeCounter` and `readCounter` values

### Python Decryption Script

```python
#!/usr/bin/env python3
"""Decrypt YpsoPump BLE traffic from Wireshark PCAP export."""

import sys
import struct
from nacl.aead import XCHACHA20POLY1305_IETF

def decrypt_payload(encrypted_payload: bytes, shared_key: bytes) -> bytes:
    """
    Decrypt a YpsoPump BLE payload.
    Format: ciphertext || nonce (nonce is last 24 bytes)
    """
    if len(encrypted_payload) < 24 + 16:  # min: nonce + tag
        raise ValueError(f"Payload too short: {len(encrypted_payload)} bytes")

    nonce = encrypted_payload[-24:]           # Last 24 bytes
    ciphertext = encrypted_payload[:-24]      # Everything before nonce

    box = XCHACHA20POLY1305_IETF(shared_key)
    plaintext = box.decrypt(ciphertext, nonce)

    # Parse counters from end of plaintext
    if len(plaintext) >= 12:
        command_data = plaintext[:-12]
        reboot_counter = struct.unpack('>I', plaintext[-12:-8])[0]
        rw_counter = struct.unpack('>Q', plaintext[-8:])[0]
        return command_data, reboot_counter, rw_counter

    return plaintext, None, None


def main():
    if len(sys.argv) < 3:
        print("Usage: decrypt_ypso.py <shared_key_hex> <payload_hex>")
        print("   or: decrypt_ypso.py <shared_key_hex> -f <pcap_export.txt>")
        sys.exit(1)

    shared_key = bytes.fromhex(sys.argv[1])
    assert len(shared_key) == 32, "Shared key must be 32 bytes"

    if sys.argv[2] == '-f':
        # Read hex payloads from file (one per line)
        with open(sys.argv[3]) as f:
            for i, line in enumerate(f):
                line = line.strip()
                if not line:
                    continue
                try:
                    payload = bytes.fromhex(line)
                    data, reboot, counter = decrypt_payload(payload, shared_key)
                    print(f"[{i}] Decrypted ({len(data)} bytes): {data.hex()}")
                    print(f"     Reboot: {reboot}, Counter: {counter}")
                except Exception as e:
                    print(f"[{i}] Error: {e}")
    else:
        payload = bytes.fromhex(sys.argv[2])
        data, reboot, counter = decrypt_payload(payload, shared_key)
        print(f"Decrypted ({len(data)} bytes): {data.hex()}")
        print(f"Reboot counter: {reboot}")
        print(f"Read/Write counter: {counter}")


if __name__ == '__main__':
    main()
```

### Installation

```bash
pip install pynacl
python decrypt_ypso.py <shared_key_hex> <payload_hex>
```

### Example

```bash
# Shared key (example, 32 bytes hex)
KEY="a1b2c3d4e5f6...32_bytes_hex..."

# Encrypted payload from Wireshark (hex)
PAYLOAD="deadbeef...ciphertext_hex...nonce_24_bytes_hex"

python decrypt_ypso.py $KEY $PAYLOAD
# Output: Decrypted (16 bytes): 001e000a...
#         Reboot counter: 5
#         Read/Write counter: 42
```

## Wireshark Lua Dissector (Advanced)

For inline decryption in Wireshark, create a custom Lua dissector:

```lua
-- ypso_dissector.lua
-- Place in ~/.config/wireshark/plugins/

local ypso_proto = Proto("ypso", "YpsoPump BLE Protocol")

-- Fields
local f_nonce = ProtoField.bytes("ypso.nonce", "Nonce (24 bytes)")
local f_ciphertext = ProtoField.bytes("ypso.ciphertext", "Ciphertext")
local f_tag = ProtoField.bytes("ypso.tag", "Poly1305 Tag (16 bytes)")

ypso_proto.fields = { f_nonce, f_ciphertext, f_tag }

function ypso_proto.dissector(buffer, pinfo, tree)
    local length = buffer:len()
    if length < 40 then return end  -- min: 16 tag + 24 nonce

    pinfo.cols.protocol = "YpsoPump"

    local subtree = tree:add(ypso_proto, buffer(), "YpsoPump Encrypted Payload")

    -- Nonce is last 24 bytes
    local nonce_offset = length - 24
    subtree:add(f_nonce, buffer(nonce_offset, 24))

    -- Tag is last 16 bytes before nonce
    if nonce_offset >= 16 then
        subtree:add(f_tag, buffer(nonce_offset - 16, 16))
        subtree:add(f_ciphertext, buffer(0, nonce_offset - 16))
    end
end

-- Register for BLE ATT
local btatt_table = DissectorTable.get("btatt.handle")
-- Add your characteristic handles here after discovery
-- btatt_table:add(0x0012, ypso_proto)
```

## Android HCI Snoop Log (Alternative)

If you don't have an nRF Sniffer, use Android's built-in BLE logging:

```bash
# Enable HCI snoop log
adb shell settings put secure bluetooth_hci_log 1

# Restart Bluetooth
adb shell svc bluetooth disable && adb shell svc bluetooth enable

# Let CamAPS communicate with pump...

# Pull the log
adb pull /data/misc/bluetooth/logs/btsnoop_hci.log

# Open in Wireshark
wireshark btsnoop_hci.log
```

Note: HCI snoop captures from the phone's perspective (after BLE pairing encryption is removed), so you see the application-layer payload directly.

## Tips

1. **Start sniffing before connection** — the nRF Sniffer needs to see the initial connection to follow the frequency hopping
2. **Stay close** — BLE range is ~10m; the sniffer must be within range of both phone and pump
3. **Reduce interference** — disable other BLE devices nearby
4. **Capture multiple sessions** — counter values increase, which helps identify the counter fields
5. **Export as hex** — Wireshark's "Follow BLE stream" or copy hex dumps for the decryption script
6. **Compare encrypted/decrypted** — knowing the command helps identify the payload structure
