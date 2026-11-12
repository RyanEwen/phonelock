package home.phonelock.parent

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The child phone's icon for [pkg], from [AppIconCache], or a lettered circle until it arrives.
 */
@Composable
internal fun AppIcon(dev: String, pkg: String, label: String, size: Dp = 24.dp) {
    val icons by AppIconCache.icons.collectAsState()
    val icon = icons[dev]?.get(pkg)
    if (icon != null) {
        Image(icon, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center) {
            Text(label.take(1).uppercase(), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * An app's icon and name on one line, for the lists below. [note], if given, is a small second line under
 * the name (for example its daily limit), in the error colour when [noteAlert].
 */
@Composable
internal fun AppName(dev: String, pkg: String, label: String, modifier: Modifier = Modifier,
                     note: String? = null, noteAlert: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        AppIcon(dev, pkg, label, 32.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, fontWeight = FontWeight.Medium)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall,
                color = if (noteAlert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A launcher-style grid of the child phone's apps: icon with the name underneath, [columns] across.
 * [badge], if given, is drawn on each icon's corner (e.g. "✕" while editing); [notes] adds a small second
 * line under chosen apps (e.g. "until 7:00 PM"); [dimmed] apps are drawn greyed out. Built from rows rather than a
 * lazy grid because it sits inside the main screen's scrolling column.
 */
@Composable
internal fun AppIconGrid(dev: String, state: PhoneState, pkgs: List<String>, onTap: (String) -> Unit,
                         badge: String? = null, columns: Int = 4, notes: Map<String, String> = emptyMap(),
                         dimmed: Set<String> = emptySet()) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        pkgs.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { p ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onTap(p) }
                        .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        // Dimmed apps (paused for a while) stay in place, greyed out.
                        Box(Modifier.alpha(if (p in dimmed) 0.35f else 1f)) {
                            AppIcon(dev, p, state.label(p), 48.dp)
                            if (badge != null) Text(badge, Modifier.align(Alignment.TopEnd)
                                .background(MaterialTheme.colorScheme.errorContainer, CircleShape)
                                .padding(horizontal = 5.dp), style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(state.label(p), style = MaterialTheme.typography.labelMedium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        notes[p]?.let {
                            // Two lines, so a note like "Paused until / 9:34 PM" fits under a narrow icon.
                            Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 2,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * "Allowed right now", mirroring the device's home screen: scheduled apps (tap to pause), apps allowed for a
 * while (tap to extend or stop) and apps paused for a while (greyed out; tap to allow again).
 */
@Composable
internal fun AllowedNowSection(dev: String, state: PhoneState, now: Long) {
    var blocking by remember(dev) { mutableStateOf<String?>(null) }
    var unpausing by remember(dev) { mutableStateOf<String?>(null) }
    var granted by remember(dev) { mutableStateOf<String?>(null) }
    val bit = modeBit(state.mode)
    // Mirrors the device's home screen: scheduled apps, then apps allowed for a while, then apps paused
    // for a while (only those the schedule would otherwise allow now).
    // Wi-Fi and Bluetooth have their own section (DeviceControlsSection), not app icons.
    val forNow = appsOnly(state.home).filter { (state.masks[it] ?: (MODE_NORMAL or MODE_LIMITED)) and bit != 0 }
    val paused = state.blocks.filterValues { it > now }.filterKeys { it in forNow }
    val scheduled = phoneFirst(state, forNow - paused.keys)
    val live = state.grants.filterValues { it > now }.filterKeys { !DeviceControls.isControl(it) }
    // Wi-Fi and Bluetooth allowed now (by schedule and not paused, or for a while): pills after the icons.
    val controlsNow = DeviceControls.ALL.filter { id ->
        val scheduled = id in state.home && (state.masks[id] ?: (MODE_NORMAL or MODE_LIMITED)) and bit != 0
        (scheduled && (state.blocks[id] ?: 0) <= now) || (state.grants[id] ?: 0) > now
    }

    Section("Allowed right now") {
        if (scheduled.isEmpty() && live.isEmpty() && paused.isEmpty() && controlsNow.isEmpty()) {
            EmptyNote("Nothing is allowed right now. Allow an app for a while from the list below.")
        }
        if (scheduled.isNotEmpty()) {
            Text("Tap an app to pause it for a while.", style = MaterialTheme.typography.bodySmall)
            AppIconGrid(dev, state, scheduled, onTap = { blocking = it })
        }
        if (live.isNotEmpty()) {
            Text("Allowed for a while", style = MaterialTheme.typography.titleSmall)
            AppIconGrid(dev, state, phoneFirst(state, live.keys), onTap = { granted = it },
                notes = live.mapValues { "until ${time(it.value)}" })
        }
        if (paused.isNotEmpty()) {
            Text("Paused for a while", style = MaterialTheme.typography.titleSmall)
            AppIconGrid(dev, state, phoneFirst(state, paused.keys), onTap = { unpausing = it },
                notes = paused.mapValues { "until ${time(it.value)}" }, dimmed = paused.keys)
        }
        ControlPills(dev, controlsNow, label = { id ->
            DeviceControls.label(id) + (state.grants[id]?.takeIf { it > now }?.let { " · until ${time(it)}" } ?: "")
        }, onTap = { id -> if ((state.grants[id] ?: 0) > now) granted = id else blocking = id })
    }

    blocking?.let { p ->
        LengthDialog("Pause ${state.label(p)} for how long?", onPick = { m -> Hub.block(dev, p, m); blocking = null },
            onDismiss = { blocking = null })
    }
    unpausing?.let { p ->
        LengthDialog("Allow ${state.label(p)} for how long?", onPick = { m -> Hub.grant(dev, p, m); unpausing = null },
            onDismiss = { unpausing = null },
            extra = "Allow again now" to { Hub.unblock(dev, p); unpausing = null })
    }
    granted?.let { p ->
        val until = state.grants[p] ?: 0
        /** Gives back what was running before a stop, as Undo for "Stop now" and "Stop all". */
        fun regrant(grants: Map<String, Long>) {
            val nowMs = System.currentTimeMillis()
            grants.filterValues { it > nowMs }.forEach { (app, end) -> Hub.grant(dev, app, ((end - nowMs) / 60_000).coerceAtLeast(1)) }
        }
        OptionsDialog("${state.label(p)} until ${time(until)}", onDismiss = { granted = null }, options = listOfNotNull(
            MenuEntry("Add 30 minutes") {
                Hub.grant(dev, p, (until - System.currentTimeMillis()) / 60_000 + 30); granted = null
            },
            MenuEntry("Stop now") {
                val before = mapOf(p to until)
                Hub.revoke(dev, p); granted = null
                Undo.offer("${state.label(p)} stopped") { regrant(before) }
            },
            if (live.size > 1) MenuEntry("Stop all allowed-for-a-while apps") {
                val before = live
                Hub.revoke(dev, "all"); granted = null
                Undo.offer("All allowed-for-a-while apps stopped") { regrant(before) }
            } else null,
        ))
    }
}

/**
 * The one order for apps everywhere in this app, matching the device's home screen: Phone first, then
 * alphabetical by name.
 */
internal fun phoneFirst(state: PhoneState, pkgs: Collection<String>): List<String> {
    val dialers = setOf("com.google.android.dialer", "com.android.dialer")
    return pkgs.sortedWith(compareBy<String>({ it !in dialers }, { state.label(it).lowercase() }))
}

/** The schedule bit for a situation name from the device. */
internal fun modeBit(mode: String) = when (mode) {
    "bedtime" -> MODE_BEDTIME
    "limited" -> MODE_LIMITED
    else -> MODE_NORMAL
}

/**
 * "Not allowed right now": apps blocked at the moment (unscheduled, outside their hours, or paused); tap
 * one, then pick a length to allow it for a while. Hiding apps lives in the full app list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NotAllowedNowSection(dev: String, state: PhoneState) {
    var pick by remember(dev) { mutableStateOf<String?>(null) }
    // Unscheduled apps, scheduled ones the current situation excludes (e.g. at bedtime), and paused ones.
    val bit = modeBit(state.mode)
    val nowMs = System.currentTimeMillis()
    // Paused apps that the schedule allows now appear greyed out under "Allowed right now" instead; ones the
    // schedule excludes anyway are listed here as usual.
    val outOfHours = appsOnly(state.home).filter { (state.masks[it] ?: (MODE_NORMAL or MODE_LIMITED)) and bit == 0 }
    val paused = state.blocks.filterValues { it > nowMs }.filterKeys { it in outOfHours }
    val blockedNow = outOfHours
    val grantable = phoneFirst(state, appsOnly(blockedNow + state.others).distinct())
    // Wi-Fi and Bluetooth not allowed now (out of hours, paused, or never ticked): pills after the icons.
    val controlsOff = if (!state.full || DeviceControls.ALL.none { it in state.home || it in state.others || it in state.notOffered })
        emptyList() // not known yet, or a lock app too old to have them
    else DeviceControls.ALL.filter { id ->
        val scheduled = id in state.home && (state.masks[id] ?: (MODE_NORMAL or MODE_LIMITED)) and bit != 0
        val allowed = (scheduled && (state.blocks[id] ?: 0) <= nowMs) || (state.grants[id] ?: 0) > nowMs
        !allowed && id !in state.notOffered
    }

    Section("Not allowed right now") {
        Text("Tap an app to allow it for a while.", style = MaterialTheme.typography.bodySmall)
        AppIconGrid(dev, state, grantable, onTap = { pick = it },
            notes = paused.mapValues { "Paused until\n${time(it.value)}" })
        ControlPills(dev, controlsOff, label = { id ->
            DeviceControls.label(id) + (state.blocks[id]?.takeIf { it > nowMs }?.let { " · paused until ${time(it)}" } ?: "")
        }, onTap = { pick = it })
    }

    pick?.let { p ->
        // A paused app can simply be given back; any app can be allowed for a set time.
        val unpause = if (p in paused || (state.blocks[p] ?: 0) > nowMs) ("Allow again now" to { Hub.unblock(dev, p); pick = null }) else null
        LengthDialog("Allow ${state.label(p)} for how long?", onPick = { m -> Hub.grant(dev, p, m); pick = null },
            onDismiss = { pick = null }, extra = unpause)
    }
}

/**
 * Asks "[title]" with the standard lengths, plus "Until a time…" and "Custom length…" (up to 24 hours, the
 * protocol's limit); [onPick] gets the minutes. [extra], if given, is one more choice shown first (label and
 * action), e.g. "Allow again now".
 */
@Composable
internal fun LengthDialog(title: String, onPick: (Long) -> Unit, onDismiss: () -> Unit,
                          extra: Pair<String, () -> Unit>? = null) {
    val ctx = LocalContext.current
    var custom by remember { mutableStateOf(false) }
    if (custom) {
        CustomLengthDialog(title, onPick = onPick, onDismiss = onDismiss)
        return
    }
    OptionsDialog(title, onDismiss, options = listOfNotNull(
        extra?.let { (label, action) -> MenuEntry(label, onClick = action) },
        *GRANT_DURATIONS.map { m -> MenuEntry(fmtMinutes(m)) { onPick(m) } }.toTypedArray(),
        MenuEntry("Until a time…") {
            val now = java.util.Calendar.getInstance()
            android.app.TimePickerDialog(ctx, { _, h, min -> onPick(minutesUntil(h, min)) },
                now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE),
                android.text.format.DateFormat.is24HourFormat(ctx)).show()
        },
        MenuEntry("Custom length…") { custom = true },
    ))
}

/** Hours and minutes fields for a length that isn't in the list. */
@Composable
private fun CustomLengthDialog(title: String, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    var hours by remember { mutableStateOf("") }
    var mins by remember { mutableStateOf("") }
    val total = (hours.toLongOrNull() ?: 0) * 60 + (mins.toLongOrNull() ?: 0)
    val ok = total in 1..MAX_GRANT_MINUTES
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(hours, { hours = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f),
                        label = { Text("Hours") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(mins, { mins = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f),
                        label = { Text("Minutes") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                if (total > MAX_GRANT_MINUTES) Text("At most 24 hours.", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onPick(total) }, enabled = ok) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** Longest grant or block the device accepts (24 hours). */
private const val MAX_GRANT_MINUTES = 24 * 60L

/** Minutes from now until the next [hour]:[minute] (tomorrow if that time has passed today); at least 1. */
private fun minutesUntil(hour: Int, minute: Int): Long {
    val now = java.util.Calendar.getInstance()
    val target = (now.clone() as java.util.Calendar).apply {
        set(java.util.Calendar.HOUR_OF_DAY, hour)
        set(java.util.Calendar.MINUTE, minute)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
        if (!after(now)) add(java.util.Calendar.DAY_OF_MONTH, 1)
    }
    return ((target.timeInMillis - now.timeInMillis + 59_999) / 60_000).coerceIn(1, MAX_GRANT_MINUTES)
}
