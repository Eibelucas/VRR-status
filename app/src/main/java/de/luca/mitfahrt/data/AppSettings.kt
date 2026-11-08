package de.luca.mitfahrt.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

enum class SettingKind { BOOLEAN, CHOICE, NUMBER, TEXT }
data class SettingChoice(val value: String, val label: String)
data class SettingDefinition(
    val key: String, val title: String, val group: String, val default: String,
    val kind: SettingKind, val choices: List<SettingChoice> = emptyList(),
    val range: IntRange = 0..100, val advanced: Boolean = false, val hint: String = ""
) {
    fun clean(value: String): String? {
        val text = value.trim()
        if (text.length > 4096 || text.any { it == '\u0000' }) return null
        return when (kind) {
            SettingKind.BOOLEAN -> text.takeIf { it == "true" || it == "false" }
            SettingKind.CHOICE -> text.takeIf { v -> choices.any { it.value == v } }
            SettingKind.NUMBER -> text.toIntOrNull()?.takeIf { it in range }?.toString()
            SettingKind.TEXT -> when (key) {
                "quiet_start", "quiet_end" -> runCatching { LocalTime.parse(text).toString() }.getOrNull()
                "routes_free_dates" -> runCatching {
                    text.split(',').map(String::trim).filter(String::isNotEmpty).distinct().take(365)
                        .map { LocalDate.parse(it).toString() }.joinToString(",")
                }.getOrNull()
                "alert_leads" -> text.split(',').mapNotNull { it.trim().toIntOrNull() }
                    .filter { it in listOf(60, 120, 180, 300) }.distinct().sortedDescending()
                    .takeIf { it.isNotEmpty() }?.joinToString(",")
                "accent_hex" -> text.takeIf { Regex("#[0-9a-fA-F]{6}").matches(it) }
                "home_cards" -> text.split(',').map(String::trim)
                    .filter { it in listOf("planned", "nearby", "favorites", "suggestions", "school", "last", "map") }
                    .distinct().joinToString(",")
                "nav_order" -> text.split(',').map(String::trim).filter { it in listOf("ride", "stops", "reports", "settings") }
                    .distinct().let { (it + listOf("ride", "stops", "reports", "settings")).distinct().joinToString(",") }
                "notification_actions" -> text.split(',').map(String::trim).filter { it in listOf("board", "arrival", "stop", "map") }
                    .distinct().take(3).joinToString(",")
                "watch_actions" -> text.split(',').map(String::trim).filter { it in listOf("board", "arrival", "target", "stop") }
                    .distinct().joinToString(",")
                "report_fields" -> (text.split(',').map(String::trim).filter { it in listOf("line", "route", "date", "operator_times", "own_arrival", "sources") } + "sources")
                    .distinct().joinToString(",")
                else -> text
            }
        }
    }
}

/** One catalog is used for validation, search, reset and the controls themselves. */
object SettingsCatalog {
    val groups = linkedMapOf(
        "home" to "Startseite", "routes" to "Routen & Alltag", "live" to "Abfahrten & Live-Daten",
        "map" to "Buskarte", "alerts" to "Hinweise & Vibration", "nowbar" to "Now Bar & Widgets",
        "watch" to "Watch", "reports" to "Ankunft & Belege", "automation" to "Erkennung & Vorschläge",
        "appearance" to "Darstellung", "privacy" to "Daten & Datenschutz", "updates" to "Updates"
    )
    private fun b(key: String, title: String, group: String, default: Boolean, advanced: Boolean = false, hint: String = "") =
        SettingDefinition(key, title, group, default.toString(), SettingKind.BOOLEAN, advanced = advanced, hint = hint)
    private fun c(key: String, title: String, group: String, default: String, vararg choices: Pair<String, String>, advanced: Boolean = false) =
        SettingDefinition(key, title, group, default, SettingKind.CHOICE, choices.map { SettingChoice(it.first, it.second) }, advanced = advanced)
    private fun n(key: String, title: String, group: String, default: Int, range: IntRange, advanced: Boolean = false, hint: String = "") =
        SettingDefinition(key, title, group, default.toString(), SettingKind.NUMBER, range = range, advanced = advanced, hint = hint)
    private fun t(key: String, title: String, group: String, default: String = "", advanced: Boolean = true, hint: String = "") =
        SettingDefinition(key, title, group, default, SettingKind.TEXT, advanced = advanced, hint = hint)

    val definitions: List<SettingDefinition> = listOf(
        c("start_page", "Beim Öffnen anzeigen", "home", "ride", "ride" to "Übersicht", "stops" to "Haltestellen", "routes" to "Eigene Routen", "map" to "Karte"),
        t("home_cards", "Startkarten & Reihenfolge", "home", "planned,nearby,favorites,suggestions,school,last", false, "Reihenfolge auswählen; nicht gewählte Karten bleiben verborgen."),
        n("nearby_radius", "Suchradius in Metern", "home", 500, 100..3000),
        b("nearby_expand", "Suchradius bei Bedarf erweitern", "home", true),
        n("departure_count", "Abfahrten pro Haltestelle", "home", 5, 1..15),
        c("stop_sort", "Haltestellen sortieren", "home", "distance", "distance" to "Entfernung", "departure" to "Nächste Abfahrt", "manual" to "Eigene Reihenfolge"),
        b("manual_only", "Nur eigene Haltestellen & Routen", "home", false),
        t("excluded_stops", "Ausgeblendete Haltestellen", "home", hint = "Haltestellenkennungen, durch Kommas getrennt."),
        t("preferred_stops", "Bevorzugte Haltestellen", "home", hint = "Haltestellenkennungen in der gewünschten Reihenfolge."),
        b("routes_school_paused", "Schulrouten pausieren", "routes", false),
        t("routes_free_dates", "Eigene freie Tage", "routes", hint = "Datum als JJJJ-MM-TT, mehrere Tage durch Kommas getrennt."),
        n("routes_default_walk_minutes", "Fußweg in Minuten", "routes", 5, 0..60),
        n("routes_default_buffer_minutes", "Zeitpuffer in Minuten", "routes", 2, 0..30),
        n("routes_visible_count", "Routen auf der Startseite", "routes", 3, 1..24, true),
        c("refresh_mode", "Aktualisierung", "live", "balanced", "frequent" to "Häufig", "balanced" to "Ausgewogen", "saving" to "Sparsam"),
        c("refresh_waiting", "Beim Warten aktualisieren", "live", "15", "15" to "Alle 15 Sekunden", "30" to "Alle 30 Sekunden", "60" to "Jede Minute", "120" to "Alle 2 Minuten", advanced = true),
        c("refresh_riding", "Unterwegs aktualisieren", "live", "30", "15" to "Alle 15 Sekunden", "30" to "Alle 30 Sekunden", "60" to "Jede Minute", "120" to "Alle 2 Minuten", advanced = true),
        c("time_display", "Abfahrtszeit anzeigen", "live", "both", "minutes" to "Restminuten", "clock" to "Uhrzeit", "both" to "Beides"),
        c("delay_display", "Verspätung anzeigen", "live", "minutes", "minutes" to "+ Minuten", "times" to "Plan & Prognose"),
        b("live_only", "Abfahrten ohne Live-Zeit ausblenden", "live", false),
        b("show_cancelled", "Ausgefallene Fahrten anzeigen", "live", true),
        t("line_allow", "Erlaubte Linien", "live", hint = "Linien durch Kommas trennen, z. B. 007, 017."),
        t("line_block", "Ausgeblendete Linien", "live", hint = "Linien durch Kommas trennen."),
        c("transport_filter", "Verkehrsmittel", "live", "all", "all" to "Alle", "bus" to "Bus", "rail" to "Bahn"),
        c("data_detail", "Quellenanzeige", "live", "compact", "compact" to "Kompakt", "full" to "Ausführlich", advanced = true),
        b("map_waiting", "Karte beim Warten", "map", true),
        b("map_onboard", "Karte während der Fahrt", "map", true),
        b("map_home", "Karte auf der Startseite", "map", false),
        c("map_camera", "Kartenausschnitt", "map", "bus", "bus" to "Bus folgen", "boarding" to "Einstieg", "route" to "Strecke"),
        n("map_zoom", "Standardzoom", "map", 15, 10..19, true),
        c("map_follow_resume", "Bus nach Verschieben wieder folgen", "map", "30", "0" to "Nur manuell", "15" to "Nach 15 Sekunden", "30" to "Nach 30 Sekunden", "60" to "Nach einer Minute", advanced = true),
        b("map_stops", "Haltestellen auf der Karte", "map", true),
        b("map_route", "Verfügbaren Linienverlauf anzeigen", "map", true),
        b("map_other_buses", "Andere passende Busse anzeigen", "map", false, true),
        c("map_theme", "Kartendarstellung", "map", "system", "system" to "Wie App", "light" to "Hell", "dark" to "Dunkel"),
        c("map_external", "Externe Karten-App", "map", "chooser", "chooser" to "Jedes Mal wählen", "google" to "Google Maps", "osm" to "OpenStreetMap"),
        c("map_cache_mb", "Kartenspeicher", "map", "64", "16" to "16 MB", "32" to "32 MB", "64" to "64 MB", "128" to "128 MB", advanced = true),
        c("map_size", "Kartenhöhe", "map", "normal", "normal" to "Normal", "large" to "Groß"),
        b("alert_arrival", "Bus kommt", "alerts", true),
        t("alert_leads", "Vorwarnungen in Sekunden", "alerts", "60", false, "60, 120, 180 oder 300; mehrere Werte durch Kommas trennen."),
        b("alert_walking", "Ans Losgehen erinnern", "alerts", false),
        b("alert_delay", "Neue Verspätungen melden", "alerts", false),
        n("delay_threshold", "Verspätung melden ab Minuten", "alerts", 5, 1..60),
        n("delay_change", "Erneut melden bei Änderung um Minuten", "alerts", 3, 1..30, true),
        b("alert_cancelled", "Ausfall melden", "alerts", true),
        b("alert_exit", "Ans Aussteigen erinnern", "alerts", true),
        c("alert_target", "Vibration auf", "alerts", "both", "phone" to "Handy", "watch" to "Watch", "both" to "Beiden Geräten", "auto" to "Watch, wenn verbunden"),
        c("vibration_arrival", "Vibration: Bus kommt", "alerts", "double", "short" to "Kurz", "double" to "Zweimal", "long" to "Lang", "off" to "Aus", advanced = true),
        c("vibration_exit", "Vibration: Aussteigen", "alerts", "long", "short" to "Kurz", "double" to "Zweimal", "long" to "Lang", "off" to "Aus", advanced = true),
        c("vibration_walking", "Vibration: Losgehen", "alerts", "short", "short" to "Kurz", "double" to "Zweimal", "long" to "Lang", "off" to "Aus", advanced = true),
        c("vibration_delay", "Vibration: Verspätung", "alerts", "double", "short" to "Kurz", "double" to "Zweimal", "long" to "Lang", "off" to "Aus", advanced = true),
        c("vibration_cancelled", "Vibration: Ausfall", "alerts", "long", "short" to "Kurz", "double" to "Zweimal", "long" to "Lang", "off" to "Aus", advanced = true),
        b("quiet_enabled", "Ruhezeiten", "alerts", false),
        t("quiet_start", "Ruhezeit ab", "alerts", "22:00", false, "Stunden:Minuten"),
        t("quiet_end", "Ruhezeit bis", "alerts", "07:00", false, "Stunden:Minuten"),
        b("quiet_critical", "Wichtige Störungen in Ruhezeiten melden", "alerts", false, true),
        b("nowbar_enabled", "Now Bar verwenden", "nowbar", true),
        c("nowbar_content", "Now-Bar-Anzeige", "nowbar", "full", "full" to "Alle Fahrtdetails", "line" to "Linie & Ziel", "time" to "Zeit", "next" to "Nächster Halt"),
        t("notification_actions", "Aktionen in der Benachrichtigung", "nowbar", "board,arrival,stop", hint = "board, arrival, map, stop; maximal drei Aktionen."),
        b("lockscreen_details", "Fahrtdetails auf dem Sperrbildschirm", "nowbar", true),
        c("widget_kind", "Widget-Inhalt", "nowbar", "active", "stop" to "Haltestelle", "route" to "Eigene Route", "active" to "Aktive Fahrt"),
        t("widget_stop_id", "Widget-Haltestelle", "nowbar", hint = "Kennung einer gespeicherten Haltestelle."),
        t("widget_route_id", "Widget-Route", "nowbar", hint = "Kennung einer eigenen Route."),
        c("widget_action", "Beim Antippen des Widgets", "nowbar", "preview", "preview" to "Vorschau", "departures" to "Haltestellen", "ride" to "Aktive Fahrt", "map" to "Karte"),
        c("widget_theme", "Widget-Darstellung", "nowbar", "system", "system" to "Wie App", "dark" to "Dunkel", "light" to "Hell"),
        n("widget_opacity", "Widget-Deckkraft in Prozent", "nowbar", 100, 20..100, true),
        n("widget_count", "Abfahrten im Widget", "nowbar", 3, 1..5, true),
        c("watch_start", "Watch-Startansicht", "watch", "active", "active" to "Aktive Fahrt", "routes" to "Eigene Routen", "favorites" to "Favoriten"),
        c("watch_detail", "Watch-Anzeige", "watch", "detail", "large" to "Große Zeit", "detail" to "Ausführlich"),
        c("watch_tile", "Watch-Kachel", "watch", "active", "active" to "Aktive Fahrt", "route" to "Nächste Route", "stop" to "Haltestelle"),
        t("watch_tile_id", "Route oder Haltestelle für Watch-Kachel", "watch"),
        t("watch_actions", "Watch-Schnellaktionen", "watch", "board,arrival,stop", hint = "board, arrival, target, stop"),
        b("watch_show_connection", "Verbindungsstatus anzeigen", "watch", true),
        b("watch_avoid_duplicates", "Doppelte Erinnerungen vermeiden", "watch", false),
        b("arrival_auto", "Eigene Ankunft erkennen", "reports", false),
        n("arrival_radius", "Ankunftsradius in Metern", "reports", 100, 25..500),
        b("arrival_confirm", "Erkannte Ankunft bestätigen", "reports", true),
        b("arrival_store", "Eigene Ankunft speichern", "reports", true),
        b("report_offer", "Beleg bei Verspätung vorschlagen", "reports", true),
        n("report_offer_threshold", "Beleg vorschlagen ab Minuten", "reports", 5, 1..60),
        b("report_personal", "Name & Klasse in Belegen", "reports", false),
        t("report_name", "Name im Beleg", "reports", advanced = false),
        t("report_class", "Klasse im Beleg", "reports", advanced = false),
        c("report_style", "PDF-Darstellung", "reports", "compact", "compact" to "Kompakt", "full" to "Ausführlich"),
        t("report_note", "Zusatznotiz im Beleg", "reports"),
        t("report_fields", "Inhalte des Belegs", "reports", "line,route,date,operator_times,own_arrival,sources", hint = "line, route, date, operator_times, own_arrival, sources"),
        t("report_filename", "Beleg-Dateiname", "reports", "Mitfahrt", hint = "Dateinamen beginnen mit diesem Text."),
        n("report_retention_days", "Belege nach Tagen löschen", "reports", 0, 0..365, true, "0 = behalten"),
        n("arrival_retention_days", "Eigene Ankunft nach Tagen löschen", "reports", 0, 0..365, true, "0 = behalten"),
        b("wifi_detection", "NEW-Hotspot erkennen", "automation", true),
        t("wifi_names", "Bekannte Bus-WLANs", "automation", "NEW-Hotspot", hint = "Namen durch Kommas trennen."),
        b("wifi_confirm", "Hinweis zur WLAN-Erkennung anzeigen", "automation", true),
        b("learn_stops", "Haltestellen vorschlagen", "automation", true),
        b("learn_routes", "Strecken vorschlagen", "automation", true),
        c("suggestion_threshold", "Vorschläge nach Aufrufen", "automation", "3", "3" to "3 Aufrufe", "5" to "5 Aufrufe", "10" to "10 Aufrufe"),
        c("theme", "Helligkeit", "appearance", "system", "system" to "Wie Android", "light" to "Hell", "dark" to "Dunkel"),
        c("accent", "Farben", "appearance", "new", "new" to "NEW", "system" to "Android", "custom" to "Eigene Akzentfarbe"),
        t("accent_hex", "Eigene Akzentfarbe", "appearance", "#B40055", false, "Hex-Farbe, z. B. #B40055"),
        b("oled", "Schwarzer Hintergrund", "appearance", false),
        c("density", "Informationsdichte", "appearance", "normal", "compact" to "Kompakt", "normal" to "Normal", "comfortable" to "Großzügig"),
        n("font_scale", "Schriftgröße in Prozent", "appearance", 100, 85..150),
        b("high_contrast", "Hoher Kontrast", "appearance", false),
        b("reduce_motion", "Weniger Animationen", "appearance", false),
        c("surface_style", "Kartenstil", "appearance", "flat", "flat" to "Flächen", "outlined" to "Umrandungen"),
        t("nav_order", "Navigation & Reihenfolge", "appearance", "ride,stops,reports,settings", hint = "ride, stops, reports, settings"),
        c("location_mode", "Standort verwenden", "privacy", "active", "off" to "Aus", "foreground" to "Bei geöffneter App", "active" to "Auch während aktiver Fahrten"),
        b("history_enabled", "Fahrthistorie speichern", "privacy", true),
        n("history_days", "Fahrthistorie nach Tagen löschen", "privacy", 0, 0..365, true, "0 = behalten"),
        b("export_personal", "Persönliche Angaben in Sicherungen", "privacy", false, true),
        c("update_check", "Nach Updates suchen", "updates", "open", "manual" to "Nur manuell", "open" to "Beim Öffnen", "daily" to "Täglich", "weekly" to "Wöchentlich"),
        b("update_wifi_only", "APK nur über WLAN herunterladen", "updates", false),
        b("update_show_changes", "Änderungen vor dem Update anzeigen", "updates", true)
    )
    val byKey = definitions.associateBy { it.key }
    fun clean(values: Map<String, String>): Map<String, String> = values.entries.mapNotNull { (key, value) ->
        byKey[key]?.clean(value)?.let { key to it }
    }.toMap()
}

data class SettingsProfile(
    val id: String, val name: String, val values: Map<String, String> = emptyMap(),
    val scheduled: Boolean = false, val days: Set<Int> = (1..5).toSet(),
    val startMinute: Int = 420, val endMinute: Int = 900
) {
    fun active(now: Instant): Boolean = scheduled && RouteWindow(days, startMinute, endMinute).active(now)
}

data class AppSettings(
    val values: Map<String, String> = emptyMap(),
    val profiles: List<SettingsProfile> = emptyList(),
    val manualProfileId: String = "base", val activeProfileId: String? = null,
    val recentKeys: List<String> = emptyList(), val advanced: Boolean = false
) {
    fun string(key: String, default: String = ""): String = values[key] ?: SettingsCatalog.byKey[key]?.default ?: default
    fun bool(key: String, default: Boolean = false): Boolean = string(key, default.toString()).toBooleanStrictOrNull() ?: default
    fun int(key: String, default: Int = 0): Int = string(key, default.toString()).toIntOrNull() ?: default
    fun csv(key: String): List<String> = string(key).split(',').map(String::trim).filter(String::isNotEmpty)
    val activeProfileName: String get() = profiles.firstOrNull { it.id == activeProfileId }?.name ?: "Standard"
}

object AppSettingsCodec {
    const val MAX_PROFILES = 12
    const val MAX_BYTES = 1_048_576
    fun encode(base: Map<String, String>, profiles: List<SettingsProfile>, manual: String, recent: List<String>, advanced: Boolean): String = JSONObject().apply {
        put("version", 1); put("values", JSONObject(SettingsCatalog.clean(base)))
        put("manual", manual); put("recent", JSONArray(recent.filter { it in SettingsCatalog.byKey }.distinct().take(12)))
        put("advanced", advanced)
        put("profiles", JSONArray(profiles.take(MAX_PROFILES).map { profile -> JSONObject().apply {
            put("id", profile.id); put("name", profile.name.take(40)); put("values", JSONObject(SettingsCatalog.clean(profile.values)))
            put("scheduled", profile.scheduled); put("days", JSONArray(profile.days.sorted()))
            put("start", profile.startMinute); put("end", profile.endMinute)
        } }))
    }.toString()

    fun decode(value: String, now: Instant = Instant.now()): AppSettings {
        require(value.toByteArray().size <= MAX_BYTES)
        val json = JSONObject(value)
        require(json.optInt("version", 1) == 1)
        fun values(obj: JSONObject?): Map<String, String> = if (obj == null) emptyMap() else
            SettingsCatalog.clean(obj.keys().asSequence().associateWith { obj.optString(it) })
        val base = values(json.optJSONObject("values"))
        val array = json.optJSONArray("profiles") ?: JSONArray()
        val profiles = (0 until minOf(array.length(), MAX_PROFILES)).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("id").takeIf { Regex("[a-zA-Z0-9_-]{1,80}").matches(it) } ?: return@mapNotNull null
            val name = obj.optString("name").trim().take(40).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val weekdays = obj.optJSONArray("days") ?: JSONArray((1..5).toList())
            val days = (0 until weekdays.length()).map { weekdays.optInt(it) }.filter { it in 1..7 }.toSet()
            val start = obj.optInt("start", 420).coerceIn(0, 1439)
            val end = obj.optInt("end", 900).coerceIn(0, 1439)
            SettingsProfile(id, name, values(obj.optJSONObject("values")), obj.optBoolean("scheduled", false), days, start, end)
        }.distinctBy { it.id }
        val manual = json.optString("manual", "base").takeIf { it == "base" || it == "auto" || profiles.any { p -> p.id == it } } ?: "base"
        val active = when (manual) { "base" -> null; "auto" -> profiles.firstOrNull { it.active(now) }?.id; else -> manual }
        val recent = json.optJSONArray("recent") ?: JSONArray()
        return AppSettings(base + profiles.firstOrNull { it.id == active }?.values.orEmpty(), profiles, manual, active,
            (0 until recent.length()).map { recent.optString(it) }.filter { it in SettingsCatalog.byKey }.distinct().take(12),
            json.optBoolean("advanced", false))
    }
    internal fun base(json: String): Map<String, String> = JSONObject(json).optJSONObject("values")?.let { obj ->
        SettingsCatalog.clean(obj.keys().asSequence().associateWith { obj.optString(it) })
    }.orEmpty()
}

class AppSettingsStore(context: Context, private val clock: () -> Instant = Instant::now) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }
    init {
        migrateLegacy()
        mutable.value = read()
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }
    fun current(): AppSettings = read().also { mutable.value = it }
    fun refresh() { mutable.value = read() }
    fun close() { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    @Synchronized fun set(key: String, value: String): Boolean {
        val clean = SettingsCatalog.byKey[key]?.clean(value) ?: return false
        val old = read(); val base = rawBase()
        val active = old.activeProfileId
        val profiles = old.profiles.map { if (it.id == active) it.copy(values = it.values + (key to clean)) else it }
        persist(if (active == null) base + (key to clean) else base, profiles, old.manualProfileId,
            (listOf(key) + old.recentKeys).distinct().take(12), old.advanced)
        return true
    }
    @Synchronized fun setAdvanced(enabled: Boolean) { val old = read(); persist(rawBase(), old.profiles, old.manualProfileId, old.recentKeys, enabled) }
    @Synchronized fun selectProfile(id: String) {
        val old = read()
        if (id != "base" && id != "auto" && old.profiles.none { it.id == id }) return
        persist(rawBase(), old.profiles, id, old.recentKeys, old.advanced)
    }
    @Synchronized fun addProfile(name: String): String? {
        val old = read(); val clean = name.trim().take(40)
        if (clean.isEmpty() || old.profiles.size >= AppSettingsCodec.MAX_PROFILES) return null
        val id = UUID.randomUUID().toString()
        persist(rawBase(), old.profiles + SettingsProfile(id, clean), id, old.recentKeys, old.advanced)
        return id
    }
    @Synchronized fun updateProfile(profile: SettingsProfile): Boolean {
        val old = read()
        if (profile.name.isBlank() || profile.name.length > 40 || !RouteWindow(profile.days, profile.startMinute, profile.endMinute).valid() ||
            old.profiles.none { it.id == profile.id }) return false
        persist(rawBase(), old.profiles.map { if (it.id == profile.id) profile.copy(values = SettingsCatalog.clean(profile.values)) else it },
            old.manualProfileId, old.recentKeys, old.advanced)
        return true
    }
    @Synchronized fun deleteProfile(id: String) {
        val old = read()
        persist(rawBase(), old.profiles.filterNot { it.id == id }, if (old.manualProfileId == id) "base" else old.manualProfileId,
            old.recentKeys, old.advanced)
    }
    @Synchronized fun resetGroup(group: String) {
        val old = read(); val keys = SettingsCatalog.definitions.filter { it.group == group }.map { it.key }.toSet()
        if (old.activeProfileId == null) persist(rawBase() - keys, old.profiles, old.manualProfileId, old.recentKeys - keys, old.advanced)
        else persist(rawBase(), old.profiles.map { if (it.id == old.activeProfileId) it.copy(values = it.values - keys) else it },
            old.manualProfileId, old.recentKeys - keys, old.advanced)
    }
    fun exportSettings(includePersonal: Boolean = false): String {
        val old = read()
        val excluded = if (includePersonal) emptySet() else setOf("report_name", "report_class", "report_note")
        return AppSettingsCodec.encode(rawBase() - excluded, old.profiles.map { it.copy(values = it.values - excluded) },
            "base", emptyList(), old.advanced)
    }
    @Synchronized fun importSettings(value: String): Boolean = runCatching {
        val parsed = AppSettingsCodec.decode(value, clock())
        val base = AppSettingsCodec.base(value)
        persist(base, parsed.profiles, "base", emptyList(), parsed.advanced)
        true
    }.getOrDefault(false)
    private fun raw(): String = runCatching { prefs.getString("state", null) }.getOrNull() ?: "{\"version\":1}"
    private fun rawBase(): Map<String, String> = runCatching { AppSettingsCodec.base(raw()) }.getOrDefault(emptyMap())
    private fun read(): AppSettings = runCatching { AppSettingsCodec.decode(raw(), clock()) }.getOrDefault(AppSettings())
    private fun persist(base: Map<String, String>, profiles: List<SettingsProfile>, manual: String, recent: List<String>, advanced: Boolean) {
        prefs.edit().putString("state", AppSettingsCodec.encode(base, profiles, manual, recent, advanced)).apply()
        mutable.value = read()
    }
    private fun migrateLegacy() {
        if (prefs.contains("state")) return
        val legacy = app.getSharedPreferences("journey_alert_options", Context.MODE_PRIVATE)
        val values = mutableMapOf<String, String>()
        listOf("arrival" to "alert_arrival", "exit" to "alert_exit").forEach { (old, new) ->
            if (legacy.contains(old)) runCatching { legacy.getBoolean(old, true) }.getOrNull()?.let { values[new] = it.toString() }
        }
        if (legacy.contains("lead_seconds")) runCatching { legacy.getInt("lead_seconds", 60) }.getOrNull()
            ?.takeIf { it in listOf(60, 120, 180, 300) }?.let { values["alert_leads"] = it.toString() }
        app.getSharedPreferences("appearance", Context.MODE_PRIVATE).let { appearance ->
            if (appearance.contains("mode")) runCatching { appearance.getString("mode", null) }.getOrNull()
                ?.takeIf { it in listOf("system", "dark", "light") }?.let { values["theme"] = it }
        }
        prefs.edit().putString("state", AppSettingsCodec.encode(values, emptyList(), "base", emptyList(), false)).apply()
    }
    companion object { const val PREFERENCES = "app_customization" }
}
