package de.luca.mitfahrt.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.luca.mitfahrt.data.*

/** Compact, real saved stations. Undo is surfaced by the caller's shared snackbar. */
@Composable
fun SavedStopsSection(state: SavedStopsState, onSelect: (Stop) -> Unit, onToggleFavorite: (Stop) -> Unit,
    onRemoveRecent: (Stop) -> Unit, modifier: Modifier = Modifier, query: String = "", compact: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    val favorites = state.favorites.filter { SavedStops.matches(it, query) }
    val recent = state.recent.filter { stop -> !state.isFavorite(stop) && SavedStops.matches(stop, query) }
    if (favorites.isEmpty() && (compact || recent.isEmpty())) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (favorites.isNotEmpty()) {
            Text("Favoriten", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            favorites.take(if (expanded) SavedStops.MAX_FAVORITES else if (compact) 2 else 4).forEach { stop ->
                key(stop.id) { SavedStopItem(stop, true, onSelect, onToggleFavorite, null) }
            }
        }
        if (!compact && recent.isNotEmpty()) {
            Text("Zuletzt geöffnet", color = Soft, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            recent.take(if (expanded) SavedStops.MAX_RECENT else 3).forEach { stop ->
                key(stop.id) { SavedStopItem(stop, false, onSelect, onToggleFavorite, onRemoveRecent) }
            }
        }
        if (!expanded && (favorites.size > (if (compact) 2 else 4) || !compact && recent.size > 3))
            TextButton(onClick = { expanded = true }) { Text("Mehr anzeigen") }
        if (expanded) TextButton(onClick = { expanded = false }) { Text("Weniger anzeigen") }
    }
}

@Composable
private fun SavedStopItem(stop: Stop, favorite: Boolean, onSelect: (Stop) -> Unit,
    onToggleFavorite: (Stop) -> Unit, onRemoveRecent: ((Stop) -> Unit)?) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable { onSelect(stop) }.padding(vertical = 14.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (favorite) Icons.Outlined.LocationOn else Icons.Outlined.History, null, tint = if (favorite) Lime else Soft,
                modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(12.dp))
            Text(shortName(stop.name), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
        }
        IconToggleButton(checked = favorite, onCheckedChange = { onToggleFavorite(stop) }) {
            Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                if (favorite) "${shortName(stop.name)} aus Favoriten entfernen" else "${shortName(stop.name)} merken",
                tint = if (favorite) Lime else Soft)
        }
        if (!favorite && onRemoveRecent != null) IconButton(onClick = { onRemoveRecent(stop) }) {
            Icon(Icons.Outlined.Close, "${shortName(stop.name)} aus zuletzt geöffnet entfernen", tint = Soft, modifier = Modifier.size(18.dp))
        }
    }
}
