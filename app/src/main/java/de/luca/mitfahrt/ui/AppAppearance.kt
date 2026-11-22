package de.luca.mitfahrt.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.luca.mitfahrt.data.AppSettings
import de.luca.mitfahrt.data.AppSettingsStore
import de.luca.mitfahrt.data.TransitColors
import de.luca.mitfahrt.data.TripResultPreferences
import kotlin.math.roundToInt

/**
 * Colour roles of the app. [band] is the "Zielschild": the filled surface of the running ride, always with white text.
 * [hairline] separates rows and is decorative only; [outline] marks control edges (at least 3:1).
 */
private data class UiPalette(val background: Color, val surface: Color, val muted: Color, val accent: Color,
    val text: Color, val warning: Color, val outline: Color, val dark: Boolean,
    val band: Color = accent, val hairline: Color = outline, val well: Color = surface, val onAccent: Color = Color.White)
private val Light = UiPalette(Color(0xFFF2F2F3), Color.White, Color(0xFF62636D), Color(0xFFB40055), Color(0xFF22262F), Color(0xFF925000),
    Color(0xFF8D8493), false, band = Color(0xFFB40055), hairline = Color(0xFFD9D5DF), well = Color(0xFFE8E8EB), onAccent = Color.White)
private val Dark = UiPalette(Color(0xFF18131D), Color(0xFF271D2C), Color(0xFFCBC0CE), Color(0xFFFF9BC7), Color(0xFFF8F2F8), Color(0xFFFFCB83),
    Color(0xFF88768D), true, band = Color(0xFF870040), hairline = Color(0xFF3E3344), well = Color(0xFF33283A), onAccent = Color(0xFF18131D))
private val LocalPalette = staticCompositionLocalOf { Light }
internal val LocalAppSettings = staticCompositionLocalOf { AppSettings() }
internal val Ink: Color @Composable get() = LocalPalette.current.background
internal val Panel: Color @Composable get() = LocalPalette.current.surface
internal val Soft: Color @Composable get() = LocalPalette.current.muted
internal val Lime: Color @Composable get() = LocalPalette.current.accent
internal val Paper: Color @Composable get() = LocalPalette.current.text
internal val Amber: Color @Composable get() = LocalPalette.current.warning
/** Surface of the running ride ("Zielschild"); never used as a foreground colour. */
internal val BrandBerry: Color @Composable get() = LocalPalette.current.band
/** Thin separator between rows of a board or list; decorative only. */
internal val Hairline: Color @Composable get() = LocalPalette.current.hairline
/** Recessed field such as "Wohin?". */
internal val Well: Color @Composable get() = LocalPalette.current.well

internal fun contrastRatio(foreground: Color, background: Color): Double =
    androidx.core.graphics.ColorUtils.calculateContrast(foreground.copy(alpha = 1f).toArgb(), background.copy(alpha = 1f).toArgb())

/** Keeps the hue of [seed] and shifts its lightness until it reaches [minimum] against every background. */
internal fun safeAccent(seed: Color, backgrounds: List<Color>, minimum: Double = 4.5): Color {
    val towardsLight = backgrounds.first().luminance() < .5f
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(seed.copy(alpha = 1f).toArgb(), hsl)
    var color = seed.copy(alpha = 1f)
    repeat(60) {
        if (backgrounds.minOf { contrastRatio(color, it) } >= minimum) return color
        hsl[2] = (hsl[2] + if (towardsLight) .02f else -.02f).coerceIn(0f, 1f)
        color = Color(androidx.core.graphics.ColorUtils.HSLToColor(hsl))
    }
    return color
}

/** White or the dark ink, whichever reads better on [color]. */
internal fun onColorFor(color: Color): Color =
    if (contrastRatio(Color.White, color) >= contrastRatio(Color(0xFF18131D), color)) Color.White else Color(0xFF18131D)

/** True when the app setting or the system asks for less motion. */
internal val reduceMotion: Boolean @Composable get() =
    LocalAppSettings.current.bool("reduce_motion") || !android.animation.ValueAnimator.areAnimatorsEnabled()

private val Tabular = "tnum"
private fun TextStyle.tabular() = copy(fontFeatureSettings = Tabular)

/** One type scale for the whole app; every style uses figures of equal width so times do not jump. */
internal val MitfahrtTypography: Typography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.copy(fontSize = 88.sp, lineHeight = 88.sp, fontWeight = FontWeight.Medium, letterSpacing = (-2).sp).tabular(),
        displayMedium = t.displayMedium.copy(fontSize = 57.sp, lineHeight = 64.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1).sp).tabular(),
        displaySmall = t.displaySmall.copy(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold).tabular(),
        headlineLarge = t.headlineLarge.copy(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold).tabular(),
        headlineMedium = t.headlineMedium.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold).tabular(),
        headlineSmall = t.headlineSmall.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold).tabular(),
        titleLarge = t.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold).tabular(),
        titleMedium = t.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold).tabular(),
        titleSmall = t.titleSmall.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold).tabular(),
        bodyLarge = t.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp).tabular(),
        bodyMedium = t.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp).tabular(),
        bodySmall = t.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp).tabular(),
        labelLarge = t.labelLarge.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold).tabular(),
        labelMedium = t.labelMedium.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium).tabular(),
        labelSmall = t.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium).tabular(),
    )
}

/** Time column of a departure board. */
internal val BoardTime = TextStyle(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = Tabular)

/** Three radii with meaning: sign 6, control 12, container 20. */
internal val MitfahrtShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(20.dp),
)
internal val SignShape = RoundedCornerShape(6.dp)

@Composable
internal fun rememberAppSettingsStore(): AppSettingsStore {
    val context = LocalContext.current
    val shared = remember(context) { (context.applicationContext as? de.luca.mitfahrt.MitfahrtApplication)?.settingsStore }
    if (shared != null) return shared
    val local = remember(context) { AppSettingsStore(context) }
    DisposableEffect(local) { onDispose { local.close() } }
    return local
}

@Composable
internal fun MitfahrtTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = rememberAppSettingsStore()
    val settings by store.state.collectAsStateWithLifecycle()
    val dark = when (settings.string("theme", "system")) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val systemContrast = Build.VERSION.SDK_INT >= 34 &&
        (context.getSystemService(android.app.UiModeManager::class.java)?.contrast ?: 0f) >= .5f
    val highContrast = settings.bool("high_contrast", false) || systemContrast
    val original = (if (dark) Dark else Light).let {
        if (!highContrast) it
        else if (dark) it.copy(accent = Color(0xFFFFC1DB), warning = Color(0xFFFFDDB8), muted = it.text, outline = it.text)
        else it.copy(accent = Color(0xFF8F0044), band = Color(0xFF8F0044), warning = Color(0xFF7A2E00), muted = it.text, outline = it.text)
    }
    val dynamic = settings.string("accent", "new") == "system" && Build.VERSION.SDK_INT >= 31
    val base = if (dynamic) { if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) }
        else if (dark) darkColorScheme() else lightColorScheme()
    val background = if (dark && settings.bool("oled", false)) Color.Black else original.background
    val seed = when (settings.string("accent", "new")) {
        "system" -> base.primary
        "custom" -> runCatching { Color(android.graphics.Color.parseColor(settings.string("accent_hex", "#B40055"))) }.getOrDefault(original.accent)
        else -> original.accent
    }
    // A chosen accent keeps its hue but is moved until it reads as text on page and card (WCAG AA).
    val accent = if (seed == original.accent) seed else safeAccent(seed, listOf(background, original.surface))
    val band = if (seed == original.accent) original.band else safeAccent(seed, listOf(Color.White))
    val palette = original.copy(accent = accent, band = band, background = background,
        onAccent = onColorFor(accent),
        outline = if (settings.string("surface_style", "flat") == "outlined") original.text else original.outline)
    val density = LocalDensity.current
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalPalette provides palette, LocalAppSettings provides settings,
        LocalDensity provides Density(density.density, density.fontScale * settings.int("font_scale", 100).coerceIn(85, 150) / 100f)) {
        // Every role is set on purpose: Material defaults (secondaryContainer, surfaceContainer*, tint) would bleed violet.
        val warningContainer = if (dark) Color(0xFF693C00) else Color(0xFFFFDDB8)
        val scheme = base.copy(
            primary = palette.accent, onPrimary = palette.onAccent,
            primaryContainer = palette.band, onPrimaryContainer = Color.White,
            inversePrimary = if (dark) Color(0xFFB40055) else Color(0xFFFF9BC7),
            secondary = palette.text, onSecondary = palette.background,
            secondaryContainer = palette.text, onSecondaryContainer = palette.background,
            tertiary = palette.warning, onTertiary = palette.background,
            tertiaryContainer = warningContainer, onTertiaryContainer = if (dark) Color(0xFFFFDDB8) else Color(0xFF2E1500),
            error = if (dark) Color(0xFFFFB4AB) else Color(0xFFBA1A1A), onError = if (dark) Color(0xFF690005) else Color.White,
            errorContainer = if (dark) Color(0xFF93000A) else Color(0xFFFFDAD6), onErrorContainer = if (dark) Color(0xFFFFDAD6) else Color(0xFF410002),
            background = palette.background, onBackground = palette.text, surface = palette.surface, onSurface = palette.text,
            surfaceVariant = palette.well, onSurfaceVariant = palette.muted,
            surfaceTint = Color.Transparent,
            surfaceBright = palette.surface, surfaceDim = palette.background,
            surfaceContainerLowest = palette.surface, surfaceContainerLow = palette.surface, surfaceContainer = palette.surface,
            surfaceContainerHigh = palette.surface, surfaceContainerHighest = palette.surface,
            inverseSurface = palette.text, inverseOnSurface = palette.background,
            outline = palette.outline, outlineVariant = palette.hairline, scrim = Color.Black)
        MaterialTheme(colorScheme = scheme, typography = MitfahrtTypography, shapes = MitfahrtShapes) {
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
    val colors = LocalAppSettings.current.string("line_route_colors")
    val custom = remember(colors, line) {
        TransitColors.decode(colors)[TripResultPreferences.lineKey(line)]?.let { Color(android.graphics.Color.parseColor(it)) }
    }
    val background = if (cancelled) Color.Transparent else custom ?: if (onBrand) Color.White else Lime
    val content = when {
        cancelled -> Amber
        custom != null -> if (custom.luminance() >= .179f) Color.Black else Color.White
        onBrand -> BrandBerry
        else -> MaterialTheme.colorScheme.onPrimary
    }
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(background)
        .then(if (cancelled) Modifier.border(1.dp, Amber, RoundedCornerShape(6.dp)) else Modifier)
        .widthIn(min = if (large) 52.dp else 40.dp).padding(horizontal = if (large) 10.dp else 8.dp, vertical = if (large) 7.dp else 4.dp),
        contentAlignment = Alignment.Center) {
        Text(line, color = content, fontSize = if (large) 18.sp else 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            textDecoration = if (cancelled) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceCard() {
    val store = rememberAppSettingsStore()
    val settings by store.state.collectAsStateWithLifecycle()
    val dark = LocalPalette.current.dark
    val accentMode = settings.string("accent", "new")
    val accentHex = settings.string("accent_hex", "#B40055")
    val fontScale = settings.int("font_scale", 100).coerceIn(85, 150)
    val density = settings.string("density", "standard")
    val previewPad = when (density) { "compact" -> 10.dp; "comfortable" -> 16.dp; else -> 13.dp }
    val swatches = listOf(
        "#B40055" to "NEW Beere",
        "#006B5F" to "Tanne",
        "#2F5DA8" to "Kobalt",
        "#7C428A" to "Pflaume",
        "#B65A00" to "Bernstein",
        "#333333" to "Graphit"
    )

    AppCard(Modifier.fillMaxWidth(), shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Well),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Palette, null, tint = Lime, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("Darstellung", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text("Live-Vorschau für Design, Farbe und Schrift", color = Soft, fontSize = 12.sp)
                }
            }

            // Live miniature departure row reacting immediately to every visual setting.
            Surface(shape = ControlShape, color = Well, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = previewPad),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LineSign("017", large = true)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Rheindahlen", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("Steig 2 · +2 Min", color = Amber, fontSize = 12.sp, maxLines = 1)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("3 Min", style = BoardTime, color = Paper)
                        Text("LIVE", color = Lime, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Theme selector
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Modus", color = Soft, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        Triple("system", "System", Icons.Outlined.SettingsBrightness),
                        Triple("light", "Hell", Icons.Outlined.LightMode),
                        Triple("dark", "Dunkel", Icons.Outlined.DarkMode)
                    ).forEach { (key, label, icon) ->
                        FilterChip(
                            selected = settings.string("theme", "system") == key,
                            onClick = { store.set("theme", key) },
                            leadingIcon = { Icon(icon, null, Modifier.size(16.dp)) },
                            label = { Text(label, maxLines = 1) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Accent color selector + direct color swatches
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Akzentfarbe", color = Soft, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    if (Build.VERSION.SDK_INT >= 31) {
                        FilterChip(
                            selected = accentMode == "system",
                            onClick = { store.set("accent", if (accentMode == "system") "new" else "system") },
                            label = { Text("Android-Farben", fontSize = 12.sp) }
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    swatches.forEach { (hex, name) ->
                        val isNew = hex.equals("#B40055", true)
                        val selected = (isNew && accentMode == "new") || (accentMode == "custom" && accentHex.equals(hex, true))
                        val swatchColor = remember(hex) { Color(android.graphics.Color.parseColor(hex)) }
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(swatchColor)
                                .then(if (selected) Modifier.border(2.5.dp, Paper, CircleShape) else Modifier.border(1.dp, Hairline, CircleShape))
                                .clickable(role = Role.RadioButton) {
                                    if (isNew) {
                                        store.set("accent", "new")
                                    } else {
                                        store.set("accent_hex", hex)
                                        store.set("accent", "custom")
                                    }
                                }
                                .semantics { contentDescription = name },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // Density chips
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Abstände", color = Soft, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("compact" to "Kompakt", "standard" to "Standard", "comfortable" to "Luftig").forEach { (key, label) ->
                        FilterChip(
                            selected = density == key,
                            onClick = { store.set("density", key) },
                            label = { Text(label, maxLines = 1) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Font size slider
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Schriftgröße", color = Soft, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Surface(shape = RoundedCornerShape(8.dp), color = Well) {
                        Text("$fontScale %", color = Paper, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
                Slider(
                    value = fontScale.toFloat(),
                    onValueChange = { raw ->
                        val stepped = ((raw / 5f).roundToInt() * 5).coerceIn(85, 150)
                        if (stepped != fontScale) store.set("font_scale", stepped.toString())
                    },
                    valueRange = 85f..150f,
                    steps = 12
                )
            }

            HorizontalDivider(color = Hairline)

            // Quick visual toggles
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("OLED-Schwarz", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(if (dark) "Tiefschwarzer Hintergrund aktivierbar" else "Wirkt im Dunkelmodus", color = Soft, fontSize = 12.sp)
                }
                Switch(
                    checked = settings.bool("oled", false),
                    onCheckedChange = { store.set("oled", it.toString()) }
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Klare Kartenkonturen", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("Zeichnet einen deutlichen Rahmen um Karten", color = Soft, fontSize = 12.sp)
                }
                Switch(
                    checked = settings.string("surface_style", "flat") == "outlined",
                    onCheckedChange = { store.set("surface_style", if (it) "outlined" else "flat") }
                )
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
    if (reduceMotion) Icon(Icons.Outlined.HourglassEmpty, "Wird geladen", modifier = modifier, tint = color)
    else CircularProgressIndicator(modifier = modifier, color = color, strokeWidth = strokeWidth)
}

@Composable
internal fun AppLinearProgressIndicator(modifier: Modifier = Modifier, progress: (() -> Float)? = null,
    color: Color = MaterialTheme.colorScheme.primary, trackColor: Color = MaterialTheme.colorScheme.surfaceVariant) {
    if (progress != null) LinearProgressIndicator(progress = progress, modifier = modifier, color = color, trackColor = trackColor)
    else if (reduceMotion) HorizontalDivider(modifier = modifier, color = color)
    else LinearProgressIndicator(modifier = modifier, color = color, trackColor = trackColor)
}
