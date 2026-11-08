package de.luca.mitfahrt.watch

import de.luca.mitfahrt.shared.WatchCommand
import java.time.Instant

/** Commands act on the screen the user saw, never a different journey or newly browsed route. */
internal object WatchCommandPolicy {
    fun sameJourney(command: WatchCommand, currentJourney: String): Boolean =
        command.journey.isNotEmpty() && command.journey == currentJourney

    fun startAllowed(command: WatchCommand, currentJourney: String, selected: String,
        choicesAt: Instant, now: Instant, availableIds: Set<String>): Boolean =
        command.action == "start" && command.journey == currentJourney && command.contextId.isNotEmpty() &&
            command.contextId == selected && now.epochSecond - choicesAt.epochSecond in 0..120 && command.id in availableIds
}
