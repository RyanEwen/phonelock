package home.phonelock.parent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** A refresh with no reply after this long means the device isn't answering (offline, off, or no signal). */
private const val NO_ANSWER_MS = 20_000L
/** Not heard from in this long: the status dot turns amber. */
private const val QUIET_MS = 40 * 60_000L
/** A battery reading older than this shows its age. */
private const val BATTERY_FRESH_MS = 20 * 60_000L

/**
 * The selected device at a glance: name; a quiet line with when it was last heard from and its battery; the
 * current situation as a title with what comes next; and a button to start or end a situation by hand. [askedAt] is when this app
 * last asked it for its state, so a refresh that goes unanswered says so instead of doing nothing visible.
 * [connected] is this phone's own link to the hub (live connection or push).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhoneHeader(dev: String, name: String, state: PhoneState, vitals: Vitals, askedAt: Long, connected: Boolean, now: Long,
                         onRefresh: () -> Unit, onMenu: () -> Unit, onLockNow: () -> Unit = {},
                         onStartMode: () -> Unit = {}, onEndMode: () -> Unit = {}) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                IconButton(onClick = onMenu) { Icon(Icons.Default.MoreVert, contentDescription = "Device settings") }
            }
            // Meta line: whether the device is answering, and its battery, both as plain secondary text.
            val status = deviceStatus(vitals, state, askedAt, connected, now)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Green while the device is answering and recently heard from; amber otherwise.
                    Box(Modifier.size(8.dp).background(if (status.ok) Color(0xFF3DBE6B) else Color(0xFFE0A030), CircleShape))
                    Text(status.text, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                if (vitals.battery >= 0) BatteryLevel(vitals, state, now)
            }
            InUseLine(dev, state, now)

            // The situation is the main thing: what the child can use right now, and what comes next.
            val manualOn = state.manualMode.isNotEmpty() && state.manualUntil > System.currentTimeMillis()
            val situation = situationLines(state, manualOn)
            Column(Modifier.padding(top = 4.dp)) {
                Text(situation.first, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    color = if (state.mode != "normal") MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onPrimaryContainer)
                situation.second?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            // Start limited hours or bedtime by hand, or end the one that's running: a real button.
            FilledTonalButton(onClick = if (manualOn) onEndMode else onStartMode) {
                Icon(Icons.Default.DateRange, contentDescription = null, Modifier.size(18.dp))
                Text(if (manualOn) "End ${modeLabel(state.manualMode).lowercase()} now" else "Start limited hours or bedtime",
                    Modifier.padding(start = 8.dp))
            }
            if (state.ringing) Pill("Ringing", highlighted = true)
            if (state.parentMode) Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Parent mode" + if (state.parentUntil > 0) " until ${time(state.parentUntil)}" else " on",
                    highlighted = true)
                androidx.compose.material3.TextButton(onClick = onLockNow) { Text("Lock now") }
            }
        }
    }
}

/** After this long, the in-use line says when it was true ("as of 9:40 p.m."), since it may have changed. */
private const val IN_USE_FRESH_MS = 2 * 60_000L

/**
 * What the child was doing when the device last reported: "Using YouTube · 12 min" with its icon, "On the home
 * screen" or "Screen off". The device reports this only when asked (opening or refreshing this app), so an
 * older report is labelled with its time and its duration stops there. Nothing for lock apps too old to say.
 */
@Composable
private fun InUseLine(dev: String, state: PhoneState, now: Long) {
    val screenOn = state.screenOn ?: return
    val stale = now - state.updated > IN_USE_FRESH_MS
    val asOf = if (stale) ", as of ${time(state.updated)}" else ""
    val color = MaterialTheme.colorScheme.onPrimaryContainer
    val style = MaterialTheme.typography.bodyMedium
    when {
        state.using.isNotEmpty() -> Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppIcon(dev, state.using, state.label(state.using), 20.dp)
            // Counting on while fresh; frozen at the report's time once it may be out of date.
            val open = (if (stale) state.updated else now) - state.usingSince
            val length = if (open < 60_000) "just opened" else "open ${fmtMinutes(open / 60_000)}"
            Text("Using ${state.label(state.using)} · $length$asOf", style = style, color = color,
                fontWeight = FontWeight.Medium)
        }
        screenOn -> Text("On the home screen$asOf", style = style, color = color)
        else -> Text("Screen off$asOf", style = style, color = color)
    }
}

/** The header's status line and whether things look fine ([ok]: answering and recently heard from). */
private data class DeviceStatus(val text: String, val ok: Boolean)

/**
 * "Seen 3 min ago", "Checking… · seen 3 min ago" right after a refresh, and "Not answering · last seen 18
 * min ago" when a refresh got no reply in [NO_ANSWER_MS], and "Getting settings…" until the device's full
 * settings have arrived (editing waits for them). Prefixed "Connecting…" while this phone itself
 * has no link to the hub, since then nothing from the device could arrive either.
 */
private fun deviceStatus(v: Vitals, state: PhoneState, askedAt: Long, connected: Boolean, now: Long): DeviceStatus {
    val seen = maxOf(v.seen, state.updated)
    val prefix = if (connected) "" else "Connecting… · "
    if (seen <= 0) return DeviceStatus(prefix + "No reply yet", false)
    val waiting = askedAt > v.heard
    val text = when {
        waiting && now - askedAt >= NO_ANSWER_MS -> "Not answering · last seen ${ago(now - seen)}"
        waiting -> "Checking… · seen ${ago(now - seen)}"
        // Settings (app lists, schedule) still on their way: said here, in place, instead of in each section.
        !state.full -> "Getting settings… · seen ${ago(now - seen)}"
        else -> "Seen ${ago(now - seen)}"
    }
    val ok = connected && !(waiting && now - askedAt >= NO_ANSWER_MS) && now - seen < QUIET_MS
    return DeviceStatus(prefix + text, ok)
}

/**
 * The situation as a title and an optional detail line: "Bedtime" / "Until 7:00 AM", "Limited hours" /
 * "Started by hand, until 3:15 PM", or in normal hours what comes next: "Normal hours" / "Bedtime at 9:30 PM".
 * Times more than a day away get their weekday.
 */
@Composable
private fun situationLines(state: PhoneState, manualOn: Boolean): Pair<String, String?> {
    val title = modeLabel(state.mode)
    if (manualOn) return title to "Started by hand, until ${whenText(state.manualUntil)}"
    val until = state.modeUntil
    if (until <= 0) return title to null
    val at = whenText(until)
    if (state.mode != "normal") return title to "Until $at"
    val next = state.schedule.modeAt(until)
    return title to if (next == "normal") null else "${modeLabel(next)} at $at"
}

/**
 * Battery as secondary detail next to "Seen …": a small battery drawn to its level, the percentage, and
 * "charging" or the reading's age. Low (the parents' alert level, or 15% when the alert is off) turns it red.
 */
@Composable
private fun BatteryLevel(vitals: Vitals, state: PhoneState, now: Long) {
    val low = !vitals.charging && vitals.battery <= (state.battAlert.takeIf { it > 0 } ?: 15)
    val colour = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer
    val age = if (now - vitals.batteryAt > BATTERY_FRESH_MS) " · ${ago(now - vitals.batteryAt)}" else ""
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BatteryGlyph(vitals.battery, colour)
        Text("${vitals.battery}%" + (if (vitals.charging) " · charging" else "") + age + (if (low) " · low" else ""),
            style = MaterialTheme.typography.bodyMedium, color = colour,
            fontWeight = if (low) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** A horizontal battery outline filled to [level] percent. */
@Composable
private fun BatteryGlyph(level: Int, colour: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(width = 20.dp, height = 11.dp)) {
        val stroke = 1.5.dp.toPx()
        val tip = 2.dp.toPx()
        val body = androidx.compose.ui.geometry.Size(size.width - tip, size.height)
        val r = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
        drawRoundRect(colour, size = body, cornerRadius = r, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        val inset = stroke + 1.dp.toPx()
        val fill = (body.width - inset * 2) * level.coerceIn(0, 100) / 100f
        drawRect(colour, topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(fill, body.height - inset * 2))
        drawRect(colour, topLeft = androidx.compose.ui.geometry.Offset(body.width, size.height * 0.3f),
            size = androidx.compose.ui.geometry.Size(tip, size.height * 0.4f))
    }
}

/** The time of [ms], with the weekday in front when it's more than a day away. */
@Composable
private fun whenText(ms: Long): String {
    val t = time(ms)
    if (ms - System.currentTimeMillis() < 24 * 3600_000L) return t
    return java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(java.util.Date(ms)) + " " + t
}

/** A small rounded label; [highlighted] uses the tertiary colour so non-normal states stand out. */
@Composable
internal fun Pill(text: String, highlighted: Boolean = false) {
    val bg = if (highlighted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surface
    val fg = if (highlighted) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurface
    Text(text, Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelLarge, color = fg)
}
