package home.phonelock.parent

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp

/**
 * The main screen's "Unfamiliar screens" section: Google services screens the device reported, each with
 * its screenshot (tap to enlarge) and Mark OK / Block / Dismiss. Shown only while there are some, like
 * Requests.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScreenAlertsSection(dev: String) {
    val all by ScreenAlerts.alerts.collectAsState()
    val mine = all.filter { it.dev == dev }
    if (mine.isEmpty()) return
    var enlarged by remember { mutableStateOf<ImageBitmap?>(null) }

    Section("Unfamiliar screens") {
        Text("Google screens that aren't known to be safe or blocked. Mark OK to stop hearing about one; " +
            "Block sends her back whenever it opens.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        mine.forEach { a ->
            // Decoded once per report; the files are small JPEGs.
            val image = remember(a.image) { a.image?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() } }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (image != null) {
                    Image(image, "Screenshot", Modifier.width(72.dp).heightIn(max = 150.dp)
                        .clip(RoundedCornerShape(8.dp)).clickable { enlarged = image }, contentScale = ContentScale.Fit)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(a.name, style = MaterialTheme.typography.titleSmall)
                    val from = if (a.from.isNotEmpty()) "From ${a.from} · " else ""
                    Text(from + time(a.ts), style = MaterialTheme.typography.bodySmall)
                    a.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { ScreenAlerts.decide(a.id, "ok") }) { Text("Mark OK") }
                        OutlinedButton(onClick = { ScreenAlerts.decide(a.id, "block") }) { Text("Block") }
                        TextButton(onClick = { ScreenAlerts.decide(a.id, "dismiss") }) { Text("Dismiss") }
                    }
                }
            }
        }
    }

    enlarged?.let { img ->
        AppDialog(onDismissRequest = { enlarged = null },
            confirmButton = { TextButton(onClick = { enlarged = null }) { Text("Close") } },
            text = { Image(img, "Screenshot", Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) })
    }
}

/**
 * Settings' "Google screens" section: the groups of Google screens the device blocks unless allowed (account
 * management, Google settings, help, feedback, Quick Share), then single screens a parent decided about. Each
 * row says where it stands and has a menu to allow it (always, for a while, or until bedtime), block it, or go
 * back to the default. Hidden until the device reports its groups (an older lock app doesn't).
 */
@Composable
internal fun ScreenRulesSection(dev: String, state: PhoneState?) {
    val st = state ?: return
    val singles = st.screenRules.keys.filterNot { it.startsWith(Hub.AREA_PREFIX) }
    if (st.screenAreas.isEmpty() && singles.isEmpty()) return
    val now = System.currentTimeMillis()
    // "Until bedtime" only makes sense when a bedtime is coming up; the device checks again when asked.
    val bedtimeAhead = st.mode != "bedtime" && st.schedule.any { it.mode == "bedtime" }

    Section("Google screens") {
        Text("Google screens that lead out of the lock. They're blocked unless you allow them; you're told when " +
            "she's sent back from one.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        st.screenAreas.forEach { area ->
            ScreenRuleRow(dev, Hub.AREA_PREFIX + area.key, area.name, st.screenRules[Hub.AREA_PREFIX + area.key],
                blockedByDefault = true, now = now, bedtimeAhead = bedtimeAhead)
        }
        singles.sortedBy { ScreenAlerts.screenName(it) }.forEach { screen ->
            ScreenRuleRow(dev, screen, ScreenAlerts.screenName(screen), st.screenRules[screen],
                blockedByDefault = false, now = now, bedtimeAhead = bedtimeAhead)
        }
    }
}

/**
 * One row of [ScreenRulesSection]: [title], where it stands under [rule] ("ok", "block", "ok:<until>" or none,
 * which means blocked for an area and reported for a single screen), and its menu.
 */
@Composable
private fun ScreenRuleRow(dev: String, key: String, title: String, rule: String?, blockedByDefault: Boolean,
                          now: Long, bedtimeAhead: Boolean) {
    val until = rule?.takeIf { it.startsWith("ok:") }?.removePrefix("ok:")?.toLongOrNull()?.takeIf { it > now }
    val allowed = rule == "ok" || until != null
    val decided = rule == "ok" || rule == "block" || until != null
    val status = when {
        rule == "ok" -> "Allowed"
        until != null -> "Allowed until ${time(until)}"
        rule == "block" -> "Blocked"
        blockedByDefault -> "Blocked (default)"
        else -> "Reported when it opens"
    }

    /** Sends a decision about this row. */
    fun set(rule: String, minutes: Int = 0, untilBedtime: Boolean = false) =
        Hub.setScreenRule(dev, key, rule, minutes, untilBedtime)

    val entries = buildList {
        if (rule != "ok") add(MenuEntry("Allow") { set("ok") })
        add(MenuEntry("Allow for 15 min") { set("ok", minutes = 15) })
        add(MenuEntry("Allow for 1 hour") { set("ok", minutes = 60) })
        if (bedtimeAhead) add(MenuEntry("Allow until bedtime") { set("ok", untilBedtime = true) })
        if (rule != "block") add(MenuEntry("Block") { set("block") })
        if (decided) add(MenuEntry(if (blockedByDefault) "Back to blocked" else "Forget decision") { set("") })
    }
    ListRow(title, AnnotatedString(status), leading = { SymbolBadge(if (allowed) "✓" else "⛔") }, actions = entries)
}
