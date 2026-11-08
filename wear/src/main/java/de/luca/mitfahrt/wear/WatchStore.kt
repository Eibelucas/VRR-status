package de.luca.mitfahrt.wear

import android.app.Application
import android.content.Context
import android.util.Base64
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import de.luca.mitfahrt.shared.WatchProtocol
import de.luca.mitfahrt.shared.HapticTestProtocol
import de.luca.mitfahrt.shared.WatchCompanion
import de.luca.mitfahrt.shared.WatchLibrary
import com.google.android.gms.wearable.Wearable
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class WatchApplication : Application() { val store by lazy { WatchStore(this) } }

class WatchStore(context: Context) {
    private val tileContext = context.applicationContext
    private val prefs = context.getSharedPreferences("watch_ride", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(runCatching {
        prefs.getString("snapshot", null)?.let { WatchProtocol.decode(Base64.decode(it, Base64.NO_WRAP)) }
    }.getOrNull())
    val ride = mutable.asStateFlow()
    private val libraryState = MutableStateFlow(runCatching {
        prefs.getString("library", null)?.let { WatchCompanion.decode(Base64.decode(it, Base64.NO_WRAP)) }
    }.getOrNull() ?: WatchLibrary(Instant.EPOCH))
    val library = libraryState.asStateFlow()
    val syncMessage = MutableStateFlow<String?>(null)
    val commandFeedback = MutableStateFlow<String?>(null)
    val refreshing = MutableStateFlow(false)
    val commandBusy = MutableStateFlow(false)

    @Synchronized fun receiveLibrary(bytes: ByteArray?) {
        val snapshot = runCatching { bytes?.let(WatchCompanion::decode) }.getOrNull() ?: return
        if (libraryState.value.sentAt > snapshot.sentAt) return
        prefs.edit().putString("library", Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
        libraryState.value = snapshot
        snapshot.settings.filterKeys { it.startsWith("vibration_") || it.startsWith("quiet_") || it == "alert_target" }
            .forEach { (key, value) -> prefs.edit().putString(key, value).apply() }
        RideTileUpdates.request(tileContext, force = true)
        RideComplicationUpdates.request(tileContext)
    }

    @Synchronized fun receive(bytes: ByteArray?) {
        val snapshot = runCatching { bytes?.let(WatchProtocol::decode) }.getOrNull() ?: return
        if (mutable.value?.sentAt?.isAfter(snapshot.sentAt) == true) return
        val previous = mutable.value
        prefs.edit().putString("snapshot", Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
        mutable.value = snapshot
        syncMessage.value = null
        if (previous?.copy(sentAt = Instant.EPOCH) != snapshot.copy(sentAt = Instant.EPOCH))
            RideTileUpdates.request(tileContext)
        RideComplicationUpdates.request(tileContext)
    }
}

class WatchDataService : WearableListenerService() {
    override fun onMessageReceived(event: com.google.android.gms.wearable.MessageEvent) {
        if (event.path == WatchCompanion.RESULT) {
            (application as WatchApplication).store.apply {
                commandBusy.value = false
                commandFeedback.value = event.data.take(512).toByteArray().toString(Charsets.UTF_8)
            }
            return
        }
        if (event.path == HapticTestProtocol.REQUEST) {
            val request = HapticTestProtocol.decode(event.data, Instant.now()) ?: return
            val triggered = de.luca.mitfahrt.alerts.ArrivalHaptics.testPulse(this)
            runCatching {
                Wearable.getMessageClient(this).sendMessage(event.sourceNodeId, HapticTestProtocol.ACK,
                    HapticTestProtocol.encodeAck(request, triggered))
            }
            return
        }
        if (event.path != de.luca.mitfahrt.shared.ArrivalAlert.PATH) return
        de.luca.mitfahrt.shared.ArrivalAlert.recentId(event.data, java.time.Instant.now())?.let {
            de.luca.mitfahrt.alerts.ArrivalHaptics.pulse(this, it)
        }
    }
    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == WatchProtocol.PATH)
            (application as WatchApplication).store.receive(event.dataItem.data)
        else if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == WatchCompanion.LIBRARY)
            (application as WatchApplication).store.receiveLibrary(event.dataItem.data)
    }
}
