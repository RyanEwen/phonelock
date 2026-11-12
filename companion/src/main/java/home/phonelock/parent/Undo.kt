package home.phonelock.parent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * "Done. Undo" messages for changes that are easy to make by mistake (ticking a box, hiding an app), and
 * plain notices that a request was sent ([notify]) or offers with a button of their own ([action]).
 * [UndoHost] shows them at the bottom, one at a time.
 */
object Undo {
    /** One message: [text], and optionally a button [label] that runs [run]; [long] keeps it up longer. */
    private class Item(val text: String, val label: String?, val run: (() -> Unit)?, val long: Boolean = false)

    private val items = Channel<Item>(Channel.CONFLATED)

    /** Shows [text] with an Undo button that runs [undo]. A newer message replaces a pending one. */
    fun offer(text: String, undo: () -> Unit) {
        items.trySend(Item(text, "Undo", undo))
    }

    /** Shows [text] with no action: confirms that something was sent when its result comes later. */
    fun notify(text: String) {
        items.trySend(Item(text, null, null))
    }

    /**
     * Shows [text] with a button [label] that runs [run], kept up longer than an Undo since it offers
     * something new rather than reversing a tap (for example "Version 1.0.15 is available · Update").
     */
    fun action(text: String, label: String, run: () -> Unit) {
        items.trySend(Item(text, label, run, long = true))
    }

    /** The snackbar area; place once at the root of the screen. */
    @Composable
    fun BoxScope.UndoHost() {
        val host = remember { SnackbarHostState() }
        LaunchedEffect(Unit) {
            items.receiveAsFlow().collect { item ->
                host.currentSnackbarData?.dismiss()
                val result = host.showSnackbar(item.text, actionLabel = item.label,
                    duration = if (item.long) SnackbarDuration.Long else SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) item.run?.invoke()
            }
        }
        SnackbarHost(host, Modifier.align(Alignment.BottomCenter).safeDrawingPadding())
    }
}
