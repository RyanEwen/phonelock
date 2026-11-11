package home.phonelock.parent

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.google.android.gms.maps.model.LatLng

/** A gap between readings longer than this is shown, so "no data" can't be mistaken for "stayed put". */
internal const val GAP_MS = 45 * 60_000L
private const val DAY_MS = 24 * 3600_000L

/**
 * One line of a day's timeline. Everything but a gap is an event: it has a time and (usually) a position on
 * the map, and can be selected from the list or the slider.
 */
private sealed interface TimelineRow {
    val at: Long
    /** Where it happened, for the map; null when unknown (e.g. a call before the first reading). */
    val position: LatLng? get() = null

    data class At(val place: Place) : TimelineRow {
        override val at get() = place.from
        override val position get() = place.latLng
    }
    data class NoReadings(val from: Long, val to: Long) : TimelineRow { override val at get() = from }
    data class Call(val call: CallEntry, val where: Place?) : TimelineRow {
        override val at get() = call.ts
        override val position get() = where?.latLng
    }
    data class App(val session: AppSession, val where: Place?) : TimelineRow {
        override val at get() = session.from
        override val position get() = where?.latLng
    }
    /** The device's current location: always today's last event. */
    data class Now(val loc: PhoneLocation?, val time: Long, val locating: Boolean = false) : TimelineRow {
        override val at get() = time
        override val position get() = loc?.let { l -> if (l.lat != null && l.lng != null) LatLng(l.lat, l.lng) else null }
    }
}

/**
 * Where [dev] is and has been, a day at a time over the last 7 days. The map stays at the top; under it, a
 * slider that steps from event to event, then the day's timeline (places, gaps, calls and app use, with the
 * current location as today's latest event), which scrolls on its own. Tapping an event, or stepping to it,
 * shows it on the map. Opening the page, and Refresh, ask the device where it is now and for anything newer.
 */
@Composable
internal fun LocationHistoryScreen(dev: String, name: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val all by LocationHistory.places.collectAsState()
    val allApps by LocationHistory.apps.collectAsState()
    val addresses by LocationHistory.addresses.collectAsState()
    val allCalls by DeviceData.calls.collectAsState()
    val locations by PhoneTools.locations.collectAsState()
    val locating by PhoneTools.locating.collectAsState()

    /** Asks the device where it is now, and for any readings this phone hasn't got yet. */
    fun refresh() {
        PhoneTools.find(dev)
        LocationHistory.requestNewer(dev)
    }
    LaunchedEffect(dev) {
        refresh()
        DeviceData.loadCallLog(dev)
    }

    val places = all[dev].orEmpty().sortedBy { it.from }
    val now = System.currentTimeMillis()
    val current = locations[dev]

    // Every day with something on it (a place spanning several days counts for each), newest first, over
    // the 7 days kept; today is always there, for the current location.
    val days = remember(places, allApps[dev], allCalls[dev], startOfDay(now)) {
        val cutoff = startOfDay(now - 6 * DAY_MS)
        val set = sortedSetOf(startOfDay(now))
        places.forEach { p ->
            var d = maxOf(startOfDay(p.from), cutoff)
            while (d <= p.to) { set += d; d = nextDay(d) }
        }
        allApps[dev].orEmpty().forEach { set += startOfDay(it.from) }
        allCalls[dev].orEmpty().forEach { set += startOfDay(it.ts) }
        set.filter { it >= cutoff }.sortedDescending()
    }
    var day by remember(dev) { mutableLongStateOf(days.first()) }
    if (day !in days) day = days.first()
    val isToday = day == startOfDay(now)
    var showCalls by remember { mutableStateOf(true) }
    var showApps by remember { mutableStateOf(true) }

    val dayEnd = nextDay(day) // not day + 24 h: days are 23 or 25 hours when the clocks change
    val dayPlaces = places.filter { it.to >= day && it.from < dayEnd }
    val nowRow = if (isToday) TimelineRow.Now(current, current?.fixTime ?: now) else null
    val rows = timeline(
        places = dayPlaces,
        calls = if (showCalls) allCalls[dev].orEmpty().filter { it.ts >= day && it.ts < dayEnd } else emptyList(),
        apps = if (showApps) allApps[dev].orEmpty().filter { it.to >= day && it.from < dayEnd } else emptyList(),
        current = nowRow,
        locating = dev in locating,
    )
    // Events are the rows that can be selected; the latest starts selected.
    val events = rows.filterNot { it is TimelineRow.NoReadings }
    var selected by remember(dev, day, events.size) { mutableIntStateOf(events.lastIndex) }
    val selectedRow = events.getOrNull(selected)

    val list = rememberLazyListState()
    // Keep the selected event in view (the list's first item holds the day and filter chips). Keyed on the
    // selection itself, so rows changing (an address arriving) don't pull the list back while scrolling.
    LaunchedEffect(day, selected) {
        val i = rows.indexOf(selectedRow)
        if (i >= 0) list.animateScrollToItem(i + 1)
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Location", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold)
            IconButton(onClick = ::refresh, enabled = dev !in locating) {
                Icon(Icons.Default.Refresh, contentDescription = if (dev in locating) "Locating" else "Refresh")
            }
        }

        // Wide screens (tablets, unfolded foldables, landscape) put the map beside the timeline; narrow ones
        // stack them, with the map's height capped so the timeline always keeps room, and vice versa.
        BoxWithConstraints(Modifier.weight(1f)) {
            val wide = maxWidth >= 600.dp && maxWidth > maxHeight * 0.9f
            val map = @Composable { m: Modifier ->
                TrailMap(
                    places = dayPlaces,
                    marker = selectedRow?.position,
                    markerTitle = selectedRow?.let { clock(it.at) },
                    selection = selectedRow,
                    addresses = addresses,
                    now = nowRow?.position,
                    modifier = m,
                )
            }
            val side = @Composable { m: Modifier ->
                Column(m) {
                    // Steps from event to event.
                    if (events.size > 1) {
                        Slider(value = selected.toFloat(), onValueChange = { selected = it.roundToInt() },
                            valueRange = 0f..events.lastIndex.toFloat(), steps = (events.size - 2).coerceAtLeast(0))
                    }
                    // An event from before the first reading (or with none near it) has no place on the map.
                    if (selectedRow != null && selectedRow.position == null) {
                        Text("No location recorded at ${clock(selectedRow.at)}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Timeline(list, days, day, now, showCalls, showApps, rows, events, selected, addresses,
                        onDay = { day = it }, onCalls = { showCalls = !showCalls }, onApps = { showApps = !showApps },
                        onSelect = { selected = it }, modifier = Modifier.weight(1f))
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    map(Modifier.weight(1.2f).fillMaxHeight().padding(bottom = 12.dp))
                    side(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                // Between 180 dp and 45% of the space, never leaving the timeline less than 220 dp.
                val mapHeight = (maxHeight * 0.45f).coerceAtMost(maxHeight - 220.dp).coerceAtLeast(180.dp)
                Column(Modifier.fillMaxSize()) {
                    map(Modifier.height(mapHeight))
                    side(Modifier.weight(1f))
                }
            }
        }
    }
}

/** The day and filter chips, then the day's events, scrolling on their own. */
@Composable
private fun Timeline(
    list: androidx.compose.foundation.lazy.LazyListState,
    days: List<Long>, day: Long, now: Long, showCalls: Boolean, showApps: Boolean,
    rows: List<TimelineRow>, events: List<TimelineRow>, selected: Int, addresses: Map<String, String>,
    onDay: (Long) -> Unit, onCalls: () -> Unit, onApps: () -> Unit, onSelect: (Int) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(modifier, state = list, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    days.forEach { d ->
                        FilterChip(selected = d == day, onClick = { onDay(d) }, label = { Text(dayName(d, now)) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = showCalls, onClick = onCalls, label = { Text("Calls") })
                    FilterChip(selected = showApps, onClick = onApps, label = { Text("App use") })
                }
            }
        }
        if (rows.isEmpty()) item { Text("Nothing on this day.", style = MaterialTheme.typography.bodyMedium) }
        items(rows) { row ->
            val index = events.indexOf(row)
            TimelineItem(row, addresses, now, selected = index >= 0 && index == selected,
                onClick = if (index >= 0) ({ onSelect(index) }) else null)
        }
    }
}

/**
 * A day's rows in time order: its places, a "no readings" row wherever readings stopped for over [GAP_MS],
 * its calls and app use (each placed where the device was then), and, today, the [current] location last.
 */
private fun timeline(places: List<Place>, calls: List<CallEntry>, apps: List<AppSession>,
                     current: TimelineRow.Now?, locating: Boolean): List<TimelineRow> {
    val rows = mutableListOf<TimelineRow>()
    places.forEachIndexed { i, p ->
        val prev = places.getOrNull(i - 1)
        if (prev != null && p.from - prev.to > GAP_MS) rows += TimelineRow.NoReadings(prev.to, p.from)
        rows += TimelineRow.At(p)
    }
    calls.forEach { rows += TimelineRow.Call(it, placeAt(places, it.ts)) }
    apps.forEach { rows += TimelineRow.App(it, placeAt(places, it.from)) }
    return rows.sortedBy { it.at } + listOfNotNull(current?.copy(locating = locating))
}

/** One timeline line: its time, what happened and where; highlighted while it's the one on the map. */
@Composable
private fun TimelineItem(row: TimelineRow, addresses: Map<String, String>, now: Long, selected: Boolean,
                         onClick: (() -> Unit)?) {
    fun where(p: Place?) = p?.let { "at " + (addresses[it.spot] ?: "%.4f, %.4f".format(it.lat, it.lng)) }
    if (row is TimelineRow.At) LaunchedEffect(row.place.spot) { LocationHistory.lookUp(row.place) }
    val time: String
    val title: String
    val detail: String?
    when (row) {
        is TimelineRow.At -> {
            val p = row.place
            time = clockSpan(p.from, p.to)
            title = addresses[p.spot] ?: "%.5f, %.5f".format(p.lat, p.lng)
            detail = buildList {
                if (p.accuracyM > 0) add("within ${p.accuracyM} m")
                if (p.battery >= 0) add("battery ${p.battery}%" + if (p.charging) " (charging)" else "")
            }.joinToString(" · ").ifEmpty { null }
        }
        is TimelineRow.NoReadings -> {
            time = clockSpan(row.from, row.to)
            title = "No readings"
            detail = null
        }
        is TimelineRow.Call -> {
            val c = row.call
            val who = c.name.ifEmpty { c.number }
            time = clock(c.ts)
            title = "📞 " + when (c.type) {
                "out" -> "Called $who"
                "in" -> "Call from $who"
                "missed" -> "Missed call from $who"
                "rejected", "blocked" -> "Blocked call from $who"
                else -> "Call with $who"
            } + if (c.durationS > 0) ", ${fmtMinutes(maxOf(1, c.durationS / 60))}" else ""
            detail = where(row.where)
        }
        is TimelineRow.App -> {
            val s = row.session
            time = clockSpan(s.from, s.to)
            title = "📱 ${s.label}, ${fmtMinutes(maxOf(1, (s.to - s.from) / 60_000))}"
            detail = where(row.where)
        }
        is TimelineRow.Now -> {
            val l = row.loc
            time = l?.fixTime?.let { clock(it) } ?: "Now"
            title = "📍 Now: " + when {
                l == null && row.locating -> "locating…"
                l == null -> "no answer (offline or switched off)"
                l.error != null -> l.error
                else -> l.address ?: "%.5f, %.5f".format(l.lat ?: 0.0, l.lng ?: 0.0)
            }
            detail = l?.takeIf { it.error == null }?.let {
                buildList {
                    it.fixTime?.let { t -> add("seen ${ago(now - t)}") }
                    it.accuracyM?.let { a -> add("within $a m") }
                    it.battery?.let { b -> add("battery $b%" + if (it.charging) " (charging)" else "") }
                }.joinToString(" · ").ifEmpty { null }
            }
        }
    }
    val muted = row is TimelineRow.NoReadings
    Row(Modifier.fillMaxWidth()
        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(14.dp))
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(time, Modifier.width(92.dp), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium,
                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            detail?.let {
                Text(it.replaceFirstChar { c -> c.uppercase() }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Midnight at the start of [t]'s day, in the phone's time zone. */
private fun startOfDay(t: Long): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = t
    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

/** Midnight at the start of the day after [day] (23 or 25 hours later when the clocks change). */
private fun nextDay(day: Long): Long = startOfDay(day + DAY_MS + 3 * 3600_000L)

/** "Today", "Yesterday", or "Mon, Nov 9". */
private fun dayName(d: Long, now: Long): String = when (startOfDay(now) - d) {
    0L -> "Today"
    in 1..DAY_MS + 3600_000 -> "Yesterday"
    else -> java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.getDefault()).format(java.util.Date(d))
}

/**
 * Opens ([lat], [lng]) in Google Maps (or any map app) with a pin labelled [name]: a geo: link, so no map
 * SDK is needed. Falls back to the web when no map app is installed.
 */
internal fun openMap(ctx: android.content.Context, lat: Double, lng: Double, name: String) {
    val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(name)})")
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: ActivityNotFoundException) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$lat,$lng")))
    }
}
