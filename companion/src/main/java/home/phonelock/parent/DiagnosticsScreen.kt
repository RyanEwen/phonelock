package home.phonelock.parent

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * What the device reports about itself: a checklist (failed requirements first) and its recent log, with
 * Share to send the whole report as text (e.g. to whoever is troubleshooting). Asks for a fresh report
 * when opened.
 */
@Composable
internal fun DiagnosticsScreen(dev: String, name: String, onBack: () -> Unit) {
    val reports by Diagnostics.reports.collectAsState()
    val waiting by Diagnostics.waiting.collectAsState()
    val report = reports[dev]
    val asking = dev in waiting
    var showLog by remember(dev) { mutableStateOf(false) }
    LaunchedEffect(dev) { Diagnostics.request(dev) }
    val ctx = LocalContext.current

    Page("Diagnostics", onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { Diagnostics.request(dev) }, enabled = !asking) {
                Text(if (asking) "Asking $name…" else "Ask again")
            }
            if (report != null) Button(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "$name diagnostics")
                    .putExtra(Intent.EXTRA_TEXT, asText(name, report))
                ctx.startActivity(Intent.createChooser(send, "Share diagnostics"))
            }) { Text("Share") }
        }
        if (report == null) {
            Text(if (asking) "Waiting for $name to answer. If it's offline or off, nothing will come."
                else "No report yet.", style = MaterialTheme.typography.bodyMedium)
            return@Page
        }
        Text("Report from ${time(report.ts)}", style = MaterialTheme.typography.bodySmall)
        Section("Checks") {
            // Problems first, so they can't hide in a long list.
            report.checks.sortedBy { if (it.ok == false) 0 else 1 }.forEach { CheckRow(it) }
        }
        Section("Recent log") {
            TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide log" else "Show log") }
            if (showLog) Text(report.log.ifBlank { "Empty." }, Modifier.horizontalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, softWrap = false)
        }
    }
}

/** "✓ Device owner", "✗ Call screening role / Re-run the setup script", or "Battery: 54%". */
@Composable
private fun CheckRow(c: DiagCheck) {
    val mark = when (c.ok) { true -> "✓"; false -> "✗"; null -> "•" }
    val color = if (c.ok == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row {
        Text(mark, Modifier.width(24.dp), color = color, fontWeight = FontWeight.Bold)
        Column {
            Text(c.name, color = color, style = MaterialTheme.typography.bodyMedium)
            c.value?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** The whole report as plain text, for sharing. */
private fun asText(name: String, r: DiagReport): String = buildString {
    appendLine("$name diagnostics, ${java.util.Date(r.ts)}")
    appendLine()
    r.checks.forEach { c ->
        val mark = when (c.ok) { true -> "[ok]"; false -> "[FAIL]"; null -> "-" }
        appendLine("$mark ${c.name}" + (c.value?.let { ": $it" } ?: ""))
    }
    appendLine()
    appendLine("Recent log:")
    append(r.log)
}
