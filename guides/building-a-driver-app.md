# Building a YpsoPump Driver App

Complete walkthrough for creating an Android app that communicates directly with the YpsoPump insulin pump.

## Prerequisites

- Android Studio (latest)
- Android phone with BLE support (min SDK 31 / Android 12)
- Rooted phone (for initial key extraction only)
- CamAPS FX installed (for key exchange, see below)
- Basic Kotlin/Android knowledge

## Step 1: Project Setup

### build.gradle.kts (app)

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.ypsopump.driver"
    compileSdk = 35
    defaultConfig {
        minSdk = 31
        targetSdk = 35
    }
}

dependencies {
    // Lazysodium for XChaCha20-Poly1305 + Curve25519
    implementation("com.goterl:lazysodium-android:5.1.0")
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // Android BLE
    implementation("androidx.core:core-ktx:1.13.1")

    // Coroutines for async BLE
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
```

### AndroidManifest.xml

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />

    <application ...>
        <!-- Your activities -->
    </application>
</manifest>
```

## Step 2: BLE Scanning

### Scan for YpsoPump

```kotlin
import android.bluetooth.le.ScanFilter
import android.os.ParcelUuid

val YPSO_SCAN_UUID = ParcelUuid.fromString("669a0c20-0008-969e-e211-ffffffffffff")

fun startScan() {
    val filter = ScanFilter.Builder()
        .setServiceUuid(YPSO_SCAN_UUID)
        .build()

    val settings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    bluetoothLeScanner.startScan(listOf(filter), settings, scanCallback)
}

val scanCallback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
        val name = result.device.name ?: return
        if (name.startsWith("mylife YpsoPump")) {
            // Found a YpsoPump!
            stopScan()
            connectToPump(result.device)
        }
    }
}
```

## Step 3: GATT Connection

```kotlin
fun connectToPump(device: BluetoothDevice) {
    device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
}

val gattCallback = object : BluetoothGattCallback() {
    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            gatt.requestMtu(512)  // Request large MTU
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
        gatt.discoverServices()
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            // Find YpsoPump services
            val service = gatt.getService(UUID.fromString("669a0c20-0008-969e-e211-eeeeeeeeeeee"))
            if (service != null) {
                // Enable notifications, start communication
                enableNotifications(gatt, service)
            }
        }
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        // Received encrypted data from pump
        val decrypted = decrypt(value)
        handleResponse(characteristic.uuid, decrypted)
    }
}
```

## Step 4: Key Exchange

### Option A: CamAPS Proxy (Recommended)

The easiest approach is to let CamAPS handle the key exchange, then extract the shared key. See [frida-key-extraction.md](frida-key-extraction.md) for detailed Frida scripts.

After extraction, you have:
- `sharedKey` (32 bytes)
- `pumpPublicKey` (32 bytes)
- Initial counter values (write=0, read=0)

### Option B: Direct Key Exchange (Requires Backend Access)

If you want to implement key exchange yourself (e.g., via gRPC MITM):

```kotlin
// Step 1: Read challenge + pump public key (64 bytes)
val challengeAndKey = readKeyExchangeCharacteristic()
val challenge = challengeAndKey.sliceArray(0 until 32)
val pumpPublicKey = challengeAndKey.sliceArray(32 until 64)

// Step 2: Generate app keypair
val appKeyPair = lazySodium.cryptoBoxKeypair()

// Step 3-5: Get 116-byte payload from backend (or via MITM)
val encryptedPayload = get116BytePayload(
    challenge, pumpPublicKey, appKeyPair.publicKey
)

// Step 6: Write 116 bytes to pump
writeKeyExchangeCharacteristic(encryptedPayload)

// Step 7: Reset counters
writeCounter = 0L
readCounter = 0L

// Step 8: Compute shared key
val rawShared = ByteArray(32)
lazySodium.cryptoScalarmult(rawShared, appKeyPair.secretKey.asBytes, pumpPublicKey)
val sharedKey = ByteArray(32)
lazySodium.cryptoCoreHChaCha20(sharedKey, ByteArray(16), rawShared, null)
```

## Step 5: Encryption / Decryption

```kotlin
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import java.nio.ByteBuffer

class YpsoCrypto(private val sharedKey: ByteArray) {
    private val lazySodium = LazySodiumAndroid(SodiumAndroid())
    private var writeCounter: Long = 0
    private var readCounter: Long = 0
    private var rebootCounter: Int = 0

    /**
     * Encrypt command data for sending to pump.
     * Output format: ciphertext || nonce (nonce APPENDED)
     */
    fun encrypt(commandData: ByteArray): ByteArray {
        // Build plaintext: command + rebootCounter(4B) + writeCounter(8B)
        val counterData = ByteBuffer.allocate(12)
            .putInt(rebootCounter)
            .putLong(writeCounter)
            .array()
        val plaintext = commandData + counterData

        // Generate random 24-byte nonce
        val nonce = lazySodium.randomBytesBuf(24)

        // Encrypt with XChaCha20-Poly1305
        val ciphertext = ByteArray(plaintext.size + 16) // +16 for Poly1305 tag
        lazySodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
            ciphertext, null,
            plaintext, plaintext.size.toLong(),
            null, 0, null,
            nonce, sharedKey
        )

        writeCounter++

        // Return ciphertext || nonce (nonce at END)
        return ciphertext + nonce
    }

    /**
     * Decrypt data received from pump.
     * Input format: ciphertext || nonce (nonce at END)
     */
    fun decrypt(blePayload: ByteArray): ByteArray {
        // Extract nonce (last 24 bytes)
        val nonce = blePayload.sliceArray(blePayload.size - 24 until blePayload.size)
        val ciphertext = blePayload.sliceArray(0 until blePayload.size - 24)

        // Decrypt
        val plaintext = ByteArray(ciphertext.size - 16) // -16 for tag
        lazySodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
            plaintext, null, null,
            ciphertext, ciphertext.size.toLong(),
            null, 0, nonce, sharedKey
        )

        // Parse counters from end of plaintext
        val commandData = plaintext.sliceArray(0 until plaintext.size - 12)
        val buf = ByteBuffer.wrap(plaintext, plaintext.size - 12, 12)
        val pumpRebootCounter = buf.getInt()
        val pumpReadCounter = buf.getLong()

        // Handle reboot
        if (pumpRebootCounter > rebootCounter) {
            rebootCounter = pumpRebootCounter
            writeCounter = 0
        }

        // Validate read counter (must be strictly increasing)
        if (pumpReadCounter <= readCounter && readCounter != 0L) {
            throw SecurityException("Read counter not increasing: $pumpReadCounter <= $readCounter")
        }
        readCounter = pumpReadCounter

        return commandData
    }
}
```

## Step 6: Sending Commands

### Command Pattern

```kotlin
fun sendCommand(gatt: BluetoothGatt, characteristicUuid: UUID, commandData: ByteArray) {
    val service = gatt.getService(UUID.fromString("669a0c20-0008-969e-e211-eeeeeeeeeeee"))
    val characteristic = service?.getCharacteristic(characteristicUuid) ?: return

    // Enable notifications first
    gatt.setCharacteristicNotification(characteristic, true)
    val descriptor = characteristic.getDescriptor(
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") // CCCD
    )
    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
    gatt.writeDescriptor(descriptor)

    // Encrypt and send
    val encrypted = crypto.encrypt(commandData)
    characteristic.value = encrypted
    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    gatt.writeCharacteristic(characteristic)

    // Response arrives via onCharacteristicChanged callback
}
```

### Read Pump Status (Command Index 30)

```kotlin
fun readPumpStatus(gatt: BluetoothGatt) {
    // GET_SYSTEM_STATUS is command index 30
    val statusRequest = byteArrayOf(0x00) // Simple read request
    sendCommand(gatt, SYSTEM_STATUS_UUID, statusRequest)
}
```

### Deliver Bolus (Command Index 27)

```kotlin
fun deliverBolus(gatt: BluetoothGatt, unitsX10: Int) {
    // START_STOP_BOLUS is command index 27
    // Units are encoded as integer × 10 (e.g., 1.5U = 15)
    val bolusData = ByteBuffer.allocate(4).putInt(unitsX10).array()
    sendCommand(gatt, START_STOP_BOLUS_UUID, bolusData)
}
```

### Set TBR (Command Index 29)

```kotlin
fun setTBR(gatt: BluetoothGatt, percentage: Int, durationMinutes: Int) {
    // START_STOP_TBR is command index 29
    val tbrData = ByteBuffer.allocate(8)
        .putInt(percentage)
        .putInt(durationMinutes)
        .array()
    sendCommand(gatt, START_STOP_TBR_UUID, tbrData)
}
```

## Step 7: Counter Management

Counters must be persisted across app restarts:

```kotlin
class CounterStore(context: Context) {
    private val prefs = context.getSharedPreferences("ypso_counters", Context.MODE_PRIVATE)

    var writeCounter: Long
        get() = prefs.getLong("writeCounter", 0)
        set(value) = prefs.edit().putLong("writeCounter", value).apply()

    var readCounter: Long
        get() = prefs.getLong("readCounter", 0)
        set(value) = prefs.edit().putLong("readCounter", value).apply()

    var rebootCounter: Int
        get() = prefs.getInt("rebootCounter", 0)
        set(value) = prefs.edit().putInt("rebootCounter", value).apply()
}
```

## Step 8: Reading History (COUNT/INDEX/VALUE Pattern)

```kotlin
suspend fun readAlarmHistory(gatt: BluetoothGatt): List<ByteArray> {
    // Step 1: Read ALARM_ENTRY_COUNT (index 11)
    val countResponse = sendCommandAndWait(gatt, ALARM_ENTRY_COUNT_UUID, byteArrayOf())
    val count = ByteBuffer.wrap(countResponse).getInt()

    val entries = mutableListOf<ByteArray>()
    for (i in 0 until count) {
        // Step 2: Write ALARM_ENTRY_INDEX (index 12) to select entry
        val indexData = ByteBuffer.allocate(4).putInt(i).array()
        sendCommandAndWait(gatt, ALARM_ENTRY_INDEX_UUID, indexData)

        // Step 3: Read ALARM_ENTRY_VALUE (index 13)
        val value = sendCommandAndWait(gatt, ALARM_ENTRY_VALUE_UUID, byteArrayOf())
        entries.add(value)
    }
    return entries
}
```

## Step 9: Polling & Status Loop

```kotlin
class PumpStatusPoller(
    private val gatt: BluetoothGatt,
    private val crypto: YpsoCrypto
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun startPolling(intervalMs: Long = 60_000) {
        scope.launch {
            while (isActive) {
                try {
                    readPumpStatus(gatt)
                    // Parse response: battery, reservoir, state
                } catch (e: Exception) {
                    // Handle disconnection, retry
                }
                delay(intervalMs)
            }
        }
    }
}
```

## Step 10: Integration with CGM

For a closed-loop system, you need CGM data. Options:

1. **xDrip+**: Broadcast receiver for CGM values
2. **AAPS**: Full integration via PumpSync
3. **Direct Dexcom/Libre**: Separate BLE connection

```kotlin
// Receive xDrip+ broadcasts
class CgmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.eveningoutpost.dexdrip.BgEstimate") {
            val glucose = intent.getDoubleExtra("com.eveningoutpost.dexdrip.Extras.BgEstimate", 0.0)
            val timestamp = intent.getLongExtra("com.eveningoutpost.dexdrip.Extras.Time", 0)
            // Use glucose value for algorithm decisions
        }
    }
}
```

## Error Handling

Always check for pump error codes in responses:

```kotlin
fun handleError(statusCode: Int) {
    when (statusCode) {
        0 -> { /* GATT_SUCCESS */ }
        134 -> { /* BLE_READ_ERROR_INVALID_SHARED_KEY - need new key exchange */ }
        136 -> { /* KEY_EXCHANGE_ERROR_BLOCKED_OR_BUSY - wait and retry */ }
        138 -> { /* BLE_WRITE_ERROR_ENCRYPTION_FAILED - check key/counters */ }
        139 -> { /* COUNTER_ERROR - counter desync, may need reset */ }
        140 -> { /* NO_SHARED_KEY - key exchange required */ }
        176 -> { /* TBR_COMMAND_TEMPORARILY_UNAVAILABLE - retry later */ }
        else -> { /* See docs/08-security-findings.md for all 64 codes */ }
    }
}
```

## Security Considerations

1. **Store shared key securely**: Use Android Keystore or EncryptedSharedPreferences
2. **Never log keys**: Shared key should never appear in logcat
3. **Handle key expiration**: Re-extract key via Frida if 28-day expiry is triggered
4. **Validate counters strictly**: Counter errors indicate potential replay attack or desync
5. **Rate limit commands**: Don't flood the pump with rapid commands
