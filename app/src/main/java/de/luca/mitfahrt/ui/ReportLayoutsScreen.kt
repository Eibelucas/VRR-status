package de.luca.mitfahrt.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.luca.mitfahrt.reports.ReportLayout

/** Own report layouts, e.g. one per school. They change header and sections, never the facts. */
@Composable
internal fun ReportLayoutsScreen(layouts: List<ReportLayout>, selected: String?, onNew: () -> ReportLayout,
    onSave: (ReportLayout) -> Unit, onRemove: (String) -> Unit, onSelect: (String?) -> Unit, onBack: () -> Unit) {
    var editing by remember { mutableStateOf<ReportLayout?>(null) }
    BackHandler { if (editing != null) editing = null else onBack() }
    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (editing != null) editing = null else onBack() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück")
                }
                Text(if (editing != null) "Layout bearbeiten" else "Berichtslayouts", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() })
            }
            val draft = editing
            if (draft == null) LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
                item {
                    Text("Ein Layout legt Absender, Schule und Abschnitte fest. Inhalte und Quellenangaben bleiben gleich, " +
                        "der Hinweis auf einen privaten Bericht steht immer im PDF.", color = Soft, fontSize = 13.sp, lineHeight = 18.sp)
                }
                item {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onSelect(null) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected == null, onClick = { onSelect(null) })
                        Text("Standard", fontSize = 16.sp, modifier = Modifier.weight(1f))
                    }
                }
                items(layouts, key = { it.id }) { layout ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected == layout.id, onClick = { onSelect(layout.id) })
                        Column(Modifier.weight(1f).clickable { editing = layout }.padding(vertical = 8.dp)) {
                            Text(layout.name, fontSize = 16.sp)
                            Text(listOfNotNull(layout.school.ifBlank { null }, layout.sections.size.let { "$it Abschnitte" }).joinToString(" · "),
                                color = Soft, fontSize = 13.sp)
                        }
                        IconButton(onClick = { editing = layout }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.ChevronRight, "Bearbeiten", tint = Soft) }
                    }
                }
                if (layouts.size < ReportLayout.MAX_LAYOUTS) item {
                    OutlinedButton(onClick = { editing = onNew() }, shape = ControlShape, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp)) {
                        Text("Neues Layout")
                    }
                }
            } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { OutlinedTextField(draft.name, { editing = draft.copy(name = it.take(40)) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item {
                    OutlinedTextField(draft.senderLines.joinToString("\n"), { text -> editing = draft.copy(senderLines = text.split('\n').take(4).map { it.take(ReportLayout.MAX_LINE) }) },
                        label = { Text("Absender, bis zu 4 Zeilen") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
                }
                item { OutlinedTextField(draft.school, { editing = draft.copy(school = it.take(ReportLayout.MAX_LINE)) }, label = { Text("Schule (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(draft.schoolClass, { editing = draft.copy(schoolClass = it.take(30)) }, label = { Text("Klasse (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item { Text("Abschnitte", fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp).semantics { heading() }) }
                items(ReportLayout.SECTIONS.entries.toList(), key = { it.key }) { (key, label) ->
                    val checked = key in draft.sections
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Checkbox,
                        onValueChange = { on -> editing = draft.copy(sections = if (on) draft.sections + key else draft.sections - key) }),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = null); Spacer(Modifier.width(12.dp)); Text(label)
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(onClick = { onSave(draft); editing = null }, shape = ControlShape, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Speichern") }
                        if (layouts.any { it.id == draft.id }) OutlinedButton(onClick = { onRemove(draft.id); editing = null }, shape = ControlShape,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Löschen") }
                    }
                    Text("Bereits erstellte PDFs bleiben unverändert.", color = Soft, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}
