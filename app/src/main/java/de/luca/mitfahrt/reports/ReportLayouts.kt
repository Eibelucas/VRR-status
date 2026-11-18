package de.luca.mitfahrt.reports

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A reusable look for journey reports, e.g. for one school. It changes the header and which
 * sections appear; it never changes the facts, their sources or the private-report notice.
 */
data class ReportLayout(
    val id: String, val name: String,
    val senderLines: List<String> = emptyList(), val school: String = "", val schoolClass: String = "",
    val sections: Set<String> = SECTIONS.keys
) {
    companion object {
        val SECTIONS = linkedMapOf("summary" to "Zusammenfassung", "timeline" to "Verlauf", "connection" to "Haltestellen und Zeiten", "sources" to "Datenquellen")
        const val MAX_LAYOUTS = 12
        const val MAX_LINE = 80
    }

    fun clean(): ReportLayout = copy(name = name.trim().take(40).ifBlank { "Layout" },
        senderLines = senderLines.map { it.replace(Regex("\\s+"), " ").trim().take(MAX_LINE) }.filter { it.isNotEmpty() }.take(4),
        school = school.trim().take(MAX_LINE), schoolClass = schoolClass.trim().take(30),
        sections = sections.intersect(SECTIONS.keys).ifEmpty { setOf("summary") })
}

object ReportLayouts {
    fun encode(layouts: List<ReportLayout>, selected: String?): String = JSONObject().apply {
        put("version", 1); put("selected", selected ?: JSONObject.NULL)
        put("layouts", JSONArray(layouts.map { layout -> JSONObject().apply {
            put("id", layout.id); put("name", layout.name); put("sender", JSONArray(layout.senderLines))
            put("school", layout.school); put("class", layout.schoolClass); put("sections", JSONArray(layout.sections.toList()))
        } }))
    }.toString()

    fun decode(value: String?): Pair<List<ReportLayout>, String?> = runCatching {
        val root = JSONObject(value ?: return emptyList<ReportLayout>() to null)
        val array = root.getJSONArray("layouts")
        val layouts = (0 until minOf(array.length(), ReportLayout.MAX_LAYOUTS)).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").takeIf { it.matches(Regex("[0-9a-f-]{8,36}")) } ?: return@mapNotNull null
            fun strings(key: String) = item.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            ReportLayout(id, item.optString("name"), strings("sender"), item.optString("school"), item.optString("class"),
                strings("sections").toSet()).clean()
        }.distinctBy { it.id }
        layouts to root.optString("selected").takeIf { id -> layouts.any { it.id == id } }
    }.getOrDefault(emptyList<ReportLayout>() to null)

    /** Applies a layout on top of the existing report options. */
    fun apply(base: ReportCustomization, layout: ReportLayout?): ReportCustomization = if (layout == null) base else base.copy(
        senderLines = layout.senderLines, school = layout.school,
        schoolClass = layout.schoolClass.ifBlank { base.schoolClass }, sections = layout.sections, layoutName = layout.name)
}

class ReportLayoutStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("report_layouts", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(ReportLayouts.decode(prefs.getString("state", null)))
    /** Layouts and the id of the chosen one (null = standard report). */
    val state: StateFlow<Pair<List<ReportLayout>, String?>> = mutable.asStateFlow()
    val selected: ReportLayout? get() = state.value.let { (layouts, id) -> layouts.firstOrNull { it.id == id } }

    @Synchronized private fun write(layouts: List<ReportLayout>, selected: String?) {
        val value = layouts.take(ReportLayout.MAX_LAYOUTS) to selected?.takeIf { id -> layouts.any { it.id == id } }
        prefs.edit().putString("state", ReportLayouts.encode(value.first, value.second)).apply()
        mutable.value = value
    }
    fun save(layout: ReportLayout) = state.value.let { (layouts, selected) ->
        val clean = layout.clean()
        write(if (layouts.any { it.id == clean.id }) layouts.map { if (it.id == clean.id) clean else it } else layouts + clean, selected)
    }
    fun remove(id: String) = state.value.let { (layouts, selected) -> write(layouts.filterNot { it.id == id }, selected.takeUnless { it == id }) }
    fun select(id: String?) = write(state.value.first, id)
    fun newLayout(): ReportLayout = ReportLayout(UUID.randomUUID().toString(), "Schule")
    fun replaceAll(layouts: List<ReportLayout>, selected: String?) = write(layouts.map { it.clean() }, selected)
}
