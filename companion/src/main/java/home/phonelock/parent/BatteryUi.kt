package home.phonelock.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** Low-battery alert levels offered to parents, in percent; 0 is "Off". */
private val ALERT_LEVELS = listOf(0, 10, 15, 20, 30)

/**
 * Device settings row: when to get a "Battery low" alert. The level lives on the device, so both parents
 * share it; it can be changed once a full state has said what it is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BatteryAlertSetting(dev: String, state: PhoneState?) {
    val current = state?.battAlert ?: -1
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Low battery alert", style = MaterialTheme.typography.labelLarge)
        if (current < 0) {
            Text("Waiting for the device to report its setting.", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ALERT_LEVELS.forEach { level ->
                FilterChip(selected = current == level, onClick = { Hub.setBatteryAlert(dev, level) },
                    label = { Text(if (level == 0) "Off" else "$level%") })
            }
        }
    }
}
