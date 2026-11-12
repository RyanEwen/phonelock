package home.phonelock.parent

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/**
 * Requests this app is waiting on a device to answer, so screens can show that something is happening
 * instead of sitting still until the answer lands.
 *
 * A request is tracked under a [kind] (for example "files" or "contacts"; screens pick their own names) and
 * the id of the message sent. It ends when the device sends anything whose `req` is that id (see [answered],
 * called for every incoming message), and otherwise turns [Wait.slow] after [SLOW_MS] and gives up after
 * [GIVE_UP_MS], when [noAnswer] remembers it until that kind is asked again.
 */
object Pending {
    /** One request in flight: the message [id], when it was sent, and whether it is taking unusually long. */
    data class Wait(val id: String, val since: Long, val slow: Boolean = false)

    private const val SLOW_MS = 10_000L
    private const val GIVE_UP_MS = 60_000L

    private val _waits = MutableStateFlow<Map<String, Map<String, Wait>>>(emptyMap())
    /** Device -> kind -> the newest request of that kind still unanswered. */
    val waits: StateFlow<Map<String, Map<String, Wait>>> = _waits.asStateFlow()

    private val _noAnswer = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    /** Device -> kinds whose last request got no answer in time (likely offline or asleep). */
    val noAnswer: StateFlow<Map<String, Set<String>>> = _noAnswer.asStateFlow()

    private val main = Handler(Looper.getMainLooper())

    /** Sends [m] to [dev] and tracks it as the current [kind] request. */
    fun send(dev: String, kind: String, m: JSONObject) {
        start(dev, kind, m.getString("id"))
        Hub.send(dev, m)
    }

    /** Tracks message [id] (already sent, or about to be) as the current [kind] request for [dev]. */
    fun start(dev: String, kind: String, id: String) {
        _noAnswer.update { it + (dev to (it[dev].orEmpty() - kind)) }
        _waits.update { it + (dev to (it[dev].orEmpty() + (kind to Wait(id, System.currentTimeMillis())))) }

        // Timers check the id, so a newer request of the same kind isn't affected by an older one's.
        main.postDelayed({ change(dev, kind, id) { it.copy(slow = true) } }, SLOW_MS)
        main.postDelayed({
            if (_waits.value[dev]?.get(kind)?.id == id) {
                change(dev, kind, id) { null }
                _noAnswer.update { it + (dev to (it[dev].orEmpty() + kind)) }
                // Said in the snackbar, over the page, rather than as a line that pushes content down.
                Undo.notify("No answer from ${Hub.deviceName(dev)}'s device. It may be off or offline; try again later.")
            }
        }, GIVE_UP_MS)
    }

    /** A message from [dev] answering request [req]: whatever kind it was, it's no longer pending. */
    fun answered(dev: String, req: String) {
        if (req.isEmpty()) return
        _waits.update { all ->
            val mine = all[dev] ?: return@update all
            val left = mine.filterValues { it.id != req }
            if (left.size == mine.size) all else all + (dev to left)
        }
    }

    /** Ends [dev]'s [kind] wait however it was answered (for replies without a request id, such as ringing). */
    fun clear(dev: String, kind: String) {
        _waits.update { all -> all[dev]?.let { all + (dev to (it - kind)) } ?: all }
    }

    /** Drops everything about [dev] (the device was removed). */
    fun forget(dev: String) {
        _waits.update { it - dev }
        _noAnswer.update { it - dev }
    }

    /** Replaces (or with null removes) [dev]'s [kind] wait, if it is still request [id]. */
    private fun change(dev: String, kind: String, id: String, f: (Wait) -> Wait?) {
        _waits.update { all ->
            val cur = all[dev]?.get(kind)
            if (cur?.id != id) return@update all
            val next = f(cur)
            val mine = if (next == null) all[dev].orEmpty() - kind else all[dev].orEmpty() + (kind to next)
            all + (dev to mine)
        }
    }
}

/**
 * A thin progress line whose space is always kept, so it appearing or going never moves anything. Runs
 * while [active]. Pages put one under their title bar; dialogs at the top of their content.
 */
@Composable
internal fun ProgressTrack(active: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp)) {
        if (active) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
    }
}

/** Whether a [kind] request to [dev] is still unanswered, so "nothing here" can wait until the answer is in. */
@Composable
internal fun isWaiting(dev: String, kind: String): Boolean {
    val waits by Pending.waits.collectAsState()
    return waits[dev]?.get(kind) != null
}

/** The app's "nothing here" line, one style for every empty list. */
@Composable
internal fun EmptyNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
