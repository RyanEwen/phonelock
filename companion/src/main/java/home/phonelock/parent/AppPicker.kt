package home.phonelock.parent

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * "All apps ▾": a menu of [apps] (package to name, most used first) to tick the ones to show; none ticked
 * ([chosen] empty) is [allLabel], meaning the default view. The menu stays open while ticking. Shared by
 * Activity and the App time report so both filter apps the same way.
 */
@Composable
internal fun AppPicker(apps: List<Pair<String, String>>, chosen: Set<String>, onChange: (Set<String>) -> Unit,
                       allLabel: String = "All apps") {
    var open by remember { mutableStateOf(false) }
    val title = when (chosen.size) {
        0 -> allLabel
        1 -> apps.firstOrNull { it.first in chosen }?.second ?: "1 app"
        else -> "${chosen.size} apps"
    }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("$title ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(allLabel) }, onClick = { onChange(emptySet()); open = false },
                leadingIcon = { Checkbox(checked = chosen.isEmpty(), onCheckedChange = null) })
            apps.forEach { (app, label) ->
                DropdownMenuItem(text = { Text(label) },
                    onClick = { onChange(if (app in chosen) chosen - app else chosen + app) },
                    leadingIcon = { Checkbox(checked = app in chosen, onCheckedChange = null) })
            }
        }
    }
}
