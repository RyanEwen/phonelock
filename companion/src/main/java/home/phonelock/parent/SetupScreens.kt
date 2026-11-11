package home.phonelock.parent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * "Set up a new device": collects the names, parent PIN and optional Wi-Fi, creates the pairing, adds the
 * phone to this app, then shows the setup QR code with step-by-step instructions. [done] gets the new
 * phone's id, or null if cancelled before a code was made.
 */
@Composable
internal fun SetupPhoneScreen(done: (String?) -> Unit) {
    val parentName by Hub.parentName.collectAsState()
    var child by remember { mutableStateOf("") }
    var you by remember { mutableStateOf(parentName) }
    var pin by remember { mutableStateOf("") }
    var wifi by remember { mutableStateOf("") }
    var wifiPassword by remember { mutableStateOf("") }
    var created by remember { mutableStateOf<Pair<Device, String>?>(null) }

    created?.let { (device, payload) ->
        BackHandler { done(device.id) }
        SetupCode(device, payload) { done(device.id) }
        return
    }
    BackHandler { done(null) }

    val pinOk = pin.length in 4..8 && pin.all(Char::isDigit)
    Column(Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Set up a new device", style = MaterialTheme.typography.headlineSmall)
        Text("This makes a QR code that sets up your child's phone or tablet from scratch: it installs PhoneLock, " +
            "locks it and connects it to this app. Setting up erases the device.")
        OutlinedTextField(child, { child = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Child's device name") },
            supportingText = { Text("What this app calls their device, e.g. their first name") })
        OutlinedTextField(you, { you = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Your name") },
            supportingText = { Text("Shown on their device, e.g. \"${you.ifBlank { "Dad" }} allowed Camera\"") })
        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(8) }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Parent PIN") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation(),
            isError = pin.isNotEmpty() && !pinOk,
            supportingText = { Text("4 to 8 digits. Unlocks parent mode on their device: hold the clock for 4 seconds") })
        OutlinedTextField(wifi, { wifi = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Wi-Fi name (optional)") },
            supportingText = { Text("Connects the device during setup. Leave empty to choose Wi-Fi on the device") })
        if (wifi.isNotBlank()) OutlinedTextField(wifiPassword, { wifiPassword = it }, Modifier.fillMaxWidth(),
            singleLine = true, label = { Text("Wi-Fi password") },
            visualTransformation = PasswordVisualTransformation())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val d = Setup.newDevice(child)
                Hub.setParentName(you)
                Hub.addDevice(d)
                created = d to Setup.provisioningJson(d, pin, wifi, wifiPassword)
            }, enabled = child.isNotBlank() && you.isNotBlank() && pinOk) { Text("Make setup code") }
            TextButton(onClick = { done(null) }) { Text("Cancel") }
        }
    }
}

/**
 * Keeps the screen out of screenshots and the recent-apps preview while it's shown: the setup and pairing
 * codes carry the phone's key (and the setup code the PIN).
 */
@Composable
private fun SecureScreen() {
    val activity = LocalContext.current as? android.app.Activity ?: return
    DisposableEffect(Unit) {
        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** The setup QR code and what to do with it. */
@Composable
private fun SetupCode(device: Device, payload: String, done: () -> Unit) {
    SecureScreen()
    Column(Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Set up ${device.name}'s device", style = MaterialTheme.typography.headlineSmall)
        Text("1. Back up anything on their device that you want to keep. Setup erases it.")
        Text("2. Factory reset it: Settings > System > Reset options > Erase all data.")
        Text("3. On the first welcome screen, tap the same blank spot 6 times. A QR scanner opens.")
        Text("4. Scan this code. The device downloads PhoneLock, locks itself and connects to this app.")
        QrCode(payload)
        Text("Keep this code private: it holds the device's key and your PIN.",
            style = MaterialTheme.typography.bodySmall)
        Text("Afterwards, hold the clock on their home screen for 4 seconds and enter your PIN for parent " +
            "mode. Use it to add their Google account, and to turn on screen view in Settings > " +
            "Accessibility > Parent screen view.")
        Button(onClick = done) { Text("Done") }
    }
}

/** Lets another parent add [device] to their app by scanning its pairing code. */
@Composable
internal fun SharePhoneDialog(device: Device, close: () -> Unit) {
    SecureScreen()
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Share ${device.name}'s device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("On the other parent's phone, open PhoneLock, tap \"Add a device that's already set up\" " +
                    "and scan this code.")
                QrCode(Setup.pairingLink(device))
                Text("Anyone with this code can control the device. Only show it to the other parent.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Done") } })
}
