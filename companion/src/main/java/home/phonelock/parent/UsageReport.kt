package home.phonelock.parent

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/** The span a report covers. */
enum class Period(val label: String) { DAY("Day"), WEEK("Week"), MONTH("Month") }

/**
 * One coloured part of the chart: a single app, or everything else ("Other").
 * [key] is "app:<package>" or "other".
 */
data class Series(val key: String, val label: String, val apps: Set<String>, val color: Color)

/** Distinct colours for chart parts, readable on light and dark backgrounds; "Other" is grey. */
private val PALETTE = listOf(
    Color(0xFF4C8DF6), Color(0xFFF2994A), Color(0xFF27AE60), Color(0xFFEB5757),
    Color(0xFF9B51E0), Color(0xFF00A3A3), Color(0xFFD4A017), Color(0xFFE056A0),
)
private val OTHER_COLOR = Color(0xFF9AA0A6)

/** How many apps "All apps" shows by name before the rest become "Other". */
private const val TOP_APPS = 5

/**
 * The days [period] covers around [anchor]: just that day, its week (starting on the phone's usual first
 * day of the week), or its month.
 */
fun periodDays(period: Period, anchor: LocalDate): List<LocalDate> = when (period) {
    Period.DAY -> listOf(anchor)
    Period.WEEK -> {
        val first: DayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
        val start = anchor.with(TemporalAdjusters.previousOrSame(first))
        (0L until 7L).map { start.plusDays(it) }
    }
    Period.MONTH -> {
        val start = anchor.withDayOfMonth(1)
        (0L until anchor.lengthOfMonth().toLong()).map { start.plusDays(it) }
    }
}

/** Moves [anchor] one [period] back ([step] -1) or forward (+1). */
fun shift(period: Period, anchor: LocalDate, step: Int): LocalDate = when (period) {
    Period.DAY -> anchor.plusDays(step.toLong())
    Period.WEEK -> anchor.plusWeeks(step.toLong())
    Period.MONTH -> anchor.plusMonths(step.toLong())
}

/** Minutes per app over [dates] from [days] (day -> app -> minutes). */
fun totalsOver(days: Map<String, Map<String, Int>>, dates: List<LocalDate>): Map<String, Int> {
    val out = HashMap<String, Int>()
    dates.forEach { d -> days[d.toString()]?.forEach { (app, m) -> out[app] = (out[app] ?: 0) + m } }
    return out
}

/** [days] keeping only the apps in [keep] (a day with none of them stays, empty). */
fun onlyApps(days: Map<String, Map<String, Int>>, keep: Set<String>): Map<String, Map<String, Int>> =
    days.mapValues { (_, apps) -> apps.filterKeys { it in keep } }

/**
 * The chart's parts. With nothing [selected], the [TOP_APPS] most used apps over the period and "Other";
 * otherwise the selected apps ("app:<package>"), in the order chosen.
 */
fun seriesFor(selected: List<String>, totals: Map<String, Int>, label: (String) -> String): List<Series> {
    if (selected.isEmpty()) {
        val top = totals.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(TOP_APPS).map { it.key }
        val parts = top.mapIndexed { i, p -> Series("app:$p", label(p), setOf(p), PALETTE[i % PALETTE.size]) }
        val rest = totals.keys - top.toSet()
        return if (rest.any { (totals[it] ?: 0) > 0 }) parts + Series("other", "Other", rest, OTHER_COLOR) else parts
    }
    return selected.filter { it.startsWith("app:") }.mapIndexed { i, key ->
        key.removePrefix("app:").let { Series(key, label(it), setOf(it), PALETTE[i % PALETTE.size]) }
    }
}

/** Minutes [s] took on [day]. */
fun minutesOn(days: Map<String, Map<String, Int>>, day: LocalDate, s: Series): Int =
    days[day.toString()]?.let { apps -> s.apps.sumOf { apps[it] ?: 0 } } ?: 0

/**
 * A stacked bar chart: one bar per entry of [bars] (a day, or for the Day view a single part), each split
 * into [series] colours, with hour gridlines on the left and [xLabel] under each bar ("" leaves it blank).
 * Tapping a bar calls [onTap] with its index.
 */
@Composable
fun StackedBars(bars: List<List<Int>>, series: List<Series>, xLabel: (Int) -> String, onTap: (Int) -> Unit = {}) {
    val measurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val maxTotal = bars.maxOfOrNull { it.sum() } ?: 0
    val top = niceCeiling(maxTotal)
    Canvas(Modifier.fillMaxWidth().height(220.dp).pointerInput(bars.size) {
        detectTapGestures { pos ->
            val left = 44.dp.toPx()
            val slot = (size.width - left) / bars.size.coerceAtLeast(1)
            val i = ((pos.x - left) / slot).toInt()
            if (pos.x >= left && i in bars.indices) onTap(i)
        }
    }) {
        val left = 44.dp.toPx()
        val bottom = size.height - 22.dp.toPx()
        val style = TextStyle(fontSize = 11.sp, color = textColor)

        // Gridlines at zero, half and the top value, labelled in hours and minutes.
        listOf(0, top / 2, top).distinct().forEach { m ->
            val y = bottom - bottom * m / top.coerceAtLeast(1)
            drawLine(axisColor, Offset(left, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            val t = measurer.measure(shortMinutes(m), style)
            drawText(t, topLeft = Offset(left - t.size.width - 6.dp.toPx(), y - t.size.height / 2f))
        }

        if (bars.isEmpty() || top == 0) return@Canvas
        val slot = (size.width - left) / bars.size
        val barWidth = (slot * 0.62f).coerceAtMost(36.dp.toPx())
        bars.forEachIndexed { i, parts ->
            val x = left + slot * i + (slot - barWidth) / 2
            var y = bottom
            parts.forEachIndexed { s, m ->
                if (m <= 0) return@forEachIndexed
                val h = bottom * m / top
                drawSegment(series[s].color, Offset(x, y - h), Size(barWidth, h))
                y -= h
            }
            val label = xLabel(i)
            if (label.isNotEmpty()) {
                val t = measurer.measure(label, style)
                drawText(t, topLeft = Offset(x + barWidth / 2 - t.size.width / 2, bottom + 4.dp.toPx()))
            }
        }
    }
}

/** One coloured piece of a bar, with a hairline gap above it so stacked parts read as separate. */
private fun DrawScope.drawSegment(color: Color, topLeft: Offset, size: Size) {
    val gap = 1.dp.toPx().coerceAtMost(size.height / 2)
    drawRoundRect(color, topLeft = Offset(topLeft.x, topLeft.y + gap), size = Size(size.width, size.height - gap),
        cornerRadius = CornerRadius(3.dp.toPx()))
}

/** A round top for the chart: the next of 30 min, 1 h, 2 h, 3 h… above [minutes]. */
private fun niceCeiling(minutes: Int): Int {
    if (minutes <= 0) return 60
    val steps = listOf(30, 60, 120, 180, 240, 360, 480, 600, 720, 960, 1200, 1440)
    return steps.firstOrNull { it >= minutes } ?: (((minutes + 59) / 60) * 60)
}

/** "45m", "2h", "1h30" for axis labels. */
private fun shortMinutes(m: Int): String = when {
    m == 0 -> "0"
    m < 60 -> "${m}m"
    m % 60 == 0 -> "${m / 60}h"
    else -> "${m / 60}h${m % 60}"
}
