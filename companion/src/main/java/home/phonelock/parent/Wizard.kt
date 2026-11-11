package home.phonelock.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The overview that opens a step-by-step flow: the flow's [heading] with a back arrow, [title], what's coming
 * and what's needed ([content], usually a [StepOutline] and [Requirements]), then Get started. It isn't one
 * of the numbered steps. [onBack] leaves the flow.
 */
@Composable
internal fun WizardIntro(heading: String, title: String, onBack: () -> Unit, onStart: () -> Unit,
                         content: @Composable () -> Unit) {
    Page(heading, onBack) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        content()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart) { Text("Get started") }
            TextButton(onClick = onBack) { Text("Cancel") }
        }
    }
}

/**
 * One step of a step-by-step flow (adding a device, setup): the flow's [heading] with a back arrow, "Step 2
 * of 3" (left out when there's only one step), the step's [title], its [content], then the primary [next]
 * button and Back. Long forms are split into these so each screen asks for one thing. [onBack] goes to the previous step (or leaves the flow from
 * the first); a null [onNext] hides the primary button (e.g. while waiting).
 */
@Composable
internal fun WizardStep(
    heading: String,
    step: Int,
    steps: Int,
    title: String,
    onBack: () -> Unit,
    next: String = "Next",
    nextEnabled: Boolean = true,
    onNext: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    Page(heading, onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (steps > 1) Text("Step $step of $steps", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
        content()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onNext != null) Button(onClick = onNext, enabled = nextEnabled) { Text(next) }
            TextButton(onClick = onBack) { Text("Back") }
        }
    }
}

/**
 * A numbered outline of what's coming ([items]: title and a line of explanation), for the first step of a
 * flow, so the parent knows what to expect before starting.
 */
@Composable
internal fun StepOutline(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEachIndexed { i, (title, detail) ->
            Row {
                Text("${i + 1}.", Modifier.width(28.dp), fontWeight = FontWeight.SemiBold)
                Column {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text(detail, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** A short bulleted list of requirements ("You'll need"). */
@Composable
internal fun Requirements(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    }
}
