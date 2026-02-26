package app.aaps.pump.ypsopump.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.ypsopump.YpsoPumpConst
import app.aaps.pump.ypsopump.crypto.SessionCrypto
import app.aaps.pump.ypsopump.data.YpsoPumpState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE communication manager for YpsoPump.
 *
 * Handles scanning, GATT connection, service discovery, notification
 * management, and encrypted read/write operations.
 *
 * Communication flow:
 *   scan → connect → discoverServices → enableNotifications →
 *   [keyExchange if needed] → sendCommand / receiveResponse
 */
@Singleton
class YpsoBleManager @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val sessionCrypto: SessionCrypto,
    private val pumpState: YpsoPumpState
) {

    private var bluetoothGatt: BluetoothGatt? = null
    private var pendingResponse: CompletableDeferred<ByteArray>? = null
    private val characteristicMap = GattAttributes.CharacteristicMap()

    val isConnected: Boolean
        get() = bluetoothGatt != null && pumpState.connectionState == ConnectionState.CONNECTED

    enum class ConnectionState {
        DISCONNECTED, SCANNING, CONNECTING, DISCOVERING, READY, CONNECTED
    }

    // -- Scanning --

    @SuppressLint("MissingPermission")
    fun startScan(onFound: (BluetoothDevice) -> Unit) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = manager.adapter.bluetoothLeScanner ?: run {
            aapsLogger.error(LTag.PUMP, "BLE Scanner not available")
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(YpsoPumpConst.SCAN_FILTER_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        pumpState.connectionState = ConnectionState.SCANNING
        aapsLogger.info(LTag.PUMP, "Starting BLE scan for YpsoPump...")

        scanner.startScan(listOf(filter), settings, object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                if (name.startsWith(YpsoPumpConst.DEVICE_NAME_PREFIX)) {
                    aapsLogger.info(LTag.PUMP, "Found YpsoPump: $name (${result.device.address})")
                    scanner.stopScan(this)
                    onFound(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                aapsLogger.error(LTag.PUMP, "BLE scan failed: $errorCode")
                pumpState.connectionState = ConnectionState.DISCONNECTED
            }
        })
    }

    // -- Connection --

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        pumpState.connectionState = ConnectionState.CONNECTING
        aapsLogger.info(LTag.PUMP, "Connecting to ${device.name}...")
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.let { gatt ->
            aapsLogger.info(LTag.PUMP, "Disconnecting from pump")
            gatt.disconnect()
            gatt.close()
        }
        bluetoothGatt = null
        pumpState.connectionState = ConnectionState.DISCONNECTED
        sessionCrypto.reset()
    }

    // -- Command Send/Receive --

    /**
     * Send an encrypted command and wait for the response.
     * @param characteristicUuid UUID of the target characteristic
     * @param commandData raw command data (will be encrypted)
     * @return decrypted response data
     */
    @SuppressLint("MissingPermission")
    suspend fun sendCommand(characteristicUuid: UUID, commandData: ByteArray): ByteArray {
        val gatt = bluetoothGatt ?: throw IllegalStateException("Not connected")
        val service = gatt.getService(YpsoPumpConst.GENERAL_SERVICE_UUID)
            ?: throw IllegalStateException("YpsoPump service not found")
        val characteristic = service.getCharacteristic(characteristicUuid)
            ?: throw IllegalStateException("Characteristic $characteristicUuid not found")

        // Encrypt
        val encrypted = sessionCrypto.encrypt(commandData)

        // Set up response deferred
        val deferred = CompletableDeferred<ByteArray>()
        pendingResponse = deferred

        // Enable notifications
        gatt.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(YpsoPumpConst.CCCD_UUID)
        cccd?.let {
            it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(it)
        }

        // Write encrypted data
        characteristic.value = encrypted
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        gatt.writeCharacteristic(characteristic)

        aapsLogger.debug(LTag.PUMP, "Sent ${encrypted.size} bytes to $characteristicUuid")

        // Wait for response with timeout
        return try {
            withTimeout(YpsoPumpConst.COMMAND_TIMEOUT_MS) {
                val response = deferred.await()
                // Decrypt response
                sessionCrypto.decrypt(response)
            }
        } catch (e: TimeoutCancellationException) {
            pendingResponse = null
            throw RuntimeException("Command timeout for $characteristicUuid", e)
        }
    }

    // -- GATT Callback --

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    aapsLogger.info(LTag.PUMP, "GATT connected, requesting MTU 512")
                    pumpState.connectionState = ConnectionState.CONNECTING
                    gatt.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    aapsLogger.info(LTag.PUMP, "GATT disconnected (status=$status)")
                    pumpState.connectionState = ConnectionState.DISCONNECTED
                    bluetoothGatt = null
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            aapsLogger.info(LTag.PUMP, "MTU changed to $mtu")
            pumpState.connectionState = ConnectionState.DISCOVERING
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                aapsLogger.info(LTag.PUMP, "Services discovered")
                val service = gatt.getService(YpsoPumpConst.GENERAL_SERVICE_UUID)
                if (service != null) {
                    aapsLogger.info(LTag.PUMP, "Found YpsoPump service with ${service.characteristics.size} characteristics")
                    pumpState.connectionState = ConnectionState.CONNECTED
                } else {
                    aapsLogger.error(LTag.PUMP, "YpsoPump service not found!")
                    disconnect()
                }
            } else {
                aapsLogger.error(LTag.PUMP, "Service discovery failed: $status")
                disconnect()
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            aapsLogger.debug(LTag.PUMP, "Notification from ${characteristic.uuid}: ${value.size} bytes")
            pendingResponse?.complete(value)
            pendingResponse = null
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            aapsLogger.debug(LTag.PUMP, "Notification from ${characteristic.uuid}: ${value.size} bytes")
            pendingResponse?.complete(value)
            pendingResponse = null
        }
    }
}
