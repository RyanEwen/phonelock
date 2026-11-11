package home.phonelock.parent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * "Add a child's device": first only the choice between setting up a new device and adding one another
 * parent already set up, then that path's steps. A pairing link opened from outside ([initialLink]) goes
 * straight to adding an existing device. [done] gets the added device's id, or null when cancelled.
 */
@Composable
internal fun AddDeviceScreen(initialLink: String?, canCancel: Boolean, done: (String?) -> Unit) {
    // A pairing link opened while this screen is showing switches to adding that device.
    var path by rememberSaveable(initialLink) { mutableStateOf(if (initialLink != null) "existing" else null) }
    when (path) {
        "new" -> WifiSetupScreen(existing = null) { added -> if (added != null) done(added) else path = null }
        "existing" -> AddExistingDevice(initialLink) { added -> if (added != null) done(added) else path = null }
        else -> ChooseHowToAdd(canCancel, onPick = { path = it }, onCancel = { done(null) })
    }
}

/** The two ways to add a device, as cards with what each involves; nothing else on screen. */
@Composable
private fun ChooseHowToAdd(canCancel: Boolean, onPick: (String) -> Unit, onCancel: () -> Unit) {
    if (canCancel) BackHandler(onBack = onCancel)
    Column(Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Add a child's device", style = MaterialTheme.typography.headlineSmall)
        Text("Is PhoneLock already on their phone or tablet?")
        ChoiceCard("Set up a new device",
            "Install PhoneLock on their device from this phone, over Wi-Fi. Takes about 10 minutes, and " +
                "nothing on it is erased.",
            "For a device that doesn't have PhoneLock yet.") { onPick("new") }
        ChoiceCard("Add a device another parent set up",
            "Scan the pairing code from the other parent's app, and you'll both manage it.",
            "For a device that already has PhoneLock.") { onPick("existing") }
        if (canCancel) TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun ChoiceCard(title: String, body: String, whenToUse: String, onClick: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Text(whenToUse, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Adding a device another parent set up: what to expect, scan (or paste) their pairing code, then names.
 * [done] gets the device id, or null to go back to the choice.
 */
@Composable
private fun AddExistingDevice(initialLink: String?, done: (String?) -> Unit) {
    val heading = "Add a device"
    var link by remember { mutableStateOf(initialLink ?: "") }
    val parsed = Hub.parsePairing(link)
    // A link opened from outside skips the explanation and the scan.
    var step by rememberSaveable { mutableIntStateOf(if (parsed != null) 3 else 1) }
    var scanning by remember { mutableStateOf(false) }
    val parentName by Hub.parentName.collectAsState()
    var you by remember { mutableStateOf(parentName) }
    var deviceName by remember(parsed?.topic) { mutableStateOf(parsed?.name ?: "") }

    if (scanning) {
        BackHandler { scanning = false }
        QrScanner(onResult = { link = it; scanning = false; if (Hub.parsePairing(it) != null) step = 3 },
            onCancel = { scanning = false })
        return
    }

    when (step) {
        1 -> WizardIntro(heading, "What to expect", onBack = { done(null) }, onStart = { step = 2 }) {
            StepOutline(listOf(
                "Get the pairing code" to "The other parent opens PhoneLock, taps ⋮ on the device, then Share " +
                    "with another parent. It shows a QR code.",
                "Scan it" to "With this phone's camera, or paste the pairing link if they sent it to you.",
                "Name it" to "Choose what this app calls the device, and how your name appears on it.",
            ))
            Text("You'll need", style = MaterialTheme.typography.titleSmall)
            Requirements(listOf("The other parent's phone nearby, or a pairing link from them"))
        }
        2 -> WizardStep(heading, 1, 2, "Scan the pairing code", onBack = { step = 1 },
            nextEnabled = parsed != null, onNext = { step = 3 }) {
            Text("On the other parent's phone: PhoneLock > ⋮ on the device > Share with another parent.",
                style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { scanning = true }) { Text("Scan QR code") }
            OutlinedTextField(link, { link = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Or paste a pairing link") },
                isError = link.isNotBlank() && parsed == null,
                supportingText = {
                    Text(when {
                        parsed != null -> "Pairing code recognised"
                        link.isNotBlank() -> "This isn't a PhoneLock pairing link"
                        else -> "Starts with phonelock://pair"
                    })
                })
        }
        else -> WizardStep(heading, 2, 2, "Name it", onBack = { step = 2 },
            next = "Add device", nextEnabled = parsed != null && you.isNotBlank(), onNext = {
                parsed?.let {
                    Hub.setParentName(you)
                    Hub.addDevice(it.copy(name = deviceName.ifBlank { it.name }))
                    done(it.id)
                }
            }) {
            OutlinedTextField(deviceName, { deviceName = it }, Modifier.fillMaxWidth(),
                label = { Text("Child's device name") }, singleLine = true,
                supportingText = { Text("What this app calls their device, e.g. their first name") })
            OutlinedTextField(you, { you = it }, Modifier.fillMaxWidth(),
                label = { Text("Your name") }, singleLine = true,
                supportingText = { Text("Shown on their device, e.g. \"${you.ifBlank { "Dad" }} allowed Camera\"") })
        }
    }
}
