package de.luca.mitfahrt.updates

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import de.luca.mitfahrt.shared.WatchApkHeader
import de.luca.mitfahrt.shared.WatchApkPhase
import de.luca.mitfahrt.shared.WatchApkStatus
import de.luca.mitfahrt.shared.WatchBuildInfo
import de.luca.mitfahrt.shared.WatchUpdateProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Collects what watches report about updates. The update screen registers a foreground listener; a manifest
 * listener service can forward to [handle] as well, so reports also arrive while the screen is closed.
 */
object WatchUpdateBridge {
    val info = MutableStateFlow<WatchBuildInfo?>(null)
    val infoByNode = MutableStateFlow<Map<String, WatchBuildInfo>>(emptyMap())
    val status = MutableStateFlow<WatchApkStatus?>(null)

    /** Returns true when the message belonged to the watch update protocol. */
    fun handle(path: String, data: ByteArray, sourceNodeId: String): Boolean = when (path) {
        WatchUpdateProtocol.WATCH_INFO_PATH -> { WatchUpdateProtocol.decodeInfo(data)?.let { accept(sourceNodeId, it) }; true }
        WatchUpdateProtocol.WATCH_APK_STATUS_PATH -> { WatchUpdateProtocol.decodeStatus(data)?.let { status.value = it }; true }
        else -> false
    }

    fun handle(events: DataEventBuffer) {
        for (event in events) {
            val uri = event.dataItem.uri
            if (event.type != DataEvent.TYPE_CHANGED || uri.path != WatchUpdateProtocol.WATCH_INFO_PATH) continue
            val node = uri.host ?: continue
            event.dataItem.data?.let(WatchUpdateProtocol::decodeInfo)?.let { accept(node, it) }
        }
    }

    fun accept(nodeId: String, report: WatchBuildInfo) {
        val previous = infoByNode.value[nodeId]
        if (previous != null && previous.reportedAtMillis > report.reportedAtMillis) return
        infoByNode.value = infoByNode.value + (nodeId to report)
        info.value = report
    }
}

/** Finds the connected watch, asks for its build and streams a verified watch APK over the Wear OS channel. */
class WatchUpdateTransfer(context: Context) {
    private val context = context.applicationContext
    private val messageListener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
        WatchUpdateBridge.handle(event.path, event.data, event.sourceNodeId)
    }
    private val dataListener = DataClient.OnDataChangedListener { events -> WatchUpdateBridge.handle(events) }

    /** Foreground listeners work without any manifest entry while the app is open. */
    fun listen() {
        runCatching { Wearable.getMessageClient(context).addListener(messageListener) }
        runCatching { Wearable.getDataClient(context).addListener(dataListener) }
    }

    fun stopListening() {
        runCatching { Wearable.getMessageClient(context).removeListener(messageListener) }
        runCatching { Wearable.getDataClient(context).removeListener(dataListener) }
    }

    /** Connected watches, nearby ones first. Empty when Wear OS services are missing on this phone. */
    suspend fun connectedWatches(): List<Node> = try {
        withTimeout(10_000) { Wearable.getNodeClient(context).connectedNodes.await() }.sortedByDescending { it.isNearby }
    } catch (cancelled: CancellationException) {
        if (cancelled is kotlinx.coroutines.TimeoutCancellationException) emptyList() else throw cancelled
    } catch (_: Exception) { emptyList() }

    /** Reads the last published report and asks every connected watch for a fresh one. */
    suspend fun requestInfo(nodes: List<Node>) {
        runCatching {
            val items = withTimeout(10_000) {
                Wearable.getDataClient(context).getDataItems(Uri.parse("wear://*${WatchUpdateProtocol.WATCH_INFO_PATH}"),
                    DataClient.FILTER_LITERAL).await()
            }
            try {
                for (item in items) {
                    val node = item.uri.host ?: continue
                    if (nodes.none { it.id == node }) continue
                    item.data?.let(WatchUpdateProtocol::decodeInfo)?.let { WatchUpdateBridge.accept(node, it) }
                }
            } finally { items.release() }
        }.onFailure { if (it is CancellationException && it !is kotlinx.coroutines.TimeoutCancellationException) throw it }
        for (node in nodes) try {
            withTimeout(10_000) {
                Wearable.getMessageClient(context).sendMessage(node.id, WatchUpdateProtocol.WATCH_INFO_REQUEST_PATH, byteArrayOf()).await()
            }
        } catch (cancelled: CancellationException) {
            if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
        } catch (_: Exception) { /* Older watch builds do not answer; the screen says so. */ }
    }

    /**
     * Streams header and APK, then keeps the channel open until the watch confirmed it read everything
     * (any status after RECEIVING) or the wait runs out. Returns that status, or null when the watch stayed silent.
     */
    suspend fun send(nodeId: String, file: File, update: UpdateInfo, onProgress: (Float) -> Unit): WatchApkStatus? {
        require(file.isFile && file.length() == update.size) { "Die Uhr-APK fehlt. Bitte erneut herunterladen." }
        val header = WatchUpdateProtocol.encodeHeader(WatchApkHeader(update.versionCode, update.versionName, update.size, update.sha256))
        WatchUpdateBridge.status.value = null
        val channels = Wearable.getChannelClient(context)
        val channel = try {
            withTimeout(30_000) { channels.openChannel(nodeId, WatchUpdateProtocol.WATCH_APK_CHANNEL_PATH).await() }
        } catch (cancelled: CancellationException) {
            if (cancelled is kotlinx.coroutines.TimeoutCancellationException) error(UNREACHABLE) else throw cancelled
        } catch (_: Exception) { error(UNREACHABLE) }
        val answer = try {
            withContext(Dispatchers.IO) {
                channels.getOutputStream(channel).await().use { output ->
                    output.write(header)
                    file.inputStream().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        var sent = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            sent += read
                            onProgress((sent.toFloat() / update.size).coerceIn(0f, 1f))
                        }
                    }
                    output.flush()
                }
            }
            withTimeoutOrNull(VERIFY_TIMEOUT_MILLIS) { awaitStatus(update, WatchApkPhase.RECEIVING) }
        } finally {
            withContext(NonCancellable) { runCatching { withTimeoutOrNull(10_000) { channels.close(channel).await() } } }
        }
        if (answer?.phase != WatchApkPhase.VERIFYING) return answer
        // The watch read everything; its final answer follows after the package checks.
        return withTimeoutOrNull(FINAL_ANSWER_TIMEOUT_MILLIS) { awaitStatus(update, WatchApkPhase.RECEIVING, WatchApkPhase.VERIFYING) } ?: answer
    }

    private suspend fun awaitStatus(update: UpdateInfo, vararg skip: WatchApkPhase): WatchApkStatus =
        WatchUpdateBridge.status.first { status ->
            status != null && status.phase !in skip &&
                (status.versionCode == update.versionCode || status.phase == WatchApkPhase.ERROR)
        }!!

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        private const val VERIFY_TIMEOUT_MILLIS = 5 * 60_000L
        private const val FINAL_ANSWER_TIMEOUT_MILLIS = 60_000L
        private const val UNREACHABLE = "Die Uhr ist nicht erreichbar. Prüfe die Verbindung in der Wear-OS-App."

        /** 1-tap handoff to apps like Wear Installer, Bugjaeger or Easy Fire Tools that install on the watch over ADB. */
        fun shareWatchApkIntent(context: Context, file: File): Intent {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.reports", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = APK_MIME
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, file.name)
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return Intent.createChooser(send, "Uhr-APK teilen")
        }
    }
}
