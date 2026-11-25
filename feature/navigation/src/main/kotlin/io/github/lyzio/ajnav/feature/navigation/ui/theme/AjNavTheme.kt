package io.github.lyzio.ajnav.feature.navigation.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** LyzLarm palette (design doc §2, UI mockup 2026-11-25). */
object AjColors {
    val Darkest = Color(0xFF0F1A1C)
    val Bg = Color(0xFF1B2F33)
    val Land = Color(0xFF20363A)
    val Card = Color(0xFF243D42)
    val Border = Color(0xFF2C474D)
    val Ghost = Color(0xFF33525A)
    val Pill = Color(0xFF006266)
    val Teal = Color(0xFF4FB3A9)
    val Coral = Color(0xFFED4C67)
    val CoralHover = Color(0xFFF07A8E)
    val Muted = Color(0xFFA9BCC0)
    val Sub = Color(0xFF9DB1B5)
    val Body = Color(0xFFD3DEE0)
    val Mint = Color(0xFFE6F4F3)
    val Ice = Color(0xFFEEF3F3)
}

private val AjColorScheme = darkColorScheme(
    primary = AjColors.Teal,
    onPrimary = AjColors.Darkest,
    primaryContainer = AjColors.Pill,
    onPrimaryContainer = AjColors.Ice,
    secondary = AjColors.Coral,
    onSecondary = AjColors.Darkest,
    background = AjColors.Bg,
    onBackground = AjColors.Body,
    surface = AjColors.Card,
    onSurface = AjColors.Ice,
    surfaceVariant = AjColors.Land,
    onSurfaceVariant = AjColors.Muted,
    surfaceContainer = AjColors.Darkest,
    surfaceContainerHigh = AjColors.Card,
    outline = AjColors.Border,
    error = AjColors.Coral,
    onError = AjColors.Darkest,
)

@Composable
fun AjNavTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AjColorScheme, typography = Typography(), content = content)
}
