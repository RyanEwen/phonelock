package home.phonelock.parent

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * The one-time "what this app does" screen shown before the first device is added: what is collected from
 * the child's device, who can read it, what the child is told, and the parent's agreement. Google Play
 * requires this prominent disclosure and consent for monitoring apps; it is also simply the honest start.
 */
object Disclosure {
    const val PRIVACY_POLICY_URL = "https://github.com/RyanEwen/phonelock/blob/main/PRIVACY.md"
    /** Bump when the disclosure text changes materially, so parents see and accept the new version. */
    private const val VERSION = 2

    private fun prefs(c: Context) = c.getSharedPreferences("hub", Context.MODE_PRIVATE)

    fun accepted(c: Context) = prefs(c).getInt("disclosure_accepted", 0) >= VERSION

    fun accept(c: Context) = prefs(c).edit().putInt("disclosure_accepted", VERSION).apply()
}

@Composable
internal fun DisclosureScreen(onAccept: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Before you start", style = MaterialTheme.typography.headlineSmall)
        Text("PhoneLock is a parental control app. It lets you manage your child's phone or tablet and see " +
            "information from it.")
        Section("What you'll see from your child's device") {
            Text("• Its location: on request, and a log every 15 minutes (every 2 while moving), kept 7 days\n" +
                "• Battery level, and when it turns off or on\n" +
                "• Installed apps, requests to use them, and which apps they use and for how long\n" +
                "• Wi-Fi networks they join and Bluetooth devices they pair\n" +
                "• Calls (call log) and contacts\n" +
                "• Files, photos and videos in its shared storage, when you open them\n" +
                "• Its screen, when you tap View screen")
        }
        Section("Who can read it") {
            Text("Only you and other parents you share the device with. Everything is end-to-end encrypted " +
                "on the devices; the relay server that passes messages along, and Google's push service, " +
                "only ever see encrypted data. Nothing is sold or used for ads.")
        }
        Section("Your child is told") {
            Text("Their home screen says that location, battery, calls and app use are shared with you, they get a notice " +
                "each time a parent views their screen, and a notification shows that parent controls are " +
                "active. PhoneLock can't be hidden from them.")
        }
        Text("By continuing, you confirm you are the parent or legal guardian of the child whose device " +
            "you'll manage, or have their permission, and that you'll use PhoneLock only for that purpose.",
            style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Disclosure.PRIVACY_POLICY_URL)))
        }) { Text("Privacy policy") }
        Button(onClick = {
            Disclosure.accept(ctx)
            onAccept()
        }) { Text("I agree, continue") }
    }
}
