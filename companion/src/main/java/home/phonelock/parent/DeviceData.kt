package home.phonelock.parent

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** One safe number. */
data class SafeEntry(val name: String, val number: String)

/** Whether outgoing / incoming calls are limited to [safe]. */
data class CallSettings(val limitOut: Boolean = false, val limitIn: Boolean = false, val safe: List<SafeEntry> = emptyList()) {
    fun safeJson() = JSONArray().apply { safe.forEach { put(JSONObject().put("name", it.name).put("number", it.number)) } }
    fun toJson(): JSONObject = JSONObject().put("limitOut", limitOut).put("limitIn", limitIn).put("safe", safeJson())

    companion object {
        fun from(o: JSONObject?): CallSettings {
            if (o == null) return CallSettings()
            val a = o.optJSONArray("safe") ?: JSONArray()
            return CallSettings(o.optBoolean("limitOut"), o.optBoolean("limitIn"),
                (0 until a.length()).map { a.getJSONObject(it) }.map { SafeEntry(it.optString("name"), it.optString("number")) })
        }
    }
}

/** One call: [type] in / out / missed / rejected / blocked / voicemail; [durationS] in seconds. */
data class CallEntry(val ts: Long, val number: String, val name: String, val type: String, val durationS: Long)

/** [key] is Android's lookup key, which survives contacts being re-merged. */
data class Contact(val key: String, val name: String, val numbers: List<String>)

data class FileEntry(val name: String, val dir: Boolean, val size: Long, val mtime: Long)

/** The folder a device last listed, or an error from the last file operation. */
data class Folder(val path: String, val entries: List<FileEntry>, val error: String? = null, val loading: Boolean = false)

/**
 * Per-device call log, contacts and file browsing for the parent app (see PROTOCOL.md: calls, calllog,
 * contacts, files, file). The call log is kept on this phone so it survives restarts; the rest is
 * fetched when a screen opens.
 */
object DeviceData {
    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("devicedata", Context.MODE_PRIVATE) }

    private val _calls = MutableStateFlow<Map<String, List<CallEntry>>>(emptyMap())
    val calls: StateFlow<Map<String, List<CallEntry>>> = _calls.asStateFlow()
    private val _contacts = MutableStateFlow<Map<String, List<Contact>>>(emptyMap())
    val contacts: StateFlow<Map<String, List<Contact>>> = _contacts.asStateFlow()
    private val _folders = MutableStateFlow<Map<String, Folder>>(emptyMap())
    val folders: StateFlow<Map<String, Folder>> = _folders.asStateFlow()
    /** Status line for downloads and uploads ("Saved …", "Uploading …"), per device. */
    val transfer = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Most calls kept per device. */
    private const val MAX_CALLS = 500

    fun init(c: Context) {
        app = c.applicationContext
        runCatching {
            val o = JSONObject(prefs.getString("calls", "{}")!!)
            _calls.value = o.keys().asSequence().associateWith { k -> parseCalls(o.getJSONArray(k)) }
        }
    }

    // ---------- requests ----------

    fun loadCallLog(dev: String) = Hub.send(dev, Hub.msg("calllog"))
    fun loadContacts(dev: String) = Hub.send(dev, Hub.msg("contacts").put("op", "list"))
    fun addContact(dev: String, name: String, number: String) =
        Hub.send(dev, Hub.msg("contacts").put("op", "add").put("name", name).put("number", number))
    fun removeContact(dev: String, key: String) = Hub.send(dev, Hub.msg("contacts").put("op", "remove").put("key", key))

    /** File requests this app sent: replies to other parents' requests are ignored (no stray downloads). */
    private val myFileRequests = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    private fun track(o: JSONObject): JSONObject {
        synchronized(myFileRequests) {
            myFileRequests.add(o.getString("id"))
            while (myFileRequests.size > 200) myFileRequests.remove(myFileRequests.first())
        }
        return o
    }

    fun list(dev: String, path: String) {
        _folders.update { m -> m + (dev to (m[dev]?.copy(loading = true, error = null) ?: Folder(path, emptyList(), loading = true))) }
        fileOp(dev, "ls", path)
    }

    fun fileOp(dev: String, op: String, path: String, extra: (JSONObject) -> Unit = {}) =
        Hub.send(dev, track(Hub.msg("files").put("op", op).put("path", path).also(extra)))

    /**
     * Uploads the file at [uri] into folder [path] on [dev], all off the main thread: it is copied to a
     * private cache file (to know its size), then streamed encrypted piece by piece as an ntfy attachment
     * whose sealed message is the `files` put request.
     */
    fun upload(dev: String, path: String, uri: android.net.Uri) {
        val d = Hub.devices.value.firstOrNull { it.id == dev } ?: return
        thread(name = "upload") {
            val cr = app.contentResolver
            val name = runCatching {
                cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull() ?: "file"
            val tmp = java.io.File(app.cacheDir, "upload.tmp")
            try {
                setTransfer(dev, "Preparing $name…")
                val size = cr.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                } ?: throw IOException("Couldn't read $name")
                if (size > MAX_UPLOAD) {
                    setTransfer(dev, "$name is over 48 MB")
                    return@thread
                }
                setTransfer(dev, "Uploading $name…")
                val o = track(Hub.msg("files").put("op", "put").put("path", path).put("name", name))
                val key = Crypto.key(d.secret)
                val h = URL("${d.server}/${d.topic}").openConnection() as HttpURLConnection
                h.requestMethod = "PUT"
                h.doOutput = true
                h.connectTimeout = 20_000
                h.readTimeout = 120_000
                h.setRequestProperty("Filename", "blob.bin")
                h.setRequestProperty("Message", Crypto.seal(key, o.toString()))
                h.setFixedLengthStreamingMode(BlobStream.sealedSize(size))
                tmp.inputStream().buffered().use { input ->
                    h.outputStream.buffered().use { out ->
                        BlobStream.seal(key, "phonelock/file/${o.getString("id")}", input, size, out)
                    }
                }
                val code = h.responseCode
                h.disconnect()
                setTransfer(dev, if (code in 200..299) "Sent $name; waiting for the device…" else "Upload failed (HTTP $code)")
            } catch (e: Throwable) {
                setTransfer(dev, "Upload failed: ${e.message}")
            } finally {
                tmp.delete()
            }
        }
    }

    /** Largest file sent either way (the hub takes 50 MB including encryption overhead). */
    private const val MAX_UPLOAD = 48L * 1024 * 1024

    private fun setTransfer(dev: String, text: String) = transfer.update { it + (dev to text) }

    // ---------- replies ----------

    fun handle(d: Device, m: JSONObject, attachmentUrl: String?) {
        when (m.optString("t")) {
            "calls" -> onCalls(d.id, m)
            "contacts" -> m.optJSONArray("list")?.let { a ->
                _contacts.update { it + (d.id to (0 until a.length()).map { i -> a.getJSONObject(i) }.map { o ->
                    val nums = o.optJSONArray("numbers") ?: JSONArray()
                    Contact(o.optString("key"), o.optString("name"), (0 until nums.length()).map { nums.getString(it) })
                }) }
            }
            // Only replies to this app's own file requests; another parent's browsing and downloads stay theirs.
            "files" -> if (m.optString("req") in myFileRequests) onFiles(d.id, m)
            "file" -> if (m.optString("req") in myFileRequests) onFile(d, m, attachmentUrl)
        }
    }

    private fun onCalls(dev: String, m: JSONObject) {
        val incoming = parseCalls(m.optJSONArray("entries") ?: JSONArray())
        _calls.update { all ->
            val merged = (incoming + (all[dev] ?: emptyList())).distinctBy { it.ts to it.number }
                .sortedByDescending { it.ts }.take(MAX_CALLS)
            all + (dev to merged)
        }
        val o = JSONObject()
        _calls.value.forEach { (k, l) ->
            o.put(k, JSONArray().apply { l.forEach { put(JSONObject().put("ts", it.ts).put("number", it.number)
                .put("name", it.name).put("type", it.type).put("dur", it.durationS)) } })
        }
        prefs.edit().putString("calls", o.toString()).apply()
    }

    private fun parseCalls(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }.map {
        CallEntry(it.optLong("ts"), it.optString("number"), it.optString("name"), it.optString("type"), it.optLong("dur"))
    }

    private fun onFiles(dev: String, m: JSONObject) {
        val error = m.optString("error").takeIf { it.isNotEmpty() }
        val a = m.optJSONArray("entries")
        _folders.update { all ->
            val cur = all[dev]
            val folder = if (a != null) Folder(m.optString("dir"), (0 until a.length()).map { a.getJSONObject(it) }.map {
                FileEntry(it.optString("name"), it.optBoolean("dir"), it.optLong("size"), it.optLong("mtime"))
            }, error) else (cur ?: Folder("", emptyList())).copy(error = error, loading = false)
            all + (dev to folder)
        }
        if (m.optString("op") == "put") setTransfer(dev, error ?: "Uploaded ${m.optString("path").substringAfterLast('/')}")
    }

    /** A downloaded file: stream it from the hub, decrypting piece by piece into Downloads/PhoneLock. */
    private fun onFile(d: Device, m: JSONObject, url: String?) {
        val name = m.optString("name").ifEmpty { "file" }
        if (url == null || !url.startsWith(d.server + "/")) return
        setTransfer(d.id, "Downloading $name…")
        thread(name = "download") {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/PhoneLock")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val cr = app.contentResolver
            val target = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            try {
                if (target == null) throw IOException("Couldn't create the file")
                val h = URL(url).openConnection() as HttpURLConnection
                h.connectTimeout = 20_000
                h.readTimeout = 120_000
                try {
                    h.inputStream.buffered().use { input ->
                        (cr.openOutputStream(target) ?: throw IOException("Couldn't write the file")).use { out ->
                            BlobStream.open(Crypto.key(d.secret), "phonelock/file/${m.getString("id")}", input, out, MAX_UPLOAD)
                        }
                    }
                } finally {
                    h.disconnect()
                }
                cr.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                setTransfer(d.id, "Saved $name to Downloads/PhoneLock")
            } catch (e: Throwable) {
                Log.w(TAG, "download: $e")
                target?.let { runCatching { cr.delete(it, null, null) } }
                setTransfer(d.id, "Couldn't download $name")
            }
        }
    }

}
