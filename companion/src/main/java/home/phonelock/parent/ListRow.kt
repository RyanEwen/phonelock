package home.phonelock.parent

import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import android.telephony.PhoneNumberUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Avatar colours: muted enough for white initials, distinct enough to tell neighbours apart. */
private val AVATAR_COLORS = listOf(
    Color(0xFF5C6BC0), Color(0xFF26A69A), Color(0xFFEF6C00), Color(0xFF8E24AA),
    Color(0xFF00897B), Color(0xFFD81B60), Color(0xFF3949AB), Color(0xFF6D4C41),
)

/**
 * [number] laid out the usual way for this phone's country ("+1 705-555-1234"), so numbers saved in
 * different styles look alike. Returned unchanged when it can't be formatted.
 */
internal fun formatNumber(number: String): String =
    runCatching { PhoneNumberUtils.formatNumber(number, Locale.getDefault().country) }.getOrNull() ?: number

/** A round avatar with [name]'s first letter (or "#" for a bare number), its colour fixed by the name. */
@Composable
internal fun InitialAvatar(name: String) {
    val letter = name.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString() ?: "#"
    val color = AVATAR_COLORS[Math.floorMod(name.hashCode(), AVATAR_COLORS.size)]
    Box(Modifier.size(40.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Text(letter, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** A round 40 dp badge holding [symbol] (an emoji), on a soft background, for rows that aren't people. */
@Composable
internal fun SymbolBadge(symbol: String) {
    Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center) {
        Text(symbol, style = MaterialTheme.typography.titleMedium)
    }
}

/** A small rounded tag after a name, such as "Safe". */
@Composable
private fun Tag(text: String) {
    Text(text, Modifier.background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(6.dp))
        .padding(horizontal = 6.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
}

/**
 * The app's list row: a 40 dp [leading] badge, [title] with an optional [tag], a [detail] line, and a ⋮ menu
 * of [actions] when there are any; [onClick] makes the whole row tappable and [onLongClick] adds a long
 * press (for starting a selection). Used by contacts, the safe list, the call log, the schedule and files so
 * every list reads the same way.
 */
@Composable
internal fun ListRow(title: String, detail: AnnotatedString, leading: @Composable () -> Unit, tag: String? = null,
                     actions: List<MenuEntry> = emptyList(), actionsEnabled: Boolean = true, onClick: (() -> Unit)? = null,
                     onLongClick: (() -> Unit)? = null) {
    val base = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
    val tappable = when {
        onLongClick != null -> base.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)
        onClick != null -> base.clickable(onClick = onClick)
        else -> base
    }
    Row(tappable.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        leading()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, Modifier.weight(1f, fill = false), fontWeight = FontWeight.Medium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                tag?.let { Tag(it) }
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (actions.isNotEmpty()) RowMenu("More for $title", enabled = actionsEnabled, entries = actions)
    }
}

/** A [ListRow] for a person or number, with their initial as the badge. */
@Composable
internal fun PersonRow(name: String, detail: AnnotatedString, tag: String? = null,
                       actions: List<MenuEntry> = emptyList(), actionsEnabled: Boolean = true) =
    ListRow(name, detail, leading = { InitialAvatar(name) }, tag = tag, actions = actions, actionsEnabled = actionsEnabled)
