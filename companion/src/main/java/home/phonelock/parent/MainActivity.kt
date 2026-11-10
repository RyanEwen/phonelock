package home.phonelock.parent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
    private val selected = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readIntent(intent)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        ParentService.start(this)
        setContent {
            val ctx = LocalContext.current
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) {
                Surface(Modifier.fillMaxSize()) {
                    val devices by Hub.devices.collectAsState()
                    var adding by remember { mutableStateOf(false) }
                    val link by pendingLink
                    if (devices.isEmpty() || adding || link != null) {
                        AddPhoneScreen(link, canCancel = devices.isNotEmpty()) { added ->
                            pendingLink.value = null
                            adding = false
                            if (added != null) selected.value = added
                        }
                    } else {
                        val sel = selected.value?.takeIf { s -> devices.any { it.id == s } } ?: devices.first().id
                        MainScreen(devices, sel, onSelect = { selected.value = it }, onAdd = { adding = true })
                    }
                }
            }
        }
    }

    private fun readIntent(i: Intent?) {
        i?.dataString?.let { pendingLink.value = it }
        i?.getStringExtra("device")?.let { selected.value = it }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        Hub.refreshAll()
    }
}

@Composable
private fun AddPhoneScreen(initialLink: String?, canCancel: Boolean, done: (String?) -> Unit) {
    var link by remember { mutableStateOf(initialLink ?: "") }
    var scanning by remember { mutableStateOf(false) }
    val parentName by Hub.parentName.collectAsState()
    var you by remember { mutableStateOf(parentName) }
    val parsed = Hub.parsePairing(link)
    var phoneName by remember(parsed?.topic) { mutableStateOf(parsed?.name ?: "") }

    if (scanning) {
        BackHandler { scanning = false }
        QrScanner(onResult = { link = it; scanning = false }, onCancel = { scanning = false })
        return
    }
    if (canCancel) BackHandler { done(null) }
    Column(Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Add a phone", style = MaterialTheme.typography.headlineSmall)
        Text("Scan the pairing QR code for the phone you want to manage, or paste its pairing link.")
        Button(onClick = { scanning = true }) { Text("Scan QR code") }
        OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), label = { Text("Pairing link") },
            isError = link.isNotBlank() && parsed == null,
            supportingText = { if (parsed != null) Text("Pairing code recognised") })
        if (parsed != null) OutlinedTextField(phoneName, { phoneName = it }, Modifier.fillMaxWidth(),
            label = { Text("Phone name") }, singleLine = true)
        OutlinedTextField(you, { you = it }, Modifier.fillMaxWidth(),
            label = { Text("Your name, shown on their notifications") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                parsed?.let {
                    Hub.setParentName(you)
                    Hub.addDevice(it.copy(name = phoneName.ifBlank { it.name }))
                    done(it.id)
                }
            }, enabled = parsed != null && you.isNotBlank()) { Text("Add phone") }
            if (canCancel) TextButton(onClick = { done(null) }) { Text("Cancel") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainScreen(devices: List<Device>, dev: String, onSelect: (String) -> Unit, onAdd: () -> Unit) {
    val states by Hub.states.collectAsState()
    val allRequests by Hub.requests.collectAsState()
    val allEvents by Hub.events.collectAsState()
    val connected by Hub.connected.collectAsState()
    val state = states[dev] ?: PhoneState()
    val requests = allRequests.filter { it.device == dev }
    val events = allEvents[dev] ?: emptyList()
    val name = Hub.deviceName(dev)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    var menu by remember { mutableStateOf(false) }

    Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            devices.forEach { d ->
                val pending = allRequests.count { it.device == d.id }
                FilterChip(selected = d.id == dev, onClick = { onSelect(d.id) },
                    label = { Text(Hub.deviceName(d.id) + if (pending > 0) "  •$pending" else "") })
            }
            TextButton(onClick = onAdd) { Text("+ Add phone") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    (if (connected) "Connected" else "Connecting…") +
                        (if (state.updated > 0) " · updated ${ago(now - state.updated)}" else " · no reply yet") +
                        (if (state.parentMode) " · parent mode on" else ""),
                    style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { Hub.refresh(dev) }) { Text("Refresh") }
            TextButton(onClick = { menu = true }) { Text("⋮") }
        }
        BatteryHint()

        if (requests.isNotEmpty()) Section("Requests") {
            requests.forEach { r ->
                Text("${r.label} for ${fmtMinutes(r.minutes)} · ${time(r.ts)}", fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { Hub.grant(dev, r.app, r.minutes, r.req) }) { Text("Allow ${fmtMinutes(r.minutes)}") }
                    if (r.minutes != 30L) OutlinedButton(onClick = { Hub.grant(dev, r.app, 30, r.req) }) { Text("30 min") }
                    OutlinedButton(onClick = { Hub.deny(r) }) { Text("Deny") }
                }
            }
        }

        Section("Allowed right now") {
            val live = state.grants.filterValues { it > now }
            if (live.isEmpty()) Text("Only the usual home-screen apps.", style = MaterialTheme.typography.bodyMedium)
            live.forEach { (pkg, until) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(state.label(pkg), fontWeight = FontWeight.Medium)
                        Text("until ${time(until)} (${fmtMinutes((until - now) / 60_000 + 1)} left)",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = {
                        Hub.grant(dev, pkg, (until - System.currentTimeMillis()) / 60_000 + 30)
                    }) { Text("+30 min") }
                    TextButton(onClick = { Hub.revoke(dev, pkg) }) { Text("Stop") }
                }
            }
            if (live.size > 1) OutlinedButton(onClick = { Hub.revoke(dev, "all") }) { Text("Stop all") }
        }

        Section("Give access") {
            var pick by remember(dev) { mutableStateOf<String?>(null) }
            var mins by remember { mutableLongStateOf(60L) }
            if (state.others.isEmpty()) Text("Tap Refresh to load the phone's apps.", style = MaterialTheme.typography.bodySmall)
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
            Button(onClick = { pick?.let { Hub.grant(dev, it, mins); pick = null } }, enabled = pick != null) {
                Text(pick?.let { "Allow ${state.label(it)} for ${fmtMinutes(mins)}" } ?: "Pick an app")
            }
        }

        Section("Home screen apps") {
            var adding by remember(dev) { mutableStateOf(false) }
            state.home.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.label(p), Modifier.weight(1f))
                    var confirm by remember { mutableStateOf(false) }
                    TextButton(onClick = { confirm = true }) { Text("Remove") }
                    if (confirm) AlertDialog(
                        onDismissRequest = { confirm = false },
                        title = { Text("Remove ${state.label(p)}?") },
                        text = { Text("It will disappear from the home screen and be hidden.") },
                        confirmButton = { TextButton(onClick = { Hub.home(dev, "remove", p); confirm = false }) { Text("Remove") } },
                        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
                }
            }
            OutlinedButton(onClick = { adding = !adding }) { Text(if (adding) "Done" else "Add an app…") }
            if (adding) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.others.sortedBy { state.label(it).lowercase() }.forEach { p ->
                    OutlinedButton(onClick = { Hub.home(dev, "add", p); adding = false }) { Text("+ ${state.label(p)}") }
                }
            }
        }

        if (events.isNotEmpty()) Section("Activity") {
            events.take(20).forEach { e ->
                Text("${time(e.ts)}  ${e.text}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (menu) PhoneMenu(dev, name) { menu = false }
}

@Composable
private fun PhoneMenu(dev: String, name: String, close: () -> Unit) {
    var rename by remember { mutableStateOf(name) }
    var you by remember { mutableStateOf(Hub.parentName.value) }
    var confirmRemove by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(rename, { rename = it }, label = { Text("Phone name") }, singleLine = true)
                OutlinedTextField(you, { you = it }, label = { Text("Your name") }, singleLine = true)
                TextButton(onClick = { confirmRemove = true }) { Text("Remove this phone…") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (rename.isNotBlank()) Hub.renameDevice(dev, rename)
                if (you.isNotBlank()) Hub.setParentName(you)
                close()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Close") } })
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove $name?") },
        text = { Text("This app stops managing it. The phone stays locked; you'll need its pairing QR code to add it again.") },
        confirmButton = { TextButton(onClick = { Hub.removeDevice(dev); confirmRemove = false; close() }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } })
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
