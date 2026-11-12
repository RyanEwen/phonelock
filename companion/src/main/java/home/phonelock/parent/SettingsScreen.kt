package home.phonelock.parent

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString

/**
 * One device's settings, from the ⋮ on its header card: names, parent access (parent mode, PIN, battery
 * alert), the lock app (version, updates, diagnostics, repairs), and sharing or removing the device. A page
 * of sections rather than a long dialog, so each group is easy to find; [onOpen] opens Diagnostics or Fix
 * setup, which come back here.
 */
@Composable
internal fun SettingsScreen(dev: String, name: String, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val states by Hub.states.collectAsState()
    val parentName by Hub.parentName.collectAsState()
    val st = states[dev]
    var rename by remember(dev) { mutableStateOf(name) }
    var you by remember { mutableStateOf(parentName) }
    var confirmRemove by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var parentMode by remember { mutableStateOf(false) }
    var changingPin by remember { mutableStateOf(false) }
    var recovery by remember { mutableStateOf(false) }

    /** One tappable setting: a symbol, what it does, and a line about it. */
    @Composable
    fun Setting(symbol: String, title: String, detail: String, onClick: () -> Unit) =
        ListRow(title, AnnotatedString(detail), leading = { SymbolBadge(symbol) }, onClick = onClick)

    Page("Settings", onBack) {
        Section("Names") {
            OutlinedTextField(rename, { rename = it }, Modifier.fillMaxWidth(), label = { Text("Child's device name") },
                singleLine = true)
            OutlinedTextField(you, { you = it }, Modifier.fillMaxWidth(), label = { Text("Your name") }, singleLine = true,
                supportingText = { Text("Shown on their device") })
            // Names are typed, so they're saved together on purpose rather than with every key press.
            OutlinedButton(enabled = (rename.isNotBlank() && rename != name) || (you.isNotBlank() && you != parentName),
                onClick = {
                    if (rename.isNotBlank() && rename != name) Hub.renameDevice(dev, rename)
                    if (you.isNotBlank() && you != parentName) Hub.setParentName(you)
                    Undo.notify("Names saved")
                }) { Text("Save names") }
        }
        Section("Parent access") {
            Setting("🔓", "Parent mode…", "Unlock Settings and the Play Store on their device for a while") { parentMode = true }
            Setting("🔑", "Change parent PIN…", "Used on their device by holding the clock for 4 seconds") { changingPin = true }
            BatteryAlertSetting(dev, st)
        }
        Section("Lock app") {
            Text(buildString {
                append("Version ${st?.version?.takeIf { it > 0 } ?: "unknown"}")
                st?.updateReady?.takeIf { it > 0 }?.let { append(" · version $it installs when the screen is next off") }
            })
            Setting("⬆", "Check for update now", "Installs the next time their screen is off") { Hub.updateNow(dev) }
            Setting("🩺", "Diagnostics", "Checks and recent log from their device") { onOpen("diag") }
            Setting("📶", "Fix setup over Wi-Fi", "Repairs permissions using wireless debugging") { onOpen("fixsetup") }
            Setting("🛠", "Recovery: turn on debugging…", "For repairs from a computer") { recovery = true }
        }
        Section("Sharing") {
            Setting("👥", "Share with another parent…", "Lets another parent's phone manage this device") { sharing = true }
            // Removing is the one destructive setting, so it's a red button rather than a row like the others.
            TextButton(onClick = { confirmRemove = true }) {
                Text("Remove this device…", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (recovery) DebuggingDialog(name, onPick = { Hub.debugging(dev, it); recovery = false }, onDismiss = { recovery = false })
    if (parentMode) ParentModeDialog(name, onPick = { Hub.parentMode(dev, it); parentMode = false },
        onDismiss = { parentMode = false })
    if (changingPin) PinDialog(onSave = { Hub.setPin(dev, it); changingPin = false }, onDismiss = { changingPin = false })
    if (sharing) Hub.devices.value.firstOrNull { it.id == dev }?.let { d ->
        SharePhoneDialog(d.copy(name = name)) { sharing = false }
    }
    if (confirmRemove) AppDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove $name?") },
        text = {
            Text("This app stops managing it. Their device stays locked as it is. To manage it again, another " +
                "parent can share it with you, or set it up again.")
        },
        confirmButton = { DangerButton("Remove") { Hub.removeDevice(dev); confirmRemove = false; onBack() } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } })
}
