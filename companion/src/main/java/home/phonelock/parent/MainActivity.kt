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
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import java.util.Date

private const val INTENT_USED = "intent_used"
private const val SELECTED = "selected_device"

class MainActivity : ComponentActivity() {
    private val pendingLink = mutableStateOf<String?>(null)
    private val selected = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated activity still carries its original intent; read it only if it wasn't used yet.
        if (savedInstanceState?.getBoolean(INTENT_USED) != true) readIntent(intent)
        savedInstanceState?.getString(SELECTED)?.let { selected.value = it }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        ParentService.start(this)
        setContent {
            val ctx = LocalContext.current
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) {
                Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) {
                    val devices by Hub.devices.collectAsState()
                    var adding by rememberSaveable { mutableStateOf(false) }
                    val link by pendingLink
                    // Shown until accepted, also to parents already using the app when the disclosure changes.
                    var disclosed by remember { mutableStateOf(Disclosure.accepted(ctx)) }
                    if (!disclosed) {
                        DisclosureScreen { disclosed = true }
                    } else if (devices.isEmpty() || adding || link != null) {
                        AddDeviceScreen(link, canCancel = devices.isNotEmpty()) { added ->
                            pendingLink.value = null
                            adding = false
                            if (added != null) selected.value = added
                        }
                    } else {
                        val sel = selected.value?.takeIf { s -> devices.any { it.id == s } } ?: devices.first().id
                        MainScreen(devices, sel, onSelect = { selected.value = it }, onAdd = { adding = true })
                    }
                    with(Undo) { UndoHost() }
                } }
            }
        }
    }

    /** Takes a pairing link or a device to show from [i], then clears them so they're used once. */
    private fun readIntent(i: Intent?) {
        i?.dataString?.let { pendingLink.value = it }
        i?.getStringExtra("device")?.let { selected.value = it }
        i?.let { setIntent(Intent(it).setData(null).apply { removeExtra("device") }) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(INTENT_USED, true)
        // Rotating or unfolding recreates the activity: stay on the same child's device.
        outState.putString(SELECTED, selected.value)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        Hub.refreshStale()
        Push.catchUp()
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
    // Calls, Contacts or Files open as their own page over the main screen.
    var page by rememberSaveable(dev) { mutableStateOf<String?>(null) }
    var startingMode by remember(dev) { mutableStateOf(false) }
    when (page) {
        "calls" -> { CallsScreen(dev, state) { page = null }; return }
        "contacts" -> { ContactsScreen(dev, state) { page = null }; return }
        "files" -> { FilesScreen(dev, state) { page = null }; return }
        "history" -> { LocationHistoryScreen(dev, name) { page = null }; return }
        "settings" -> { SettingsScreen(dev, name, onOpen = { page = it }) { page = null }; return }
        // Opened from Settings, so they go back there.
        "diag" -> { DiagnosticsScreen(dev, name) { page = "settings" }; return }
        "apptime" -> { AppTimeScreen(dev, name, state) { page = null }; return }
        "fixsetup" -> {
            WifiSetupScreen(existing = devices.firstOrNull { it.id == dev }) { page = "settings" }
            return
        }
    }

    // Wide screens (tablets, unfolded foldables, landscape) put the full app list in its own column on the
    // right; narrow ones show everything in one column.
    BoxWithConstraints(Modifier.safeDrawingPadding()) {
    val wide = maxWidth >= 840.dp || (maxWidth >= 600.dp && maxWidth > maxHeight)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Column(Modifier.weight(1f).padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            devices.forEach { d ->
                val pending = allRequests.count { it.device == d.id }
                FilterChip(selected = d.id == dev, onClick = { onSelect(d.id) },
                    label = { Text(Hub.deviceName(d.id) + if (pending > 0) "  •$pending" else "") })
            }
            TextButton(onClick = onAdd) { Text("+ Add device") }
        }
        val device = devices.firstOrNull { it.id == dev }
        val vitals by Hub.vitals.collectAsState()
        val askedAt by Hub.askedAt.collectAsState()
        // After a refresh, re-check once the no-answer wait has passed, rather than on the next 15 s tick.
        LaunchedEffect(askedAt[dev]) {
            now = System.currentTimeMillis()
            delay(21_000)
            now = System.currentTimeMillis()
        }
        PhoneHeader(dev, name, state, vitals[dev] ?: Vitals(), askedAt[dev] ?: 0L,
            connected || (device != null && Push.covers(device)), now, onRefresh = { Hub.refresh(dev) }, onMenu = { page = "settings" },
            onLockNow = { Hub.parentMode(dev, 0) },
            onStartMode = { startingMode = true }, onEndMode = { Hub.endMode(dev) })
        if (startingMode) StartModeDialog(name, onPick = { mode, mins -> Hub.startMode(dev, mode, mins); startingMode = false },
            onDismiss = { startingMode = false })
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

        FindAndReach(dev, name, state, now) { page = it }

        AllowedNowSection(dev, state, now)
        NotAllowedNowSection(dev, state)
        if (!wide) {
            AppTableSection(dev, state)
            DeviceControlsSection(dev, state)
        }
        ScheduleSection(dev, state)

        if (events.isNotEmpty()) Section("Recent events") {
            // A day heading wherever the day changes (events run newest first), so times have a date.
            val rows = events.mapIndexed { i, e ->
                e to (i == 0 || startOfDay(events[i - 1].ts) != startOfDay(e.ts))
            }
            CappedList(rows, key = dev) { (e, newDay) ->
                if (newDay) Text(dayName(startOfDay(e.ts), now), Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(time(e.ts), Modifier.width(76.dp), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(e.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    if (wide) Column(Modifier.weight(1f).padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AppTableSection(dev, state)
        DeviceControlsSection(dev, state)
    }
    }
    }

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

/**
 * The colour of section cards: a step lighter than the page in dark mode and a step darker in light mode, so
 * cards stand apart without relying on shadows (an elevated card's shadow is invisible on a dark page).
 */
@Composable
internal fun sectionCardColors() = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    // Soft, borderless cards with a little more room; the shared shape and colour for every section.
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = sectionCardColors()) {
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
