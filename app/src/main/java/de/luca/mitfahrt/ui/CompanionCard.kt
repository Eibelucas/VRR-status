package de.luca.mitfahrt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.luca.mitfahrt.data.*
import java.time.Instant

/**
 * State of an accompanied journey with several legs. The next leg starts only by the explicit
 * button; the transfer is judged only with live data on both sides.
 */
@Composable
fun CompanionCard(companion: JourneyCompanion, phase: CompanionPhase, tracked: Ride?, next: Ride?, now: Instant, busy: Boolean,
    onContinue: () -> Unit, onResume: () -> Unit, onAlternatives: () -> Unit, onEnd: () -> Unit) {
    val nextLeg = companion.nextLeg
    val transfer = JourneyCompanions.transfer(companion, tracked, next, now)
    val nextBoarding = next?.takeIf { nextLeg != null && it.departure.tripCode == nextLeg.tripCode }?.let { it.stops.getOrNull(it.boardingIndex) }
    val nextCancelled = transfer?.outgoingCancelled == true || next?.departure?.cancelled == true || nextLeg?.cancelled == true
    AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Route, null, tint = Lime, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(when (phase) {
                    CompanionPhase.TRACKING -> "Abschnitt ${companion.position} von ${companion.count}"
                    CompanionPhase.TRANSFER -> "Umstieg"
                    CompanionPhase.FINISHED -> "Reise beendet"
                    CompanionPhase.DETACHED -> "Begleitung pausiert"
                }, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f).semantics { heading() })
                Text("Ziel: ${shortName(companion.destinationName)}", color = Soft, fontSize = 12.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
            }
            when (phase) {
                CompanionPhase.TRACKING -> {
                    Text("Ausstieg: ${shortName(companion.leg.destination.name)}" +
                        companion.leg.destination.platform.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(), fontSize = 15.sp)
                    if (nextLeg == null && companion.finalWalkSeconds >= 60)
                        Text("Danach ${durationLabel(companion.finalWalkSeconds)} zu Fuß", color = Soft, fontSize = 13.sp)
                }
                CompanionPhase.DETACHED -> Text(if (companion.trackedKey == null || tracked == null)
                    "${companion.leg.line} ab ${shortName(companion.leg.origin.name)} wird gerade nicht verfolgt."
                    else "Gerade wird eine andere Fahrt verfolgt.", color = Soft, fontSize = 14.sp)
                CompanionPhase.FINISHED -> Text("Der letzte Abschnitt ist beendet.", color = Soft, fontSize = 14.sp)
                CompanionPhase.TRANSFER -> Unit
            }
            if (nextLeg != null && phase != CompanionPhase.FINISHED) {
                val planned = nextLeg.origin.plannedDeparture
                val live = nextBoarding?.estimatedDeparture
                Text(buildString {
                    append(if (phase == CompanionPhase.TRANSFER) "Weiter mit " else "Danach ")
                    append(nextLeg.line.ifBlank { nextLeg.lineName })
                    if (nextLeg.direction.isNotBlank()) append(" Richtung ${shortName(nextLeg.direction)}")
                    append(" ab ${shortName(nextLeg.origin.name)}")
                }, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(listOfNotNull(
                    if (live != null) "${clockTime(live)} live" else planned?.let { "${clockTime(it)} laut Fahrplan" },
                    nextLeg.origin.platform.takeIf { it.isNotBlank() },
                    companion.transferWalkSeconds.takeIf { it >= 60 }?.let { "${durationLabel(it)} Fußweg" }).joinToString(" · "),
                    color = if (live != null) Lime else Soft, fontSize = 13.sp)
                when {
                    nextCancelled -> Warning("Anschluss fällt laut VRR aus.")
                    transfer?.risk == TransferRisk.MISSED -> Warning("Anschluss laut Live-Daten nicht erreichbar.")
                    transfer?.risk == TransferRisk.TIGHT -> Warning("Anschluss knapp: ${(transfer?.slackSeconds ?: 0) / 60} Min Puffer laut Live-Daten.")
                    transfer?.risk == TransferRisk.OK -> Text("${(transfer?.slackSeconds ?: 0) / 60} Min Puffer laut Live-Daten", color = Soft, fontSize = 12.sp)
                }
            }
            when (phase) {
                CompanionPhase.TRACKING, CompanionPhase.TRANSFER -> if (nextLeg != null) {
                    if (phase == CompanionPhase.TRANSFER) Button(onClick = onContinue, enabled = !busy && !nextCancelled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Nächsten Abschnitt begleiten") }
                    else OutlinedButton(onClick = onContinue, enabled = !busy && !nextCancelled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Ausgestiegen · weiter mit ${nextLeg.line.ifBlank { "nächster Fahrt" }}") }
                    if (nextCancelled || transfer?.risk == TransferRisk.MISSED || transfer?.risk == TransferRisk.TIGHT)
                        OutlinedButton(onClick = onAlternatives, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Alternativen suchen") }
                }
                CompanionPhase.DETACHED -> Button(onClick = onResume, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text("Abschnitt ${companion.position} begleiten")
                }
                CompanionPhase.FINISHED -> Unit
            }
            TextButton(onClick = onEnd, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (phase == CompanionPhase.FINISHED) "Schließen" else "Begleitung beenden")
            }
        }
    }
}

@Composable
private fun Warning(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.WarningAmber, null, tint = Amber, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Amber, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
