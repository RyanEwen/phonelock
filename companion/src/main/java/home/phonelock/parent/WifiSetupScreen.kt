package home.phonelock.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import home.phonelock.parent.adb.AdbDiscovery

/** What the parent has entered so far, kept across the steps. */
private class SetupDraft(existing: Device?) {
    var child by mutableStateOf("")
    var you by mutableStateOf(Hub.parentName.value)
    var pin by mutableStateOf("")
    var pinAgain by mutableStateOf("")
    var code by mutableStateOf("")
    var picked by mutableStateOf<String?>(null)
    var manual by mutableStateOf("")
    var connectPort by mutableStateOf("")
    // New devices end locked down; when fixing one, debugging may still be wanted (e.g. for testing).
    var turnOff by mutableStateOf(existing == null)
}

/**
 * Sets up a child's device from this phone over Wireless debugging ([ChildSetup]), as steps: what to expect,
 * preparing their device, names and PIN, pairing, then live progress. With [existing], it fixes the setup of a
 * device already in this app (re-applies the one-time grants): what it does, then pairing. [onDone] gets the
 * id of the device that was added, or null (also when backing out of the first step).
 */
@Composable
internal fun WifiSetupScreen(existing: Device?, onDone: (String?) -> Unit) {
    val run by ChildSetup.run.collectAsState()
    val current = run
    // Kept while setup runs, so "Try again" returns to the pairing step with everything still filled in.
    val draft = remember { SetupDraft(existing) }
    var step by rememberSaveable { mutableIntStateOf(1) }
    if (current != null) {
        Page(if (existing == null) "Set up a new device" else "Fix setup", onBack = {
            // Leaving mid-setup is allowed; it carries on and the result shows next time.
            ChildSetup.reset()
            onDone(current.deviceId)
        }) {
            SetupProgress(current, onRetry = {
                draft.code = "" // each pairing needs a fresh code
                ChildSetup.reset()
            }) {
                ChildSetup.reset()
                onDone(current.deviceId)
            }
        }
        return
    }

    if (existing != null) {
        val heading = "Fix setup"
        when (step) {
            1 -> WizardIntro(heading, "What this does", onBack = { onDone(null) }, onStart = { step = 2 }) {
                Text("Fix setup turns PhoneLock's features back on for ${existing.name}: screen view, call limits " +
                    "and call log, files, ringing through Do Not Disturb, and working while idle. It doesn't " +
                    "change its pairing, schedule or apps.", style = MaterialTheme.typography.bodyMedium)
                Text("You'll need", style = MaterialTheme.typography.titleSmall)
                Requirements(listOf(
                    "Their device on the same Wi-Fi as this phone",
                    "Parent mode unlocked on it (hold the clock for 4 seconds, enter the PIN), to reach Settings",
                    "Developer options turned on there: Settings > About phone, tap Build number 7 times",
                ))
            }
            else -> PairStep(heading, 1, 1, draft, existing, onBack = { step = 1 })
        }
        return
    }

    val heading = "Set up a new device"
    when (step) {
        1 -> WizardIntro(heading, "What to expect", onBack = { onDone(null) }, onStart = { step = 2 }) {
            StepOutline(listOf(
                "Prepare their device" to "Same Wi-Fi, accounts removed for now, Developer options on. About 5 minutes.",
                "Choose names and a PIN" to "The PIN unlocks parent mode on their device.",
                "Pair with this phone" to "Their device shows a 6-digit code; you type it here.",
                "PhoneLock sets it up" to "Installs, connects it to this app and turns on its features. About 2 minutes.",
            ))
            Text("You'll need", style = MaterialTheme.typography.titleSmall)
            Requirements(listOf(
                "Their phone or tablet, with Android 13 or later, charged and in your hands",
                "Both devices on the same Wi-Fi network",
                "To sign out of its accounts until setup is done (you add them back in parent mode)",
            ))
            Text("Nothing on their device is erased.", style = MaterialTheme.typography.bodyMedium)
        }
        2 -> WizardStep(heading, 1, 3, "Prepare their device", onBack = { step = 1 }, onNext = { step = 3 }) {
            StepOutline(listOf(
                "Connect to Wi-Fi" to "The same network as this phone.",
                "Remove its accounts" to "Settings > Passwords & accounts: remove each one (Google and others). " +
                    "Android only lets PhoneLock take charge of a device with no accounts. You add them back at the end.",
                "Turn on Developer options" to "Settings > About phone: tap Build number 7 times, until it says " +
                    "you're a developer.",
            ))
        }
        3 -> {
            val pinsMatch = draft.pin == draft.pinAgain
            WizardStep(heading, 2, 3, "Names and PIN", onBack = { step = 2 },
                nextEnabled = draft.child.isNotBlank() && draft.you.isNotBlank() && draft.pin.length >= 4 && pinsMatch,
                onNext = { step = 4 }) {
                OutlinedTextField(draft.child, { draft.child = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Child's device name") },
                    supportingText = { Text("What this app calls their device, e.g. their first name") })
                OutlinedTextField(draft.you, { draft.you = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Your name") }, supportingText = { Text("Shown on their device") })
                PinField(draft.pin, { draft.pin = it }, "Parent PIN (4 to 8 digits)", "Unlocks parent mode on their device")
                PinField(draft.pinAgain, { draft.pinAgain = it }, "PIN again",
                    if (draft.pinAgain.isNotEmpty() && !pinsMatch) "Doesn't match" else "To be sure it's right",
                    isError = draft.pinAgain.isNotEmpty() && !pinsMatch)
            }
        }
        else -> PairStep(heading, 3, 3, draft, null, onBack = { step = 3 })
    }
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: String, hint: String, isError: Boolean = false) {
    OutlinedTextField(value, { onChange(it.filter(Char::isDigit).take(8)) }, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(label) }, supportingText = { Text(hint) }, isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
}

/**
 * The pairing step: what to open on their device, the device as found on the network, and its code. Rarely
 * needed options (typing its address, keeping debugging on) are folded away. Starts [ChildSetup].
 */
@Composable
private fun PairStep(heading: String, step: Int, steps: Int, draft: SetupDraft, existing: Device?, onBack: () -> Unit) {
    val ctx = LocalContext.current
    // Look for devices showing "Pair device with pairing code" on this network.
    val discovery = remember { AdbDiscovery(ctx) }
    DisposableEffect(Unit) {
        discovery.start()
        onDispose { discovery.close() }
    }
    val found by discovery.found.collectAsState()
    val pairable = found.filter { it.pairing }
    var moreOptions by remember { mutableStateOf(false) }

    // The device to pair: picked from what's showing on the network, or typed as address:port.
    val endpoint = pairable.firstOrNull { it.name == draft.picked } ?: pairable.singleOrNull()
    // A typed address must be complete (host:port); it's never mixed with a found device's port.
    val typedHost = draft.manual.substringBeforeLast(":", "").takeIf { it.isNotEmpty() }
    val typedPort = draft.manual.substringAfterLast(":", "").toIntOrNull()?.takeIf { it in 1..65535 }
    val typing = draft.manual.isNotBlank()
    val host = if (typing) typedHost.takeIf { typedPort != null } else endpoint?.host
    val port = if (typing) typedPort.takeIf { typedHost != null } else endpoint?.port

    WizardStep(heading, step, steps, "Pair with their device", onBack = onBack,
        next = if (existing == null) "Set up" else "Fix setup",
        nextEnabled = host != null && port != null && draft.code.length == 6,
        onNext = {
            discovery.close()
            ChildSetup.start(ChildSetup.Request(host!!, port!!, draft.code, existing?.name ?: draft.child.trim(),
                draft.you.trim(), draft.pin, existing, draft.connectPort.toIntOrNull(), draft.turnOff))
        }) {
        Text("On their device: Settings > System > Developer options > Wireless debugging. Turn it on, then " +
            "tap Pair device with pairing code. Keep that screen open until setup starts.",
            style = MaterialTheme.typography.bodyMedium)
        if (pairable.isEmpty()) {
            Text("Looking for it… It appears here once its pairing code is showing.",
                style = MaterialTheme.typography.bodySmall)
        } else {
            pairable.forEach { e ->
                FilterChip(selected = endpoint == e, onClick = { draft.picked = e.name },
                    label = { Text("${e.name} (${e.host})") })
            }
        }
        OutlinedTextField(draft.code, { draft.code = it.filter(Char::isDigit).take(6) }, Modifier.fillMaxWidth(),
            singleLine = true, label = { Text("Wi-Fi pairing code") },
            supportingText = { Text("The 6 digits on their screen") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))

        TextButton(onClick = { moreOptions = !moreOptions }) {
            Text(if (moreOptions) "Fewer options" else "Not found, or more options")
        }
        if (moreOptions) {
            OutlinedTextField(draft.manual, { draft.manual = it.trim() }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Its IP address & port") },
                supportingText = { Text("Shown under the pairing code, e.g. 192.168.1.20:37755") })
            OutlinedTextField(draft.connectPort, { draft.connectPort = it.filter(Char::isDigit).take(5) },
                Modifier.fillMaxWidth(), singleLine = true, label = { Text("Its debugging port") },
                supportingText = { Text("Only if setup can't find it: the port after \"IP address & Port\" on the Wireless debugging screen") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Turn off debugging when done", style = MaterialTheme.typography.bodyMedium)
                    Text("Recommended, so it can't be used to undo PhoneLock. Parent mode can turn it back on.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(draft.turnOff, { draft.turnOff = it })
            }
        }
    }
}

@Composable
private fun SetupProgress(run: SetupRun, onRetry: () -> Unit, onClose: () -> Unit) {
    Section(if (run.finished) (if (run.failed) "Setup stopped" else "Done") else "Setting up…") {
        run.steps.forEach { s ->
            val (mark, color) = when (s.state) {
                StepState.WAITING -> "·" to MaterialTheme.colorScheme.onSurfaceVariant
                StepState.RUNNING -> "…" to MaterialTheme.colorScheme.primary
                StepState.DONE -> "✓" to MaterialTheme.colorScheme.onSurface
                StepState.SKIPPED -> "–" to MaterialTheme.colorScheme.onSurfaceVariant
                StepState.FAILED -> "✗" to MaterialTheme.colorScheme.error
            }
            Row {
                Text(mark, Modifier.width(24.dp), color = color, fontWeight = FontWeight.Bold)
                Column {
                    Text(s.label, color = color, style = MaterialTheme.typography.bodyMedium)
                    s.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    run.outcome?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
    if (run.finished && !run.failed) Text(
        (if (run.debuggingOff) "Wireless debugging is now off on their device. "
        else "Wireless debugging is still on on their device; turn it off in parent mode once it's working. ") +
            "Add their accounts back in parent mode (hold the clock for 4 seconds).", style = MaterialTheme.typography.bodyMedium)
    // Setup takes a couple of minutes: keep this phone awake and in front meanwhile (Android may pause a
    // background app part way through).
    if (!run.finished) {
        val view = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(Unit) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
        Text("Keep this screen open until setup finishes.", style = MaterialTheme.typography.bodySmall)
    }
    if (run.finished) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (run.failed) {
            Button(onClick = onRetry) { Text("Try again") }
            TextButton(onClick = onClose) { Text("Cancel") }
        } else {
            Button(onClick = onClose) { Text("Done") }
        }
    }
}
