package home.phonelock.parent

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.AnnotatedString

/**
 * This app's own update, as [AppUpdate] reports it. Each build flavor has its own [AppUpdate]: the GitHub
 * build checks the public releases and installs with the parent's confirmation; the Play build leaves updates
 * to the Play Store and reports nothing.
 *
 * [installed] and [latest] are version codes ([latest] is 0 until a newer release is known), [size] is the
 * newer APK's size in bytes, [busy] is true while it downloads or installs, and [progress] (0..1) is how far
 * the download is.
 */
data class UpdateState(
    val installed: Int = 0,
    val latest: Int = 0,
    val size: Long = 0,
    val busy: Boolean = false,
    val progress: Float? = null,
) {
    val available: Boolean get() = latest > installed
}

/**
 * Settings' "This app" section: its version, and either "Update to …" or "Check for update". Shown only in
 * builds that update themselves (the GitHub build); the Play Store build has nothing here.
 */
@Composable
internal fun AppUpdateSection() {
    if (!AppUpdate.supported) return
    val st by AppUpdate.state.collectAsState()

    Section("This app") {
        Text("Version 1.0.${st.installed}")
        ProgressTrack(st.busy)
        when {
            st.busy -> Text(st.progress?.let { "Downloading version 1.0.${st.latest}: ${(it * 100).toInt()}%" }
                ?: "Installing version 1.0.${st.latest}…", style = MaterialTheme.typography.bodyMedium)

            st.available -> ListRow("Update to version 1.0.${st.latest}",
                AnnotatedString("${st.size / (1024 * 1024)} MB download. Android asks you to confirm the install."),
                leading = { SymbolBadge("⬇") }, onClick = { AppUpdate.install() })

            else -> ListRow("Check for update", AnnotatedString("From the PhoneLock releases on GitHub"),
                leading = { SymbolBadge("⬆") }, onClick = { AppUpdate.check(asked = true) })
        }
    }
}
