package home.phonelock.parent

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** Situation bits, matching lock/.../Schedule.java. */
const val MODE_NORMAL = 1
const val MODE_LIMITED = 2
const val MODE_BEDTIME = 4
private const val DEFAULT_MASK = MODE_NORMAL or MODE_LIMITED

/**
 * One time range of limited hours or bedtime. [days]: bit 0 = Monday … bit 6 = Sunday. [start]/[end]:
 * minutes after midnight; `end < start` runs past midnight, `end == start` is all day.
 */
data class ScheduleRule(val mode: String, val days: Int, val start: Int, val end: Int) {
    fun toJson(): JSONObject = JSONObject().put("mode", mode).put("days", days).put("start", start).put("end", end)

    companion object {
        fun listFrom(a: JSONArray?): List<ScheduleRule> = if (a == null) emptyList() else
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                ScheduleRule(it.optString("mode"), it.optInt("days"), it.optInt("start"), it.optInt("end"))
            }

        fun toJson(rules: List<ScheduleRule>) = JSONArray().apply { rules.forEach { put(it.toJson()) } }
    }
}

/**
 * The situation ("normal", "limited", "bedtime") these rules give at [ms], matching the device's
 * Schedule.modeAt: bedtime wins over limited hours, overnight ranges spill into the next day, and
 * start == end covers the whole day.
 */
fun List<ScheduleRule>.modeAt(ms: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = ms }
    val day = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Monday = 0
    val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    fun has(r: ScheduleRule, d: Int) = r.days and (1 shl d) != 0
    fun covers(r: ScheduleRule) = when {
        r.start == r.end -> has(r, day)
        r.start < r.end -> has(r, day) && minute >= r.start && minute < r.end
        else -> (has(r, day) && minute >= r.start) || (has(r, (day + 6) % 7) && minute < r.end)
    }
    val hits = filter(::covers)
    return when {
        hits.any { it.mode == "bedtime" } -> "bedtime"
        hits.isNotEmpty() -> "limited"
        else -> "normal"
    }
}

private val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")
private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** "Limited hours", "Bedtime", "Normal" for a mode name from the phone. */
fun modeLabel(mode: String) = when (mode) {
    "bedtime" -> "Bedtime"
    "limited" -> "Limited hours"
    else -> "Normal"
}

/** "normal hours", "limited hours", "bedtime" for one situation bit, for messages. */
internal fun situationName(bit: Int) = when (bit) {
    MODE_BEDTIME -> "bedtime"
    MODE_LIMITED -> "limited hours"
    else -> "normal hours"
}

/** "Weekdays", "Every day", "Mon, Wed, Fri", … */
private fun describeDays(days: Int): String = when (days and 0x7f) {
    0x7f -> "Every day"
    0x1f -> "Weekdays"
    0x60 -> "Weekends"
    else -> DAY_NAMES.filterIndexed { i, _ -> days and (1 shl i) != 0 }.joinToString(", ")
}

@Composable
private fun clock(minutes: Int): String {
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, minutes / 60)
        set(Calendar.MINUTE, minutes % 60)
    }
    return DateFormat.getTimeFormat(LocalContext.current).format(cal.time)
}

/**
 * "Apps and when they're allowed": one row per app on the device with a checkbox for each situation; no
 * ticks means it's only ever allowed for a while. Each row's ⋮ menu hides or uninstalls the app; hidden
 * apps are listed below, only to be shown again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppTableSection(dev: String, state: PhoneState) {
    var showHidden by remember(dev) { mutableStateOf(false) }
    var uninstalling by remember(dev) { mutableStateOf<String?>(null) }
    uninstalling?.let { p ->
        AlertDialog(
            onDismissRequest = { uninstalling = null },
            title = { Text("Uninstall ${state.label(p)}?") },
            text = { Text("It's removed from their device with its data. This can't be undone; you'd have to install it again.") },
            confirmButton = { TextButton(onClick = { Hub.uninstall(dev, p); uninstalling = null }) { Text("Uninstall") } },
            dismissButton = { TextButton(onClick = { uninstalling = null }) { Text("Cancel") } })
    }
    // Every app on the device, Phone first and then A to Z, ticked or not.
    val rows = phoneFirst(state, appsOnly((state.home + state.others).distinct()))
    Section("Apps and when they're allowed") {
        if (!state.full) Text("Loading the device's settings… Changes are possible once they arrive.",
            style = MaterialTheme.typography.bodySmall)
        Text("Ticked apps are on their home screen during that time. Apps with no ticks are only allowed for a while.",
            style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("", Modifier.weight(1f))
            listOf("Normal", "Limited", "Bedtime").forEach {
                Text(it, Modifier.width(CELL), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.width(MENU))
        }
        HorizontalDivider()
        rows.forEach { p ->
            val mask = if (p in state.home) state.masks[p] ?: DEFAULT_MASK else 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppName(dev, p, state.label(p), Modifier.weight(1f))
                listOf(MODE_NORMAL, MODE_LIMITED, MODE_BEDTIME).forEach { bit ->
                    Row(Modifier.width(CELL), horizontalArrangement = Arrangement.Center) {
                        Checkbox(checked = mask and bit != 0, enabled = state.full, onCheckedChange = { on ->
                            Hub.appModeBit(dev, p, bit, on)
                            Undo.offer("${state.label(p)}: ${situationName(bit)} ${if (on) "allowed" else "not allowed"}") {
                                Hub.appModeBit(dev, p, bit, !on)
                            }
                        })
                    }
                }
                AppMenu(state, p, canHide = mask == 0, onHide = {
                    Hub.offer(dev, p, false)
                    Undo.offer("${state.label(p)} hidden") { Hub.offer(dev, p, true) }
                }, onUninstall = { uninstalling = p })
            }
        }
        // Hidden apps live here only to be brought back.
        val hidden = appsOnly(state.notOffered)
        if (hidden.isNotEmpty()) {
            TextButton(onClick = { showHidden = !showHidden }) {
                Text(if (showHidden) "Hide the hidden apps" else "Hidden apps (${hidden.size})")
            }
            if (showHidden) phoneFirst(state, hidden).forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppName(dev, p, state.label(p), Modifier.weight(1f))
                    TextButton(enabled = state.full, onClick = {
                        Hub.offer(dev, p, true)
                        Undo.offer("${state.label(p)} shown") { Hub.offer(dev, p, false) }
                    }) { Text("Show") }
                }
            }
        }
    }
}

/** Width of one Normal / Limited / Bedtime checkbox column, and of the row menu. */
private val CELL = 56.dp
private val MENU = 40.dp

/**
 * The ⋮ menu on an app row: Hide (only for apps with no ticks, so hiding never quietly takes an app off the
 * home screen) and Uninstall (only for apps installed on the device).
 */
@Composable
private fun AppMenu(state: PhoneState, pkg: String, canHide: Boolean, onHide: () -> Unit, onUninstall: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.width(MENU)) {
        IconButton(onClick = { open = true }, enabled = state.full) {
            Icon(Icons.Default.MoreVert, contentDescription = "More for ${state.label(pkg)}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(if (canHide) "Hide" else "Hide (untick it first)") },
                enabled = canHide,
                onClick = { open = false; onHide() })
            if (pkg in state.removable) DropdownMenuItem(
                text = { Text("Uninstall…") },
                onClick = { open = false; onUninstall() })
        }
    }
}

/** "Schedule": the limited-hours and bedtime ranges, with add and remove. */
@Composable
internal fun ScheduleSection(dev: String, state: PhoneState) {
    // The rule being added (index -1) or edited (its index in the device's list).
    var editing by remember(dev) { mutableStateOf<Pair<Int, ScheduleRule>?>(null) }
    val now = System.currentTimeMillis()
    // Shown in the order they next happen, a running one first; edits still address the device's list.
    val order = state.schedule.indices.sortedBy { state.schedule[it].nextStart(now) }
    Section("Schedule") {
        if (state.schedule.isEmpty()) {
            Text("No limited hours or bedtime yet, so it's normal hours all the time.",
                style = MaterialTheme.typography.bodyMedium)
        }
        order.forEach { i ->
            val r = state.schedule[i]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(modeLabel(r.mode), style = MaterialTheme.typography.titleSmall)
                    Text(if (r.start == r.end) "${describeDays(r.days)}, all day"
                        else "${describeDays(r.days)}, ${clock(r.start)} to ${clock(r.end)}",
                        style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(enabled = state.full, onClick = { editing = i to r }) { Text("Edit") }
                TextButton(enabled = state.full, onClick = {
                    val before = state.schedule
                    Hub.setSchedule(dev, before.filterIndexed { j, _ -> j != i })
                    Undo.offer("${modeLabel(r.mode)} removed") { Hub.setSchedule(dev, before) }
                }) {
                    Text("Remove")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Edits replace the whole schedule, so they wait until the device's current one has arrived.
            OutlinedButton(enabled = state.full, onClick = { editing = -1 to ScheduleRule("limited", 0x1f, 8 * 60 + 30, 15 * 60 + 15) }) {
                Text("Add limited hours")
            }
            OutlinedButton(enabled = state.full, onClick = { editing = -1 to ScheduleRule("bedtime", 0x7f, 21 * 60 + 30, 7 * 60) }) {
                Text("Add bedtime")
            }
        }
    }
    editing?.let { (index, draft) ->
        RuleDialog(draft, isNew = index < 0, onSave = { rule ->
            val before = state.schedule
            val after = if (index < 0) before + rule else before.mapIndexed { j, r -> if (j == index) rule else r }
            Hub.setSchedule(dev, after)
            Undo.offer("${modeLabel(rule.mode)} ${if (index < 0) "added" else "changed"}") { Hub.setSchedule(dev, before) }
            editing = null
        }, onDismiss = { editing = null })
    }
}

/**
 * When this rule next starts, in ms; [now] itself if it is running at that moment, so active ones sort
 * first. Overnight rules count from their evening start; all-day rules from midnight.
 */
internal fun ScheduleRule.nextStart(now: Long): Long {
    if (listOf(this).modeAt(now) != "normal") return now
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    for (offset in 0..7) {
        val day = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_MONTH, offset)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val weekday = (day.get(Calendar.DAY_OF_WEEK) + 5) % 7
        if (days and (1 shl weekday) == 0) continue
        val startMs = day.timeInMillis + (if (start == end) 0 else start) * 60_000L
        if (startMs >= cal.timeInMillis) return startMs
    }
    return Long.MAX_VALUE
}

/** Edits a new rule's days and times. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleDialog(draft: ScheduleRule, isNew: Boolean, onSave: (ScheduleRule) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(draft.mode) }
    var days by remember { mutableIntStateOf(draft.days) }
    var start by remember { mutableIntStateOf(draft.start) }
    var end by remember { mutableIntStateOf(draft.end) }
    fun pickTime(initial: Int, set: (Int) -> Unit) {
        TimePickerDialog(ctx, { _, h, m -> set(h * 60 + m) }, initial / 60, initial % 60,
            DateFormat.is24HourFormat(ctx)).show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add ${modeLabel(mode).lowercase()}" else "Edit ${modeLabel(mode).lowercase()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Which situation this range is; editable, so a range can switch between the two.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == "limited", onClick = { mode = "limited" }, label = { Text("Limited hours") })
                    FilterChip(selected = mode == "bedtime", onClick = { mode = "bedtime" }, label = { Text("Bedtime") })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DAY_LETTERS.forEachIndexed { i, l ->
                        FilterChip(selected = days and (1 shl i) != 0, onClick = { days = days xor (1 shl i) },
                            label = { Text(l) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("From", Modifier.width(56.dp))
                    TextButton(onClick = { pickTime(start) { start = it } }) { Text(clock(start)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Until", Modifier.width(56.dp))
                    TextButton(onClick = { pickTime(end) { end = it } }) { Text(clock(end)) }
                }
                when {
                    end == start -> Text("All day.", style = MaterialTheme.typography.bodySmall)
                    end < start -> Text("Runs past midnight into the next day.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft.copy(mode = mode, days = days, start = start, end = end)) },
                enabled = days != 0) { Text(if (isNew) "Add" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
