package com.ypsopump.test.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ypsopump.test.key.SecureKeyStore
import com.ypsopump.test.ui.theme.PumpColors
import com.ypsopump.test.viewmodel.KeyViewModel

@Composable
fun KeyScreen(keyStore: SecureKeyStore) {
    val vm = remember { KeyViewModel(keyStore) }
    val state by vm.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Key Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (state.hasKey)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (state.hasKey) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (state.hasKey) PumpColors.connected else PumpColors.disconnected
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (state.hasKey) "Shared Key Loaded" else "No Key Configured",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                if (state.hasKey) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Preview: ${state.keyPreview}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "Stored: ${state.storedAt}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Hex Input
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Import Shared Key", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.inputText,
                    onValueChange = { vm.updateInput(it) },
                    label = { Text("Hex Key (64 chars)") },
                    placeholder = { Text("e.g. a1b2c3d4e5f6...") },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 4,
                    supportingText = {
                        val len = state.inputText.replace(" ", "").replace(":", "").length
                        Text("$len / 64 hex chars")
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.importHexKey() },
                        enabled = state.inputText.isNotBlank()
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Import")
                    }
                    if (state.hasKey) {
                        OutlinedButton(
                            onClick = { vm.deleteKey() },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete Key")
                        }
                    }
                }

                // Status message
                if (state.statusMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        state.statusMessage,
                        color = if (state.isError) MaterialTheme.colorScheme.error
                        else PumpColors.connected,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // Frida Instructions
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Key Extraction via Frida", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "1. Install Frida on rooted device\n" +
                    "2. Run: frida -U -f net.sinovo.mylife.app \\\n" +
                    "   -l mylife-key-extraction.js --no-pause\n" +
                    "3. In mylife app: connect to pump\n" +
                    "4. Frida captures shared key during pairing\n" +
                    "5. adb pull /data/local/tmp/mylife_keys/shared_key.hex\n" +
                    "6. Paste the hex key above",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
