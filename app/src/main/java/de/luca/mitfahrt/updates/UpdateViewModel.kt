package de.luca.mitfahrt.updates

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wearable.Node
import de.luca.mitfahrt.BuildConfig
import de.luca.mitfahrt.data.AppSettingsStore
import de.luca.mitfahrt.shared.WatchApkPhase
import de.luca.mitfahrt.shared.WatchApkStatus
import de.luca.mitfahrt.shared.WatchBuildInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class UpdatePhase { IDLE, CHECKING, CURRENT, EMPTY, AVAILABLE, DOWNLOADING, READY, ERROR }
data class UpdateState(val phase: UpdatePhase = UpdatePhase.IDLE, val info: UpdateInfo? = null,
    val progress: Float = 0f, val file: File? = null, val message: String? = null, val changes: String? = null)

enum class WatchUpdatePhase { IDLE, CHECKING, CURRENT, EMPTY, AVAILABLE, DOWNLOADING, READY, SENDING,
    WAITING_ON_WATCH, INSTALLER_OPENED, PERMISSION_REQUIRED, ERROR }

/** [watch] stays null until the watch reported its build; older watch builds never do. */
data class WatchUpdateState(val phase: WatchUpdatePhase = WatchUpdatePhase.IDLE, val connectionChecked: Boolean = false,
    val nodeId: String? = null, val nodeName: String? = null, val watch: WatchBuildInfo? = null,
    val info: UpdateInfo? = null, val progress: Float = 0f, val file: File? = null, val message: String? = null,
    val adb: WatchAdbCommands? = null) {
    val busy get() = phase in setOf(WatchUpdatePhase.CHECKING, WatchUpdatePhase.DOWNLOADING, WatchUpdatePhase.SENDING)
}

/** Pure decision for the watch screen; a missing report is never treated as "up to date". */
internal fun watchPhaseFor(info: UpdateInfo, watch: WatchBuildInfo?): WatchUpdatePhase =
    if (watch != null && watch.versionCode >= info.versionCode) WatchUpdatePhase.CURRENT else WatchUpdatePhase.AVAILABLE

/** Maps a watch report to the phone phase, or null when it belongs to another transfer. */
internal fun watchPhaseFor(status: WatchApkStatus, sentVersion: Long?): WatchUpdatePhase? {
    if (sentVersion == null) return null
    if (status.versionCode != sentVersion && !(status.phase == WatchApkPhase.ERROR && status.versionCode == 0L)) return null
    return when (status.phase) {
        WatchApkPhase.RECEIVING, WatchApkPhase.VERIFYING -> WatchUpdatePhase.SENDING
        WatchApkPhase.WAITING_FOR_APP -> WatchUpdatePhase.WAITING_ON_WATCH
        WatchApkPhase.INSTALLER_OPENED -> WatchUpdatePhase.INSTALLER_OPENED
        WatchApkPhase.PERMISSION_REQUIRED -> WatchUpdatePhase.PERMISSION_REQUIRED
        WatchApkPhase.ERROR -> WatchUpdatePhase.ERROR
    }
}

private const val SILENT_WATCH = "Die Uhr hat nicht geantwortet. Ältere Uhr-Versionen können noch keine Updates empfangen: " +
    "installiere diese Version einmal per ADB oder Wear Installer, danach geht es direkt aus der App."
private const val NO_ANSWER = "Die Uhr hat nicht geantwortet. Öffne Mitfahrt auf der Uhr und sende erneut."
private const val NO_WATCH = "Keine Uhr verbunden. Du kannst die APK teilen oder per ADB installieren."

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val client = UpdateClient(application)
    private val settings = AppSettingsStore(application)
    private val checks = application.getSharedPreferences("update_checks", android.content.Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(UpdateState())
    val state = mutable.asStateFlow()
    private val transfer = WatchUpdateTransfer(application)
    private val watchMutable = MutableStateFlow(WatchUpdateState())
    val watchState = watchMutable.asStateFlow()
    private var watchJob: Job? = null
    @Volatile private var sentVersion: Long? = null

    init {
        transfer.listen()
        viewModelScope.launch { WatchUpdateBridge.infoByNode.collect { onWatchInfo(it) } }
        viewModelScope.launch { WatchUpdateBridge.status.collect { it?.let(::onWatchStatus) } }
    }

    override fun onCleared() {
        transfer.stopListening()
        client.close(); settings.close()
        super.onCleared()
    }

    fun checkOnOpen() {
        UpdateScheduler.schedule(getApplication())
        val mode = settings.current().string("update_check", "open")
        val last = checks.getLong("last_check", 0L)
        val now = System.currentTimeMillis()
        if (UpdateCheckPolicy.shouldCheck(mode, last, now)) check()
    }

    fun check() {
        if (mutable.value.phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING)) return
        mutable.value = UpdateState(phase = UpdatePhase.CHECKING)
        viewModelScope.launch {
            try {
                val info = client.check()
                checks.edit().putLong("last_check", System.currentTimeMillis()).apply()
                val available = info.versionCode > BuildConfig.VERSION_CODE
                val changes = if (available) try { client.changes(info) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } else null
                mutable.value = UpdateState(if (available) UpdatePhase.AVAILABLE else UpdatePhase.CURRENT, info, changes = changes)
            } catch (e: CancellationException) { throw e
            } catch (_: NoPublishedUpdate) { mutable.value = UpdateState(UpdatePhase.EMPTY)
            } catch (e: Exception) { mutable.value = UpdateState(UpdatePhase.ERROR, message = e.message ?: "Update-Suche fehlgeschlagen.") }
        }
        checkWatchUpdate(automatic = true)
    }

    fun download() {
        val state = mutable.value
        val info = state.info ?: return
        if (state.phase !in setOf(UpdatePhase.AVAILABLE, UpdatePhase.ERROR)) return
        mutable.value = state.copy(phase = UpdatePhase.DOWNLOADING, progress = 0f, message = null)
        viewModelScope.launch {
            try {
                val file = client.download(info) { progress -> mutable.value = mutable.value.copy(progress = progress) }
                mutable.value = UpdateState(UpdatePhase.READY, info, progress = 1f, file = file, changes = state.changes)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { mutable.value = UpdateState(UpdatePhase.ERROR, info, message = e.message ?: "Download fehlgeschlagen.", changes = state.changes) }
        }
    }

    /** Looks for connected watches and asks them for their installed build. */
    fun refreshWatchConnection() {
        if (watchJob?.isActive == true) return
        watchJob = viewModelScope.launch { refreshConnection() }
    }

    /** [automatic] checks only touch the network when a watch is connected. */
    fun checkWatchUpdate(automatic: Boolean = false) {
        if (watchJob?.isActive == true) return
        watchJob = viewModelScope.launch {
            val before = watchMutable.value.phase
            watchMutable.update { it.copy(phase = WatchUpdatePhase.CHECKING, message = null) }
            val node = refreshConnection()
            if (automatic && node == null) { watchMutable.update { it.copy(phase = before.takeUnless { p -> p == WatchUpdatePhase.CHECKING } ?: WatchUpdatePhase.IDLE) }; return@launch }
            try {
                val info = client.checkWatch()
                val folder = File(getApplication<Application>().cacheDir, "updates")
                val cached = File(folder, info.assetName).takeIf { it.isFile && it.length() == info.size }
                watchMutable.update { current ->
                    val tooOld = current.watch?.let { info.minSdk > it.sdkInt } == true
                    current.copy(info = info, file = cached, progress = 0f,
                        adb = runCatching { WatchAdbCommands.forApk(getApplication<Application>().packageName, info.assetName) }.getOrNull(),
                        phase = if (tooOld) WatchUpdatePhase.ERROR else watchPhaseFor(info, current.watch),
                        message = if (tooOld) "Diese Uhr-Version braucht ein neueres Wear OS (API ${info.minSdk})." else null)
                }
            } catch (e: CancellationException) { throw e
            } catch (_: NoPublishedUpdate) { watchMutable.update { it.copy(phase = WatchUpdatePhase.EMPTY, info = null) }
            } catch (e: Exception) { watchMutable.update { it.copy(phase = WatchUpdatePhase.ERROR, message = e.message ?: "Suche nach dem Uhr-Update fehlgeschlagen.") } }
        }
    }

    fun downloadAndSendWatchUpdate() {
        val info = watchMutable.value.info ?: return checkWatchUpdate()
        if (watchJob?.isActive == true) return
        watchJob = viewModelScope.launch { downloadWatch(info)?.let { sendWatch(info, it) } }
    }

    fun sendDownloadedWatchApk() {
        val state = watchMutable.value
        val info = state.info ?: return
        val file = state.file?.takeIf { it.isFile } ?: return downloadAndSendWatchUpdate()
        if (watchJob?.isActive == true) return
        watchJob = viewModelScope.launch { sendWatch(info, file) }
    }

    fun downloadWatchApkOnly() {
        val info = watchMutable.value.info ?: return
        if (watchJob?.isActive == true) return
        watchJob = viewModelScope.launch { downloadWatch(info) }
    }

    private suspend fun refreshConnection(): Node? {
        val nodes = transfer.connectedWatches()
        val reports = WatchUpdateBridge.infoByNode.value
        val node = nodes.firstOrNull { it.id in reports } ?: nodes.firstOrNull()
        watchMutable.update { it.copy(connectionChecked = true, nodeId = node?.id, nodeName = node?.displayName,
            watch = node?.id?.let(reports::get)) }
        if (nodes.isNotEmpty()) transfer.requestInfo(nodes)
        return node
    }

    private suspend fun downloadWatch(info: UpdateInfo): File? {
        watchMutable.update { it.copy(phase = WatchUpdatePhase.DOWNLOADING, progress = 0f, message = null) }
        return try {
            client.downloadWatch(info, watchMutable.value.watch?.versionCode) { progress ->
                watchMutable.update { it.copy(progress = progress) }
            }.also { file -> watchMutable.update { it.copy(phase = WatchUpdatePhase.READY, file = file, progress = 1f) } }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            watchMutable.update { it.copy(phase = WatchUpdatePhase.ERROR, message = e.message ?: "Download der Uhr-APK fehlgeschlagen.") }
            null
        }
    }

    private suspend fun sendWatch(info: UpdateInfo, file: File) {
        val node = watchMutable.value.nodeId ?: refreshConnection()?.id
        if (node == null) { watchMutable.update { it.copy(phase = WatchUpdatePhase.READY, message = NO_WATCH) }; return }
        WatchUpdateBridge.status.value = null
        sentVersion = info.versionCode
        watchMutable.update { it.copy(phase = WatchUpdatePhase.SENDING, progress = 0f, message = "Wird an die Uhr gesendet …") }
        try {
            val result = transfer.send(node, file, info) { progress -> watchMutable.update { it.copy(progress = progress) } }
            if (result?.phase == WatchApkPhase.VERIFYING) watchMutable.update {
                it.copy(phase = WatchUpdatePhase.WAITING_ON_WATCH, message = "Die Uhr hat die APK erhalten, aber noch nicht geantwortet. Öffne Mitfahrt auf der Uhr.")
            } else if (result != null) onWatchStatus(result)
            else watchMutable.update { it.copy(phase = WatchUpdatePhase.ERROR, message = if (it.watch == null) SILENT_WATCH else NO_ANSWER) }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            watchMutable.update { it.copy(phase = WatchUpdatePhase.ERROR, message = e.message ?: "Senden an die Uhr fehlgeschlagen.") }
        }
    }

    private fun onWatchStatus(status: WatchApkStatus) {
        val phase = watchPhaseFor(status, sentVersion) ?: return
        watchMutable.update { current ->
            // A late progress report must not hide a final answer the watch already gave.
            if (phase == WatchUpdatePhase.SENDING && current.phase != WatchUpdatePhase.SENDING) current
            else current.copy(phase = phase, message = status.message.ifBlank { current.message })
        }
    }

    private fun onWatchInfo(reports: Map<String, WatchBuildInfo>) {
        watchMutable.update { current ->
            val node = current.nodeId ?: return@update current
            val watch = reports[node] ?: return@update current
            val info = current.info
            val settled = setOf(WatchUpdatePhase.CURRENT, WatchUpdatePhase.AVAILABLE, WatchUpdatePhase.READY,
                WatchUpdatePhase.WAITING_ON_WATCH, WatchUpdatePhase.INSTALLER_OPENED, WatchUpdatePhase.PERMISSION_REQUIRED)
            when {
                info == null || current.phase !in settled -> current.copy(watch = watch)
                watch.versionCode >= info.versionCode -> current.copy(watch = watch, phase = WatchUpdatePhase.CURRENT,
                    message = if (current.phase == WatchUpdatePhase.CURRENT || current.phase == WatchUpdatePhase.AVAILABLE) null
                        else "Die Uhr hat jetzt Version ${watch.versionName}.")
                current.phase == WatchUpdatePhase.CURRENT -> current.copy(watch = watch, phase = WatchUpdatePhase.AVAILABLE)
                else -> current.copy(watch = watch)
            }
        }
    }
}
