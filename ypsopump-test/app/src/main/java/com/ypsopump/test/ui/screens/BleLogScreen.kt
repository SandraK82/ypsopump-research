package com.ypsopump.test.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class BleLogEntry(
    val timestamp: Long,
    val direction: String, // "TX", "RX", "NOTIFY", "INFO"
    val characteristic: String,
    val hexData: String,
    val decodedInfo: String = ""
)

object BleLog {
    private val _entries = mutableStateListOf<BleLogEntry>()
    val entries: List<BleLogEntry> get() = _entries

    fun add(direction: String, characteristic: String, hexData: String, decodedInfo: String = "") {
        _entries.add(BleLogEntry(
            timestamp = System.currentTimeMillis(),
            direction = direction,
            characteristic = characteristic,
            hexData = hexData,
            decodedInfo = decodedInfo
        ))
    }

    fun clear() = _entries.clear()
}

@Composable
fun BleLogScreen() {
    val entries = BleLog.entries
    val listState = rememberLazyListState()
    var autoScroll by remember { mutableStateOf(true) }

    LaunchedEffect(entries.size) {
        if (autoScroll && entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("BLE Log (${entries.size})", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { autoScroll = !autoScroll }) {
                    Icon(
                        if (autoScroll) Icons.Default.VerticalAlignBottom else Icons.Default.VerticalAlignTop,
                        contentDescription = "Auto-scroll"
                    )
                }
                IconButton(onClick = { BleLog.clear() }) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear log")
                }
            }
        }

        // Log entries
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(entries) { entry ->
                BleLogRow(entry)
            }

            if (entries.isEmpty()) {
                item {
                    Text(
                        "BLE log is empty.\nConnect to pump to see BLE traffic.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun BleLogRow(entry: BleLogEntry) {
    val dirColor = when (entry.direction) {
        "TX" -> Color(0xFF2196F3)
        "RX" -> Color(0xFF4CAF50)
        "NOTIFY" -> Color(0xFFFF9800)
        else -> Color(0xFF9E9E9E)
    }

    val time = remember(entry.timestamp) {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
        sdf.format(java.util.Date(entry.timestamp))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 1.dp)
    ) {
        Text(
            time,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            entry.direction.padEnd(6),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = dirColor
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            entry.characteristic.takeLast(4).padEnd(5),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            entry.hexData,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        if (entry.decodedInfo.isNotEmpty()) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                entry.decodedInfo,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
