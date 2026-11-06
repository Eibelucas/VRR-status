package de.luca.mitfahrt.wear

import android.app.Application
import android.content.Context
import android.util.Base64
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import de.luca.mitfahrt.shared.WatchProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class WatchApplication : Application() { val store by lazy { WatchStore(this) } }

class WatchStore(context: Context) {
    private val prefs = context.getSharedPreferences("watch_ride", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(runCatching {
        prefs.getString("snapshot", null)?.let { WatchProtocol.decode(Base64.decode(it, Base64.NO_WRAP)) }
    }.getOrNull())
    val ride = mutable.asStateFlow()
    val syncMessage = MutableStateFlow<String?>(null)
    val refreshing = MutableStateFlow(false)

    @Synchronized fun receive(bytes: ByteArray?) {
        val snapshot = runCatching { bytes?.let(WatchProtocol::decode) }.getOrNull() ?: return
        if (mutable.value?.sentAt?.isAfter(snapshot.sentAt) == true) return
        prefs.edit().putString("snapshot", Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
        mutable.value = snapshot
        syncMessage.value = null
    }
}

class WatchDataService : WearableListenerService() {
    override fun onMessageReceived(event: com.google.android.gms.wearable.MessageEvent) {
        if (event.path != de.luca.mitfahrt.shared.ArrivalAlert.PATH) return
        de.luca.mitfahrt.shared.ArrivalAlert.recentId(event.data, java.time.Instant.now())?.let {
            de.luca.mitfahrt.alerts.ArrivalHaptics.pulse(this, it)
        }
    }
    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == WatchProtocol.PATH)
            (application as WatchApplication).store.receive(event.dataItem.data)
    }
}
