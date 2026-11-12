package home.phonelock.parent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * The app's dialog: Material's alert dialog, but nearly as wide as the screen (16 dp each side, at most
 * Material's 560 dp) instead of the platform's narrower default, so forms with steppers, chips and app lists
 * fit on a phone without wrapping words. Every dialog in the app uses this so they all share one width.
 */
@Composable
internal fun AppDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = Modifier.padding(horizontal = 16.dp),
        dismissButton = dismissButton,
        title = title,
        text = text,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    )
}

/**
 * "Pick one" as a dialog: [title], an optional [note] explaining the choice, then [options] as full-width
 * rows (easy to hit, and clearly different from the Cancel button). Choosing one runs it; the caller closes
 * the dialog. Used wherever the app asks for a length, a mode or an action on one thing.
 */
@Composable
internal fun OptionsDialog(title: String, onDismiss: () -> Unit, options: List<MenuEntry>, note: String? = null) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                note?.let {
                    Text(it, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
                options.forEach { o ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = o.enabled, onClick = o.onClick).padding(horizontal = 12.dp)
                        .alpha(if (o.enabled) 1f else 0.4f), verticalAlignment = Alignment.CenterVertically) {
                        Text(o.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** A dialog button for an action that deletes or removes something, in the error colour so it reads as such. */
@Composable
internal fun DangerButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label, color = MaterialTheme.colorScheme.error) }
}
