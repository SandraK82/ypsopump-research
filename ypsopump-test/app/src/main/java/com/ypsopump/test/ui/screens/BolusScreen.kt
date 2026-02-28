package com.ypsopump.test.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ypsopump.test.ui.theme.PumpColors
import kotlinx.coroutines.delay

@Composable
fun BolusScreen() {
    var bolusAmount by remember { mutableFloatStateOf(0.0f) }
    var showConfirmation by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var isDelivering by remember { mutableStateOf(false) }

    // Pre-flight check states (TODO: connect to real data in Phase 5)
    val isPumpConnected = false
    val pumpState = "Unknown"
    val batteryPercent = 0
    val reservoirUnits = 0f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Bolus Delivery", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // Pre-flight Status
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isPumpConnected)
                    MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Pre-Flight Checks", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                PreFlightRow("Pump Connected", isPumpConnected)
                PreFlightRow("Pump Running", pumpState != "Stopped")
                PreFlightRow("Battery > 10%", batteryPercent > 10)
                PreFlightRow("Reservoir > 0 U", reservoirUnits > 0f)
            }
        }

        // Amount Input
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "%.1f U".format(bolusAmount),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (bolusAmount > 0) PumpColors.bolusBlue else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                Slider(
                    value = bolusAmount,
                    onValueChange = { bolusAmount = (it * 10).toInt() / 10f },
                    valueRange = 0f..30f,
                    steps = 299,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(-1.0f, -0.1f, 0.1f, 1.0f).forEach { delta ->
                        FilledTonalButton(
                            onClick = {
                                bolusAmount = (bolusAmount + delta).coerceIn(0f, 30f)
                                bolusAmount = (bolusAmount * 10).toInt() / 10f
                            }
                        ) {
                            Text(if (delta > 0) "+${delta}" else "$delta")
                        }
                    }
                }
            }
        }

        if (!showConfirmation) {
            // Review Button
            Button(
                onClick = { showConfirmation = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = bolusAmount >= 0.1f && isPumpConnected && !isDelivering,
                colors = ButtonDefaults.buttonColors(containerColor = PumpColors.bolusBlue)
            ) {
                Icon(Icons.Default.Vaccines, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Review Bolus")
            }
        } else {
            // Confirmation Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PumpColors.alarmRed.copy(alpha = 0.1f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = PumpColors.alarmRed)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Confirm Delivery", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "%.1f U Immediate Bolus".format(bolusAmount),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = PumpColors.alarmRed
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    if (countdown > 0) {
                        Text("Delivering in $countdown...", color = PumpColors.alarmRed)
                        LaunchedEffect(countdown) {
                            delay(1000)
                            if (countdown > 1) {
                                countdown--
                            } else {
                                // TODO: Phase 5 - actually deliver bolus
                                isDelivering = true
                                countdown = 0
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            showConfirmation = false
                            countdown = 0
                        }) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = { countdown = 3 },
                            enabled = countdown == 0 && !isDelivering,
                            colors = ButtonDefaults.buttonColors(containerColor = PumpColors.alarmRed)
                        ) {
                            Text("CONFIRM DELIVERY")
                        }
                    }
                }
            }
        }

        if (isDelivering) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PumpColors.bolusBlue.copy(alpha = 0.1f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Bolus delivery in progress...")
                    OutlinedButton(onClick = {
                        // TODO: Phase 5 - cancel bolus
                        isDelivering = false
                        showConfirmation = false
                    }) {
                        Icon(Icons.Default.Cancel, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Cancel Delivery")
                    }
                }
            }
        }
    }
}

@Composable
private fun PreFlightRow(label: String, passed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (passed) Icons.Default.CheckCircle else Icons.Default.Cancel,
            contentDescription = null,
            tint = if (passed) PumpColors.connected else PumpColors.disconnected,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
