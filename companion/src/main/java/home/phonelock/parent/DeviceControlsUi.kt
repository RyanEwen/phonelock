package home.phonelock.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Wi-Fi and Bluetooth on the child's device. The device schedules and allows them like apps (see the lock
 * app's BuiltInApps), but they aren't apps: here they stay out of the app grids and list, and get their own
 * section instead. The ids are the device's.
 */
internal object DeviceControls {
    const val WIFI = "home.phonelock.wifi"
    const val BLUETOOTH = "home.phonelock.bluetooth"
    val ALL = listOf(WIFI, BLUETOOTH)

    fun isControl(pkg: String) = pkg in ALL

    fun label(id: String) = if (id == BLUETOOTH) "Bluetooth settings" else "Wi-Fi settings"
}

/** [pkgs] without Wi-Fi and Bluetooth, for the app grids and list. */
internal fun appsOnly(pkgs: Collection<String>): List<String> = pkgs.filterNot(DeviceControls::isControl)

/**
 * Wi-Fi and Bluetooth as pills after an app grid (they aren't apps, so not in it): [label] says what each is
 * doing; tapping one calls [onTap] (allow or pause it for a while, as for an app).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ControlPills(dev: String, ids: List<String>, label: @Composable (String) -> String, onTap: (String) -> Unit) {
    if (ids.isEmpty()) return
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
        ids.forEach { id ->
            androidx.compose.material3.AssistChip(onClick = { onTap(id) }, label = { Text(label(id)) },
                // The same icon the child sees, sent by the device like app icons.
                leadingIcon = { AppIcon(dev, id, DeviceControls.label(id), 22.dp) })
        }
    }
}

/**
 * "Wi-Fi and Bluetooth": in which situations the child can manage each (the same Normal / Limited / Bedtime
 * ticks as apps), from the ⋮ button next to "Ask for an app". Allowing or pausing them for a while is done
 * from their pills under "Allowed right now" / "Not allowed right now". A tinted card with plain rows, so it
 * doesn't read as more apps.
 */
@Composable
internal fun DeviceControlsSection(dev: String, state: PhoneState) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Wi-Fi and Bluetooth", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("When they can join networks and pair devices themselves, from the ⋮ button next to " +
                "\"Ask for an app\". Settings stays locked either way.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("", Modifier.weight(1f))
                listOf("Normal", "Limited", "Bedtime").forEach {
                    Text(it, Modifier.width(56.dp), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                }
            }
            DeviceControls.ALL.forEach { id ->
                val mask = if (id in state.home) state.masks[id] ?: (MODE_NORMAL or MODE_LIMITED) else 0
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(DeviceControls.label(id), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium)
                    listOf(MODE_NORMAL, MODE_LIMITED, MODE_BEDTIME).forEach { b ->
                        Row(Modifier.width(56.dp), horizontalArrangement = Arrangement.Center) {
                            Checkbox(checked = mask and b != 0, enabled = state.full, onCheckedChange = { on ->
                                Hub.appModeBit(dev, id, b, on)
                                Undo.offer("${DeviceControls.label(id)}: ${situationName(b)} ${if (on) "allowed" else "not allowed"}") {
                                    Hub.appModeBit(dev, id, b, !on)
                                }
                            })
                        }
                    }
                }
            }
        }
    }
}
