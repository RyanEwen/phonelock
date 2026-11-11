package home.phonelock.parent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The selected device at a glance: name, connection (live connection or push), last update, and the current situation as a pill
 * (e.g. "Bedtime until 7:00 AM"), with refresh and settings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhoneHeader(name: String, state: PhoneState, connected: Boolean, now: Long,
                         onRefresh: () -> Unit, onMenu: () -> Unit, onLockNow: () -> Unit = {},
                         onStartMode: () -> Unit = {}, onEndMode: () -> Unit = {}) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                IconButton(onClick = onMenu) { Icon(Icons.Default.MoreVert, contentDescription = "Device settings") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Green when the live connection is up, amber while reconnecting.
                Box(Modifier.size(8.dp).background(if (connected) Color(0xFF3DBE6B) else Color(0xFFE0A030), CircleShape))
                Text((if (connected) "Connected" else "Connecting…") +
                    (if (state.updated > 0) " · updated ${ago(now - state.updated)}" else " · no reply yet"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            // Pills wrap onto more lines rather than running off a narrow screen.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                Pill(situationLine(state), highlighted = state.mode != "normal")
                if (state.ringing) Pill("Ringing", highlighted = true)
            }
            // Start limited hours or bedtime by hand, or end the one that's running.
            val manualOn = state.manualMode.isNotEmpty() && state.manualUntil > System.currentTimeMillis()
            if (manualOn) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${modeLabel(state.manualMode)} started by hand until ${time(state.manualUntil)}",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                androidx.compose.material3.TextButton(onClick = onEndMode) { Text("End now") }
            } else androidx.compose.material3.TextButton(onClick = onStartMode) { Text("Start limited hours or bedtime…") }
            if (state.parentMode) Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Parent mode" + if (state.parentUntil > 0) " until ${time(state.parentUntil)}" else " on",
                    highlighted = true)
                androidx.compose.material3.TextButton(onClick = onLockNow) { Text("Lock now") }
            }
        }
    }
}

/**
 * "Bedtime until 7:00 AM" / "Limited hours until 3:15 PM" during a scheduled situation, and in normal hours
 * what comes next: "Normal hours · Bedtime at 9:30 PM". Times more than a day away get their weekday.
 */
@Composable
private fun situationLine(state: PhoneState): String {
    val until = state.modeUntil
    if (until <= 0) return if (state.mode == "normal") "Normal hours" else modeLabel(state.mode)
    val at = whenText(until)
    if (state.mode != "normal") return "${modeLabel(state.mode)} until $at"
    val next = state.schedule.modeAt(until)
    return if (next == "normal") "Normal hours" else "Normal hours · ${modeLabel(next)} at $at"
}

/** The time of [ms], with the weekday in front when it's more than a day away. */
@Composable
private fun whenText(ms: Long): String {
    val t = time(ms)
    if (ms - System.currentTimeMillis() < 24 * 3600_000L) return t
    return java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(java.util.Date(ms)) + " " + t
}

/** A small rounded label; [highlighted] uses the tertiary colour so non-normal states stand out. */
@Composable
internal fun Pill(text: String, highlighted: Boolean = false) {
    val bg = if (highlighted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surface
    val fg = if (highlighted) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurface
    Text(text, Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelLarge, color = fg)
}
