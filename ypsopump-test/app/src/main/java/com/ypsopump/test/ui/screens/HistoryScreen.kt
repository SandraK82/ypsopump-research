package com.ypsopump.test.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ypsopump.test.data.EventType
import com.ypsopump.test.data.HistoryEntry
import com.ypsopump.test.ui.theme.PumpColors
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HistoryScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Events", "Alerts", "System")

    // TODO: Phase 6 - Load from CommandExecutor
    var entries by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var filterType by remember { mutableStateOf<Int?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Tab Row
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        // Action Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(
                onClick = { /* TODO: Phase 6 - load history */ },
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Download, contentDescription = null)
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text("Load ${tabs[selectedTab]}")
            }
            Text(
                "${entries.size} entries",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
        }

        // History List
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val filtered = if (filterType != null) entries.filter { it.entryType == filterType }
                else entries

            items(filtered) { entry ->
                HistoryEntryCard(entry)
            }

            if (entries.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            "Connect to pump and tap 'Load' to download history.\n" +
                            "History download may take up to 6 minutes for large logs.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryEntryCard(entry: HistoryEntry) {
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val entryColor = when {
        EventType.isBolusEvent(entry.entryType) -> PumpColors.bolusBlue
        EventType.isTbrEvent(entry.entryType) -> PumpColors.tbrGreen
        EventType.isAlertEvent(entry.entryType) -> PumpColors.alarmRed
        else -> PumpColors.systemGray
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = entryColor.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Time
            Text(
                dateFormat.format(Date(entry.timestamp * 1000)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            // Type + Values
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.entryTypeName, style = MaterialTheme.typography.bodySmall, color = entryColor)
                if (entry.value1 != 0 || entry.value2 != 0) {
                    Text(
                        "v1=${entry.value1} v2=${entry.value2} v3=${entry.value3}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            // Seq
            Text(
                "#${entry.sequence}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
