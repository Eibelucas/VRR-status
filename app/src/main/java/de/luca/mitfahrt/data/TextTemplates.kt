package de.luca.mitfahrt.data

/** One value that can be placed in an own text, e.g. `{linie}`. */
data class TemplateVariable(val name: String, val label: String, val sample: String)

/** A text the user can compose: where it appears and what it shows without an own template. */
data class TemplateSlot(val key: String, val group: String, val label: String, val default: String, val maxLength: Int = 120)

/**
 * Own texts from variables. A template is plain text with `{name}` placeholders. Parts are
 * separated by " · "; a part whose variable is empty right now is left out, so a missing live
 * time never shows as a gap or an invented value.
 */
object TextTemplates {
    const val SEPARATOR = " · "
    private val placeholder = Regex("\\{([a-zäöüß_0-9]{1,24})\\}")

    val variables = listOf(
        TemplateVariable("linie", "Linie", "017"),
        TemplateVariable("ziel", "Ausstieg", "Hbf"),
        TemplateVariable("richtung", "Richtung", "Rheydt Hbf"),
        TemplateVariable("einstieg", "Einstieg", "Bismarckplatz"),
        TemplateVariable("halt", "Nächster Halt", "Schillerplatz"),
        TemplateVariable("minuten", "Minuten (live)", "6"),
        TemplateVariable("berechnet", "Minuten (berechnet)", "5"),
        TemplateVariable("abfahrt", "Abfahrtszeit", "08:06"),
        TemplateVariable("ankunft", "Ankunftszeit", "08:18"),
        TemplateVariable("verspaetung", "Verspätung", "+2 Min"),
        TemplateVariable("quelle", "Datenquelle", "Live"),
        TemplateVariable("status", "Aktueller Hinweis", "Bus kommt in 6:00"),
        TemplateVariable("umstieg", "Umstieg", "Umstieg: RE8 um 08:20")
    )
    private val names = variables.map { it.name }.toSet()

    val slots = listOf(
        TemplateSlot("tpl_nowbar_title_wait", "Now Bar und Benachrichtigung", "Titel beim Warten", "{linie} → {ziel}"),
        TemplateSlot("tpl_nowbar_text_wait", "Now Bar und Benachrichtigung", "Text beim Warten", "{status} · {verspaetung} · {umstieg}"),
        TemplateSlot("tpl_nowbar_title_ride", "Now Bar und Benachrichtigung", "Titel im Bus", "{linie} → {ziel}"),
        TemplateSlot("tpl_nowbar_text_ride", "Now Bar und Benachrichtigung", "Text im Bus", "{status} · {verspaetung} · {umstieg}"),
        TemplateSlot("tpl_nowbar_chip", "Now Bar und Benachrichtigung", "Kurztext in der Now Bar", "{linie} · {minuten} Min", 24),
        TemplateSlot("tpl_mapwidget_title", "Widget Buskarte", "Titel", "{linie} · {ziel}", 60),
        TemplateSlot("tpl_mapwidget_status", "Widget Buskarte", "Zeile unter der Skizze", "{quelle} · noch etwa {berechnet} Min", 80)
    )

    val sample: Map<String, String> = variables.associate { it.name to it.sample }

    /** Names in [template] that are not known variables; shown to the user instead of being dropped silently. */
    fun unknown(template: String): List<String> = placeholder.findAll(template).map { it.groupValues[1] }.filter { it !in names }.distinct().toList()

    fun clean(template: String, maxLength: Int): String = template.replace(Regex("[\\n\\r\\t]+"), " ").take(maxLength)

    fun render(template: String, values: Map<String, String>): String =
        template.split(SEPARATOR.trim()).mapNotNull { part ->
            var missing = false
            val text = placeholder.replace(part) { match ->
                val value = values[match.groupValues[1]]?.trim().orEmpty()
                if (value.isEmpty()) missing = true
                value
            }.trim()
            text.takeIf { !missing && it.isNotEmpty() }
        }.joinToString(SEPARATOR)

    /** The own text for [slot], or null when own texts are off, the template is empty or nothing is left of it. */
    fun custom(settings: AppSettings, slot: String, values: Map<String, String>): String? {
        if (!settings.bool("templates_enabled", false)) return null
        val template = settings.string(slot).takeIf { it.isNotBlank() } ?: return null
        val limit = slots.firstOrNull { it.key == slot }?.maxLength ?: 120
        return render(template, values).take(limit).takeIf { it.isNotBlank() }
    }
}
