package de.luca.mitfahrt.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.luca.mitfahrt.RideViewModel
import java.time.LocalDate

/** Explicit export and import of rides and reports as one file; no cloud, nothing in the background. */
@Composable
internal fun BackupScreen(vm: RideViewModel) {
    val message by vm.backupMessage.collectAsStateWithLifecycle()
    val busy by vm.backupBusy.collectAsStateWithLifecycle()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(vm::exportBackup) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importBackup) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Fahrtenarchiv, Fahrtberichte, erstellte PDFs und ältere Belege in eine Datei. Du wählst selbst, wo sie liegt.",
            fontSize = 15.sp, lineHeight = 21.sp)
        Button(onClick = { save.launch("Mitfahrt-Sicherung-${LocalDate.now()}.zip") }, enabled = !busy, shape = ControlShape,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Sicherung erstellen") }
        OutlinedButton(onClick = { open.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !busy, shape = ControlShape,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Sicherung einlesen") }
        if (busy) AppLinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, fontSize = 15.sp, lineHeight = 21.sp) }
        Text("Beim Einlesen wird nichts gelöscht. Vorhandene Fahrten und Berichte bleiben, neuere Stände aus der Sicherung kommen dazu. " +
            "Erstellte PDFs werden nie überschrieben. Die Datei enthält deine Fahrten und eigenen Angaben: Gib sie nur weiter, wenn du das willst.",
            color = Soft, fontSize = 13.sp, lineHeight = 18.sp)
    }
}
