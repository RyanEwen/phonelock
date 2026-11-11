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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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

/** An app's icon and name on one line, for the lists below. */
@Composable
internal fun AppName(dev: String, pkg: String, label: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        AppIcon(dev, pkg, label, 32.dp)
        Spacer(Modifier.width(12.dp))
        Text(label, fontWeight = FontWeight.Medium)
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
    val forNow = state.home.filter { (state.masks[it] ?: (MODE_NORMAL or MODE_LIMITED)) and bit != 0 }
    val paused = state.blocks.filterValues { it > now }.filterKeys { it in forNow }
    val scheduled = phoneFirst(state, forNow - paused.keys)
    val live = state.grants.filterValues { it > now }

    Section("Allowed right now") {
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
        val until = live[p] ?: 0
        AlertDialog(
            onDismissRequest = { granted = null },
            title = { Text("${state.label(p)} until ${time(until)}") },
            text = {
                Column {
                    TextButton(onClick = {
                        Hub.grant(dev, p, (until - System.currentTimeMillis()) / 60_000 + 30); granted = null
                    }) { Text("Add 30 minutes") }
                    TextButton(onClick = { Hub.revoke(dev, p); granted = null }) { Text("Stop now") }
                    if (live.size > 1) TextButton(onClick = { Hub.revoke(dev, "all"); granted = null }) {
                        Text("Stop all allowed-for-a-while apps")
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { granted = null }) { Text("Close") } })
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
    val outOfHours = state.home.filter { (state.masks[it] ?: (MODE_NORMAL or MODE_LIMITED)) and bit == 0 }
    val paused = state.blocks.filterValues { it > nowMs }.filterKeys { it in outOfHours }
    val blockedNow = outOfHours
    val grantable = phoneFirst(state, (blockedNow + state.others).distinct())

    Section("Not allowed right now") {
        Text(if (state.others.isEmpty() && state.notOffered.isEmpty()) "Tap Refresh to load the device's apps."
            else "Tap an app to allow it for a while.", style = MaterialTheme.typography.bodySmall)
        AppIconGrid(dev, state, grantable, onTap = { pick = it },
            notes = paused.mapValues { "Paused until\n${time(it.value)}" })
    }

    pick?.let { p ->
        // A paused app can simply be given back; any app can be allowed for a set time.
        val unpause = if (p in paused) ("Allow again now" to { Hub.unblock(dev, p); pick = null }) else null
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                extra?.let { (label, action) -> TextButton(onClick = action) { Text(label) } }
                GRANT_DURATIONS.forEach { m ->
                    TextButton(onClick = { onPick(m) }) { Text(fmtMinutes(m)) }
                }
                TextButton(onClick = {
                    val now = java.util.Calendar.getInstance()
                    android.app.TimePickerDialog(ctx, { _, h, min -> onPick(minutesUntil(h, min)) },
                        now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE),
                        android.text.format.DateFormat.is24HourFormat(ctx)).show()
                }) { Text("Until a time…") }
                TextButton(onClick = { custom = true }) { Text("Custom length…") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** Hours and minutes fields for a length that isn't in the list. */
@Composable
private fun CustomLengthDialog(title: String, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    var hours by remember { mutableStateOf("") }
    var mins by remember { mutableStateOf("") }
    val total = (hours.toLongOrNull() ?: 0) * 60 + (mins.toLongOrNull() ?: 0)
    val ok = total in 1..MAX_GRANT_MINUTES
    AlertDialog(
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
        confirmButton = { TextButton(onClick = { onPick(total) }, enabled = ok) {
            Text(if (ok) fmtMinutes(total) else "OK") } },
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
