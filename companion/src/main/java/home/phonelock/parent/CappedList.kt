package home.phonelock.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

/**
 * Shows the first [initial] of [items], with "Show more" adding [step] at a time and "Show less" folding
 * back, so long histories neither fill the screen nor draw hundreds of rows. [key] resets it (e.g. per
 * device).
 */
@Composable
internal fun <T> CappedList(items: List<T>, key: Any, initial: Int = 5, step: Int = 20, row: @Composable (T) -> Unit) {
    var shown by rememberSaveable(key) { mutableIntStateOf(initial) }
    items.take(shown).forEach { row(it) }
    if (items.size > initial) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (items.size > shown) TextButton(onClick = { shown += step }) { Text("Show more (${items.size - shown})") }
        if (shown > initial) TextButton(onClick = { shown = initial }) { Text("Show less") }
    }
}
