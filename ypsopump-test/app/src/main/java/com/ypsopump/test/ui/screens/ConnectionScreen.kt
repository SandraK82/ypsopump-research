package com.ypsopump.test.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ypsopump.test.ble.BleScanner
import com.ypsopump.test.ble.ScannedPump
import com.ypsopump.test.ui.theme.PumpColors

@Composable
fun ConnectionScreen() {
    val context = LocalContext.current
    val scanner = remember { BleScanner(context) }
    val scanResults by scanner.scanResults.collectAsState()
    val isScanning by scanner.isScanning.collectAsState()
    val connectedDevice by scanner.connectedDevice.collectAsState()
    val connectionState by scanner.connectionState.collectAsState()

    var permissionsGranted by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionsGranted = permissions.values.all { it }
    }

    LaunchedEffect(Unit) {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Connection Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when (connectionState) {
                    "Connected" -> MaterialTheme.colorScheme.primaryContainer
                    "Authenticated" -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    when (connectionState) {
                        "Connected", "Authenticated" -> Icons.Default.BluetoothConnected
                        "Connecting" -> Icons.Default.BluetoothSearching
                        else -> Icons.Default.BluetoothDisabled
                    },
                    contentDescription = null,
                    tint = when (connectionState) {
                        "Authenticated" -> PumpColors.authenticated
                        "Connected" -> PumpColors.connected
                        else -> PumpColors.disconnected
                    },
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(connectionState, style = MaterialTheme.typography.titleMedium)
                    connectedDevice?.let {
                        Text(
                            "${it.name} (${it.address})",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        // Scan Button
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (isScanning) scanner.stopScan() else scanner.startScan()
                },
                enabled = permissionsGranted
            ) {
                Icon(
                    if (isScanning) Icons.Default.Stop else Icons.Default.Search,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (isScanning) "Stop Scan" else "Scan for Pumps")
            }

            if (connectedDevice != null) {
                OutlinedButton(onClick = { scanner.disconnect() }) {
                    Icon(Icons.Default.LinkOff, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Disconnect")
                }
            }
        }

        if (isScanning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // Scan Results
        Text(
            "Found Devices (${scanResults.size})",
            style = MaterialTheme.typography.titleSmall
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(scanResults) { pump ->
                PumpDeviceCard(
                    pump = pump,
                    isConnected = pump.address == connectedDevice?.address,
                    onConnect = { scanner.connect(pump) }
                )
            }
        }
    }
}

@Composable
private fun PumpDeviceCard(
    pump: ScannedPump,
    isConnected: Boolean,
    onConnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(pump.name, style = MaterialTheme.typography.bodyLarge)
                Text(pump.address, style = MaterialTheme.typography.bodySmall)
                Text("RSSI: ${pump.rssi} dBm", style = MaterialTheme.typography.bodySmall)
            }
            if (!isConnected) {
                FilledTonalButton(onClick = onConnect) {
                    Text("Connect")
                }
            } else {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Connected",
                    tint = PumpColors.connected
                )
            }
        }
    }
}
