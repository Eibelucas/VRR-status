package de.luca.mitfahrt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.ExitToApp
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.luca.mitfahrt.alerts.HapticTestState
import de.luca.mitfahrt.data.AlertPreferences

@Composable
fun AlertPreferencesCard(
    preferences: AlertPreferences,
    onArrival: (Boolean) -> Unit,
    onExit: (Boolean) -> Unit,
    onLeadSeconds: (Int) -> Unit,
    hapticTest: HapticTestState? = null,
    onHapticTest: (() -> Unit)? = null
) {
    AppCard(shape = Shape, colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Well),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.NotificationsActive, null, tint = Lime, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text("Hinweise & Vibration", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text("Handy & Uhr · „Nicht stören“ gilt weiter", color = Soft, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = Hairline, modifier = Modifier.padding(horizontal = 18.dp))
            AlertSwitch(
                icon = Icons.Outlined.DirectionsBus,
                title = "Bus kommt",
                subtitle = "Erinnert dich vor dem Einsteigen",
                checked = preferences.arrivalEnabled,
                onChange = onArrival
            )
            if (preferences.arrivalEnabled) {
                Column(Modifier.fillMaxWidth().padding(start = 66.dp, end = 18.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Vorlaufzeit", color = Soft, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AlertPreferences.LEAD_OPTIONS.forEach { seconds ->
                            FilterChip(
                                selected = preferences.leadSeconds == seconds,
                                onClick = { onLeadSeconds(seconds) },
                                label = { Text("${seconds / 60} Min") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = Hairline, modifier = Modifier.padding(start = 66.dp, end = 18.dp))
            AlertSwitch(
                icon = Icons.Outlined.ExitToApp,
                title = "Ausstieg",
                subtitle = "Einen Halt vor deinem gewählten Ziel",
                checked = preferences.exitEnabled,
                onChange = onExit
            )
            if (onHapticTest != null) {
                HorizontalDivider(color = Hairline, modifier = Modifier.padding(horizontal = 18.dp))
                Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        shape = ControlShape,
                        onClick = onHapticTest,
                        enabled = hapticTest?.running != true,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)
                    ) {
                        Icon(Icons.Outlined.Vibration, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (hapticTest?.running == true) "Uhr wird geprüft …" else "Vibration testen")
                    }
                    hapticTest?.message?.let { Text(it, color = Soft, fontSize = 12.sp, lineHeight = 17.sp) }
                }
            }
        }
    }
}

@Composable
private fun AlertSwitch(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Well),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = if (checked) Lime else Soft, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Text(subtitle, color = Soft, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
