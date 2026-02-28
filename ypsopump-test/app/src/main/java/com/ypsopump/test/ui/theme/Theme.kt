package com.ypsopump.test.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF90CAF9),
    secondary = Color(0xFF80CBC4),
    tertiary = Color(0xFFCE93D8),
    error = Color(0xFFEF5350),
    surface = Color(0xFF121212),
    background = Color(0xFF121212),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1565C0),
    secondary = Color(0xFF00897B),
    tertiary = Color(0xFF7B1FA2),
    error = Color(0xFFD32F2F),
)

// Pump-specific colors
object PumpColors {
    val bolusBlue = Color(0xFF1976D2)
    val tbrGreen = Color(0xFF388E3C)
    val alarmRed = Color(0xFFD32F2F)
    val systemGray = Color(0xFF757575)
    val batteryLow = Color(0xFFFF9800)
    val batteryOk = Color(0xFF4CAF50)
    val reservoirLow = Color(0xFFFF9800)
    val connected = Color(0xFF4CAF50)
    val disconnected = Color(0xFFF44336)
    val authenticated = Color(0xFF2196F3)
}

@Composable
fun YpsoPumpTestTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(LocalContext.current)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !darkTheme ->
            dynamicLightColorScheme(LocalContext.current)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
