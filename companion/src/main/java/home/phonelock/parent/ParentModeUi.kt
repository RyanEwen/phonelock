package home.phonelock.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Lengths offered for remote parent mode, in minutes. */
private val PARENT_MODE_MINUTES = listOf(15L, 60L, 240L)

/**
 * Confirms remote parent mode and asks for how long. It unlocks Settings and the Play Store for whoever
 * holds the device, so the dialog says so; it always ends by itself or when the screen turns off.
 */
@Composable
internal fun ParentModeDialog(name: String, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    OptionsDialog("Unlock $name for a parent?", onDismiss,
        note = "Settings and the Play Store open on their device for anyone holding it. It locks again " +
            "when the time ends or the screen turns off.",
        options = PARENT_MODE_MINUTES.map { m -> MenuEntry("For ${fmtMinutes(m)}") { onPick(m) } })
}

/** New parent PIN, typed twice. 4 to 8 digits, like the setup screen. */
@Composable
internal fun PinDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val valid = pin.length in 4..8 && pin.all(Char::isDigit)
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change parent PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(8) }, label = { Text("New PIN (4 to 8 digits)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                OutlinedTextField(again, { again = it.filter(Char::isDigit).take(8) }, label = { Text("Type it again") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    isError = again.isNotEmpty() && again != pin,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                Text("Used on their device: hold the clock for 4 seconds.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = valid && pin == again, onClick = { onSave(pin) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/**
 * Starts limited hours or bedtime by hand: pick which, then how long (the usual lengths, a clock time or a
 * custom length). If the schedule is stricter meanwhile (bedtime starting during manual limited hours), the
 * schedule wins; when it ends, the schedule applies again.
 */
@Composable
internal fun StartModeDialog(name: String, onPick: (String, Long) -> Unit, onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf<String?>(null) }
    val chosen = mode
    if (chosen != null) {
        LengthDialog("${modeLabel(chosen)} for how long?", onPick = { onPick(chosen, it) }, onDismiss = onDismiss)
        return
    }
    OptionsDialog("Start on $name's device", onDismiss,
        note = "Only the apps ticked for it are available. A stricter schedule still applies.",
        options = listOf(MenuEntry("Limited hours") { mode = "limited" }, MenuEntry("Bedtime") { mode = "bedtime" }))
}
