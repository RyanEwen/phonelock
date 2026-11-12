package home.phonelock.parent

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** A tonal button with an icon over its label, for the Activity / Ring / View screen row. */
@Composable
internal fun ActionTile(icon: ImageVector, label: String, modifier: Modifier = Modifier, enabled: Boolean = true,
                       highlighted: Boolean = false, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 76.dp),
        shape = RoundedCornerShape(20.dp), contentPadding = PaddingValues(8.dp),
        colors = if (highlighted) ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer) else ButtonDefaults.filledTonalButtonColors()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = null)
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

/** Auto-refresh cadence and length for "Keep refreshing" on the screen view. */
private const val REFRESH_EVERY_MS = 10_000L
private const val REFRESH_FOR_MS = 2 * 60_000L

/**
 * "Find and reach" card for one device: find it (and open the spot in Google Maps), ring it, view its
 * screen with optional auto-refresh, and open the Calls / Contacts / Files pages ([onOpen]). [now] is the screen's ticking clock, for "x min ago" text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FindAndReach(dev: String, name: String, state: PhoneState, now: Long, onOpen: (String) -> Unit) {
    val locations by PhoneTools.locations.collectAsState()
    val screens by PhoneTools.screens.collectAsState()
    val locating by PhoneTools.locating.collectAsState()
    val capturing by PhoneTools.capturing.collectAsState()
    var confirmRing by remember { mutableStateOf(false) }

    Section("Find and reach") {
        // Three equal tonal tiles: icon above label, easy to hit with a thumb.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Activity opens the map page, which finds the device and shows where it has been and what it did.
            ActionTile(Icons.Default.LocationOn, "Activity", modifier = Modifier.weight(1f)) { onOpen("history") }
            if (state.ringing) {
                ActionTile(Icons.Default.Notifications, "Stop ringing", highlighted = true,
                    modifier = Modifier.weight(1f)) { PhoneTools.stopRinging(dev) }
            } else {
                // Waiting shows in the tile itself, so nothing on the page moves.
                val asking = isWaiting(dev, "ring")
                ActionTile(Icons.Default.Notifications, if (asking) "Ringing…" else "Ring", enabled = !asking,
                    modifier = Modifier.weight(1f)) { confirmRing = true }
            }
            ActionTile(Icons.Default.Face, if (dev in capturing) "Capturing…" else "View screen",
                enabled = dev !in capturing, modifier = Modifier.weight(1f)) { PhoneTools.viewScreen(dev) }
        }
        // Ringing is confirmed by the device's state rather than a reply to the request.
        LaunchedEffect(state.ringing) { if (state.ringing) Pending.clear(dev, "ring") }
        // Second row: pages for calls, contacts, files and app time.
        MoreRow(onOpen)

        screens[dev]?.let { ScreenResult(dev, it, capturing = dev in capturing) }
    }

    if (confirmRing) AppDialog(
        onDismissRequest = { confirmRing = false },
        title = { Text("Ring $name?") },
        text = { Text("It rings at full volume for 2 minutes, even on silent, until someone taps Stop.") },
        confirmButton = {
            TextButton(onClick = { PhoneTools.ring(dev); confirmRing = false }) { Text("Ring") }
        },
        dismissButton = { TextButton(onClick = { confirmRing = false }) { Text("Cancel") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScreenResult(dev: String, shot: PhoneScreen, capturing: Boolean) {
    var refreshing by remember(dev) { mutableStateOf(false) }
    // Re-request every few seconds while "Keep refreshing" is on; stops by itself, or when leaving the screen.
    LaunchedEffect(dev, refreshing) {
        if (!refreshing) return@LaunchedEffect
        val end = System.currentTimeMillis() + REFRESH_FOR_MS
        while (System.currentTimeMillis() < end) {
            delay(REFRESH_EVERY_MS)
            PhoneTools.viewScreen(dev)
        }
        refreshing = false
    }

    shot.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    shot.image?.let {
        Image(it, contentDescription = "Device screen", Modifier.fillMaxWidth().heightIn(max = 560.dp),
            contentScale = ContentScale.Fit)
    }
    Text(listOfNotNull(shot.label, time(shot.ts)).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { PhoneTools.viewScreen(dev) }, enabled = !capturing) { Text("Refresh") }
        FilterChip(selected = refreshing, onClick = { refreshing = !refreshing },
            label = { Text("Keep refreshing (2 min)") })
        TextButton(onClick = { refreshing = false; PhoneTools.clearScreen(dev) }) { Text("Hide") }
    }
}
