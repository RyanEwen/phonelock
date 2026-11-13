package home.phonelock.parent

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.LatLng

/**
 * The main screen's emergency card for [dev], at the top while an alert is recent: the same panel as the
 * full-screen alert. Hidden when there's none.
 */
@Composable
internal fun EmergencySection(dev: String) {
    val all by EmergencyAlerts.alerts.collectAsState()
    val a = all.firstOrNull { it.dev == dev } ?: return
    EmergencyPanel(a, onClose = null)
}

/**
 * One emergency alert: who and when, her latest location on a map with the address, battery, her messages,
 * who's on it, and the answers (I'm on it, Call her, Silence, Dismiss). [onClose] closes the full-screen
 * alert (null on the main screen).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EmergencyPanel(a: EmergencyAlerts.Sos, onClose: (() -> Unit)?) {
    val ctx = LocalContext.current
    val places by LocationHistory.places.collectAsState()
    val addresses by LocationHistory.addresses.collectAsState()
    val danger = if (a.cancelled) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.errorContainer
    val onDanger = if (a.cancelled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onErrorContainer

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = danger)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(EmergencyAlerts.title(a), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                color = onDanger)
            Text("Pressed SOS at ${time(a.since)}", color = onDanger)
            Text(EmergencyAlerts.summary(a), style = MaterialTheme.typography.bodyLarge, color = onDanger)

            // Her trail since she pressed SOS (readings every minute), with her latest position.
            val trail = places[a.dev].orEmpty().filter { it.to >= a.since }.reversed()
            val latest = a.where?.let { LatLng(it.lat, it.lng) }
            if (trail.isNotEmpty() || latest != null) {
                TrailMap(trail, marker = latest, markerTitle = "Latest", selection = a.where, addresses = addresses,
                    now = latest, modifier = Modifier.fillMaxWidth().height(220.dp))
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!a.cancelled && Hub.parentName.value.ifBlank { "A parent" } !in a.acks) {
                    Button(onClick = { EmergencyAlerts.ack(a.id) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("I'm on it") }
                }
                a.number?.let { n ->
                    OutlinedButton(onClick = {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n"))) }
                    }) { Text("Call her") }
                }
                if (a.ringing) OutlinedButton(onClick = { EmergencyAlerts.silence(a.id) }) { Text("Silence") }
                TextButton(onClick = {
                    EmergencyAlerts.dismiss(a.id)
                    onClose?.invoke()
                }) { Text("Dismiss") }
            }
        }
    }
}

/**
 * The full-screen emergency alert: shown over the lock screen and turning the screen on, like an incoming
 * call. Closing it doesn't silence the alarm; answering does.
 */
class EmergencyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            val ctx = LocalContext.current
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) {
                Surface(Modifier.fillMaxSize()) {
                    val all by EmergencyAlerts.alerts.collectAsState()
                    val id = intent.getStringExtra("sos")
                    val a = all.firstOrNull { it.id == id } ?: all.firstOrNull()
                    Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (a == null) {
                            EmptyNote("No emergency alert right now.")
                        } else {
                            EmergencyPanel(a, onClose = { finish() })
                        }
                        TextButton(onClick = {
                            startActivity(Intent(this@EmergencyActivity, MainActivity::class.java)
                                .putExtra("device", a?.dev).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            finish()
                        }) { Text("Open PhoneLock") }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
