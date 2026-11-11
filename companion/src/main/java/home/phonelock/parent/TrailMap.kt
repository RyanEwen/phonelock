package home.phonelock.parent

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState

/** A stop of this many minutes or more gets a pin; shorter ones are just part of the line. */
private const val PIN_MINUTES = 10

/**
 * One day's trail on a Google map: the places in time order joined by a line (dashed across a gap with no
 * readings, so a missing stretch can't look like a straight trip), each place as a circle sized by how
 * precise it is, pins on longer stops, the device's current position [now] in green, and a red marker at
 * [marker] (the selected timeline event). [places] are oldest first. The camera fits the whole day (and
 * [now]) when the day changes, and moves to [marker] whenever [selection] changes.
 */
@Composable
internal fun TrailMap(
    places: List<Place>,
    marker: LatLng?,
    markerTitle: String?,
    selection: Any?,
    addresses: Map<String, String>,
    now: LatLng?,
    modifier: Modifier = Modifier,
) {
    val camera = rememberCameraPositionState()
    var loaded by remember { mutableStateOf(false) }
    val dayKey = places.firstOrNull()?.from

    // Fit the day once the map can be measured, and again whenever another day is shown.
    LaunchedEffect(loaded, dayKey) {
        val points = places.map { it.latLng } + listOfNotNull(now)
        if (!loaded || points.isEmpty()) return@LaunchedEffect
        if (points.distinct().size == 1) {
            camera.move(CameraUpdateFactory.newLatLngZoom(points[0], 15f))
        } else {
            val b = LatLngBounds.builder()
            points.forEach { b.include(it) }
            // The padding must leave room on a small map (it throws otherwise); fall back to the centre.
            try {
                camera.move(CameraUpdateFactory.newLatLngBounds(b.build(), 48))
            } catch (e: IllegalStateException) {
                camera.move(CameraUpdateFactory.newLatLngZoom(b.build().center, 13f))
            }
        }
    }
    // Follow the selected event.
    LaunchedEffect(loaded, selection) {
        if (loaded && marker != null && selection != null) {
            camera.animate(CameraUpdateFactory.newLatLngZoom(marker, maxOf(camera.position.zoom, 15f)))
        }
    }

    val line = MaterialTheme.colorScheme.primary
    val area = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    GoogleMap(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)),
        cameraPositionState = camera,
        uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false),
        onMapLoaded = { loaded = true },
    ) {
        // The trail, segment by segment so gaps can be drawn dashed.
        for (i in 1 until places.size) {
            val prev = places[i - 1]
            val cur = places[i]
            val gap = cur.from - prev.to > GAP_MS
            Polyline(points = listOf(prev.latLng, cur.latLng), color = line, width = 10f,
                pattern = if (gap) listOf(Dash(24f), Gap(18f)) else null)
        }
        places.forEach { p ->
            Circle(center = p.latLng, radius = maxOf(p.accuracyM.toDouble(), 20.0), fillColor = area,
                strokeColor = line, strokeWidth = 2f)
            if ((p.to - p.from) / 60_000 >= PIN_MINUTES) {
                Marker(state = rememberUpdatedMarkerState(position = p.latLng),
                    title = addresses[p.spot] ?: "Stop",
                    snippet = clockSpan(p.from, p.to),
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
            }
        }
        now?.let {
            Marker(state = rememberUpdatedMarkerState(position = it), title = "Now", zIndex = 2f,
                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
        }
        marker?.let {
            Marker(state = rememberUpdatedMarkerState(position = it), title = markerTitle, zIndex = 3f)
        }
    }
}

internal val Place.latLng get() = LatLng(lat, lng)

/**
 * Where the device was at [t]: the place covering that time, else a point on the straight line between the
 * places either side (it was travelling, or there's a gap). Null outside the day's readings.
 */
internal fun positionAt(places: List<Place>, t: Long): LatLng? {
    if (places.isEmpty() || t < places.first().from || t > places.last().to) return null
    places.firstOrNull { t in it.from..it.to }?.let { return it.latLng }
    val i = places.indexOfFirst { it.from > t }
    if (i <= 0) return places.last().latLng
    val a = places[i - 1]
    val b = places[i]
    val f = ((t - a.to).toDouble() / (b.from - a.to).coerceAtLeast(1)).coerceIn(0.0, 1.0)
    return LatLng(a.lat + (b.lat - a.lat) * f, a.lng + (b.lng - a.lng) * f)
}

/**
 * The place covering [t], or the last one before it if that ended no more than [GAP_MS] earlier. Null when
 * there was no reading near that time (location off, no signal), rather than an old place.
 */
internal fun placeAt(places: List<Place>, t: Long): Place? =
    places.firstOrNull { t in it.from..it.to } ?: places.lastOrNull { it.to <= t && t - it.to <= GAP_MS }

/** "2:15 p.m." in the phone's own time format. */
internal fun clock(t: Long): String = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(t))

/**
 * "2:15 p.m." for a single reading, else "2:15 p.m. – 5:30 p.m.". A span crossing midnight shows the days
 * too ("Fri 8:00 p.m. – Sat 9:00 a.m."), so it can't read as the same evening.
 */
internal fun clockSpan(from: Long, to: Long): String {
    val a = clock(from)
    val b = clock(to)
    if (to - from < 60_000 || a == b) return a
    val day = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
    val sameDay = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).let { it.format(java.util.Date(from)) == it.format(java.util.Date(to)) }
    return if (sameDay) "$a – $b" else "${day.format(java.util.Date(from))} $a – ${day.format(java.util.Date(to))} $b"
}
