package de.luca.mitfahrt.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.luca.mitfahrt.data.*
import java.time.LocalDate

/** Local punctuality figures for own routes; VRR forecasts and own records are kept apart. */
@Composable
internal fun PunctualityScreen(entries: List<ArchivedJourney>, marks: List<ArrivalMark>, today: LocalDate) {
    var days by rememberSaveable { mutableStateOf(30) }
    var route by rememberSaveable { mutableStateOf<String?>(null) }
    val routes = PunctualityStats.routes(entries)
    val result = PunctualityStats.compute(entries, marks, if (days == 0) null else today.minusDays(days - 1L), today, route)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7 to "7 Tage", 30 to "30 Tage", 90 to "90 Tage", 0 to "Alles").forEach { (value, label) ->
                FilterChip(selected = days == value, onClick = { days = value }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        if (routes.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = route == null, onClick = { route = null }, label = { Text("Alle Strecken") }, modifier = Modifier.heightIn(min = 48.dp))
            routes.take(8).forEach { option ->
                FilterChip(selected = route == option.key, onClick = { route = option.key }, label = { Text(option.label) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        Text("${result.rides} Fahrten im Zeitraum", fontSize = 16.sp)
        if (result.rides == 0) {
            Text("Noch keine begleiteten Fahrten in diesem Zeitraum.", color = Soft)
            return@Column
        }
        Figure("Laut VRR-Prognose am Ausstieg", result.forecastRate, result.forecastRides, result.forecastMedianDelay,
            "${result.forecastMissing} ohne Prognose, nicht mitgezählt")
        Figure("Laut eigener Ankunft", result.ownRate, result.ownRides, result.ownMedianDelay,
            "${result.ownMissing} ohne eigene Ankunft, nicht mitgezählt")
        Text("Pünktlich heißt: weniger als ${PunctualityStats.LATE_SECONDS / 60} Minuten nach Fahrplan. Die VRR-Zahl beruht auf der letzten Prognose, " +
            "die die App gesehen hat, nicht auf einer gemessenen Ankunft. Alles wird nur auf diesem Gerät berechnet.",
            color = Soft, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun Figure(title: String, rate: Int?, count: Int, median: Long?, missing: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.semantics { heading() })
        if (rate == null) Text("Keine Angaben", color = Soft, fontSize = 15.sp)
        else {
            Text("$rate % pünktlich", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(listOfNotNull("aus $count Fahrten", median?.let { "Median ${if (it >= 0) "+" else "−"}${kotlin.math.abs(it) / 60} Min" })
                .joinToString(" · "), fontSize = 14.sp)
            if (count < 5) Text("Wenige Fahrten, die Quote schwankt stark.", color = Amber, fontSize = 13.sp)
        }
        Text(missing, color = Soft, fontSize = 13.sp)
    }
}
