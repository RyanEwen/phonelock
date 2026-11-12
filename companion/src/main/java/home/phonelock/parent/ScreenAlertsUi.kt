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
 * Settings' "Google screens" section: the screens a parent marked OK or blocked on this device, each with
 * a menu to forget the decision. Hidden when there are none.
 */
@Composable
internal fun ScreenRulesSection(dev: String, state: PhoneState?) {
    val rules = state?.screenRules.orEmpty()
    if (rules.isEmpty()) return
    Section("Google screens") {
        rules.entries.sortedBy { ScreenAlerts.screenName(it.key) }.forEach { (screen, rule) ->
            val ok = rule == "ok"
            ListRow(ScreenAlerts.screenName(screen),
                AnnotatedString(if (ok) "Allowed: not reported" else "Blocked: she's sent back from it"),
                leading = { SymbolBadge(if (ok) "✓" else "⛔") },
                actions = listOf(MenuEntry("Forget decision") { Hub.setScreenRule(dev, screen, "") }))
        }
    }
}
