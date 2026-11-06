package de.luca.mitfahrt.alerts

import android.content.Context
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import de.luca.mitfahrt.shared.HapticTestProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class HapticTestState(val running: Boolean = false, val phoneTriggered: Boolean = false,
    val watchConfirmed: Boolean = false, val message: String? = null)

/** Explicit, bounded test. A transport send alone is never presented as a watch acknowledgement. */
class HapticTestClient(context: Context) {
    private val context = context.applicationContext
    private val mutable = MutableStateFlow(HapticTestState())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()

    suspend fun run() {
        if (!mutex.tryLock()) return
        var client: MessageClient? = null
        var listener: MessageClient.OnMessageReceivedListener? = null
        val phone = ArrivalHaptics.testPulse(context)
        val prefix = if (phone) "Handy-Test ausgelöst." else "Handy-Vibration nicht verfügbar."
        mutable.value = HapticTestState(running = true, phoneTriggered = phone, message = "Uhr wird geprüft …")
        try {
            withTimeout(12_000) {
                val request = HapticTestProtocol.create()
                val expectedNodes = ConcurrentHashMap.newKeySet<String>()
                val acknowledged = CompletableDeferred<Boolean>()
                val messageClient = Wearable.getMessageClient(context)
                client = messageClient
                val callback = MessageClient.OnMessageReceivedListener { event ->
                    if (event.path == HapticTestProtocol.ACK && event.sourceNodeId in expectedNodes) {
                        val ack = HapticTestProtocol.matchingAck(event.data, Instant.now(), request, event.sourceNodeId, expectedNodes)
                        if (ack != null) acknowledged.complete(ack.triggered)
                    }
                }
                listener = callback
                messageClient.addListener(callback).await()
                val nodes = Wearable.getNodeClient(context).connectedNodes.await()
                expectedNodes.addAll(nodes.map { it.id })
                if (nodes.isEmpty()) {
                    mutable.value = HapticTestState(phoneTriggered = phone, message = "$prefix Keine Uhr verbunden.")
                    return@withTimeout
                }
                var sent = false
                for (node in nodes) {
                    try {
                        messageClient.sendMessage(node.id, HapticTestProtocol.REQUEST, HapticTestProtocol.encode(request)).await()
                        sent = true
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (_: Exception) { /* Other connected watches can still acknowledge. */ }
                }
                if (!sent) {
                    mutable.value = HapticTestState(phoneTriggered = phone, message = "$prefix Watch-App nicht erreichbar.")
                    return@withTimeout
                }
                val triggered = acknowledged.await()
                mutable.value = HapticTestState(phoneTriggered = phone, watchConfirmed = true,
                    message = if (triggered) "$prefix Uhr hat den Test bestätigt."
                        else "$prefix Uhr erreicht, Vibration dort nicht verfügbar.")
            }
        } catch (_: TimeoutCancellationException) {
            mutable.value = HapticTestState(phoneTriggered = phone, message = "$prefix Keine Bestätigung von der Uhr.")
        } catch (cancelled: CancellationException) {
            mutable.value = HapticTestState(phoneTriggered = phone, message = "$prefix Test beendet.")
            throw cancelled
        } catch (_: Exception) {
            mutable.value = HapticTestState(phoneTriggered = phone, message = "$prefix Watch-Verbindung nicht verfügbar.")
        } finally {
            listener?.let { callback -> runCatching { client?.removeListener(callback) } }
            mutex.unlock()
        }
    }
}
