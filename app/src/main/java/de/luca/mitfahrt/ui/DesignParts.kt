package de.luca.mitfahrt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A chip that shows "Name: value" and opens the choices as a menu. */
@Composable
internal fun MenuChip(name: String, value: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected = false, onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp),
            label = { Text("$name: $value") },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null, Modifier.size(18.dp)) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (label, key) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onSelect(key) })
            }
        }
    }
}

/** Where a time comes from. Every time in the app carries one of these, so nobody waits for a bus that only exists on paper. */
internal enum class TimeSource { LIVE, PLAN, ESTIMATE }

/**
 * Herkunftsmarke: a filled dot plus "live" for fresh real-time data, a ring plus "Plan" for the timetable,
 * "ca." for estimates. Shape and word carry the meaning, colour only repeats it.
 */
@Composable
internal fun SourceMark(source: TimeSource, modifier: Modifier = Modifier, detail: String? = null, onBand: Boolean = false) {
    val label = when (source) { TimeSource.LIVE -> "live"; TimeSource.PLAN -> "Plan"; TimeSource.ESTIMATE -> "ca." }
    val color = when {
        onBand -> Color.White
        source == TimeSource.LIVE -> MaterialTheme.colorScheme.primary
        else -> Soft
    }
    val spoken = when (source) {
        TimeSource.LIVE -> "Live-Daten"
        TimeSource.PLAN -> "laut Fahrplan"
        TimeSource.ESTIMATE -> "geschätzt"
    } + (detail?.let { ", $it" } ?: "")
    Row(modifier.clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        val dot = Modifier.size(7.dp).clip(CircleShape)
        if (source == TimeSource.LIVE) Box(dot.background(color)) else Box(dot.border(1.5.dp, color, CircleShape))
        Text(listOfNotNull(label, detail).joinToString(" "), color = color, fontSize = 12.sp, lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
