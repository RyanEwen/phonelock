package home.phonelock.parent

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

private val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")
private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** "Limited hours", "Bedtime", "Normal" for a mode name from the phone. */
fun modeLabel(mode: String) = when (mode) {
    "bedtime" -> "Bedtime"
    "limited" -> "Limited hours"
    else -> "Normal"
}

/** "normal hours", "limited hours", "bedtime" for one situation bit, for messages. */
private fun situationName(bit: Int) = when (bit) {
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
 * ticks means it's only ever allowed for a while. "Edit list" hides apps that will never be allowed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppTableSection(dev: String, state: PhoneState) {
    var editing by remember(dev) { mutableStateOf(false) }
    // Every app on the device: scheduled ones in home-screen order, then the rest alphabetically.
    val rows = state.home + state.others.sortedBy { state.label(it).lowercase() }
    Section("Apps and when they're allowed") {
        if (!state.full) Text("Loading the device's settings… Changes are possible once they arrive.",
            style = MaterialTheme.typography.bodySmall)
        Text(if (editing) "Hide apps you'll never allow; they leave this list and their Ask picker."
            else "Ticked apps are on their home screen during that time. Apps with no ticks are only allowed for a while.",
            style = MaterialTheme.typography.bodySmall)
        if (!editing) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("", Modifier.weight(1f))
            listOf("Normal", "Limited", "Bedtime").forEach {
                Text(it, Modifier.width(64.dp), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            }
        }
        HorizontalDivider()
        rows.forEach { p ->
            val mask = if (p in state.home) state.masks[p] ?: DEFAULT_MASK else 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppName(dev, p, state.label(p), Modifier.weight(1f))
                if (editing) {
                    // Scheduled apps must be unticked first, so hiding never silently takes away a home app.
                    if (mask == 0) TextButton(onClick = {
                        Hub.offer(dev, p, false)
                        Undo.offer("${state.label(p)} hidden") { Hub.offer(dev, p, true) }
                    }) { Text("Hide") }
                } else {
                    listOf(MODE_NORMAL, MODE_LIMITED, MODE_BEDTIME).forEach { bit ->
                        Row(Modifier.width(64.dp), horizontalArrangement = Arrangement.Center) {
                            Checkbox(checked = mask and bit != 0, enabled = state.full, onCheckedChange = { on ->
                                Hub.appModeBit(dev, p, bit, on)
                                Undo.offer("${state.label(p)}: ${situationName(bit)} ${if (on) "allowed" else "not allowed"}") {
                                    Hub.appModeBit(dev, p, bit, !on)
                                }
                            })
                        }
                    }
                }
            }
        }
        if (editing && state.notOffered.isNotEmpty()) {
            Text("Hidden", style = MaterialTheme.typography.titleSmall)
            state.notOffered.sortedBy { state.label(it).lowercase() }.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppName(dev, p, state.label(p), Modifier.weight(1f))
                    TextButton(onClick = {
                        Hub.offer(dev, p, true)
                        Undo.offer("${state.label(p)} shown") { Hub.offer(dev, p, false) }
                    }) { Text("Show") }
                }
            }
        }
        OutlinedButton(onClick = { editing = !editing }, enabled = state.full) {
            Text(if (editing) "Done" else "Edit list")
        }
    }
}

/** "Schedule": the limited-hours and bedtime ranges, with add and remove. */
@Composable
internal fun ScheduleSection(dev: String, state: PhoneState) {
    var editing by remember(dev) { mutableStateOf<ScheduleRule?>(null) }
    Section("Schedule") {
        if (state.schedule.isEmpty()) {
            Text("No limited hours or bedtime yet, so it's normal hours all the time.",
                style = MaterialTheme.typography.bodyMedium)
        }
        state.schedule.forEachIndexed { i, r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(modeLabel(r.mode), style = MaterialTheme.typography.titleSmall)
                    Text(if (r.start == r.end) "${describeDays(r.days)}, all day"
                        else "${describeDays(r.days)}, ${clock(r.start)} to ${clock(r.end)}",
                        style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(enabled = state.full, onClick = { Hub.setSchedule(dev, state.schedule.filterIndexed { j, _ -> j != i }) }) {
                    Text("Remove")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Edits replace the whole schedule, so they wait until the phone's current one has arrived.
            OutlinedButton(enabled = state.full, onClick = { editing = ScheduleRule("limited", 0x1f, 8 * 60 + 30, 15 * 60 + 15) }) {
                Text("Add limited hours")
            }
            OutlinedButton(enabled = state.full, onClick = { editing = ScheduleRule("bedtime", 0x7f, 21 * 60 + 30, 7 * 60) }) {
                Text("Add bedtime")
            }
        }
    }
    editing?.let { draft ->
        RuleDialog(draft, onSave = { Hub.setSchedule(dev, state.schedule + it); editing = null },
            onDismiss = { editing = null })
    }
}

/** Edits a new rule's days and times. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleDialog(draft: ScheduleRule, onSave: (ScheduleRule) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var days by remember { mutableIntStateOf(draft.days) }
    var start by remember { mutableIntStateOf(draft.start) }
    var end by remember { mutableIntStateOf(draft.end) }
    fun pickTime(initial: Int, set: (Int) -> Unit) {
        TimePickerDialog(ctx, { _, h, m -> set(h * 60 + m) }, initial / 60, initial % 60,
            DateFormat.is24HourFormat(ctx)).show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add ${modeLabel(draft.mode).lowercase()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            TextButton(onClick = { onSave(draft.copy(days = days, start = start, end = end)) },
                enabled = days != 0) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
