package home.phonelock.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Keeps the screen out of screenshots and the recent-apps preview while it's shown: the pairing code
 * carries the device's key.
 */
@Composable
private fun SecureScreen() {
    val activity = LocalContext.current as? android.app.Activity ?: return
    DisposableEffect(Unit) {
        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** Lets another parent add [device] to their app by scanning its pairing code. */
@Composable
internal fun SharePhoneDialog(device: Device, close: () -> Unit) {
    SecureScreen()
    AppDialog(
        onDismissRequest = close,
        title = { Text("Share ${device.name}'s device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("On the other parent's phone, open PhoneLock, tap \"Add a device another parent set up\" " +
                    "and scan this code.")
                QrCode(Setup.pairingLink(device))
                Text("Anyone with this code can control the device. Only show it to the other parent.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Done") } })
}
