# Frida Key Extraction Guide

## Overview

This guide provides Frida scripts for extracting YpsoPump BLE encryption keys from the CamAPS FX app. Three extraction methods are described:

1. **Hook `write.b()`** — capture shared key during computation
2. **Hook EncryptedSharedPreferences** — read stored keys
3. **Pump Faking** — fake a virtual pump to hijack CamAPS key exchange

## Prerequisites

- **Rooted Android phone** (Magisk recommended)
- **Frida** installed on phone and computer:
  ```bash
  pip install frida-tools
  # Push frida-server to phone
  adb push frida-server-16.x.x-android-arm64 /data/local/tmp/frida-server
  adb shell "chmod 755 /data/local/tmp/frida-server"
  adb shell "su -c /data/local/tmp/frida-server &"
  ```
- **CamAPS FX** installed and logged in
- **YpsoPump** paired (for methods 1 & 2)

## Bypassing Anti-Frida Detection

CamAPS uses `libe61d.so` for anti-Frida detection. You may need to:

### Option 1: Frida Gadget (Inject at startup)

```bash
# Patch APK with Frida gadget (avoids runtime detection)
objection patchapk -s com.camdiab.fx_alert.mgdl.apk
```

### Option 2: Early Hook (Before libe61d.so loads)

```javascript
// frida-bypass-antidebug.js
// Hook before libe61d.so initializes
Interceptor.attach(Module.findExportByName(null, "android_dlopen_ext"), {
    onEnter: function(args) {
        var path = args[0].readUtf8String();
        if (path && path.includes("libe61d")) {
            console.log("[*] Blocking libe61d.so load: " + path);
            // Replace with empty lib or patch JNI_OnLoad
            args[0] = Memory.allocUtf8String("/dev/null");
        }
    }
});
```

### Option 3: MagiskHide / Zygisk

Configure Magisk to hide root from CamAPS FX specifically.

## Method 1: Hook `write.b()` — Shared Key Computation

The obfuscated class `write` contains method `b(byte[])` which computes the shared key from the pump's public key.

```javascript
// frida-capture-sharedkey.js
// Hook the shared key computation in write.b()

Java.perform(function() {
    console.log("[*] YpsoPump Key Extraction - Method 1: write.b() hook");

    var writeClass = Java.use("write");

    // write.b(byte[]) = shared key computation
    // crypto_scalarmult(appPrivKey, pumpPubKey) → crypto_core_hchacha20()
    writeClass.b.overload("[B").implementation = function(pumpPublicKey) {
        console.log("\n[!!!] SHARED KEY COMPUTATION TRIGGERED [!!!]");
        console.log("[*] Pump Public Key (" + pumpPublicKey.length + " bytes):");
        console.log("    " + bytesToHex(pumpPublicKey));

        // Call original method
        var result = this.b(pumpPublicKey);

        // The result or internal fields now contain the shared key
        console.log("[*] Method returned: " + (result ? bytesToHex(result) : "null"));

        // Try to read internal fields for the derived shared key
        try {
            var fields = writeClass.class.getDeclaredFields();
            fields.forEach(function(field) {
                field.setAccessible(true);
                var val = field.get(null); // static fields
                if (val && val.getClass && val.getClass().getName() === "[B") {
                    var arr = Java.array("byte", val);
                    if (arr.length === 32) {
                        console.log("[*] Potential key field '" + field.getName() +
                                    "': " + bytesToHex(arr));
                    }
                }
            });
        } catch(e) {
            console.log("[*] Field enumeration: " + e);
        }

        return result;
    };

    // Also hook crypto_scalarmult for raw ECDH output
    try {
        var Sodium = Java.use("com.goterl.lazysodium.Sodium");
        Sodium.crypto_scalarmult.implementation = function(q, n, p) {
            var ret = this.crypto_scalarmult(q, n, p);
            console.log("\n[*] crypto_scalarmult() called!");
            console.log("    App Private Key: " + bytesToHex(n));
            console.log("    Pump Public Key: " + bytesToHex(p));
            console.log("    Raw Shared Secret: " + bytesToHex(q));
            return ret;
        };

        Sodium.crypto_core_hchacha20.implementation = function(out, inp, k, c) {
            var ret = this.crypto_core_hchacha20(out, inp, k, c);
            console.log("\n[*] crypto_core_hchacha20() = Key Derivation");
            console.log("    Derived Shared Key: " + bytesToHex(out));
            return ret;
        };
    } catch(e) {
        console.log("[*] Sodium hooks: " + e);
    }
});

// Helper function
function bytesToHex(bytes) {
    var hex = [];
    for (var i = 0; i < bytes.length; i++) {
        hex.push(('0' + (bytes[i] & 0xFF).toString(16)).slice(-2));
    }
    return hex.join('');
}
```

### Usage

```bash
frida -U -l frida-capture-sharedkey.js com.camdiab.fx_alert.mgdl
```

Then trigger a key exchange in CamAPS (pair a pump or wait for key renewal).

## Method 2: Hook EncryptedSharedPreferences

Read stored keys from the encrypted preferences file.

```javascript
// frida-read-stored-keys.js

Java.perform(function() {
    console.log("[*] YpsoPump Key Extraction - Method 2: EncryptedSharedPreferences");

    // Hook SharedPreferences.getString
    var SharedPreferencesImpl = Java.use("android.app.SharedPreferencesImpl");
    SharedPreferencesImpl.getString.implementation = function(key, defValue) {
        var result = this.getString(key, defValue);

        // Watch for key-related preference reads
        var interestingKeys = [
            "sharedKey", "privateKey", "publicKey",
            "pumpPublicKey", "pumpPublicKeyDate", "sharedKeyDate",
            "numericWriteCounter", "numericReadCounter", "rebootCounter"
        ];

        if (interestingKeys.indexOf(key) !== -1) {
            console.log("\n[*] SharedPreferences.getString('" + key + "')");
            if (result) {
                console.log("    Value: " + result.substring(0, Math.min(result.length, 200)));
                if (key.indexOf("Key") !== -1 && key !== "sharedKeyDate" && key !== "pumpPublicKeyDate") {
                    // Try to decode as Base64
                    try {
                        var Base64 = Java.use("android.util.Base64");
                        var decoded = Base64.decode(result, 0);
                        console.log("    Decoded (" + decoded.length + " bytes): " + bytesToHex(decoded));
                    } catch(e) {}
                }
            }
        }

        return result;
    };

    // Also hook byte array reads from Tink AEAD
    try {
        var AesGcm = Java.use("com.google.crypto.tink.aead.internal.InsecureNonceAesGcmJce");
        AesGcm.decrypt.implementation = function(nonce, ciphertext, aad) {
            var plaintext = this.decrypt(nonce, ciphertext, aad);
            if (plaintext.length === 32) {
                console.log("\n[*] Tink AES-GCM decrypt → 32-byte key!");
                console.log("    Decrypted: " + bytesToHex(plaintext));
            }
            return plaintext;
        };
    } catch(e) {
        console.log("[*] Tink hook: " + e);
    }
});

function bytesToHex(bytes) {
    var hex = [];
    for (var i = 0; i < bytes.length; i++) {
        hex.push(('0' + (bytes[i] & 0xFF).toString(16)).slice(-2));
    }
    return hex.join('');
}
```

### Usage

```bash
frida -U -l frida-read-stored-keys.js com.camdiab.fx_alert.mgdl
```

Open CamAPS and let it connect to the pump. The keys will be logged as they are read from storage.

## Method 3: Pump Faking (CamAPS Key Exchange Hijack)

This is the most powerful approach: create a fake BLE peripheral that pretends to be a YpsoPump, let CamAPS perform the key exchange, and capture everything.

### Step 1: Capture Real Pump's Challenge + Public Key

First, connect to the real pump and read its static public key:

```javascript
// frida-capture-pump-identity.js

Java.perform(function() {
    // Hook BLE reads to capture the 64-byte challenge+pubkey
    var BluetoothGatt = Java.use("android.bluetooth.BluetoothGatt");
    BluetoothGatt.readCharacteristic.implementation = function(char) {
        var ret = this.readCharacteristic(char);
        return ret;
    };

    // Hook the callback where the 64 bytes arrive
    var GattCallback = Java.use("android.bluetooth.BluetoothGattCallback");
    // Note: actual class is obfuscated, look for onCharacteristicRead
});
```

### Step 2: Create Fake BLE Peripheral

Use a second Android phone or nRF52 dev board:

```kotlin
// FakePumpPeripheral.kt (separate Android app)

class FakePumpPeripheral(private val context: Context) {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val advertiser = bluetoothManager.adapter.bluetoothLeAdvertiser

    // Real pump's public key (extracted from Step 1)
    private val pumpPublicKey: ByteArray = /* 32 bytes */
    // Fresh random challenge
    private val challenge: ByteArray = SecureRandom().let {
        ByteArray(32).also { bytes -> it.nextBytes(bytes) }
    }

    fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid.fromString("669a0c20-0008-969e-e211-ffffffffffff"))
            .setIncludeDeviceName(true)
            .build()

        // Set device name to match real pump
        bluetoothManager.adapter.setName("mylife YpsoPump XXXXXX")

        advertiser.startAdvertising(settings, data, advertiseCallback)
        openGattServer()
    }

    private fun openGattServer() {
        val server = bluetoothManager.openGattServer(context, gattServerCallback)

        // Add service with key exchange characteristic
        val service = BluetoothGattService(
            UUID.fromString("669a0c20-0008-969e-e211-eeeeeeeeeeee"),
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // Key exchange characteristic (read: 64B challenge+pubkey, write: 116B payload)
        val keyExchangeChar = BluetoothGattCharacteristic(
            KEY_EXCHANGE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(keyExchangeChar)
        server.addService(service)
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == KEY_EXCHANGE_UUID) {
                // Return challenge (32B) + pump public key (32B)
                val response = challenge + pumpPublicKey
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, response)
                Log.d("FakePump", "Sent challenge + public key (64 bytes)")
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray
        ) {
            if (characteristic.uuid == KEY_EXCHANGE_UUID && value.size == 116) {
                Log.d("FakePump", "CAPTURED 116-BYTE PAYLOAD!")
                Log.d("FakePump", value.toHexString())

                // Save the 116 bytes — this is the key exchange payload
                save116BytePayload(value)

                // Now relay this to the REAL pump from the custom driver app

                if (responseNeeded) {
                    server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            }
        }
    }
}
```

### Step 3: Relay to Real Pump

After capturing the 116 bytes:
1. Stop the fake peripheral
2. Connect your custom driver to the real pump
3. Write the 116 bytes to the real pump's key exchange characteristic
4. Compute shared key locally (same as CamAPS would)

### Step 4: Hook CamAPS for Shared Key

While CamAPS is communicating with the fake pump, also hook the shared key computation:

```javascript
// Combined hook: capture shared key + 116-byte payload
Java.perform(function() {
    // Hook writeCharacteristic to catch the 116-byte write
    var BluetoothGattCharacteristic = Java.use("android.bluetooth.BluetoothGattCharacteristic");
    BluetoothGattCharacteristic.getValue.implementation = function() {
        var value = this.getValue();
        if (value && value.length === 116) {
            console.log("[!!!] 116-byte key exchange payload!");
            console.log("    " + bytesToHex(value));
            // Save to file
            var File = Java.use("java.io.File");
            var FileOutputStream = Java.use("java.io.FileOutputStream");
            var f = FileOutputStream.$new("/sdcard/ypso_116bytes.bin");
            f.write(value);
            f.close();
            console.log("[*] Saved to /sdcard/ypso_116bytes.bin");
        }
        return value;
    };

    // Hook shared key computation
    var Sodium = Java.use("com.goterl.lazysodium.Sodium");
    Sodium.crypto_core_hchacha20.implementation = function(out, inp, k, c) {
        var ret = this.crypto_core_hchacha20(out, inp, k, c);
        console.log("[!!!] SHARED KEY DERIVED: " + bytesToHex(out));
        // Save to file
        var f = Java.use("java.io.FileOutputStream").$new("/sdcard/ypso_sharedkey.bin");
        f.write(out);
        f.close();
        console.log("[*] Saved to /sdcard/ypso_sharedkey.bin");
        return ret;
    };
});
```

## Output Summary

After successful extraction you have:

| File | Content | Size |
|------|---------|------|
| `ypso_sharedkey.bin` | XChaCha20-Poly1305 shared key | 32 bytes |
| `ypso_116bytes.bin` | Key exchange payload (for relay) | 116 bytes |
| Console log | App private key, pump public key | 32 bytes each |

## Extending Key Lifetime

The 28-day expiry is app-side only. To extend:

```javascript
// Patch the calendar check in key expiry verification
Java.perform(function() {
    var Calendar = Java.use("java.util.Calendar");
    Calendar.after.implementation = function(other) {
        // Check if this is the key expiry check (adds 28 days)
        var stack = Java.use("java.lang.Thread").currentThread().getStackTrace();
        for (var i = 0; i < stack.length; i++) {
            if (stack[i].toString().indexOf("write") !== -1 ||
                stack[i].toString().indexOf("KmsAeadKeyOrBuilder") !== -1) {
                console.log("[*] Key expiry check bypassed!");
                return false; // Key never expires
            }
        }
        return this.after(other);
    };
});
```

## Safety Notes

1. **Test thoroughly** before relying on extracted keys for insulin delivery
2. **Monitor pump responses** — error code 134 (INVALID_SHARED_KEY) means keys are wrong
3. **Keep CamAPS as fallback** — always have a way back to the official app
4. **Document your keys** — save hex dumps, timestamps, and pump serial number
