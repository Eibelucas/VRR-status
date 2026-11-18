package de.luca.mitfahrt.data

import org.json.JSONObject
import java.util.Locale

/** Palette for line badges. Saved route colours belong to RoutePlan and travel with that route. */
object TransitColors {
    val palette = listOf("Beere" to "#B40055", "Grün" to "#16804A", "Blau" to "#2F5DA8",
        "Violett" to "#7C428A", "Orange" to "#B65A00", "Graphit" to "#333333")

    fun decode(text: String): Map<String, String> {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap()
        return json.keys().asSequence().mapNotNull { line ->
            val key = TripResultPreferences.lines(line).singleOrNull()?.let(TripResultPreferences::lineKey)
                ?: return@mapNotNull null
            val color = json.optString(line).uppercase(Locale.ROOT)
            if (Regex("#[0-9A-F]{6}").matches(color)) key to color else null
        }.take(40).toMap()
    }

    fun encode(colors: Map<String, String>): String = if (colors.isEmpty()) "" else JSONObject(colors).toString()
}
