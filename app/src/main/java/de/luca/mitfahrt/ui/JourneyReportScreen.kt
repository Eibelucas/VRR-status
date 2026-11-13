package de.luca.mitfahrt.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.luca.mitfahrt.reports.*
import java.time.Instant

/** Own statements offered as one tap; anything else can be typed. */
private val observationPresets = listOf("Bus ist nicht gekommen", "Bus ist durchgefahren", "Bus war zu voll")

/**
 * The whole connection as one report: summary first, then the course of events, then the stops.
 * It shows the same blocks the PDF is made of. Nothing leaves the device before "Teilen".
 */
@Composable
internal fun JourneyReportScreen(
    report: JourneyReport, options: ReportCustomization, now: Instant, busy: Boolean,
    onBack: () -> Unit, onShare: () -> Unit, onSave: () -> Unit, onObserve: (String) -> Unit,
    onRemoveObservation: (String) -> Unit, onShareExport: (ReportExport) -> Unit, onDelete: () -> Unit
) {
    BackHandler(onBack = onBack)
    var note by rememberSaveable { mutableStateOf("") }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val running = report.state == ReportState.RUNNING
    val blocks = remember(report, options, running) { JourneyReportText.build(report, options, if (running) now else report.updatedAt) }
    Surface(Modifier.fillMaxSize(), color = Ink) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück") }
                Text("Fahrtbericht", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.DeleteOutline, "Bericht löschen", tint = Soft)
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
                blocks.forEachIndexed { index, block -> item(key = index) { ReportBlockView(block) } }
                item(key = "own") {
                    ReportHeading("Eigene Beobachtung")
                    val own = report.events(ReportEventType.OBSERVATION)
                    own.forEach { event ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${JourneyReport.clock(event.at)}  ${event.text}", modifier = Modifier.weight(1f), fontSize = 15.sp)
                            IconButton(onClick = { onRemoveObservation(event.id) }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.Close, "Beobachtung „${event.text}“ entfernen", tint = Soft)
                            }
                        }
                    }
                    Text("Steht im Bericht als deine eigene Angabe, mit der Uhrzeit des Eintrags.", color = Soft, fontSize = 13.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        observationPresets.filter { preset -> own.none { it.text == preset } }.forEach { preset ->
                            AssistChip(onClick = { onObserve(preset) }, label = { Text(preset) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = note, onValueChange = { note = it.take(300) }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Eigene Beobachtung") }, minLines = 1, maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (note.isNotBlank()) { onObserve(note); note = "" } }),
                        trailingIcon = { if (note.isNotBlank()) TextButton(onClick = { onObserve(note); note = "" }) { Text("Eintragen") } })
                }
                if (report.exports.isNotEmpty()) item(key = "exports") {
                    ReportHeading("Erstellte PDFs")
                    Text("Eine erstellte PDF bleibt, wie sie ist. Für neuere Angaben erstellst du eine weitere.", color = Soft, fontSize = 13.sp, lineHeight = 18.sp)
                    report.exports.sortedByDescending { it.number }.forEach { export ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("PDF ${export.number}" + if (export.preliminary) " · vorläufig" else "", fontWeight = FontWeight.Medium)
                                Text("${JourneyReport.day(export.at)}, ${JourneyReport.clock(export.at)} Uhr", color = Soft, fontSize = 13.sp)
                            }
                            TextButton(onClick = { onShareExport(export) }, enabled = !busy) { Text("Teilen") }
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .4f))
            // With a large font both labels do not fit side by side on a narrow phone: stack them.
            val shareLabel = if (busy) "PDF wird erstellt" else if (running) "Vorläufig teilen" else "Als PDF teilen"
            if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.25f) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onShare, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = ControlShape) { Text(shareLabel) }
                    OutlinedButton(onClick = onSave, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = ControlShape) { Text("Speichern") }
                }
            } else Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onSave, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = ControlShape) {
                    Icon(Icons.Outlined.SaveAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Speichern", maxLines = 1)
                }
                Button(onClick = onShare, enabled = !busy, modifier = Modifier.weight(1.4f).heightIn(min = 52.dp), shape = ControlShape) {
                    Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(shareLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Bericht löschen?") },
        text = { Text("${report.title}\n${JourneyReport.day(report.serviceDay)}\n\nDer Bericht und seine PDFs werden vom Gerät gelöscht.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Löschen") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } })
}

@Composable
private fun ReportHeading(text: String) {
    Column(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 8.dp)) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(6.dp))
        Box(Modifier.width(28.dp).height(3.dp).background(Lime, RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun ReportBlockView(block: ReportBlock) {
    when (block) {
        is ReportBlock.Title -> Column(Modifier.padding(top = 4.dp, bottom = 4.dp)) {
            Text(block.subtitle, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
        }
        is ReportBlock.Heading -> ReportHeading(block.text)
        is ReportBlock.Paragraph -> Text(block.text, fontSize = 16.sp, lineHeight = 23.sp, modifier = Modifier.padding(vertical = 3.dp),
            fontWeight = if (block.strong) FontWeight.SemiBold else FontWeight.Normal, color = if (block.strong) Amber else Paper)
        is ReportBlock.Small -> Text(block.text, color = Soft, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(vertical = 3.dp))
        is ReportBlock.Field -> Column(Modifier.padding(vertical = 4.dp)) {
            Text(block.label, color = Soft, fontSize = 13.sp)
            Text(block.value, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        is ReportBlock.Event -> Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {}) {
            Text(block.time, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.widthIn(min = 52.dp))
            Column(Modifier.weight(1f)) {
                Text(block.text, fontSize = 15.sp, lineHeight = 21.sp)
                Text(block.source, color = Soft, fontSize = 13.sp)
            }
        }
        is ReportBlock.LegHeader -> Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
            Text(block.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (block.detail.isNotBlank()) Text(block.detail, color = Soft, fontSize = 13.sp)
        }
        is ReportBlock.StopHeader -> StopColumns(block.name, block.planned, block.forecast, "", header = true)
        is ReportBlock.StopRow -> StopColumns(block.name, block.planned, block.forecast, block.note, header = false)
    }
}

/** Name takes the remaining width and wraps; both time columns keep their place at large font sizes. */
@Composable
private fun StopColumns(name: String, planned: String, forecast: String, note: String, header: Boolean) {
    val size = if (header) 12.sp else 15.sp
    val color = if (header) Soft else Paper
    val missing = "nicht geliefert"
    Row(Modifier.fillMaxWidth().padding(vertical = if (header) 2.dp else 5.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(name, fontSize = size, color = color, lineHeight = 20.sp)
            if (note.isNotBlank()) Text(note, fontSize = 12.sp, color = Soft)
        }
        Text(planned, fontSize = size, color = color, modifier = Modifier.widthIn(min = 58.dp)
            .semantics { if (planned == "–") contentDescription = "Fahrplan $missing" })
        Text(forecast, fontSize = size, color = if (header) Soft else if (forecast == "–") Soft else Paper,
            fontWeight = if (!header && forecast != "–") FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.widthIn(min = 92.dp).semantics { if (forecast == "–") contentDescription = "Prognose $missing" })
    }
}

/** Entry in the list of reports: route, day and the one-line result. */
@Composable
internal fun JourneyReportRow(report: JourneyReport, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClickLabel = "Bericht öffnen", onClick = onOpen).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(report.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(JourneyReport.day(report.serviceDay), report.lines.takeIf { it.isNotBlank() }?.let { "Linie $it" })
                .filterNotNull().joinToString(" · "), color = Soft, fontSize = 13.sp)
            val running = report.state == ReportState.RUNNING
            Text(if (running) "Läuft noch" else JourneyReportText.headline(report), fontSize = 14.sp,
                color = if (running) Lime else Paper)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Soft)
    }
}
