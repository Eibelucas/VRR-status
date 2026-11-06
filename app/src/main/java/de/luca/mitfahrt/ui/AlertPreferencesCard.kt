package de.luca.mitfahrt.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.luca.mitfahrt.data.AlertPreferences

@Composable
fun AlertPreferencesCard(preferences: AlertPreferences, onArrival: (Boolean) -> Unit,
    onExit: (Boolean) -> Unit, onLeadSeconds: (Int) -> Unit) {
    Card(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Vibrationshinweise", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            AlertSwitch("Bus kommt", "Vor dem Einsteigen · Handy und Uhr", preferences.arrivalEnabled, onArrival)
            if (preferences.arrivalEnabled) {
                Text("Wie früh?", color = Soft, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AlertPreferences.LEAD_OPTIONS.forEach { seconds ->
                        FilterChip(selected = preferences.leadSeconds == seconds, onClick = { onLeadSeconds(seconds) },
                            label = { Text("${seconds / 60} Min") }, modifier = Modifier.weight(1f))
                    }
                }
            }
            AlertSwitch("Ausstieg", "Einen Halt vor deinem gewählten Ziel", preferences.exitEnabled, onExit)
            Text("Ein Hinweis je Fahrt. Nicht stören und Vibrationsstärke gelten weiterhin.",
                color = Soft, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun AlertSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Soft, fontSize = 12.sp, lineHeight = 18.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
