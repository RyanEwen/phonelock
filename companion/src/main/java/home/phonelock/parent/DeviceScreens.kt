package home.phonelock.parent

import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Date

/** Full-screen page with a back arrow and [title], used by Calls, Contacts and Files. */
@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        content()
    }
}

@Composable
private fun dateTime(ts: Long): String {
    val ctx = LocalContext.current
    return DateFormat.getMediumDateFormat(ctx).format(Date(ts)) + " " + DateFormat.getTimeFormat(ctx).format(Date(ts))
}

// ---------- Calls ----------

/** Safe list and call limits, and the call log. */
@Composable
internal fun CallsScreen(dev: String, state: PhoneState, onBack: () -> Unit) {
    val log by DeviceData.calls.collectAsState()
    val contacts by DeviceData.contacts.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(dev) {
        DeviceData.loadCallLog(dev)
        DeviceData.loadContacts(dev)
    }
    val calls = state.calls
    Page("Calls", onBack) {
        Section("Limits") {
            if (!state.full) Text("Loading the device's settings…", style = MaterialTheme.typography.bodySmall)
            SwitchRow("Outgoing calls only to the safe list", "Emergency numbers always work.", calls.limitOut, state.full) {
                Hub.setCalls(dev, calls.copy(limitOut = it))
            }
            SwitchRow("Incoming calls only from the safe list", "Others, and hidden numbers, are rejected.", calls.limitIn, state.full) {
                Hub.setCalls(dev, calls.copy(limitIn = it))
            }
        }
        Section("Safe list") {
            if (calls.safe.isEmpty()) Text("No numbers yet.", style = MaterialTheme.typography.bodyMedium)
            calls.safe.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name.ifEmpty { e.number }, fontWeight = FontWeight.Medium)
                        if (e.name.isNotEmpty()) Text(e.number, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(enabled = state.full, onClick = {
                        Hub.setCalls(dev, calls.copy(safe = calls.safe - e))
                        Undo.offer("${e.name.ifEmpty { e.number }} removed from the safe list") {
                            Hub.setCalls(dev, calls)
                        }
                    }) { Text("Remove") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picking = true }, enabled = state.full) { Text("From contacts") }
                OutlinedButton(onClick = { adding = true }, enabled = state.full) { Text("Add a number") }
            }
        }
        Section("Call log") {
            if (!state.canCallLog) Text("The device hasn't allowed reading its call log yet.",
                style = MaterialTheme.typography.bodySmall)
            val entries = log[dev].orEmpty()
            if (entries.isEmpty()) Text("No calls yet.", style = MaterialTheme.typography.bodyMedium)
            CappedList(entries, key = dev, initial = 10) { c -> CallRow(c) }
        }
    }
    if (adding) NumberDialog("Add to safe list", onDismiss = { adding = false }) { name, number ->
        Hub.setCalls(dev, calls.copy(safe = calls.safe + SafeEntry(name, number)))
        adding = false
    }
    if (picking) ContactPicker(contacts[dev].orEmpty(), onDismiss = { picking = false }) { c, number ->
        Hub.setCalls(dev, calls.copy(safe = calls.safe + SafeEntry(c.name, number)))
        picking = false
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(hint, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = on, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun CallRow(c: CallEntry) {
    val arrow = when (c.type) {
        "out" -> "↗"
        "in" -> "↙"
        "missed" -> "✕"
        "rejected", "blocked" -> "⊘"
        else -> "•"
    }
    val dur = if (c.durationS > 0) " · ${c.durationS / 60}:${"%02d".format(c.durationS % 60)}" else ""
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(arrow, Modifier.width(28.dp), style = MaterialTheme.typography.titleMedium)
        Column(Modifier.weight(1f)) {
            Text(c.name.ifEmpty { c.number.ifEmpty { "Hidden number" } }, fontWeight = FontWeight.Medium)
            Text("${dateTime(c.ts)}$dur · ${c.type}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Name and number fields; [onSave] gets both trimmed. */
@Composable
private fun NumberDialog(title: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(number, { number = it }, label = { Text("Phone number") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone))
            }
        },
        confirmButton = { TextButton(enabled = number.isNotBlank(), onClick = { onSave(name.trim(), number.trim()) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** Picks one number of one of the device's contacts. */
@Composable
private fun ContactPicker(contacts: List<Contact>, onDismiss: () -> Unit, onPick: (Contact, String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a contact") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (contacts.isEmpty()) Text("Loading the device's contacts…")
                contacts.forEach { c ->
                    c.numbers.forEach { n ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(c, n) }.padding(vertical = 8.dp)) {
                            Text(c.name, fontWeight = FontWeight.Medium)
                            Text(n, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

// ---------- Contacts ----------

/** The device's contacts, with add and remove. */
@Composable
internal fun ContactsScreen(dev: String, state: PhoneState, onBack: () -> Unit) {
    val all by DeviceData.contacts.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Contact?>(null) }
    LaunchedEffect(dev) { DeviceData.loadContacts(dev) }
    val contacts = all[dev]
    Page("Contacts", onBack) {
        Button(onClick = { adding = true }) { Text("Add a contact") }
        Section("On the device") {
            if (contacts == null) Text("Loading…", style = MaterialTheme.typography.bodyMedium)
            else if (contacts.isEmpty()) Text("No contacts with phone numbers.", style = MaterialTheme.typography.bodyMedium)
            contacts.orEmpty().forEach { c ->
                val safe = c.numbers.any { n -> state.calls.safe.any { it.number == n } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name + if (safe) "  · safe" else "", fontWeight = FontWeight.Medium)
                        Text(c.numbers.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { removing = c }) { Text("Remove") }
                }
            }
        }
    }
    if (adding) NumberDialog("Add a contact", onDismiss = { adding = false }) { name, number ->
        DeviceData.addContact(dev, name.ifEmpty { number }, number)
        adding = false
    }
    removing?.let { c ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${c.name}?") },
            text = { Text("It's deleted from their device and their Google contacts. They get a notice.") },
            confirmButton = { TextButton(onClick = { DeviceData.removeContact(dev, c.key); removing = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } })
    }
}

// ---------- Files ----------

/** Browses the device's shared storage: open folders, download, upload, rename, delete, new folder. */
@Composable
internal fun FilesScreen(dev: String, state: PhoneState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val folders by DeviceData.folders.collectAsState()
    val transfers by DeviceData.transfer.collectAsState()
    var path by remember(dev) { mutableStateOf("") }
    var menu by remember { mutableStateOf<FileEntry?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    LaunchedEffect(dev, path) { DeviceData.list(dev, path) }
    val folder = folders[dev]
    // The file is read on a background thread inside upload(), never here on the main thread.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) DeviceData.upload(dev, path, uri)
    }
    val back = { if (path.isEmpty()) onBack() else path = path.substringBeforeLast('/', "") }

    Page("Files", back) {
        if (!state.canFiles) Text("The device hasn't allowed file access yet.", style = MaterialTheme.typography.bodySmall)
        Text("/" + path, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }) { Text("Upload here") }
            OutlinedButton(onClick = { newFolder = true }) { Text("New folder") }
        }
        transfers[dev]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        folder?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Section(if (path.isEmpty()) "Storage" else path.substringAfterLast('/')) {
            if (folder == null || folder.loading && folder.path != path) Text("Loading…")
            else if (folder.entries.isEmpty()) Text("Empty folder.", style = MaterialTheme.typography.bodyMedium)
            folder?.takeIf { it.path == path }?.entries?.forEach { e ->
                Row(Modifier.fillMaxWidth().clickable {
                    if (e.dir) path = if (path.isEmpty()) e.name else "$path/${e.name}" else menu = e
                }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(fileIcon(e), Modifier.width(32.dp), style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.weight(1f)) {
                        Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text((if (e.dir) "Folder" else Formatter.formatShortFileSize(ctx, e.size)) + " · " + dateTime(e.mtime),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { menu = e }) { Text("⋯") }
                }
                HorizontalDivider()
            }
        }
    }
    menu?.let { e -> FileMenu(dev, path, e) { menu = null } }
    if (newFolder) NameDialog("New folder", "", onDismiss = { newFolder = false }) { n ->
        DeviceData.fileOp(dev, "mkdir", if (path.isEmpty()) n else "$path/$n")
        newFolder = false
    }
}

private fun fileIcon(e: FileEntry): String {
    if (e.dir) return "📁"
    return when (e.name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "heic" -> "🖼"
        "mp4", "mkv", "webm", "mov", "3gp" -> "🎬"
        "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav" -> "🎵"
        else -> "📄"
    }
}

/** Download, rename or delete one file or folder. Deleting asks first, since it can't be undone. */
@Composable
private fun FileMenu(dev: String, folder: String, e: FileEntry, close: () -> Unit) {
    val full = if (folder.isEmpty()) e.name else "$folder/${e.name}"
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    when {
        renaming -> NameDialog("Rename", e.name, onDismiss = close) { n ->
            DeviceData.fileOp(dev, "mv", full) { it.put("to", n) }
            close()
        }
        deleting -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Delete ${e.name}?") },
            text = { Text(if (e.dir) "The folder and everything in it are deleted from their device. This can't be undone."
                else "It's deleted from their device. This can't be undone.") },
            confirmButton = { TextButton(onClick = { DeviceData.fileOp(dev, "rm", full); close() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
        else -> AlertDialog(
            onDismissRequest = close,
            title = { Text(e.name) },
            text = {
                Column {
                    if (!e.dir) TextButton(onClick = { DeviceData.fileOp(dev, "get", full); close() }) {
                        Text("Download to this phone")
                    }
                    TextButton(onClick = { renaming = true }) { Text("Rename") }
                    TextButton(onClick = { deleting = true }) { Text("Delete") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = close) { Text("Close") } })
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val ok = name.isNotBlank() && !name.contains('/')
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
        confirmButton = { TextButton(enabled = ok, onClick = { onSave(name.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** Row of buttons on the main screen that open Calls, Contacts and Files. */
@Composable
internal fun MoreRow(onOpen: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionTile(Icons.Default.Call, "Calls", modifier = Modifier.weight(1f)) { onOpen("calls") }
        ActionTile(Icons.Default.Person, "Contacts", modifier = Modifier.weight(1f)) { onOpen("contacts") }
        ActionTile(Icons.AutoMirrored.Filled.List, "Files", modifier = Modifier.weight(1f)) { onOpen("files") }
    }
}
