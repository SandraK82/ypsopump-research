package com.ypsopump.test.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import com.ypsopump.test.security.LogRedactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ScannedPump(
    val name: String,
    val address: String,
    val rssi: Int
)

/**
 * StateFlow-based BLE scanner for YpsoPump devices.
 * Scans for SERVICE_SECURITY UUID or name prefix "YpsoPump_".
 */
class BleScanner(private val context: Context) {

    private val TAG = "BleScanner"

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var scanner: BluetoothLeScanner? = null

    private val _scanResults = MutableStateFlow<List<ScannedPump>>(emptyList())
    val scanResults: StateFlow<List<ScannedPump>> = _scanResults.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _connectedDevice = MutableStateFlow<ScannedPump?>(null)
    val connectedDevice: StateFlow<ScannedPump?> = _connectedDevice.asStateFlow()

    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState: StateFlow<String> = _connectionState.asStateFlow()

    private val foundDevices = mutableMapOf<String, ScannedPump>()

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: return
            if (!name.startsWith("YpsoPump")) return

            val pump = ScannedPump(
                name = name,
                address = device.address,
                rssi = result.rssi
            )
            foundDevices[device.address] = pump
            _scanResults.value = foundDevices.values.sortedByDescending { it.rssi }
            LogRedactor.d(TAG, "Found: $name (${device.address}) RSSI=${result.rssi}")
        }

        override fun onScanFailed(errorCode: Int) {
            LogRedactor.e(TAG, "Scan failed: $errorCode")
            _isScanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (_isScanning.value) return
        scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        foundDevices.clear()
        _scanResults.value = emptyList()

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(YpsoPumpUuids.SERVICE_SECURITY))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(filters, settings, scanCallback)
        _isScanning.value = true
        LogRedactor.i(TAG, "BLE scan started")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanner?.stopScan(scanCallback)
        _isScanning.value = false
        LogRedactor.i(TAG, "BLE scan stopped")
    }

    fun connect(pump: ScannedPump) {
        _connectionState.value = "Connecting"
        _connectedDevice.value = pump
        // Actual connection handled by YpsoBleManager (Phase 2)
        LogRedactor.i(TAG, "Connecting to ${pump.name}")
    }

    fun disconnect() {
        _connectionState.value = "Disconnected"
        _connectedDevice.value = null
        LogRedactor.i(TAG, "Disconnected")
    }

    fun updateConnectionState(state: String) {
        _connectionState.value = state
    }
}
