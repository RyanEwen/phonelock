package home.phonelock.parent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Date

class MainActivity : ComponentActivity() {
    private val pendingLink = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingLink.value = intent?.dataString
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        ParentService.start(this)
        setContent {
            val ctx = LocalContext.current
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) {
                Surface(Modifier.fillMaxSize()) {
                    val cfg by Hub.config.collectAsState()
                    val link by pendingLink
                    if (cfg == null || link != null) {
                        PairScreen(link) { pendingLink.value = null }
                    } else {
                        MainScreen()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { pendingLink.value = it }
    }

    override fun onResume() {
        super.onResume()
        if (Hub.config.value != null) Hub.refresh(true)
    }
}

@Composable
private fun PairScreen(initialLink: String?, done: () -> Unit) {
    var link by remember { mutableStateOf(initialLink ?: "") }
    var name by remember { mutableStateOf("") }
    val parsed = Hub.parsePairing(link)
    Column(Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Pair with her phone", style = MaterialTheme.typography.headlineSmall)
        Text("Scan the pairing QR code with your camera, or paste the pairing link here.")
        OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), label = { Text("Pairing link") },
            isError = link.isNotBlank() && parsed == null)
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Your name (e.g. Dad)") },
            singleLine = true)
        Button(onClick = {
            parsed?.let { (s, t, k) -> Hub.pair(s, t, k, name); done() }
        }, enabled = parsed != null && name.isNotBlank()) { Text("Pair") }
        if (Hub.config.value != null) TextButton(onClick = done) { Text("Cancel") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainScreen() {
    val ctx = LocalContext.current
    val state by Hub.state.collectAsState()
    val requests by Hub.requests.collectAsState()
    val events by Hub.events.collectAsState()
    val connected by Hub.connected.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    var menu by remember { mutableStateOf(false) }

    Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Her phone", style = MaterialTheme.typography.headlineSmall)
                Text(
                    (if (connected) "Connected" else "Connecting…") +
                        (if (state.updated > 0) " · updated ${ago(now - state.updated)}" else "") +
                        (if (state.parentMode) " · parent mode on" else ""),
                    style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { Hub.refresh(true) }) { Text("Refresh") }
            TextButton(onClick = { menu = true }) { Text("⋮") }
        }
        BatteryHint()

        if (requests.isNotEmpty()) Section("Requests") {
            requests.forEach { r ->
                Text("${r.label} for ${fmtMinutes(r.minutes)} · ${time(r.ts)}", fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { Hub.grant(r.app, r.minutes, r.req) }) { Text("Allow ${fmtMinutes(r.minutes)}") }
                    if (r.minutes != 30L) OutlinedButton(onClick = { Hub.grant(r.app, 30, r.req) }) { Text("30 min") }
                    OutlinedButton(onClick = { Hub.deny(r) }) { Text("Deny") }
                }
            }
        }

        Section("Allowed right now") {
            val live = state.grants.filterValues { it > now }
            if (live.isEmpty()) Text("Only her normal apps.", style = MaterialTheme.typography.bodyMedium)
            live.forEach { (pkg, until) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(state.label(pkg), fontWeight = FontWeight.Medium)
                        Text("until ${time(until)} (${fmtMinutes((until - now) / 60_000 + 1)} left)",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = {
                        Hub.grant(pkg, (until - System.currentTimeMillis()) / 60_000 + 30)
                    }) { Text("+30 min") }
                    TextButton(onClick = { Hub.revoke(pkg) }) { Text("Stop") }
                }
            }
            if (live.size > 1) OutlinedButton(onClick = { Hub.revoke("all") }) { Text("Stop all") }
        }

        Section("Give access") {
            var pick by remember { mutableStateOf<String?>(null) }
            var mins by remember { mutableLongStateOf(60L) }
            if (state.others.isEmpty()) Text("Tap Refresh to load her apps.", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.others.sortedBy { state.label(it).lowercase() }.forEach { p ->
                    FilterChip(selected = pick == p, onClick = { pick = p }, label = { Text(state.label(p)) })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15L, 30L, 60L, 120L, 240L, 480L).forEach { m ->
                    FilterChip(selected = mins == m, onClick = { mins = m }, label = { Text(fmtMinutes(m)) })
                }
            }
            Button(onClick = { pick?.let { Hub.grant(it, mins); pick = null } }, enabled = pick != null) {
                Text(pick?.let { "Allow ${state.label(it)} for ${fmtMinutes(mins)}" } ?: "Pick an app")
            }
        }

        Section("Home screen apps") {
            var adding by remember { mutableStateOf(false) }
            state.home.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.label(p), Modifier.weight(1f))
                    var confirm by remember { mutableStateOf(false) }
                    TextButton(onClick = { confirm = true }) { Text("Remove") }
                    if (confirm) AlertDialog(
                        onDismissRequest = { confirm = false },
                        title = { Text("Remove ${state.label(p)}?") },
                        text = { Text("It will disappear from her home screen and be hidden.") },
                        confirmButton = { TextButton(onClick = { Hub.home("remove", p); confirm = false }) { Text("Remove") } },
                        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
                }
            }
            OutlinedButton(onClick = { adding = !adding }) { Text(if (adding) "Done" else "Add an app…") }
            if (adding) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.others.sortedBy { state.label(it).lowercase() }.forEach { p ->
                    OutlinedButton(onClick = { Hub.home("add", p); adding = false }) { Text("+ ${state.label(p)}") }
                }
            }
        }

        if (events.isNotEmpty()) Section("Activity") {
            events.take(20).forEach { e ->
                Text("${time(e.ts)}  ${e.text}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.width(1.dp))
    }

    if (menu) AlertDialog(
        onDismissRequest = { menu = false },
        title = { Text("Settings") },
        text = { Text("Paired as ${Hub.config.value?.name}. Unpairing removes the secret from this phone; " +
                "you'll need the pairing QR code to pair again.") },
        confirmButton = { TextButton(onClick = { Hub.unpair(); menu = false }) { Text("Unpair") } },
        dismissButton = { TextButton(onClick = { menu = false }) { Text("Close") } })
}

@SuppressLint("BatteryLife")
@Composable
private fun BatteryHint() {
    val ctx = LocalContext.current
    val pm = ctx.getSystemService(PowerManager::class.java)
    if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Allow background use so requests arrive right away.")
            TextButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${ctx.packageName}")))
            }) { Text("Allow") }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun time(t: Long): String = DateFormat.getTimeFormat(LocalContext.current).format(Date(t))

private fun ago(ms: Long): String = when {
    ms < 60_000 -> "just now"
    ms < 3600_000 -> "${ms / 60_000} min ago"
    else -> "${ms / 3600_000} h ago"
}
