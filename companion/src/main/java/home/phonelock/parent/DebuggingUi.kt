package home.phonelock.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * Recovery access: turn USB and wireless debugging on for a while on the child's device, for repairs or
 * updates over adb when its own screens can't be used. [onPick] gets the minutes, 0 to turn it off now.
 */
@Composable
internal fun DebuggingDialog(name: String, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Turn on debugging?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("For repairs: turns on USB and wireless debugging on $name, so it can be fixed or updated " +
                    "from a computer even if its screens aren't working. It turns off again by itself.")
                Text("While it's on, anyone with a paired computer can control the device. Its activity list " +
                    "will show where to connect.")
                listOf(30L, 60L, 120L).forEach { m ->
                    TextButton(onClick = { onPick(m) }) { Text("On for ${fmtMinutes(m)}") }
                }
                TextButton(onClick = { onPick(0) }) { Text("Turn it off now") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
