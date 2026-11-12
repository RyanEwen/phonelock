package home.phonelock.parent

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** A clock face, for the "App time" tile (the core icon set has none). */
internal val ClockIcon: ImageVector by lazy {
    ImageVector.Builder("Clock", 24.dp, 24.dp, 24f, 24f).addPath(
        PathParser().parsePathString(
            "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20zm0 2a8 8 0 1 1 0 16a8 8 0 1 1 0-16z" +
                "M11 6h2v5.6l4 2.3-1 1.7-5-2.9z"
        ).toNodes(),
        fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
    ).build()
}

/**
 * "App time" for one device: a report of time spent in apps by day, week or month, filterable by app. Daily
 * limits are set from each app's ⋮ menu in the main app table, not here. Asks the device for any days not
 * stored yet when opened.
 */
@Composable
internal fun AppTimeScreen(dev: String, name: String, state: PhoneState, onBack: () -> Unit) {
    val error by AppTime.error.collectAsState()
    LaunchedEffect(dev) { AppTime.requestHistory(dev) }

    Page("App time", onBack, dev = dev, waitingOn = setOf("usage")) {
        Text("To set a daily limit on an app, use ⋮ next to it in \"Apps and when they're allowed\" on the main screen.",
            style = MaterialTheme.typography.bodySmall)
        ReportSection(dev, state)
        error[dev]?.let { Text(it + ". Use Fix setup over Wi-Fi to allow it.", color = MaterialTheme.colorScheme.error) }
    }
}

// ---------- limits ----------

/**
 * One line on where [l] stands today, for lists: "1 h a day · 35 min left", "1 h a day · Time's up today",
 * or just the allowance when today's use hasn't been reported. [today] is the device's latest report; the
 * second value is true when the time has run out.
 */
internal fun limitStatus(l: TimeLimit, today: AppTimeToday?): Pair<String, Boolean> {
    val t = today?.takeIf { it.day == LocalDate.now().toString() }
    val allowed = l.minutes[LocalDate.now().dayOfWeek.value % 7]
    val used = t?.used?.get(l.id)
    val base = allowanceText(l.minutes)
    return when {
        t?.out?.contains(l.id) == true -> "$base · Time's up today" to true
        allowed < 0 -> "$base · no limit today" to false
        allowed == 0 -> "$base · not allowed today" to false
        used == null -> base to false
        else -> "$base · ${fmtMinutes((allowed - used).coerceAtLeast(0).toLong())} left" to false
    }
}

/** A new limit on [app], one hour a day, for "Set a daily limit". */
internal fun newLimit(app: String) = TimeLimit(TimeLimit.newId(), app, List(7) { 60 })

/** "1 h a day", "1 h on weekdays, 2 h on weekends", or "Varies by day". */
private fun allowanceText(min: List<Int>): String {
    fun one(m: Int) = when {
        m < 0 -> "No limit"
        m == 0 -> "Not allowed"
        else -> fmtMinutes(m.toLong())
    }
    val weekdays = min.subList(1, 6).distinct()
    val weekend = listOf(min[0], min[6]).distinct()
    return when {
        min.distinct().size == 1 -> if (min[0] < 0) "No limit" else if (min[0] == 0) "Not allowed any day" else "${one(min[0])} a day"
        weekdays.size == 1 && weekend.size == 1 -> "${one(weekdays[0])} on weekdays, ${one(weekend[0]).lowercase()} on weekends"
        else -> "Varies by day"
    }
}

/** Steps offered for a limit, in minutes; -1 is "No limit", 0 "Not allowed". */
private val LIMIT_STEPS = listOf(-1, 0, 15, 30, 45, 60, 90, 120, 150, 180, 240, 300, 360, 480)

/** How a limit's minutes are entered: one value, weekdays and weekends, or each day. */
private enum class LimitShape { SAME, SPLIT, EACH }

/**
 * Sets, changes or removes [initial]'s app's daily limit: how long each day, entered as one value, weekdays
 * and weekends, or day by day.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LimitDialog(state: PhoneState, initial: TimeLimit, isNew: Boolean,
                         onSave: (TimeLimit) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var minutes by remember { mutableStateOf(initial.minutes) }
    var shape by remember { mutableStateOf(shapeOf(initial.minutes)) }

    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("Daily limit for ${state.label(initial.app)}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(shape == LimitShape.SAME, { shape = LimitShape.SAME; minutes = List(7) { minutes[1] } },
                        label = { Text("Every day") })
                    FilterChip(shape == LimitShape.SPLIT, {
                        shape = LimitShape.SPLIT
                        minutes = List(7) { d -> if (d == 0 || d == 6) minutes[0] else minutes[1] }
                    }, label = { Text("Weekdays and weekends") })
                    FilterChip(shape == LimitShape.EACH, { shape = LimitShape.EACH }, label = { Text("Each day") })
                }
                when (shape) {
                    LimitShape.SAME -> Stepper("Every day", minutes[1]) { v -> minutes = List(7) { v } }
                    LimitShape.SPLIT -> {
                        Stepper("Monday to Friday", minutes[1]) { v -> minutes = List(7) { d -> if (d in 1..5) v else minutes[d] } }
                        Stepper("Saturday and Sunday", minutes[0]) { v -> minutes = List(7) { d -> if (d == 0 || d == 6) v else minutes[d] } }
                    }
                    LimitShape.EACH -> (0 until 7).map { (it + 1) % 7 }.forEach { d ->
                        Stepper(java.time.DayOfWeek.of(if (d == 0) 7 else d).getDisplayName(TextStyle.FULL, Locale.getDefault()),
                            minutes[d]) { v -> minutes = minutes.toMutableList().also { it[d] = v } }
                    }
                }
                Text("When the time runs out the app closes until tomorrow. Time you allow with \"Allow for a while\" " +
                    "is extra and doesn't count.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(initial.copy(minutes = minutes)) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (!isNew) DangerButton("Remove", onClick = onDelete)
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        })
}

private fun shapeOf(min: List<Int>): LimitShape = when {
    min.distinct().size == 1 -> LimitShape.SAME
    min.subList(1, 6).distinct().size == 1 && min[0] == min[6] -> LimitShape.SPLIT
    else -> LimitShape.EACH
}

/**
 * "[label]   −  1 h 30 min  +", stepping through [LIMIT_STEPS]. The buttons are compact and the value
 * takes only the width it needs, so a full day name keeps one line in a phone-width dialog.
 */
@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit) {
    val i = LIMIT_STEPS.indexOf(value).takeIf { it >= 0 } ?: LIMIT_STEPS.indexOfFirst { it >= value }.coerceAtLeast(0)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = { onChange(LIMIT_STEPS[(i - 1).coerceAtLeast(0)]) }, Modifier.size(40.dp), enabled = i > 0) {
            Text("−", style = MaterialTheme.typography.titleMedium)
        }
        Text(when {
            value < 0 -> "No limit"
            value == 0 -> "Not allowed"
            else -> fmtMinutes(value.toLong())
        }, Modifier.widthIn(min = 72.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1)
        IconButton(onClick = { onChange(LIMIT_STEPS[(i + 1).coerceAtMost(LIMIT_STEPS.lastIndex)]) }, Modifier.size(40.dp),
            enabled = i < LIMIT_STEPS.lastIndex) {
            Text("+", style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ---------- report ----------

/**
 * Time spent in apps over a day, week or month: a stacked bar chart, a filter to show chosen apps
 * (or the most used apps), and each part's total. Tapping a day's bar opens that day.
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ReportSection(dev: String, state: PhoneState) {
    val allDays by AppTime.days.collectAsState()
    val today by AppTime.today.collectAsState()
    val limits by AppTime.limits.collectAsState()
    val names by AppTime.labels.collectAsState()
    var period by rememberSaveable(dev) { mutableStateOf(Period.WEEK) }
    var anchorText by rememberSaveable(dev) { mutableStateOf(LocalDate.now().toString()) }
    var selected by rememberSaveable(dev) { mutableStateOf(listOf<String>()) }
    val anchor = LocalDate.parse(anchorText)

    // The stored record, with today replaced by the device's latest state when that's newer.
    val allApps = allDays[dev].orEmpty().let { d ->
        today[dev]?.takeIf { it.day.isNotEmpty() }?.let { t -> d + (t.day to t.apps) } ?: d
    }
    val label: (String) -> String = { p -> names[dev]?.get(p) ?: state.label(p) }

    // The child's apps are the ones parents manage (home screen, Ask list, hidden) plus anything under a limit; the
    // rest are system parts such as Google Play services or Settings, hidden unless asked for. Until the
    // device's full app list has arrived nothing can be told apart, so everything shows.
    var showSystem by rememberSaveable(dev) { mutableStateOf(false) }
    val herApps = (state.home + state.others + state.notOffered + limits[dev].orEmpty().map { it.app }).toSet()
    val canFilter = state.full
    val days = if (showSystem || !canFilter) allApps else onlyApps(allApps, herApps)

    val dates = periodDays(period, anchor)
    val totals = totalsOver(days, dates)
    val series = seriesFor(selected, totals, label)

    Section("App use") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Period.entries.forEachIndexed { i, p ->
                SegmentedButton(selected = period == p, onClick = { period = p },
                    shape = SegmentedButtonDefaults.itemShape(i, Period.entries.size)) { Text(p.label) }
            }
        }
        StepNav(periodTitle(period, dates),
            onEarlier = { anchorText = shift(period, anchor, -1).toString() },
            onLater = if (dates.last() < LocalDate.now()) ({ anchorText = shift(period, anchor, 1).toString() }) else null)

        val bars = if (period == Period.DAY) series.indices.map { s -> series.indices.map { if (it == s) minutesOn(days, anchor, series[s]) else 0 } }
            else dates.map { d -> series.map { minutesOn(days, d, it) } }
        if (series.isEmpty() || bars.all { it.sum() == 0 }) {
            EmptyNote(if (days.isEmpty()) "No app use recorded yet." else "No app use in this period.")
        } else {
            if (period != Period.DAY) Text("Tap a bar to see that day.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            StackedBars(bars, series, xLabel = { i -> barLabel(period, dates, i) }, onTap = { i ->
                if (period != Period.DAY) { anchorText = dates[i].toString(); period = Period.DAY }
            })
        }

        // Legend and totals: each part's time over the period (and per day on average for weeks and months).
        series.forEach { s ->
            val total = dates.sumOf { minutesOn(days, it, s) }
            val counted = dates.count { !it.isAfter(LocalDate.now()) }.coerceAtLeast(1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(s.color, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(s.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(fmtMinutes(total.toLong()) + if (period != Period.DAY) " · ${fmtMinutes((total / counted).toLong())}/day" else "",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Filter: nothing chosen shows the most used apps; choosing shows exactly those (the same menu as Activity).
        val apps = totals.entries.filter { it.value > 0 }.sortedByDescending { it.value }.map { it.key to label(it.key) }
        if (apps.size > 1) AppPicker(apps, selected.map { it.removePrefix("app:") }.toSet(),
            onChange = { chosen -> selected = chosen.map { "app:$it" } }, allLabel = "Most used apps")
        if (canFilter) SwitchRow("Include system apps", "Background parts such as Google Play services and Settings",
            on = showSystem, enabled = true) { showSystem = it }
    }
}

/** "Wednesday, Nov 11", "Nov 9 – 15", "November 2026". */
private fun periodTitle(period: Period, dates: List<LocalDate>): String {
    val first = dates.first()
    return when (period) {
        Period.DAY -> first.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
        Period.WEEK -> first.format(DateTimeFormatter.ofPattern("MMM d")) + " – " +
            dates.last().format(DateTimeFormatter.ofPattern(if (dates.last().month == first.month) "d" else "MMM d"))
        Period.MONTH -> first.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
    }
}

/** Weekday initials under a week's bars; every 7th day's number under a month's; nothing for a day. */
private fun barLabel(period: Period, dates: List<LocalDate>, i: Int): String = when (period) {
    Period.DAY -> ""
    Period.WEEK -> dates[i].dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
    Period.MONTH -> if ((dates[i].dayOfMonth - 1) % 7 == 0) dates[i].dayOfMonth.toString() else ""
}
