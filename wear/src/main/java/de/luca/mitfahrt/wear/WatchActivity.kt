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
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await

class WatchActivity : ComponentActivity(), DataClient.OnDataChangedListener {
    private val store get() = (application as WatchApplication).store
    private val client by lazy { Wearable.getDataClient(this) }
    private var refreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WatchScreen(store, onRefresh = ::refresh) }
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
    }

    private fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = lifecycleScope.launch {
            store.refreshing.value = true
            store.syncMessage.value = null
            try {
                val buffer = client.getDataItems(Uri.parse("wear://*${WatchProtocol.PATH}"), DataClient.FILTER_LITERAL).await()
                try { for (item in buffer) store.receive(item.data) } finally { buffer.release() }
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
