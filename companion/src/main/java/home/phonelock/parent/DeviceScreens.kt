package home.phonelock.parent

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import android.net.Uri
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.unit.dp
import java.util.Date

/**
 * A full-screen page: a fixed top bar (back arrow, [title], and optional [actions] at the end) that stays
 * put while the body scrolls under it. With [scroll] false the body fills the rest of the screen and scrolls
 * itself (for pages with their own lists or maps, such as Activity). Every sub-page uses this.
 *
 * Waiting never moves the content: under the title bar a thin progress line has its space kept at all
 * times, and runs while any of the [waitingOn] requests to [dev] are unanswered (see [Pending]) or while
 * [busy]. Anything floating over the body, such as a transfer's progress, goes in [overlay].
 */
@Composable
internal fun Page(title: String, onBack: () -> Unit, scroll: Boolean = true,
                  dev: String? = null, waitingOn: Set<String> = emptySet(), busy: Boolean = false,
                  actions: @Composable () -> Unit = {}, overlay: @Composable BoxScope.() -> Unit = {},
                  content: @Composable () -> Unit) {
    BackHandler(onBack = onBack)
    val waits by Pending.waits.collectAsState()
    val waiting = busy || (dev != null && waits[dev].orEmpty().keys.any { it in waitingOn })
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            actions()
        }
        ProgressTrack(waiting, Modifier.padding(bottom = 8.dp))
        Box(Modifier.weight(1f)) {
            if (scroll) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
            } else {
                Column(Modifier.fillMaxSize()) { content() }
            }
            overlay()
        }
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
    val ctx = LocalContext.current
    /** Adds to the safe list, or says it's already there (the same number in any format counts). */
    fun addSafe(name: String, number: String) {
        if (calls.isSafe(number)) {
            android.widget.Toast.makeText(ctx, "$number is already on the safe list", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            Hub.setCalls(dev, calls.withSafe(SafeEntry(name, number)))
        }
    }
    /** Takes [number] off the safe list, with Undo. */
    fun removeSafe(name: String, number: String) {
        Hub.setCalls(dev, calls.withoutSafe(number))
        Undo.offer("${name.ifEmpty { formatNumber(number) }} removed from the safe list") { Hub.setCalls(dev, calls) }
    }
    Page("Calls", onBack, dev = dev, waitingOn = setOf("calllog", "contacts"), busy = !state.full) {
        Section("Limits") {
            SwitchRow("Outgoing calls only to the safe list", "Emergency numbers always work.", calls.limitOut, state.full) {
                Hub.setCalls(dev, calls.copy(limitOut = it))
            }
            SwitchRow("Incoming calls only from the safe list", "Others, and hidden numbers, are rejected.", calls.limitIn, state.full) {
                Hub.setCalls(dev, calls.copy(limitIn = it))
            }
        }
        Section("Safe list") {
            if (state.full && calls.safe.isEmpty()) EmptyNote("No numbers on the safe list yet.")
            calls.safe.forEach { e ->
                PersonRow(e.name.ifEmpty { formatNumber(e.number) },
                    AnnotatedString(if (e.name.isNotEmpty()) formatNumber(e.number) else "No name"),
                    actionsEnabled = state.full,
                    actions = listOf(MenuEntry("Remove from safe list") { removeSafe(e.name, e.number) }))
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
            // Not "no calls" while the log is still on its way, or when it can't be read at all.
            if (entries.isEmpty() && state.canCallLog && !isWaiting(dev, "calllog")) EmptyNote("No calls yet.")
            CappedList(entries, key = dev, initial = 10) { c ->
                CallRow(c, safe = c.number.isNotEmpty() && calls.isSafe(c.number), canEdit = state.full,
                    onAddSafe = { addSafe(c.name, c.number) })
            }
        }
    }
    if (adding) NumberDialog("Add to safe list", onDismiss = { adding = false }) { name, number ->
        addSafe(name, number)
        adding = false
    }
    if (picking) ContactPicker(dev, contacts[dev], calls, onDismiss = { picking = false }) { c, number ->
        addSafe(c.name, number)
        picking = false
    }
}

@Composable
internal fun SwitchRow(title: String, hint: String, on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(hint, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = on, enabled = enabled, onCheckedChange = onChange)
    }
}

/** Who a call was with: their name, else the number laid out, else "Hidden number". */
internal fun callWho(c: CallEntry) = c.name.ifEmpty { if (c.number.isEmpty()) "Hidden number" else formatNumber(c.number) }

/** The arrow and word for a call's direction ("↗" "Outgoing"), the same in the call log and Activity. */
internal fun callKind(type: String): Pair<String, String> = when (type) {
    "out" -> "↗" to "Outgoing"
    "in" -> "↙" to "Incoming"
    "missed" -> "↙" to "Missed"
    "rejected" -> "⊘" to "Declined"
    "blocked" -> "⊘" to "Blocked"
    // A call she tried to place to a number off the safe list; it was stopped before it rang.
    "attempt" -> "⊘" to "Blocked call attempt"
    "voicemail" -> "↙" to "Voicemail"
    else -> "•" to type
}

/** A call's length as "2:05". */
internal fun callLength(seconds: Long) = "${seconds / 60}:${"%02d".format(seconds % 60)}"

/** Calls the child didn't take, shown in the error colour. */
internal val CallEntry.unanswered get() = type in setOf("missed", "rejected", "blocked", "attempt")

/**
 * One call: who, then the direction ("↗ Outgoing", with calls the child didn't take in the error colour),
 * when and how long. [safe] tags numbers on the safe list; otherwise the ⋮ menu can add the number.
 */
@Composable
private fun CallRow(c: CallEntry, safe: Boolean, canEdit: Boolean, onAddSafe: () -> Unit) {
    val (arrow, word) = callKind(c.type)
    val dirColor = if (c.unanswered) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val dur = if (c.durationS > 0) " · ${callLength(c.durationS)}" else ""
    val name = callWho(c)
    PersonRow(name, buildAnnotatedString {
        withStyle(SpanStyle(color = dirColor, fontWeight = FontWeight.Medium)) { append("$arrow $word") }
        append(" · ${dateTime(c.ts)}$dur")
    }, tag = if (safe) "Safe" else null, actionsEnabled = canEdit,
        actions = if (safe || c.number.isEmpty()) emptyList() else listOf(MenuEntry("Add to safe list", onClick = onAddSafe)))
}

/** Name and number fields; [onSave] gets both trimmed. */
@Composable
private fun NumberDialog(title: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    AppDialog(
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
private fun ContactPicker(dev: String, contacts: List<Contact>?, calls: CallSettings, onDismiss: () -> Unit,
                          onPick: (Contact, String) -> Unit) {
    // Each number once, whatever its format; numbers already on the safe list are left out.
    val choices = contacts.orEmpty().flatMap { c -> c.distinctNumbers.filterNot(calls::isSafe).map { c to it } }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a contact") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ProgressTrack(isWaiting(dev, "contacts"), Modifier.padding(bottom = 6.dp))
                when {
                    contacts == null -> Unit
                    contacts.isEmpty() -> EmptyNote("No contacts with phone numbers.")
                    choices.isEmpty() -> EmptyNote("Every contact's number is already on the safe list.")
                }
                choices.forEach { (c, n) ->
                    ListRow(c.name, AnnotatedString(formatNumber(n)), leading = { InitialAvatar(c.name) },
                        onClick = { onPick(c, n) })
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
    val calls = state.calls
    Page("Contacts", onBack, dev = dev, waitingOn = setOf("contacts")) {
        Section("On the device") {
            if (contacts != null && contacts.isEmpty()) EmptyNote("No contacts with phone numbers.")
            contacts.orEmpty().forEach { c ->
                val safeNumbers = c.distinctNumbers.filter(calls::isSafe)
                val number = c.distinctNumbers.firstOrNull()
                PersonRow(c.name, AnnotatedString(c.distinctNumbers.joinToString(", ") { formatNumber(it) }),
                    tag = if (safeNumbers.isNotEmpty()) "Safe" else null,
                    actions = listOfNotNull(
                        // The safe list changes wait for the device's call settings (a full state).
                        if (safeNumbers.isNotEmpty()) MenuEntry("Remove from safe list", enabled = state.full) {
                            Hub.setCalls(dev, safeNumbers.fold(calls) { s, n -> s.withoutSafe(n) })
                            Undo.offer("${c.name} removed from the safe list") { Hub.setCalls(dev, calls) }
                        } else if (number != null) MenuEntry("Add to safe list", enabled = state.full) {
                            Hub.setCalls(dev, calls.withSafe(SafeEntry(c.name, number)))
                        } else null,
                        MenuEntry("Delete contact…") { removing = c },
                    ))
            }
            // Below the list, like the safe list's buttons.
            OutlinedButton(onClick = { adding = true }) { Text("Add a contact") }
        }
    }
    if (adding) NumberDialog("Add a contact", onDismiss = { adding = false }) { name, number ->
        DeviceData.addContact(dev, name.ifEmpty { number }, number)
        adding = false
    }
    removing?.let { c ->
        AppDialog(
            onDismissRequest = { removing = null },
            title = { Text("Delete ${c.name}?") },
            text = { Text("It's deleted from their device and their Google contacts. They get a notice.") },
            confirmButton = { DangerButton("Delete") { DeviceData.removeContact(dev, c.key); removing = null } },
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
    var renaming by remember { mutableStateOf<FileEntry?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    // Names chosen in this folder; while any are chosen, taps choose instead of opening.
    var chosen by remember(dev, path) { mutableStateOf(setOf<String>()) }
    var deletingChosen by remember { mutableStateOf(false) }
    LaunchedEffect(dev, path) { DeviceData.list(dev, path) }
    val folder = folders[dev]
    val entries = folder?.takeIf { it.path == path }?.entries.orEmpty()
    val choosing = chosen.isNotEmpty()
    fun full(name: String) = if (path.isEmpty()) name else "$path/$name"
    fun toggle(name: String) {
        chosen = if (name in chosen) chosen - name else chosen + name
    }
    // The file is read on a background thread inside upload(), never here on the main thread.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) DeviceData.upload(dev, path, uri)
    }
    val back = {
        when {
            choosing -> chosen = emptySet()
            path.isEmpty() -> onBack()
            else -> path = path.substringBeforeLast('/', "")
        }
    }

    // Progress floats at the bottom while a file moves; its result then shows as a snackbar (DeviceData).
    // Floating at the bottom, so nothing in the list moves: a file transfer's progress (its result then shows
    // as a snackbar, see DeviceData) and, while choosing, the selection bar.
    Page("Files", back, dev = dev, waitingOn = setOf("files"), overlay = {
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            transfers[dev]?.takeIf { it.busy }?.let { TransferStatus(it) }
            if (choosing) SelectionBar(chosen, entries,
                onAll = { chosen = entries.map { it.name }.toSet() },
                onNone = { chosen = emptySet() },
                onSave = {
                    DeviceData.saveFiles(dev, entries.filter { it.name in chosen && !it.dir }.map { full(it.name) })
                    chosen = emptySet()
                },
                onDelete = { deletingChosen = true })
        }
    }) {
        if (!state.canFiles) Text("The device hasn't allowed file access yet.", style = MaterialTheme.typography.bodySmall)
        Text("/" + path, style = MaterialTheme.typography.titleSmall)
        // Stays in place while choosing (the selection bar floats at the bottom), just unavailable.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }, enabled = !choosing) { Text("Upload here") }
            OutlinedButton(onClick = { newFolder = true }, enabled = !choosing) { Text("New folder") }
            // Starting to choose with nothing ticked would look like nothing happened, so this ticks all.
            OutlinedButton(onClick = { chosen = entries.map { it.name }.toSet() }, enabled = !choosing && entries.isNotEmpty()) {
                Text("Select")
            }
        }
        folder?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Section(if (path.isEmpty()) "Storage" else path.substringAfterLast('/')) {
            // Opening another folder hides the old one's entries until the new list arrives.
            val opening = folder == null || folder.loading && folder.path != path
            if (!opening && folder!!.entries.isEmpty()) EmptyNote("This folder is empty.")
            if (!opening && !choosing && entries.size > 1) Text("Long-press to select several.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            entries.forEach { e ->
                val detail = (if (e.dir) "Folder" else Formatter.formatShortFileSize(ctx, e.size)) + " · " + dateTime(e.mtime)
                ListRow(e.name, AnnotatedString(detail),
                    leading = {
                        if (choosing) Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            Checkbox(checked = e.name in chosen, onCheckedChange = { toggle(e.name) })
                        } else SymbolBadge(fileIcon(e))
                    },
                    actions = if (choosing) emptyList() else listOfNotNull(
                        if (!e.dir) MenuEntry("Open") { DeviceData.openFile(dev, full(e.name)) } else null,
                        if (!e.dir) MenuEntry("Save to Downloads") { DeviceData.saveFile(dev, full(e.name)) } else null,
                        MenuEntry("Select") { toggle(e.name) },
                        MenuEntry("Rename…") { renaming = e },
                        MenuEntry("Delete…") {
                            chosen = setOf(e.name)
                            deletingChosen = true
                        },
                    ),
                    // Tapping a folder goes into it; tapping a file opens it (not saved; see DeviceData.openFile).
                    onClick = {
                        when {
                            choosing -> toggle(e.name)
                            e.dir -> path = full(e.name)
                            else -> DeviceData.openFile(dev, full(e.name))
                        }
                    },
                    onLongClick = { toggle(e.name) })
            }
        }
        // Room at the end so the floating bars never cover the last files.
        Spacer(Modifier.height(140.dp))
    }
    renaming?.let { e ->
        NameDialog("Rename", e.name, onDismiss = { renaming = null }) { n ->
            DeviceData.fileOp(dev, "mv", full(e.name)) { it.put("to", n) }
            renaming = null
        }
    }
    if (newFolder) NameDialog("New folder", "", onDismiss = { newFolder = false }) { n ->
        DeviceData.fileOp(dev, "mkdir", full(n))
        newFolder = false
    }
    if (deletingChosen) {
        val items = entries.filter { it.name in chosen }
        val folderCount = items.count { it.dir }
        AppDialog(
            onDismissRequest = { deletingChosen = false; if (chosen.size == 1) chosen = emptySet() },
            title = { Text(if (items.size == 1) "Delete ${items[0].name}?" else "Delete ${items.size} items?") },
            text = {
                Text(items.take(6).joinToString("\n") { it.name } + (if (items.size > 6) "\nand ${items.size - 6} more" else "") +
                    "\n\n" + (if (folderCount > 0) "Folders are deleted with everything in them. " else "") +
                    "They're deleted from their device. This can't be undone.")
            },
            confirmButton = {
                DangerButton("Delete") {
                    items.forEach { DeviceData.fileOp(dev, "rm", full(it.name)) }
                    chosen = emptySet()
                    deletingChosen = false
                }
            },
            dismissButton = { TextButton(onClick = { deletingChosen = false }) { Text("Cancel") } })
    }
}

/**
 * Floats at the bottom of Files while items are chosen: how many, Select all or none, and what can be done
 * with them. Save skips folders (they can't be downloaded whole), and says so.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionBar(chosen: Set<String>, entries: List<FileEntry>, onAll: () -> Unit, onNone: () -> Unit,
                         onSave: () -> Unit, onDelete: () -> Unit) {
    val files = entries.count { it.name in chosen && !it.dir }
    val folders = entries.count { it.name in chosen && it.dir }
    Column(Modifier.fillMaxWidth().shadow(6.dp, RoundedCornerShape(14.dp))
        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(14.dp))
        .padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${chosen.size} selected", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
            if (chosen.size < entries.size) TextButton(onClick = onAll) { Text("Select all") }
            TextButton(onClick = onNone) { Text("Cancel") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onSave, enabled = files > 0) {
                Text(if (files == 1) "Save 1 file" else "Save $files files")
            }
            OutlinedButton(onClick = onDelete) { Text("Delete…", color = MaterialTheme.colorScheme.error) }
        }
        if (folders > 0 && files > 0) Text("Folders aren't saved, only files.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/**
 * A download or upload in progress, floating over the bottom of Files: a spinner while waiting on the
 * device, and a bar while the file itself moves.
 */
@Composable
private fun TransferStatus(t: Transfer, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().shadow(6.dp, RoundedCornerShape(14.dp))
        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(14.dp))
        .padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (t.busy && t.progress == null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(t.text + (t.progress?.let { if (t.busy) " ${(it * 100).toInt()}%" else "" } ?: ""),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        if (t.busy && t.progress != null) LinearProgressIndicator(progress = { t.progress }, Modifier.fillMaxWidth())
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


@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val ok = name.isNotBlank() && !name.contains('/')
    AppDialog(
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
        ActionTile(ClockIcon, "App time", modifier = Modifier.weight(1f)) { onOpen("apptime") }
    }
}
