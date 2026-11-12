package home.phonelock.parent

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign

/**
 * "‹ Today ›": steps through days or periods, with [title] centred between the arrows. A null [onEarlier]
 * or [onLater] disables that arrow (nothing earlier kept, or nothing after today). Shared by the App time
 * report and Activity so both pages move through time the same way.
 */
@Composable
internal fun StepNav(title: String, onEarlier: (() -> Unit)?, onLater: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onEarlier?.invoke() }, enabled = onEarlier != null) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Earlier")
        }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        IconButton(onClick = { onLater?.invoke() }, enabled = onLater != null) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Later")
        }
    }
}
