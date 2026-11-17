package de.luca.mitfahrt.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.selection.toggleable
import de.luca.mitfahrt.data.AppSettings
import de.luca.mitfahrt.data.TemplateSlot
import de.luca.mitfahrt.data.TextTemplates

/**
 * Editor for own texts built from placeholders. Each field shows a preview with example values;
 * an empty field keeps the built-in wording.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TemplateEditorScreen(settings: AppSettings, onSet: (String, String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val enabled = settings.bool("templates_enabled", false)
    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück") }
                Text("Eigene Texte", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = enabled, role = Role.Switch,
                        onValueChange = { onSet("templates_enabled", it.toString()) }), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Eigene Texte verwenden", fontSize = 16.sp)
                            Text("Ein leeres Feld behält den normalen Text.", color = Soft, fontSize = 13.sp)
                        }
                        Switch(checked = enabled, onCheckedChange = null)
                    }
                    Text("Platzhalter in geschweiften Klammern werden durch die Werte deiner Fahrt ersetzt. Teile trennst du mit „·“. " +
                        "Fehlt ein Wert gerade, fällt sein Teil weg.", color = Soft, fontSize = 13.sp, lineHeight = 18.sp)
                }
                TextTemplates.slots.groupBy { it.group }.forEach { (group, slots) ->
                    item(key = group) {
                        Text(group, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 16.dp).semantics { heading() })
                    }
                    slots.forEach { slot -> item(key = slot.key) { TemplateField(slot, settings.string(slot.key), enabled) { onSet(slot.key, it) } } }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateField(slot: TemplateSlot, stored: String, enabled: Boolean, onChange: (String) -> Unit) {
    var value by remember(slot.key) { mutableStateOf(TextFieldValue(stored, TextRange(stored.length))) }
    // A reset or an import changes the stored text from outside.
    LaunchedEffect(stored) { if (stored != value.text) value = TextFieldValue(stored, TextRange(stored.length)) }
    var focused by remember { mutableStateOf(false) }
    fun update(next: TextFieldValue) {
        val clean = TextTemplates.clean(next.text, slot.maxLength)
        value = if (clean == next.text) next else TextFieldValue(clean, TextRange(clean.length))
        onChange(clean)
    }
    val shown = value.text.ifBlank { slot.default }
    val unknown = TextTemplates.unknown(value.text)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(value = value, onValueChange = ::update, enabled = enabled, singleLine = true,
            label = { Text(slot.label) }, placeholder = { Text(slot.default) }, isError = unknown.isNotEmpty(),
            supportingText = { Text(if (unknown.isNotEmpty()) "Unbekannt: ${unknown.joinToString(", ") { "{$it}" }}"
                else "${value.text.length} von ${slot.maxLength} Zeichen") },
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused })
        Text("Vorschau: " + TextTemplates.render(shown, TextTemplates.sample).take(slot.maxLength).ifBlank { "leer" },
            fontSize = 14.sp, lineHeight = 19.sp)
        // The placeholder chips belong to the field being edited; showing them for all fields would bury the page.
        if (focused && enabled) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextTemplates.variables.forEach { variable ->
                AssistChip(onClick = {
                    val start = value.selection.min.coerceIn(0, value.text.length)
                    val end = value.selection.max.coerceIn(start, value.text.length)
                    val insert = "{${variable.name}}"
                    update(TextFieldValue(value.text.replaceRange(start, end, insert), TextRange(start + insert.length)))
                }, label = { Text(variable.label) }, modifier = Modifier.heightIn(min = 48.dp))
            }
            if (value.text.isNotEmpty()) TextButton(onClick = { update(TextFieldValue("")) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Zurücksetzen") }
        }
    }
}
