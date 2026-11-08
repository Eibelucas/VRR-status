package de.luca.mitfahrt.watch

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import de.luca.mitfahrt.MitfahrtApplication
import de.luca.mitfahrt.shared.WatchProtocol
import de.luca.mitfahrt.shared.WatchCompanion

class WatchRequestService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == WatchProtocol.REQUEST) (application as MitfahrtApplication).watch.requestPublish()
        if (event.path == WatchCompanion.COMMAND) (application as MitfahrtApplication).watch.command(event.data, event.sourceNodeId)
    }
}
