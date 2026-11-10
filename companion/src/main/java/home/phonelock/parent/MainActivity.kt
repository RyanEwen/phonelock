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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
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
import androidx.lifecycle.compose.LifecycleResumeEffect
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
        Hub.refreshStale()
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

    var settingUp by remember { mutableStateOf(false) }

    if (scanning) {
        BackHandler { scanning = false }
        QrScanner(onResult = { link = it; scanning = false }, onCancel = { scanning = false })
        return
    }
    if (settingUp) {
        SetupPhoneScreen { added -> settingUp = false; if (added != null) done(added) }
        return
    }
    if (canCancel) BackHandler { done(null) }
    Column(Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Add a child's phone", style = MaterialTheme.typography.headlineSmall)
        Text("Connect this app to your child's phone so you can answer requests, give access and find it.")
        Section("New phone") {
            Text("Set it up from scratch with a QR code. This erases the phone.")
            Button(onClick = { settingUp = true }) { Text("Set up a new phone") }
        }
        Text("Add a phone that's already set up", style = MaterialTheme.typography.titleMedium)
        Text("Scan the pairing code from the other parent's app (phone settings > Share with another " +
            "parent), or paste a pairing link.")
        Button(onClick = { scanning = true }) { Text("Scan QR code") }
        OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), label = { Text("Pairing link") },
            isError = link.isNotBlank() && parsed == null,
            supportingText = {
                Text(when {
                    parsed != null -> "Pairing code recognised"
                    link.isNotBlank() -> "This isn't a PhoneLock pairing link"
                    else -> "Starts with phonelock://pair"
                })
            })
        if (parsed != null) OutlinedTextField(phoneName, { phoneName = it }, Modifier.fillMaxWidth(),
            label = { Text("Child's phone name") }, singleLine = true,
            supportingText = { Text("What this app calls their phone, e.g. their first name") })
        OutlinedTextField(you, { you = it }, Modifier.fillMaxWidth(),
            label = { Text("Your name") }, singleLine = true,
            supportingText = { Text("Shown on their phone, e.g. \"${you.ifBlank { "Dad" }} allowed Camera\"") })
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
        PhoneHeader(name, state, connected, now, onRefresh = { Hub.refresh(dev) }, onMenu = { menu = true })
        BatteryHint()

        if (requests.isNotEmpty()) Section("Requests") {
            requests.forEach { r ->
                // The child suggests a length; the parent picks the actual one (preselected to the ask).
                var mins by remember(r.req) { mutableLongStateOf(r.minutes) }
                AppName(dev, r.app, r.label)
                Text("Asked for ${fmtMinutes(r.minutes)} · ${time(r.ts)}", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (GRANT_DURATIONS + r.minutes).distinct().sorted().forEach { m ->
                        FilterChip(selected = mins == m, onClick = { mins = m }, label = { Text(fmtMinutes(m)) })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { Hub.grant(dev, r.app, mins, r.req) }) { Text("Allow ${fmtMinutes(mins)}") }
                    OutlinedButton(onClick = { Hub.deny(r) }) { Text("Deny") }
                }
            }
        }

        FindAndReach(dev, name, state, now)

        AllowedNowSection(dev, state, now)
        AllowForAWhileSection(dev, state)
        AppTableSection(dev, state)
        ScheduleSection(dev, state)

        if (events.isNotEmpty()) Section("Activity") {
            events.take(20).forEach { e ->
                Row {
                    Text(time(e.ts), Modifier.width(72.dp), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(e.text, style = MaterialTheme.typography.bodyMedium)
                }
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
    var sharing by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(rename, { rename = it }, label = { Text("Child's phone name") }, singleLine = true)
                OutlinedTextField(you, { you = it }, label = { Text("Your name") }, singleLine = true,
                    supportingText = { Text("Shown on their phone") })
                TextButton(onClick = { sharing = true }) { Text("Share with another parent…") }
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
    if (sharing) Hub.devices.value.firstOrNull { it.id == dev }?.let { d ->
        SharePhoneDialog(d.copy(name = name)) { sharing = false }
    }
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
    // Re-check whenever the app comes back to the front, e.g. after the system "Allow" dialog.
    var exempt by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
    LifecycleResumeEffect(Unit) {
        exempt = pm.isIgnoringBatteryOptimizations(ctx.packageName)
        onPauseOrDispose {}
    }
    if (exempt) return
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
internal fun Section(title: String, content: @Composable () -> Unit) {
    // Soft, borderless cards with a little more room; the shared shape for every section.
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
internal fun time(t: Long): String = DateFormat.getTimeFormat(LocalContext.current).format(Date(t))

internal fun ago(ms: Long): String = when {
    ms < 60_000 -> "just now"
    ms < 3600_000 -> "${ms / 60_000} min ago"
    else -> "${ms / 3600_000} h ago"
}
