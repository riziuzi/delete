package com.riziuzi.utho.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.riziuzi.utho.core.Lang

val LocalLang = staticCompositionLocalOf { Lang.EN }

/** English or Hindi text for the current app language. */
@Composable
fun tr(en: String, hi: String): String = if (LocalLang.current == Lang.HI) hi else en

private val Light = lightColorScheme(
    primary = Color(0xFF3443C4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFECEEFB),
    onPrimaryContainer = Color(0xFF1B1B1F),
    secondary = Color(0xFFA4520A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFDF0E1),
    onSecondaryContainer = Color(0xFF1B1B1F),
    background = Color(0xFFF6F5F1),
    onBackground = Color(0xFF1B1B1F),
    surface = Color.White,
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFECEAE4),
    onSurfaceVariant = Color(0xFF5F5E66),
    surfaceContainerHighest = Color(0xFFECEAE4),
    surfaceContainerHigh = Color(0xFFF1EFEA),
    surfaceContainer = Color.White,
    surfaceContainerLow = Color.White,
    outline = Color(0xFFC9C6BE),
    outlineVariant = Color(0xFFE4E2DC),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFAAB4FF),
    onPrimary = Color(0xFF10196B),
    primaryContainer = Color(0xFF2A3591),
    onPrimaryContainer = Color(0xFFE0E3FF),
    secondary = Color(0xFFF2B36B),
    onSecondary = Color(0xFF3A2200),
    secondaryContainer = Color(0xFF4A2E0C),
    onSecondaryContainer = Color(0xFFFFE0C2),
    background = Color(0xFF131316),
    onBackground = Color(0xFFE6E4EA),
    surface = Color(0xFF1D1D21),
    onSurface = Color(0xFFE6E4EA),
    surfaceVariant = Color(0xFF2C2B31),
    onSurfaceVariant = Color(0xFFB5B3BC),
    surfaceContainerHighest = Color(0xFF2C2B31),
    surfaceContainerHigh = Color(0xFF26252B),
    surfaceContainer = Color(0xFF1D1D21),
    surfaceContainerLow = Color(0xFF1D1D21),
    outline = Color(0xFF55545C),
    outlineVariant = Color(0xFF3A3940),
    error = Color(0xFFFFB4AB),
)

@Composable
fun UthoTheme(theme: String, lang: Lang, content: @Composable () -> Unit) {
    val dark = when (theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    // Status and navigation bar icons follow the app's theme, not the phone's (dark icons on a dark screen were invisible).
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            var c = view.context
            while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
            (c as? android.app.Activity)?.window?.let { w ->
                WindowCompat.getInsetsController(w, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    CompositionLocalProvider(LocalLang provides lang) {
        val scheme = if (dark) Dark else Light
        MaterialTheme(colorScheme = scheme) {
            // Text without its own colour follows the theme (it was black on dark screens without a Surface).
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}
