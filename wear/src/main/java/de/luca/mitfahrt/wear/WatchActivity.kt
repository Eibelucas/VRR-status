package de.luca.mitfahrt.wear

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.wearable.*
import de.luca.mitfahrt.shared.WatchProtocol
import de.luca.mitfahrt.shared.WatchCompanion
import de.luca.mitfahrt.shared.WatchCommand
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

class WatchActivity : ComponentActivity(), DataClient.OnDataChangedListener {
    private val store get() = (application as WatchApplication).store
    private val client by lazy { Wearable.getDataClient(this) }
    private var refreshJob: Job? = null
    private var commandSequence = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WatchScreen(store, onRefresh = ::refresh, onCommand = ::command) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                client.addListener(this@WatchActivity)
                refresh()
                try { awaitCancellation() } finally { client.removeListener(this@WatchActivity) }
            }
        }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == WatchProtocol.PATH)
            store.receive(event.dataItem.data)
        else if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == WatchCompanion.LIBRARY)
            store.receiveLibrary(event.dataItem.data)
    }

    private fun command(command: WatchCommand) {
        if (store.commandBusy.value) return
        val sequence = ++commandSequence
        lifecycleScope.launch {
            store.commandBusy.value = true; store.commandFeedback.value = null
            try {
                val nodes = Wearable.getNodeClient(this@WatchActivity).connectedNodes.await()
                require(nodes.isNotEmpty()) { "Handy nicht verbunden." }
                Wearable.getMessageClient(this@WatchActivity).sendMessage(nodes.first().id, WatchCompanion.COMMAND,
                    WatchCompanion.encodeCommand(command)).await()
                delay(20_000)
                if (sequence == commandSequence && store.commandBusy.value) store.commandFeedback.value = "Keine Antwort. Mitfahrt am Handy öffnen."
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) { store.commandFeedback.value = error.message ?: "Handy nicht erreichbar."
            } finally { if (sequence == commandSequence) store.commandBusy.value = false }
        }
    }

    private fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = lifecycleScope.launch {
            store.refreshing.value = true
            store.syncMessage.value = null
            try {
                val buffer = client.getDataItems(Uri.parse("wear://*${WatchProtocol.PATH}"), DataClient.FILTER_LITERAL).await()
                try { for (item in buffer) store.receive(item.data) } finally { buffer.release() }
                val library = client.getDataItems(Uri.parse("wear://*${WatchCompanion.LIBRARY}"), DataClient.FILTER_LITERAL).await()
                try { for (item in library) store.receiveLibrary(item.data) } finally { library.release() }
                val nodes = Wearable.getNodeClient(this@WatchActivity).connectedNodes.await()
                if (nodes.isEmpty()) store.syncMessage.value = "Handy nicht verbunden."
                for (node in nodes) Wearable.getMessageClient(this@WatchActivity)
                    .sendMessage(node.id, WatchProtocol.REQUEST, byteArrayOf()).await()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { store.syncMessage.value = "Mitfahrt am Handy öffnen."
            } finally { store.refreshing.value = false }
        }
    }
}
