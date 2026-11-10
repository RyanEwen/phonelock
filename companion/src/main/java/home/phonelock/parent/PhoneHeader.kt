package home.phonelock.parent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
 * The selected phone at a glance: name, connection, last update, and the current situation as a pill
 * (e.g. "Bedtime until 7:00 AM"), with refresh and settings.
 */
@Composable
internal fun PhoneHeader(name: String, state: PhoneState, connected: Boolean, now: Long,
                         onRefresh: () -> Unit, onMenu: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                IconButton(onClick = onMenu) { Icon(Icons.Default.MoreVert, contentDescription = "Phone settings") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Green when the live connection is up, amber while reconnecting.
                Box(Modifier.size(8.dp).background(if (connected) Color(0xFF3DBE6B) else Color(0xFFE0A030), CircleShape))
                Text((if (connected) "Connected" else "Connecting…") +
                    (if (state.updated > 0) " · updated ${ago(now - state.updated)}" else " · no reply yet"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(modeLabel(state.mode) + (if (state.modeUntil > 0) " until ${time(state.modeUntil)}" else ""),
                    highlighted = state.mode != "normal")
                if (state.parentMode) Pill("Parent mode on", highlighted = true)
                if (state.ringing) Pill("Ringing", highlighted = true)
            }
        }
    }
}

/** A small rounded label; [highlighted] uses the tertiary colour so non-normal states stand out. */
@Composable
internal fun Pill(text: String, highlighted: Boolean = false) {
    val bg = if (highlighted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surface
    val fg = if (highlighted) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurface
    Text(text, Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelLarge, color = fg)
}
