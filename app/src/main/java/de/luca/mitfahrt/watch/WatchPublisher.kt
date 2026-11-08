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
import de.luca.mitfahrt.MitfahrtApplication
import de.luca.mitfahrt.data.AppSettingsStore
import de.luca.mitfahrt.data.RuntimeAlertOptions
import de.luca.mitfahrt.shared.WatchCompanion

class WatchPublisher(context: Context, private val store: RideStore) {
    private val contextRef = context.applicationContext
    private val client = Wearable.getDataClient(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val companion by lazy { WatchCompanionPublisher(contextRef as MitfahrtApplication) }
    private val settings by lazy { AppSettingsStore(contextRef) }
    @Volatile private var connectedAt = Instant.EPOCH
    @Volatile private var connected = false

    fun hasConnectedWatch(): Boolean = connected && Instant.now().epochSecond - connectedAt.epochSecond in 0..60

    fun command(bytes: ByteArray, nodeId: String) {
        val command = runCatching { WatchCompanion.decodeCommand(bytes) }.getOrNull() ?: return
        scope.launch {
            val message = companion.command(command)
            runCatching { Wearable.getMessageClient(contextRef).sendMessage(nodeId, WatchCompanion.RESULT, message.toByteArray()).await() }
            publish()
        }
    }

    fun start() {
        scope.launch { settings.state.collect { publish() } }
        scope.launch { (contextRef as MitfahrtApplication).routePlans.state.collect { publish() } }
        scope.launch { (contextRef as MitfahrtApplication).savedStops.state.collect { publish() } }
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
                connected = nodes.isNotEmpty(); connectedAt = Instant.now()
                val config = settings.current()
                val options = RuntimeAlertOptions.from(config)
                val kind = when {
                    id.startsWith("cancelled/") -> "cancelled"
                    id.startsWith("delay/") -> "delay"
                    id.startsWith("walking/") -> "walking"
                    id.startsWith("exit/") -> "exit"
                    else -> "arrival"
                }
                if (!options.watch(connected) || !options.allowed(kind, Instant.now())) return@launch
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
            if (Instant.now().epochSecond - connectedAt.epochSecond >= 30) {
                connected = Wearable.getNodeClient(contextRef).connectedNodes.await().isNotEmpty(); connectedAt = Instant.now()
            }
            client.putDataItem(PutDataRequest.create(WatchProtocol.PATH)
                .setData(WatchProtocol.encode(store.state.value.watchSnapshot())).setUrgent()).await()
            companion.publish()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { /* Optional companion must never interrupt the phone journey. */ }
    }

}
