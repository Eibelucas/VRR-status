package de.luca.mitfahrt.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.luca.mitfahrt.updates.UpdateViewModel
import de.luca.mitfahrt.updates.WatchAdbCommands
import de.luca.mitfahrt.updates.WatchUpdatePhase
import de.luca.mitfahrt.updates.WatchUpdateState
import de.luca.mitfahrt.updates.WatchUpdateTransfer
import java.io.File

/** Self-wiring variant for the settings page: uses the activity's [UpdateViewModel]. */
@Composable
internal fun WatchUpdateSettings(modifier: Modifier = Modifier) {
    val updates: UpdateViewModel = viewModel()
    val state by updates.watchState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var shareFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!state.connectionChecked) updates.refreshWatchConnection() }
    WatchUpdateSection(state, onCheck = { updates.checkWatchUpdate() }, onDownloadAndSend = updates::downloadAndSendWatchUpdate,
        onSendAgain = updates::sendDownloadedWatchApk, onDownloadOnly = updates::downloadWatchApkOnly,
        onShareApk = { file ->
            shareFailed = runCatching { context.startActivity(WatchUpdateTransfer.shareWatchApkIntent(context, file)) }.isFailure
        }, modifier = modifier)
    if (shareFailed) Text("Teilen ist gerade nicht möglich. Lade die APK erneut.", color = Amber, fontSize = 13.sp)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WatchUpdateSection(state: WatchUpdateState, onCheck: () -> Unit, onDownloadAndSend: () -> Unit,
    onSendAgain: () -> Unit, onShareApk: (File) -> Unit, onDownloadOnly: () -> Unit = {}, modifier: Modifier = Modifier) {
    var showAdb by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.phase) { if (state.phase == WatchUpdatePhase.PERMISSION_REQUIRED) showAdb = true }
    val percent = (state.progress * 100).toInt()
    Column(modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .3f))
        Text("Uhr-App aktualisieren", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        Text(when {
            !state.connectionChecked -> "Verbindung zur Uhr wird geprüft …"
            state.nodeName != null -> "Verbunden: ${state.nodeName}"
            else -> "Keine Uhr verbunden."
        }, color = Soft, fontSize = 13.sp, lineHeight = 18.sp)
        val watch = state.watch
        if (watch != null) Text("Auf der Uhr: ${watch.versionName}", color = Soft, fontSize = 13.sp, lineHeight = 18.sp)
        else if (state.nodeName != null) Text("Auf der Uhr: Version nicht gemeldet. Ältere Uhr-Versionen melden sie noch nicht und brauchen einmal ADB oder Wear Installer.",
            color = Soft, fontSize = 13.sp, lineHeight = 18.sp)
        state.info?.let { Text("Neueste Uhr-Version: ${it.versionName}", color = Soft, fontSize = 13.sp, lineHeight = 18.sp) }
        if (watch?.canRequestPackageInstalls == false && state.phase != WatchUpdatePhase.PERMISSION_REQUIRED)
            Text("Die Uhr erlaubt Mitfahrt noch keine Installation. Bei Wear OS und One UI Watch gibst du das einmalig per ADB frei.",
                color = Amber, fontSize = 13.sp, lineHeight = 18.sp)
        statusText(state, percent)?.let { (text, warning) ->
            Text(text, color = if (warning) Amber else Paper, fontSize = 14.sp, lineHeight = 20.sp)
        }
        when (state.phase) {
            WatchUpdatePhase.CHECKING -> AppLinearProgressIndicator(Modifier.fillMaxWidth())
            WatchUpdatePhase.DOWNLOADING, WatchUpdatePhase.SENDING ->
                AppLinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            else -> Unit
        }
        if (!state.busy) PrimaryWatchAction(state, onCheck, onDownloadAndSend, onSendAgain)
        val file = state.file?.takeIf { it.isFile }
        if (!state.busy) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (file != null) OutlinedButton(onClick = { onShareApk(file) }, shape = ControlShape, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("APK teilen (z. B. Wear Installer)")
            } else if (state.info != null) OutlinedButton(onClick = onDownloadOnly, shape = ControlShape, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Nur APK laden")
            }
            if (state.adb != null) TextButton(onClick = { showAdb = !showAdb }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (showAdb) "ADB-Befehle ausblenden" else "ADB-Befehle anzeigen")
            }
            if (state.info != null) TextButton(onClick = onCheck, modifier = Modifier.heightIn(min = 48.dp)) { Text("Erneut prüfen") }
        }
        val adb = state.adb
        if (showAdb && adb != null) AdbCommandsPanel(adb)
    }
}

@Composable
private fun PrimaryWatchAction(state: WatchUpdateState, onCheck: () -> Unit, onDownloadAndSend: () -> Unit, onSendAgain: () -> Unit) {
    val wide = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    val hasFile = state.file?.isFile == true
    when {
        state.info == null || state.phase in setOf(WatchUpdatePhase.IDLE, WatchUpdatePhase.EMPTY) ->
            OutlinedButton(onClick = onCheck, shape = ControlShape, modifier = wide) { Text("Nach Uhr-Update suchen") }
        state.phase == WatchUpdatePhase.CURRENT -> Unit
        hasFile -> Button(onClick = onSendAgain, shape = ControlShape, modifier = wide) {
            Text(if (state.phase == WatchUpdatePhase.READY) "An Uhr senden" else "Erneut an Uhr senden")
        }
        else -> Button(onClick = onDownloadAndSend, shape = ControlShape, modifier = wide) { Text("Uhr-Update laden und senden") }
    }
}

private fun statusText(state: WatchUpdateState, percent: Int): Pair<String, Boolean>? = when (state.phase) {
    WatchUpdatePhase.IDLE -> null
    WatchUpdatePhase.CHECKING -> "Suche nach dem Uhr-Update …" to false
    WatchUpdatePhase.CURRENT -> (state.message ?: "Die Uhr ist auf dem neuesten Stand.") to false
    WatchUpdatePhase.EMPTY -> "Noch keine Uhr-Version veröffentlicht." to false
    WatchUpdatePhase.AVAILABLE -> "Version ${state.info?.versionName} für die Uhr ist verfügbar." to false
    WatchUpdatePhase.DOWNLOADING -> "Uhr-APK wird geladen · $percent %" to false
    WatchUpdatePhase.READY -> (state.message ?: "Uhr-APK ist geladen und geprüft.") to (state.message != null)
    WatchUpdatePhase.SENDING -> (state.message ?: "Wird an die Uhr gesendet …").let { if (percent < 100) "$it · $percent %" else it } to false
    WatchUpdatePhase.WAITING_ON_WATCH -> (state.message ?: "Öffne Mitfahrt auf der Uhr, dann startet die Installation.") to false
    WatchUpdatePhase.INSTALLER_OPENED -> (state.message ?: "Installation auf der Uhr bestätigen.") to false
    WatchUpdatePhase.PERMISSION_REQUIRED -> (state.message ?: "Die Uhr erlaubt Mitfahrt noch keine Installation.") to true
    WatchUpdatePhase.ERROR -> (state.message ?: "Das Uhr-Update hat nicht geklappt.") to true
}

@Composable
private fun AdbCommandsPanel(adb: WatchAdbCommands) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Wear OS und One UI Watch erlauben Apps die Installation oft erst nach einer einmaligen Freigabe per ADB. " +
            "Schalte dafür auf der Uhr die Entwickleroptionen und Debugging über WLAN ein und verbinde den Computer mit „adb connect“.",
            color = Soft, fontSize = 13.sp, lineHeight = 18.sp)
        Text("1. Installation aus Mitfahrt einmalig erlauben", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        AdbCommand(adb.allowInstalls)
        Text("Danach reicht „Erneut an Uhr senden“.", color = Soft, fontSize = 13.sp, lineHeight = 18.sp)
        Text("2. Oder die APK direkt installieren", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("Teile die APK vorher auf den Computer.", color = Soft, fontSize = 13.sp, lineHeight = 18.sp)
        AdbCommand(adb.install)
    }
}

@Composable
private fun AdbCommand(command: String) {
    val context = LocalContext.current
    var copied by remember(command) { mutableStateOf(false) }
    SelectionContainer { Text(command, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp) }
    OutlinedButton(onClick = {
        copied = runCatching {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ADB-Befehl", command))
        }.isSuccess
    }, shape = ControlShape, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (copied) "Kopiert" else "Kopieren") }
}
