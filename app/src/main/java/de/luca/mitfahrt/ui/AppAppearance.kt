package de.luca.mitfahrt.ui

import android.app.Activity
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Shape
import de.luca.mitfahrt.data.AppSettings
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.luca.mitfahrt.data.AppSettingsStore

private data class UiPalette(val background: Color, val surface: Color, val muted: Color, val accent: Color,
    val text: Color, val warning: Color, val outline: Color, val dark: Boolean)
private val Light = UiPalette(Color(0xFFF2F2F3), Color.White, Color(0xFF62636D), Color(0xFFB40055), Color(0xFF22262F), Color(0xFF925000), Color(0xFF8D8493), false)
private val Dark = UiPalette(Color(0xFF18131D), Color(0xFF271D2C), Color(0xFFCBC0CE), Color(0xFFFF9BC7), Color(0xFFF8F2F8), Color(0xFFFFCB83), Color(0xFF88768D), true)
private val LocalPalette = staticCompositionLocalOf { Light }
internal val LocalAppSettings = staticCompositionLocalOf { AppSettings() }
internal val Ink: Color @Composable get() = LocalPalette.current.background
internal val Panel: Color @Composable get() = LocalPalette.current.surface
internal val Soft: Color @Composable get() = LocalPalette.current.muted
internal val Lime: Color @Composable get() = LocalPalette.current.accent
internal val Paper: Color @Composable get() = LocalPalette.current.text
internal val Amber: Color @Composable get() = LocalPalette.current.warning
internal val BrandBerry: Color @Composable get() = LocalPalette.current.accent.let { if (LocalPalette.current.dark) Color(0xFF870040) else it }

@Composable
internal fun MitfahrtTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { AppSettingsStore(context) }
    val settings by store.state.collectAsStateWithLifecycle()
    val dark = when (settings.string("theme", "system")) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val original = if (dark) Dark else Light
    val dynamic = settings.string("accent", "new") == "system" && Build.VERSION.SDK_INT >= 31
    val base = if (dynamic) { if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) }
        else if (dark) darkColorScheme() else lightColorScheme()
    val accent = when (settings.string("accent", "new")) {
        "system" -> base.primary
        "custom" -> runCatching { Color(android.graphics.Color.parseColor(settings.string("accent_hex", "#B40055"))) }.getOrDefault(original.accent)
        else -> original.accent
    }
    val palette = original.copy(accent = accent,
        background = if (dark && settings.bool("oled", false)) Color.Black else original.background,
        muted = if (settings.bool("high_contrast", false)) original.text else original.muted,
        outline = if (settings.bool("high_contrast", false) || settings.string("surface_style", "flat") == "outlined") original.text else original.outline)
    val density = LocalDensity.current
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val cardBorder = if (settings.string("surface_style", "flat") == "outlined") androidx.compose.foundation.BorderStroke(1.dp, palette.outline) else null
    CompositionLocalProvider(LocalPalette provides palette, LocalAppSettings provides settings,
        LocalDensity provides Density(density.density, density.fontScale * settings.int("font_scale", 100).coerceIn(85, 150) / 100f)) {
        MaterialTheme(colorScheme = base.copy(primary = palette.accent, onPrimary = if (dark) palette.background else Color.White,
            background = palette.background, onBackground = palette.text, surface = palette.surface, onSurface = palette.text,
            surfaceVariant = if (cardBorder != null) palette.outline.copy(alpha = .10f) else palette.surface,
            onSurfaceVariant = palette.muted, outline = palette.outline, secondary = palette.accent)) {
            // Text outside a Surface (lists on the page background) must not fall back to black.
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides palette.text, content = content)
        }
    }
}

/**
 * Line number as on the bus display: the one place the NEW colour is used as a filled sign.
 * Corners are tighter than buttons (12) and containers (20) so it reads as a sign, not a control.
 */
@Composable
internal fun LineSign(line: String, onBrand: Boolean = false, large: Boolean = false, cancelled: Boolean = false) {
    val background = if (cancelled) Color.Transparent else if (onBrand) Color.White else Lime
    val content = if (cancelled) Amber else if (onBrand) Color(0xFF870040) else MaterialTheme.colorScheme.onPrimary
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(background)
        .then(if (cancelled) Modifier.border(1.dp, Amber, RoundedCornerShape(6.dp)) else Modifier)
        .widthIn(min = if (large) 52.dp else 40.dp).padding(horizontal = if (large) 10.dp else 8.dp, vertical = if (large) 7.dp else 4.dp),
        contentAlignment = Alignment.Center) {
        Text(line, color = content, fontSize = if (large) 18.sp else 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            textDecoration = if (cancelled) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
    }
}

@Composable
fun AppearanceCard() {
    val context = LocalContext.current
    val store = remember(context) { AppSettingsStore(context) }
    val settings by store.state.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth(), shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Darstellung", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "System", "light" to "Hell", "dark" to "Dunkel").forEach { (key, label) ->
                    FilterChip(selected = settings.string("theme", "system") == key, onClick = { store.set("theme", key) }, label = { Text(label) })
                }
            }
        }
    }
}


@Composable
internal fun AppCard(modifier: Modifier = Modifier, shape: Shape = MaterialTheme.shapes.medium,
    colors: CardColors = CardDefaults.cardColors(), content: @Composable ColumnScope.() -> Unit) {
    val border = if (LocalAppSettings.current.string("surface_style") == "outlined") BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null
    Card(modifier = modifier, shape = shape, colors = colors, border = border, content = content)
}

@Composable
internal fun AppCard(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.medium, colors: CardColors = CardDefaults.cardColors(),
    content: @Composable ColumnScope.() -> Unit) {
    val border = if (LocalAppSettings.current.string("surface_style") == "outlined") BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null
    Card(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, colors = colors, border = border, content = content)
}

@Composable
internal fun AppCircularProgressIndicator(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 4.dp) {
    if (LocalAppSettings.current.bool("reduce_motion")) Icon(Icons.Outlined.HourglassEmpty, "Wird geladen", modifier = modifier, tint = color)
    else CircularProgressIndicator(modifier = modifier, color = color, strokeWidth = strokeWidth)
}

@Composable
internal fun AppLinearProgressIndicator(modifier: Modifier = Modifier, progress: (() -> Float)? = null,
    color: Color = MaterialTheme.colorScheme.primary, trackColor: Color = MaterialTheme.colorScheme.surfaceVariant) {
    if (progress != null) LinearProgressIndicator(progress = progress, modifier = modifier, color = color, trackColor = trackColor)
    else if (LocalAppSettings.current.bool("reduce_motion")) HorizontalDivider(modifier = modifier, color = color)
    else LinearProgressIndicator(modifier = modifier, color = color, trackColor = trackColor)
}
