package com.ypsopump.test.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ypsopump.test.data.DeliveryMode
import com.ypsopump.test.ui.theme.PumpColors

@Composable
fun DashboardScreen() {
    // TODO: Connect to CommandExecutor via DashboardViewModel (Phase 3)
    var batteryPercent by remember { mutableIntStateOf(0) }
    var insulinRemaining by remember { mutableFloatStateOf(0f) }
    var deliveryMode by remember { mutableIntStateOf(-1) }
    var isLoading by remember { mutableStateOf(false) }
    var lastUpdate by remember { mutableStateOf("Not connected") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (deliveryMode >= 0)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (deliveryMode >= 0) DeliveryMode.name(deliveryMode) else "Not Connected",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(lastUpdate, style = MaterialTheme.typography.bodySmall)
            }
        }

        // Battery + Reservoir Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Battery Gauge
            Card(modifier = Modifier.weight(1f)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Battery", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    CircularGauge(
                        value = batteryPercent.toFloat(),
                        maxValue = 100f,
                        color = if (batteryPercent > 20) PumpColors.batteryOk else PumpColors.batteryLow,
                        label = "$batteryPercent%",
                        modifier = Modifier.size(100.dp)
                    )
                }
            }

            // Reservoir Gauge
            Card(modifier = Modifier.weight(1f)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Reservoir", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    CircularGauge(
                        value = insulinRemaining,
                        maxValue = 160f,
                        color = if (insulinRemaining > 20f) PumpColors.tbrGreen else PumpColors.reservoirLow,
                        label = "%.1f U".format(insulinRemaining),
                        modifier = Modifier.size(100.dp)
                    )
                }
            }
        }

        // Refresh Button
        Button(
            onClick = { /* TODO: Phase 3 - read system status */ },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Refresh, contentDescription = null)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("Refresh Status")
        }

        // Info text
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Text(
                "Connect to pump and import key first.\n" +
                "Dashboard auto-refreshes every 30 seconds when connected.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun CircularGauge(
    value: Float,
    maxValue: Float,
    color: Color,
    label: String,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 12f
            val sweepAngle = (value / maxValue * 270f).coerceIn(0f, 270f)
            val startAngle = 135f

            // Background arc
            drawArc(
                color = color.copy(alpha = 0.2f),
                startAngle = startAngle,
                sweepAngle = 270f,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                size = Size(size.width - strokeWidth, size.height - strokeWidth)
            )

            // Value arc
            drawArc(
                color = color,
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                size = Size(size.width - strokeWidth, size.height - strokeWidth)
            )
        }
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}
