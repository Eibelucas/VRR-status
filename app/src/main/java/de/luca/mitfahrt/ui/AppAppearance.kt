package de.luca.mitfahrt.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private data class UiPalette(val background: Color, val surface: Color, val muted: Color, val accent: Color,
    val text: Color, val warning: Color, val outline: Color, val dark: Boolean)
private val Light = UiPalette(Color(0xFFF2F2F3), Color.White, Color(0xFF62636D), Color(0xFFB40055), Color(0xFF22262F), Color(0xFF925000), Color(0xFF8D8493), false)
private val Dark = UiPalette(Color(0xFF18131D), Color(0xFF271D2C), Color(0xFFCBC0CE), Color(0xFFFF9BC7), Color(0xFFF8F2F8), Color(0xFFFFCB83), Color(0xFF88768D), true)
private val LocalPalette = staticCompositionLocalOf { Light }
internal val Ink: Color @Composable get() = LocalPalette.current.background
internal val Panel: Color @Composable get() = LocalPalette.current.surface
internal val Soft: Color @Composable get() = LocalPalette.current.muted
internal val Lime: Color @Composable get() = LocalPalette.current.accent
internal val Paper: Color @Composable get() = LocalPalette.current.text
internal val Amber: Color @Composable get() = LocalPalette.current.warning
internal val BrandBerry = Color(0xFFB40055)

@Composable
private fun appearance(): Pair<String, (String) -> Unit> {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("appearance", 0) }
    var mode by remember { mutableStateOf(prefs.getString("mode", "system") ?: "system") }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "mode") mode = prefs.getString("mode", "system") ?: "system"
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return mode to { value -> prefs.edit().putString("mode", value).apply(); mode = value }
}

@Composable
internal fun MitfahrtTheme(content: @Composable () -> Unit) {
    val (mode, _) = appearance()
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val palette = if (dark) Dark else Light
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = base.copy(primary = palette.accent, onPrimary = if (dark) palette.background else Color.White,
            background = palette.background, onBackground = palette.text, surface = palette.surface, onSurface = palette.text,
            surfaceVariant = palette.surface, onSurfaceVariant = palette.muted, outline = palette.outline, secondary = palette.accent), content = content)
    }
}

@Composable
fun AppearanceCard() {
    val (mode, setMode) = appearance()
    Card(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Darstellung", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "System", "light" to "Hell", "dark" to "Dunkel").forEach { (key, label) ->
                    FilterChip(selected = mode == key, onClick = { setMode(key) }, label = { Text(label) })
                }
            }
        }
    }
}
