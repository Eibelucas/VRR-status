package de.luca.mitfahrt.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.*
import de.luca.mitfahrt.shared.WatchRide
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

private val Lime = Color(0xFFFF9BC7)
private val Ink = Color(0xFF21132A)
private val Panel = Color(0xFF281E48)
private val Soft = Color(0xFFCAC0D0)
private val Amber = Color(0xFFFFCB83)

@Composable
fun WatchScreen(store: WatchStore, onRefresh: () -> Unit) {
    val ride by store.ride.collectAsStateWithLifecycle()
    val syncMessage by store.syncMessage.collectAsStateWithLifecycle()
    val refreshing by store.refreshing.collectAsStateWithLifecycle()
    var now by remember { mutableStateOf(Instant.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = Instant.now(); delay(1000) }
        }
    }
    val state = rememberTransformingLazyColumnState()
    MaterialTheme(colorScheme = ColorScheme(primary = Lime, onPrimary = Ink, primaryContainer = Panel,
        onPrimaryContainer = Color.White, background = Color.Black, onBackground = Color.White,
        surfaceContainer = Panel, onSurface = Color.White, onSurfaceVariant = Soft)) {
        AppScaffold {
            ScreenScaffold(scrollState = state) { padding ->
                TransformingLazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = padding,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text("MITFAHRT", color = Soft, fontSize = 10.sp, letterSpacing = 1.4.sp,
                            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                    val current = ride
                    if (current?.active != true) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Keine Fahrt", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, color = Lime)
                                Spacer(Modifier.height(12.dp))
                                Text("Am Handy eine Fahrt wählen.", textAlign = TextAlign.Center, color = Soft, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else {
                        if (current.demo) item { StatusText("Demofahrt", Lime) }
                        if (current.stale(now)) item { StatusText(if (!current.running) "Fahrt pausiert" else "Daten veraltet", Amber) }
                        item { RideHero(current, now) }
                        if (current.boarded) item {
                            WatchPanel {
                                Text("NÄCHSTER HALT", color = Soft, fontSize = 10.sp)
                                Spacer(Modifier.height(5.dp))
                                Text(current.nextStop, style = MaterialTheme.typography.titleSmall)
                            }
                        }
                        item {
                            val fetched = current.fetchedAt?.atZone(ZoneId.of("Europe/Berlin"))?.format(DateTimeFormatter.ofPattern("HH:mm"))
                            StatusText(listOfNotNull(current.source.takeIf { it.isNotBlank() }, fetched?.let { "Stand $it" },
                                current.delay.takeIf { it.isNotBlank() }).joinToString(" · "), Soft)
                        }
                    }
                    syncMessage?.let { message -> item { StatusText(message, Amber) } }
                    item {
                        FilledTonalButton(onClick = onRefresh, enabled = !refreshing, modifier = Modifier.fillMaxWidth()) {
                            Text(if (refreshing) "Verbinden …" else "Aktualisieren")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RideHero(ride: WatchRide, now: Instant) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xFFB40055)).padding(horizontal = 14.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${ride.line} → ${ride.destination}", color = Color.White, style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        val target = if (ride.boarded) ride.terminalAt else ride.boardingAt
        val seconds = target?.let { (it.epochSecond - now.epochSecond).coerceAtLeast(0) }
        val value = when {
            ride.cancelled -> "Fällt aus"
            seconds == null -> "–"
            ride.boarded -> "${ceil(seconds / 60.0).toInt()} Min"
            seconds == 0L -> "Erwartet"
            else -> "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
        }
        Text(value, color = Color.White, fontSize = if (value.length > 7) 26.sp else 36.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text(when {
            ride.cancelled -> "Andere Fahrt wählen"
            ride.boarded -> "bis zum Ziel"
            ride.boardingIsArrival -> "bis dein Bus kommt"
            else -> "bis zur Abfahrt"
        }, color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun WatchPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Panel).padding(16.dp), content = content)
}

@Composable
private fun StatusText(text: String, color: Color) {
    Text(text, color = color, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
}
