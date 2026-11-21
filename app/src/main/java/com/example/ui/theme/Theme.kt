package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.model.AccentPreset
import com.example.model.ThemeMode

val GvpnShapes =
  Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
  )

@Composable
fun GvpnTheme(
  themeMode: ThemeMode = ThemeMode.SYSTEM,
  accentPreset: AccentPreset = AccentPreset.CYAN_TEAL,
  content: @Composable () -> Unit,
) {
  val isDark =
    when (themeMode) {
      ThemeMode.SYSTEM -> isSystemInDarkTheme()
      ThemeMode.LIGHT -> false
      ThemeMode.DARK -> true
    }

  val colorScheme = buildColorScheme(isDark = isDark, accent = accentPreset)

  MaterialTheme(
    colorScheme = colorScheme,
    typography = GvpnTypography,
    shapes = GvpnShapes,
    content = content,
  )
}

private fun buildColorScheme(isDark: Boolean, accent: AccentPreset): ColorScheme {
  val primary = Color(accent.primaryHex)
  val secondary = Color(accent.secondaryHex)
  return if (isDark) {
    darkColorScheme(
      primary = primary,
      onPrimary = Color(0xFF04131D),
      primaryContainer = primary.copy(alpha = 0.18f),
      onPrimaryContainer = primary.copy(alpha = 0.95f),
      secondary = secondary,
      onSecondary = Color(0xFF031A12),
      secondaryContainer = secondary.copy(alpha = 0.18f),
      onSecondaryContainer = secondary.copy(alpha = 0.95f),
      background = DarkBackground,
      onBackground = DarkOnSurface,
      surface = DarkSurface,
      onSurface = DarkOnSurface,
      surfaceVariant = DarkSurfaceVariant,
      onSurfaceVariant = DarkOnSurfaceMuted,
      outline = DarkOutline,
      outlineVariant = DarkOutline.copy(alpha = 0.6f),
      error = StatusError,
      onError = Color.White,
    )
  } else {
    lightColorScheme(
      primary = primary,
      onPrimary = Color.White,
      primaryContainer = primary.copy(alpha = 0.12f),
      onPrimaryContainer = primary,
      secondary = secondary,
      onSecondary = Color.White,
      secondaryContainer = secondary.copy(alpha = 0.12f),
      onSecondaryContainer = secondary,
      background = LightBackground,
      onBackground = LightOnSurface,
      surface = LightSurface,
      onSurface = LightOnSurface,
      surfaceVariant = LightSurfaceVariant,
      onSurfaceVariant = LightOnSurfaceMuted,
      outline = LightOutline,
      outlineVariant = LightOutline.copy(alpha = 0.6f),
      error = StatusError,
      onError = Color.White,
    )
  }
}
