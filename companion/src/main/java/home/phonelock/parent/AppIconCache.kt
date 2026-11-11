package home.phonelock.parent

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Icons of the apps on each paired phone. This app can't read another phone's apps, so it asks the phone
 * (`icons`) for the ones it hasn't cached, and keeps them on disk under files/icons/<device>/<package>.
 * Icons aren't secret; the cache only saves asking again.
 */
object AppIconCache {
    private lateinit var root: File

    /** Device id -> package -> icon. */
    private val _icons = MutableStateFlow<Map<String, Map<String, ImageBitmap>>>(emptyMap())
    val icons: StateFlow<Map<String, Map<String, ImageBitmap>>> = _icons.asStateFlow()

    /** Packages requested in this process, so a burst of state messages asks once. */
    private val requested = HashSet<String>()

    fun init(c: Context) {
        root = File(c.filesDir, "icons")
        thread(name = "icons-load") { loadAll() }
    }

    /** Asks [d] for icons of any of [pkgs] that aren't cached or already requested. */
    fun ensure(d: Device, pkgs: Collection<String>) {
        val have = _icons.value[d.id].orEmpty().keys
        val missing = synchronized(requested) {
            pkgs.filter { it !in have && requested.add("${d.id}/$it") }
        }
        if (missing.isEmpty()) return
        // Small batches: the phone answers at most 60 per request, and the request itself must stay under
        // ntfy's 4 KB message limit.
        missing.chunked(30).forEach { Hub.send(d.id, Hub.msg("icons").put("apps", JSONArray(it))) }
        // If a reply never comes (phone offline), allow asking again later.
        main.postDelayed({ synchronized(requested) { missing.forEach { requested.remove("${d.id}/$it") } } },
            RETRY_MS)
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private const val RETRY_MS = 5 * 60_000L

    /** An `icons` reply: decrypt the attached JSON {pkg: base64 image}, store and publish it. */
    fun handle(d: Device, m: JSONObject, attachmentUrl: String?) {
        if (attachmentUrl == null) return
        thread(name = "icons") {
            try {
                val json = Attachments.fetch(d, attachmentUrl, m.getString("id")) ?: return@thread
                val o = JSONObject(String(json))
                val dir = deviceDir(d.id).apply { mkdirs() }
                val loaded = HashMap<String, ImageBitmap>()
                for (pkg in o.keys()) {
                    val bytes = Base64.decode(o.getString(pkg), Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                    File(dir, safeName(pkg)).writeBytes(bytes)
                    loaded[pkg] = bmp.asImageBitmap()
                }
                _icons.update { it + (d.id to (it[d.id].orEmpty() + loaded)) }
            } catch (e: Exception) {
                Log.w(TAG, "icons: $e")
            }
        }
    }

    private fun loadAll() {
        val all = HashMap<String, Map<String, ImageBitmap>>()
        for (dev in Hub.devices.value) {
            val dir = deviceDir(dev.id)
            val files = dir.listFiles() ?: continue
            all[dev.id] = files.mapNotNull { f ->
                BitmapFactory.decodeFile(f.path)?.let { f.name to it.asImageBitmap() }
            }.toMap()
        }
        _icons.update { cur -> all + cur }
    }

    /** Deletes [dev]'s cached icons (the device was removed). */
    fun forget(dev: String) {
        _icons.update { it - dev }
        synchronized(requested) { requested.removeAll { it.startsWith("$dev/") } }
        thread(name = "icons-forget") { deviceDir(dev).deleteRecursively() }
    }

    /** One folder per device, named by a hash so the topic (part of the pairing) isn't a file name. */
    private fun deviceDir(dev: String): File {
        val h = MessageDigest.getInstance("SHA-256").digest(dev.toByteArray())
        return File(root, h.take(8).joinToString("") { "%02x".format(it) })
    }

    /** Package names are already file-safe ([a-zA-Z0-9_.]); this guards against anything else. */
    private fun safeName(pkg: String) = pkg.replace(Regex("[^A-Za-z0-9_.]"), "_")
}
