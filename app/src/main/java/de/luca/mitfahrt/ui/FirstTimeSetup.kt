package de.luca.mitfahrt.ui

import androidx.compose.foundation.background
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FirstTimeSetup(locationAllowed: Boolean, notificationsAllowed: Boolean,
    onLocation: () -> Unit, onNotifications: () -> Unit, onFinish: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = step > 0) { step-- }
    MitfahrtTheme {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("MITFAHRT", fontSize = 12.sp, letterSpacing = 2.sp, color = Lime, modifier = Modifier.weight(1f))
                    Text("${step + 1} / 3", color = Soft, fontSize = 13.sp)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                    Spacer(Modifier.height(32.dp))
                    Box(Modifier.size(80.dp).clip(RoundedCornerShape(26.dp)).background(Lime), contentAlignment = Alignment.Center) {
                        Icon(when (step) { 0 -> Icons.Outlined.DirectionsBus; 1 -> Icons.Outlined.NearMe; else -> Icons.Outlined.NotificationsActive },
                            null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(38.dp))
                    }
                    Spacer(Modifier.height(28.dp))
                    Text(when (step) { 0 -> "Deine Busfahrt\nim Blick"; 1 -> "Was fährt\nin deiner Nähe?"; else -> "Auch bei\ngesperrtem Handy" },
                        fontSize = 33.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
                    Spacer(Modifier.height(20.dp))
                    if (step == 0) {
                        SetupFeature("Bus-Countdown und nächster Halt", Icons.Outlined.Schedule)
                        SetupFeature("Verspätungsberichte für die Schule", Icons.Outlined.Description)
                        SetupFeature("Optional auch auf deiner Wear-OS-Uhr", Icons.Outlined.Watch)
                    } else Text(if (step == 1) "Mit deinem Standort findest du Haltestellen und passende Strecken direkt beim Öffnen."
                        else "Erhalte Linie, nächsten Halt und Restzeit als laufende Benachrichtigung.", color = Soft, fontSize = 16.sp, lineHeight = 24.sp)
                    if (step == 1 && locationAllowed) {
                        Spacer(Modifier.height(16.dp)); Text("Standort ist freigegeben", color = Lime, fontSize = 14.sp)
                    }
                    if (step == 2) {
                        Spacer(Modifier.height(16.dp))
                        Text(if (notificationsAllowed) "Benachrichtigungen sind freigegeben" else "Du kannst das auch später einrichten.",
                            color = if (notificationsAllowed) Lime else Soft, fontSize = 14.sp)
                        if (Build.MANUFACTURER.equals("samsung", true) && Build.VERSION.SDK_INT >= 36) {
                            Spacer(Modifier.height(12.dp))
                            Text("Die Now Bar richtest du unter Einstellungen ein.", color = Soft, fontSize = 13.sp, lineHeight = 20.sp)
                        }
                    }
                    Spacer(Modifier.height(32.dp))
                }
                Button(onClick = {
                    when {
                        step == 0 -> step++
                        step == 1 && !locationAllowed -> onLocation()
                        step == 1 -> step++
                        !notificationsAllowed -> onNotifications()
                        else -> onFinish()
                    }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(when { step == 0 -> "Weiter"; step == 1 -> if (locationAllowed) "Weiter" else "Standort erlauben"
                        else -> if (notificationsAllowed) "Los geht’s" else "Benachrichtigungen erlauben" })
                }
                TextButton(onClick = { if (step == 1) step++ else onFinish() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (step == 1 && !locationAllowed) "Ohne Standort weiter" else if (step == 2) "App öffnen" else "Einführung überspringen", color = Soft)
                }
            }
        }
    }
}

@Composable
private fun SetupFeature(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Lime, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp)); Text(text, fontSize = 15.sp, lineHeight = 22.sp, color = Paper)
    }
}
