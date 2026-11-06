package de.luca.mitfahrt.watch

import android.content.Context
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import de.luca.mitfahrt.data.RideStore
import de.luca.mitfahrt.shared.WatchProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.tasks.await
import java.time.Instant

class WatchPublisher(context: Context, private val store: RideStore) {
    private val contextRef = context.applicationContext
    private val client = Wearable.getDataClient(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun start() {
        scope.launch {
            store.state.map { it.watchSnapshot() }.distinctUntilChangedBy { it.copy(sentAt = Instant.EPOCH) }.collectLatest { state ->
                publish()
                while (state.running && state.active) { delay(15_000); publish() }
            }
        }
    }

    fun requestPublish() { scope.launch { publish() } }

    fun arrivalAlert(id: String, boardingAt: Instant) {
        val bytes = de.luca.mitfahrt.shared.ArrivalAlert.encode(id, Instant.now(), boardingAt)
        scope.launch {
            try {
                val nodes = Wearable.getNodeClient(contextRef).connectedNodes.await()
                for (node in nodes) try {
                    Wearable.getMessageClient(contextRef).sendMessage(node.id, de.luca.mitfahrt.shared.ArrivalAlert.PATH, bytes).await()
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { /* A disconnected watch receives no delayed replay. */ }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { /* No delayed replay when the watch reconnects. */ }
        }
    }

    private suspend fun publish() {
        try {
            client.putDataItem(PutDataRequest.create(WatchProtocol.PATH)
                .setData(WatchProtocol.encode(store.state.value.watchSnapshot())).setUrgent()).await()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { /* Optional companion must never interrupt the phone journey. */ }
    }
}
