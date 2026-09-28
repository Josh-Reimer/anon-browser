package com.joshreimer.anonbrowser.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = TorPurple,
    onPrimary = Color.White,
    primaryContainer = TorPurpleContainerLight,
    onPrimaryContainer = TorPurpleOnContainerLight,
    secondary = TorPurple,
)

private val DarkColors = darkColorScheme(
    primary = TorPurpleLight,
    onPrimary = Color(0xFF34156B),
    primaryContainer = TorPurpleContainerDark,
    onPrimaryContainer = TorPurpleOnContainerDark,
    secondary = TorPurpleLight,
)

/** Follows the device's light/dark setting; the status bar itself stays a fixed brand
 * purple regardless (wired up separately via enableEdgeToEdge in MainActivity). */
@Composable
fun AnonBrowserTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colorScheme, content = content)
}
