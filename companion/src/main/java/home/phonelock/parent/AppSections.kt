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
 * [badge], if given, is drawn on each icon's corner (e.g. "✕" while editing). Built from rows rather than a
 * lazy grid because it sits inside the main screen's scrolling column.
 */
@Composable
internal fun AppIconGrid(dev: String, state: PhoneState, pkgs: List<String>, onTap: (String) -> Unit,
                         badge: String? = null, columns: Int = 4) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        pkgs.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { p ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onTap(p) }
                        .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box {
                            AppIcon(dev, p, state.label(p), 48.dp)
                            if (badge != null) Text(badge, Modifier.align(Alignment.TopEnd)
                                .background(MaterialTheme.colorScheme.errorContainer, CircleShape)
                                .padding(horizontal = 5.dp), style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(state.label(p), style = MaterialTheme.typography.labelMedium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** "Allowed right now": temporary grants with time left, extend and stop. */
@Composable
internal fun AllowedNowSection(dev: String, state: PhoneState, now: Long) {
    Section("Allowed right now") {
        val live = state.grants.filterValues { it > now }
        if (live.isEmpty()) Text("Only the apps scheduled for now.", style = MaterialTheme.typography.bodyMedium)
        live.forEach { (pkg, until) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    AppName(dev, pkg, state.label(pkg))
                    Text("until ${time(until)} (${fmtMinutes((until - now) / 60_000 + 1)} left)",
                        style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = {
                    Hub.grant(dev, pkg, (until - System.currentTimeMillis()) / 60_000 + 30)
                }) { Text("+30 min") }
                TextButton(onClick = { Hub.revoke(dev, pkg) }) { Text("Stop") }
            }
        }
        if (live.size > 1) OutlinedButton(onClick = { Hub.revoke(dev, "all") }) { Text("Stop all") }
    }
}

/**
 * "Allow for a while": tap an app, then pick a length. "Edit list" takes apps off the list (they also leave the
 * child's Ask picker) and shows the removed ones so they can be put back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AllowForAWhileSection(dev: String, state: PhoneState) {
    var pick by remember(dev) { mutableStateOf<String?>(null) }
    var editing by remember(dev) { mutableStateOf(false) }
    // Unscheduled apps, plus scheduled ones that the current situation blocks (e.g. at bedtime).
    val bit = when (state.mode) { "bedtime" -> MODE_BEDTIME; "limited" -> MODE_LIMITED; else -> MODE_NORMAL }
    val blockedNow = state.home.filter { (state.masks[it] ?: (MODE_NORMAL or MODE_LIMITED)) and bit == 0 }
    val others = state.others.sortedBy { state.label(it).lowercase() }
    val grantable = (blockedNow + state.others).distinct().sortedBy { state.label(it).lowercase() }

    Section("Allow for a while") {
        if (state.others.isEmpty() && state.notOffered.isEmpty()) {
            Text("Tap Refresh to load the device's apps.", style = MaterialTheme.typography.bodySmall)
        }
        if (editing) {
            Text("Tap an app to take it off this list. They won't be able to ask for it either.",
                style = MaterialTheme.typography.bodySmall)
            AppIconGrid(dev, state, others, onTap = { Hub.offer(dev, it, false) }, badge = "✕")
            if (state.notOffered.isNotEmpty()) {
                Text("Taken off the list", style = MaterialTheme.typography.titleSmall)
                AppIconGrid(dev, state, state.notOffered.sortedBy { state.label(it).lowercase() },
                    onTap = { Hub.offer(dev, it, true) }, badge = "+")
            }
            Button(onClick = { editing = false }) { Text("Done") }
            return@Section
        }

        // Tapping an app asks how long; nothing is sent until a length is chosen.
        AppIconGrid(dev, state, grantable, onTap = { pick = it })
        TextButton(onClick = { editing = true; pick = null }) { Text("Edit list") }
    }

    pick?.let { p -> GrantLengthDialog(state.label(p), onPick = { m -> Hub.grant(dev, p, m); pick = null },
        onDismiss = { pick = null }) }
}

/** Asks how long to allow [label] for; [onPick] gets the minutes. */
@Composable
internal fun GrantLengthDialog(label: String, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Allow $label for how long?") },
        text = {
            Column {
                GRANT_DURATIONS.forEach { m ->
                    TextButton(onClick = { onPick(m) }) { Text(fmtMinutes(m)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
