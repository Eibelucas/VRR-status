package de.luca.mitfahrt.widgets

import de.luca.mitfahrt.data.Departure
import de.luca.mitfahrt.data.Ride
import java.time.Instant

/** The three widget contents. [key] is the value MainActivity receives as `widget_kind`. */
enum class WidgetKind(val key: String, val label: String) {
    STOP("stop", "Haltestelle"),
    ROUTE("route", "Route"),
    ACTIVE("active", "Aktuelle Fahrt");

    companion object {
        fun fromKey(value: String?): WidgetKind? = entries.firstOrNull { it.key == value }
    }
}

/** How much a widget shows; size limits may reduce it further, never extend it. */
enum class InfoLevel(val key: String, val label: String, val maxRows: Int) {
    MINIMAL("minimal", "Wenig", 2),
    NORMAL("normal", "Normal", 3),
    DETAILED("detailed", "Viel", 4);

    companion object {
        fun fromKey(value: String?): InfoLevel = entries.firstOrNull { it.key == value } ?: NORMAL
    }
}

enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "System"),
    LIGHT("light", "Hell"),
    DARK("dark", "Dunkel");

    companion object {
        fun fromKey(value: String?): ThemeMode = entries.firstOrNull { it.key == value } ?: SYSTEM
    }
}

/** Accent colors with a light and a dark variant; both keep text on the badge readable. */
enum class WidgetAccent(val key: String, val label: String, val light: Int, val dark: Int) {
    BERRY("berry", "Beere", 0xFFB40055.toInt(), 0xFFFF9BC7.toInt()),
    BLUE("blue", "Blau", 0xFF1F5FBF.toInt(), 0xFF9CC2FF.toInt()),
    GREEN("green", "Grün", 0xFF1E7A46.toInt(), 0xFF8FD9A8.toInt()),
    ORANGE("orange", "Orange", 0xFFA45200.toInt(), 0xFFFFC27A.toInt()),
    GRAPHITE("graphite", "Graphit", 0xFF3D4048.toInt(), 0xFFD5D6DC.toInt());

    companion object {
        fun fromKey(value: String?): WidgetAccent = entries.firstOrNull { it.key == value } ?: BERRY
    }
}

data class WidgetConfig(
    val kind: WidgetKind,
    /** Saved stop or route id; never shown to the user and empty for [WidgetKind.ACTIVE]. */
    val targetId: String = "",
    /** Readable name captured at configuration time, used as title. */
    val targetName: String = "",
    val info: InfoLevel = InfoLevel.NORMAL,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val accent: WidgetAccent = WidgetAccent.BERRY,
    val opacity: Int = 100,
    /** App defaults remain live until the user chooses per-widget customization. */
    val useAppDefaults: Boolean = false,
    val action: String = "departures",
    val rowCount: Int = 3
) {
    val needsTarget: Boolean get() = kind != WidgetKind.ACTIVE
    fun isComplete(): Boolean = !needsTarget || (targetId.isNotBlank() && targetName.isNotBlank())
}

object WidgetConfigs {
    val OPACITIES = listOf(100, 85, 70, 50, 20)
    private const val MAX_ID = 256
    private const val MAX_NAME = 120

    /** Returns a storable configuration or null when it cannot describe a usable widget. */
    fun clean(config: WidgetConfig): WidgetConfig? {
        val base = config.copy(opacity = config.opacity.coerceIn(20, 100), rowCount = config.rowCount.coerceIn(1, 5),
            action = config.action.takeIf { it in WidgetIntents.ACTIONS } ?: "departures")
        if (!config.needsTarget) return base.copy(targetId = "", targetName = "")
        val id = config.targetId.trim()
        val name = config.targetName.trim()
        if (id.isEmpty() || id.length > MAX_ID || id.startsWith("demo:") || name.isEmpty() ||
            id.any { it.isWhitespace() || it.isISOControl() } || id.contains('/') || id.contains('\\')) return null
        return base.copy(targetId = id, targetName = name.take(MAX_NAME))
    }
}

/** Colors for one appearance; [background] is opaque, transparency is applied separately. */
data class WidgetPalette(
    val background: Int, val text: Int, val muted: Int, val warning: Int,
    val accent: Int, val onAccent: Int
) {
    companion object {
        // Same values as the app palette in ui/AppAppearance.kt (surface, text, muted, warning).
        fun of(dark: Boolean, accent: WidgetAccent): WidgetPalette = if (dark)
            WidgetPalette(0xFF271D2C.toInt(), 0xFFF8F2F8.toInt(), 0xFFCBC0CE.toInt(), 0xFFFFCB83.toInt(),
                accent.dark, 0xFF18131D.toInt())
        else
            WidgetPalette(0xFFFFFFFF.toInt(), 0xFF22262F.toInt(), 0xFF62636D.toInt(), 0xFF925000.toInt(),
                accent.light, 0xFFFFFFFF.toInt())

        fun alpha(opacityPercent: Int): Int = (opacityPercent.coerceIn(0, 100) * 255 + 50) / 100
    }
}

enum class FetchFailure { OFFLINE, SOURCE }

/** Last fetched data for one widget. Nothing in here is ever written back to the app's stores. */
data class WidgetSnapshot(
    val fetchedAt: Instant? = null,
    val departures: List<Departure> = emptyList(),
    /** Display-only trip refresh for the active widget; it never replaces the tracked ride. */
    val ride: Ride? = null,
    val failure: FetchFailure? = null,
    val failedAt: Instant? = null,
    /** A route lookup could not verify every candidate trip. */
    val partial: Boolean = false,
    val targetMissing: Boolean = false
)

enum class WidgetStatus { READY, NOT_CONFIGURED, TARGET_MISSING, LOADING, OFFLINE, STALE, ERROR, EMPTY }

/** All widget times are clock times: host/Doze delays cannot freeze a misleading live countdown. */
enum class TimeKind { LIVE, PROGNOSIS, SCHEDULE, CANCELLED }

sealed interface WidgetAction {
    /** Opens MainActivity with the documented widget extras. Never starts or saves a ride. */
    data class Open(val kind: WidgetKind, val id: String?, val action: String) : WidgetAction
    data object Configure : WidgetAction
}

data class WidgetRow(
    val badge: String?,
    val title: String,
    val time: String,
    val timeLabel: String,
    val timeKind: TimeKind,
    val detail: String?,
    val description: String,
    val action: WidgetAction,
    val sourceAt: Instant? = null
)

data class WidgetModel(
    val title: String,
    val status: WidgetStatus,
    val statusLine: String?,
    val rows: List<WidgetRow> = emptyList(),
    val message: String? = null,
    val open: WidgetAction,
    val mapAction: WidgetAction? = null,
    /** When the text changes without new data, e.g. a live countdown expiring. */
    val nextRenderAt: Instant? = null
) {
    val warning: Boolean get() = status in setOf(WidgetStatus.OFFLINE, WidgetStatus.STALE, WidgetStatus.ERROR,
        WidgetStatus.TARGET_MISSING)
}
