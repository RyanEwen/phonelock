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
 * "Done. Undo" messages for changes that are easy to make by mistake (ticking a box, hiding an app).
 * Anything can call [offer]; [UndoHost] shows them at the bottom of the screen, one at a time.
 */
object Undo {
    private class Item(val text: String, val undo: () -> Unit)

    private val items = Channel<Item>(Channel.CONFLATED)

    /** Shows [text] with an Undo button that runs [undo]. A newer message replaces a pending one. */
    fun offer(text: String, undo: () -> Unit) {
        items.trySend(Item(text, undo))
    }

    /** The snackbar area; place once at the root of the screen. */
    @Composable
    fun BoxScope.UndoHost() {
        val host = remember { SnackbarHostState() }
        LaunchedEffect(Unit) {
            items.receiveAsFlow().collect { item ->
                host.currentSnackbarData?.dismiss()
                val result = host.showSnackbar(item.text, actionLabel = "Undo", duration = SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) item.undo()
            }
        }
        SnackbarHost(host, Modifier.align(Alignment.BottomCenter).safeDrawingPadding())
    }
}
