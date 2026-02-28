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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ypsopump.test.ui.theme.PumpColors

data class CommandInfo(
    val id: Int,
    val name: String,
    val group: String,
    val description: String,
    val isEncrypted: Boolean = true,
    val isWrite: Boolean = false
)

val ALL_COMMANDS = listOf(
    // Base (0-4) - Unencrypted
    CommandInfo(0, "Master Version", "Base", "Read master protocol version", isEncrypted = false),
    CommandInfo(1, "Base Version", "Base", "Read base version number", isEncrypted = false),
    CommandInfo(2, "Settings Version", "Base", "Read settings version", isEncrypted = false),
    CommandInfo(3, "History Version", "Base", "Read history version", isEncrypted = false),
    CommandInfo(4, "Auth Password", "Base", "Write MD5 auth password", isEncrypted = false, isWrite = true),

    // Settings (5-9) - Encrypted
    CommandInfo(5, "Read Setting", "Settings", "Read setting by ID (GLB encoded)"),
    CommandInfo(6, "Write Setting", "Settings", "Write setting by ID", isWrite = true),
    CommandInfo(7, "Read Date", "Settings", "Read system date"),
    CommandInfo(8, "Read Time", "Settings", "Read system time"),
    CommandInfo(9, "Write Date/Time", "Settings", "Sync date and time to pump", isWrite = true),

    // History (10-25) - Encrypted
    CommandInfo(10, "Events Count", "History", "Read event history count"),
    CommandInfo(11, "Events Index", "History", "Write event index", isWrite = true),
    CommandInfo(12, "Events Value", "History", "Read event at current index"),
    CommandInfo(13, "Alerts Count", "History", "Read alert history count"),
    CommandInfo(14, "Alerts Index", "History", "Write alert index", isWrite = true),
    CommandInfo(15, "Alerts Value", "History", "Read alert at current index"),
    CommandInfo(16, "System Count", "History", "Read system log count"),
    CommandInfo(17, "System Index", "History", "Write system log index", isWrite = true),
    CommandInfo(18, "System Value", "History", "Read system log at current index"),
    CommandInfo(19, "Read All Events", "History", "Download all event history"),
    CommandInfo(20, "Read All Alerts", "History", "Download all alert history"),
    CommandInfo(21, "Read All System", "History", "Download all system logs"),
    CommandInfo(22, "Read Basal Profile A", "History", "Read 24h basal profile A"),
    CommandInfo(23, "Read Basal Profile B", "History", "Read 24h basal profile B"),
    CommandInfo(24, "Read Active Program", "History", "Read active basal program (A/B)"),
    CommandInfo(25, "Extended Read", "History", "Multi-frame read command"),

    // Control (26-32) - Encrypted, Critical
    CommandInfo(26, "System Status", "Control", "Read delivery mode, battery, reservoir"),
    CommandInfo(27, "Bolus Status", "Control", "Read current bolus delivery status"),
    CommandInfo(28, "Start Bolus", "Control", "Start insulin bolus delivery", isWrite = true),
    CommandInfo(29, "Cancel Bolus", "Control", "Cancel active bolus", isWrite = true),
    CommandInfo(30, "Start TBR", "Control", "Start temporary basal rate", isWrite = true),
    CommandInfo(31, "Cancel TBR", "Control", "Cancel active TBR (100%, 0min)", isWrite = true),
    CommandInfo(32, "Security Status", "Control", "Read security/pairing status"),
)

@Composable
fun CommandScreen() {
    var selectedGroup by remember { mutableStateOf("All") }
    var lastResponse by remember { mutableStateOf("") }

    val groups = listOf("All") + ALL_COMMANDS.map { it.group }.distinct()
    val filteredCommands = if (selectedGroup == "All") ALL_COMMANDS
        else ALL_COMMANDS.filter { it.group == selectedGroup }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Group filter chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            groups.forEach { group ->
                FilterChip(
                    selected = selectedGroup == group,
                    onClick = { selectedGroup = group },
                    label = { Text(group, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Command List
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(filteredCommands) { cmd ->
                CommandCard(cmd) {
                    // TODO: Phase 4 - execute command
                    lastResponse = "Command #${cmd.id} (${cmd.name}): Not connected"
                }
            }
        }

        // Response area
        if (lastResponse.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(
                    lastResponse,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun CommandCard(cmd: CommandInfo, onExecute: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "#${cmd.id}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(cmd.name, style = MaterialTheme.typography.bodyMedium)
                    if (cmd.isEncrypted) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Lock, null,
                            modifier = Modifier.size(14.dp),
                            tint = PumpColors.authenticated
                        )
                    }
                    if (cmd.isWrite) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Edit, null,
                            modifier = Modifier.size(14.dp),
                            tint = PumpColors.alarmRed
                        )
                    }
                }
                Text(cmd.description, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onExecute) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Execute")
            }
        }
    }
}
