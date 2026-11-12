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

/** One upload or download's status: [busy] while it's going, with [progress] (0 to 1) once that's known. */
data class Transfer(val text: String, val busy: Boolean = false, val progress: Float? = null,
                    val id: Long = System.nanoTime())

/** One safe number. */
data class SafeEntry(val name: String, val number: String) {
    companion object {
        /**
         * A number reduced to what identifies it, so "(705) 555-1234", "705-555-1234" and "+1 705 555 1234"
         * count as the same: digits only, without North America's leading country code 1.
         */
        fun key(number: String): String {
            val digits = number.filter(Char::isDigit)
            return if (digits.length == 11 && digits.startsWith("1")) digits.drop(1) else digits
        }
    }
}

/** Whether outgoing / incoming calls are limited to [safe]. */
data class CallSettings(val limitOut: Boolean = false, val limitIn: Boolean = false, val safe: List<SafeEntry> = emptyList()) {
    /**
     * These settings with [entry] on the safe list, unless the same number (in any format) is already there.
     * Duplicates added before numbers were compared this way are dropped at the same time.
     */
    fun withSafe(entry: SafeEntry): CallSettings = copy(safe = (safe + entry).distinctBy { SafeEntry.key(it.number) })

    /** These settings without [number] (in any format) on the safe list. */
    fun withoutSafe(number: String): CallSettings = copy(safe = safe.filterNot { SafeEntry.key(it.number) == SafeEntry.key(number) })

    /** Whether [number] (in any format) is on the safe list. */
    fun isSafe(number: String) = safe.any { SafeEntry.key(it.number) == SafeEntry.key(number) }

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
data class Contact(val key: String, val name: String, val numbers: List<String>) {
    /** Its numbers with the same number in different formats listed once (the first format seen). */
    val distinctNumbers: List<String> get() = numbers.distinctBy { SafeEntry.key(it) }
}

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
    /** Status of the latest download or upload, per device: shown in Files from the tap until it's done. */
    val transfer = MutableStateFlow<Map<String, Transfer>>(emptyMap())

    /** Most calls kept per device. */
    private const val MAX_CALLS = 500

    fun init(c: Context) {
        app = c.applicationContext
        // Files opened earlier are only needed while a viewer shows them.
        thread(name = "opened-cleanup") { pruneOpened() }
        runCatching {
            val o = JSONObject(prefs.getString("calls", "{}")!!)
            _calls.value = o.keys().asSequence().associateWith { k -> parseCalls(o.getJSONArray(k)) }
        }
    }

    // ---------- requests ----------

    // Each request is tracked by [Pending] under its screen's kind ("calllog", "contacts", "files"), so the
    // screen can show it is waiting for the device.
    fun loadCallLog(dev: String) = Pending.send(dev, "calllog", Hub.msg("calllog"))
    fun loadContacts(dev: String) = Pending.send(dev, "contacts", Hub.msg("contacts").put("op", "list"))
    fun addContact(dev: String, name: String, number: String) =
        Pending.send(dev, "contacts", Hub.msg("contacts").put("op", "add").put("name", name).put("number", number))
    fun removeContact(dev: String, key: String) = Pending.send(dev, "contacts", Hub.msg("contacts").put("op", "remove").put("key", key))

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

    /** Downloads requested with "Open": shown in a viewer from private temporary storage, not saved. */
    private val openRequests = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Asks [dev] for the file at [path] to open it here without saving it to Downloads. */
    fun openFile(dev: String, path: String) = requestFile(dev, path, open = true)

    /** Asks [dev] for the file at [path] to save it to Downloads/PhoneLock. */
    fun saveFile(dev: String, path: String) = requestFile(dev, path, open = false)

    /** Several files being saved from one device, one after another: [paths], the one at [index] in flight. */
    private data class Batch(val paths: List<String>, val index: Int = 0, val saved: Int = 0) {
        /** "(2 of 5) " in front of each status while there's more than one. */
        val prefix get() = if (paths.size > 1) "(${index + 1} of ${paths.size}) " else ""
    }

    /** Device -> its batch of saves; changed on the main thread, read from download threads. */
    private val batches = HashMap<String, Batch>()

    /**
     * Saves every file in [paths] to Downloads/PhoneLock, asking [dev] for one at a time so the child's
     * phone and the hub aren't sent many large files at once. A failed file is skipped, and the last
     * status says how many were saved. A new batch replaces one still running.
     */
    fun saveFiles(dev: String, paths: List<String>) {
        if (paths.isEmpty()) return
        main.post {
            synchronized(batches) { batches[dev] = Batch(paths) }
            requestFile(dev, paths[0], open = false)
        }
    }

    /** The current file of [dev]'s batch finished ([ok] or not): asks for the next, or sums up. */
    private fun batchStep(dev: String, ok: Boolean) = main.post {
        val b = synchronized(batches) { batches[dev] } ?: return@post
        val done = b.copy(saved = b.saved + if (ok) 1 else 0)
        if (done.index + 1 < done.paths.size) {
            val next = done.copy(index = done.index + 1)
            synchronized(batches) { batches[dev] = next }
            requestFile(dev, next.paths[next.index], open = false)
        } else {
            synchronized(batches) { batches.remove(dev) }
            if (done.paths.size > 1) {
                val failed = done.paths.size - done.saved
                setTransfer(dev, "Saved ${done.saved} of ${done.paths.size} files to Downloads/PhoneLock" +
                    if (failed > 0) "; $failed couldn't be downloaded" else "")
            }
        }
    }

    /** "(2 of 5) " while [dev] is saving several files, else "". */
    private fun batchPrefix(dev: String) = synchronized(batches) { batches[dev]?.prefix ?: "" }

    /**
     * Sends the `get` for [path] and shows that it's waiting for the device, which then sends the file (see
     * [onFile]). If nothing has arrived after a minute, the status says the device didn't answer.
     */
    private fun requestFile(dev: String, path: String, open: Boolean) {
        val name = path.substringAfterLast('/')
        val m = track(Hub.msg("files").put("op", "get").put("path", path))
        if (open) openRequests.add(m.getString("id"))
        val prefix = if (open) "" else batchPrefix(dev)
        val asking = setTransfer(dev, "${prefix}Asking ${Hub.deviceName(dev)}'s device for $name…", busy = true)
        // Not a Pending "files" wait: this status line is the download's own indicator.
        Hub.send(dev, m)
        main.postDelayed({
            if (transfer.value[dev]?.id == asking.id) {
                setTransfer(dev, "${prefix}No answer from ${Hub.deviceName(dev)}'s device for $name. It may be off or offline.")
                if (!open) batchStep(dev, ok = false)
            }
        }, 60_000)
    }

    fun fileOp(dev: String, op: String, path: String, extra: (JSONObject) -> Unit = {}) =
        Pending.send(dev, "files", track(Hub.msg("files").put("op", op).put("path", path).also(extra)))

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
                setTransfer(dev, "Preparing $name…", busy = true)
                val size = cr.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                } ?: throw IOException("Couldn't read $name")
                if (size > MAX_UPLOAD) {
                    setTransfer(dev, "$name is over 48 MB")
                    return@thread
                }
                setTransfer(dev, "Uploading $name…", busy = true, progress = 0f)
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
                    progressStream(h.outputStream, BlobStream.sealedSize(size)) { f ->
                        setTransfer(dev, "Uploading $name…", busy = true, progress = f)
                    }.buffered().use { out ->
                        BlobStream.seal(key, "phonelock/file/${o.getString("id")}", input, size, out)
                    }
                }
                val code = h.responseCode
                h.disconnect()
                if (code in 200..299) Pending.start(dev, "files", o.getString("id"))
                if (code in 200..299) setTransfer(dev, "Sent $name; waiting for the device to save it…", busy = true)
                else setTransfer(dev, "Upload failed (HTTP $code)")
            } catch (e: Throwable) {
                setTransfer(dev, "Upload failed: ${e.message}")
            } finally {
                tmp.delete()
            }
        }
    }

    /** Largest file sent either way (the hub takes 50 MB including encryption overhead). */
    private const val MAX_UPLOAD = 48L * 1024 * 1024

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Shows [text] as [dev]'s transfer status; [busy] adds a spinner, or a bar when [progress] (0 to 1) is known. */
    private fun setTransfer(dev: String, text: String, busy: Boolean = false, progress: Float? = null): Transfer {
        val t = Transfer(text, busy, progress)
        if (busy) {
            transfer.update { it + (dev to t) }
        } else {
            // Finished (saved, failed, no answer): said once in the snackbar, and the progress card goes.
            transfer.update { it - dev }
            main.post { Undo.notify(text) }
        }
        return t
    }

    /**
     * [out], counting what's written through it and reporting the share of [total] bytes done to [onProgress]
     * at most every 2 %, so progress updates don't flood the screen.
     */
    private fun progressStream(out: java.io.OutputStream, total: Long, onProgress: (Float) -> Unit) =
        object : java.io.FilterOutputStream(out) {
            private var done = 0L
            private var shown = -1f

            override fun write(b: ByteArray, off: Int, len: Int) {
                out.write(b, off, len)
                count(len)
            }

            override fun write(b: Int) {
                out.write(b)
                count(1)
            }

            private fun count(n: Int) {
                done += n
                val f = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                if (f - shown >= 0.02f || f >= 1f && shown < 1f) {
                    shown = f
                    onProgress(f)
                }
            }
        }

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
        saveCalls()
    }

    /** Persists the call logs (kept across restarts). */
    private fun saveCalls() {
        val o = JSONObject()
        _calls.value.forEach { (k, l) ->
            o.put(k, JSONArray().apply { l.forEach { put(JSONObject().put("ts", it.ts).put("number", it.number)
                .put("name", it.name).put("type", it.type).put("dur", it.durationS)) } })
        }
        prefs.edit().putString("calls", o.toString()).apply()
    }

    /** Deletes everything kept for [dev], including its saved call log (the device was removed). */
    fun forget(dev: String) {
        _calls.update { it - dev }
        _contacts.update { it - dev }
        _folders.update { it - dev }
        transfer.update { it - dev }
        saveCalls()
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
        // A file the device couldn't send (missing, too large): end the "Asking…" status with why.
        if (m.optString("op") == "get" && error != null) {
            val wasOpen = openRequests.remove(m.optString("req"))
            setTransfer(dev, error)
            if (!wasOpen) batchStep(dev, ok = false)
        }
    }

    /**
     * A downloaded file: stream it from the hub, decrypting piece by piece, into Downloads/PhoneLock, or for
     * "Open" into private temporary storage and then a viewer.
     */
    private fun onFile(d: Device, m: JSONObject, url: String?) {
        val name = m.optString("name").ifEmpty { "file" }
        if (url == null || !url.startsWith(d.server + "/")) return
        if (openRequests.remove(m.optString("req"))) {
            openFromHub(d, m, url, name)
            return
        }
        val size = m.optLong("size")
        val prefix = batchPrefix(d.id)
        setTransfer(d.id, "${prefix}Downloading $name…", busy = true, progress = 0f)
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
                        progressStream(cr.openOutputStream(target) ?: throw IOException("Couldn't write the file"), size) { f ->
                            setTransfer(d.id, "${prefix}Downloading $name…", busy = true, progress = f)
                        }.use { out ->
                            BlobStream.open(Crypto.key(d.secret), "phonelock/file/${m.getString("id")}", input, out, MAX_UPLOAD)
                        }
                    }
                } finally {
                    h.disconnect()
                }
                cr.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                setTransfer(d.id, "${prefix}Saved $name to Downloads/PhoneLock")
                batchStep(d.id, ok = true)
            } catch (e: Throwable) {
                Log.w(TAG, "download: $e")
                target?.let { runCatching { cr.delete(it, null, null) } }
                setTransfer(d.id, "${prefix}Couldn't download $name")
                batchStep(d.id, ok = false)
            }
        }
    }

    // ---------- Open (temporary) ----------

    /** How long an opened file is kept before it's deleted (the viewer has long since read it). */
    private const val OPENED_KEEP_MS = 3600_000L

    private val openedDir get() = java.io.File(app.cacheDir, "opened")

    /** Deletes opened files older than [OPENED_KEEP_MS]. */
    private fun pruneOpened() {
        val cutoff = System.currentTimeMillis() - OPENED_KEEP_MS
        openedDir.listFiles()?.forEach { f -> if (f.lastModified() < cutoff) f.delete() }
    }

    /**
     * Downloads [name] into private temporary storage and opens it in the viewer app for its type, which
     * gets permission to read just that file. Nothing goes to Downloads; the copy is deleted within the hour.
     */
    private fun openFromHub(d: Device, m: JSONObject, url: String, name: String) {
        val size = m.optLong("size")
        setTransfer(d.id, "Downloading $name…", busy = true, progress = 0f)
        thread(name = "open-file") {
            pruneOpened()
            // A folder per download keeps the real file name (the viewer shows it) without clashes.
            val dir = java.io.File(openedDir, m.getString("id").filter(Char::isLetterOrDigit)).apply { mkdirs() }
            val file = java.io.File(dir, name.replace('/', '_'))
            try {
                val h = URL(url).openConnection() as HttpURLConnection
                h.connectTimeout = 20_000
                h.readTimeout = 120_000
                try {
                    h.inputStream.buffered().use { input ->
                        progressStream(file.outputStream(), size) { f ->
                            setTransfer(d.id, "Downloading $name…", busy = true, progress = f)
                        }.use { out ->
                            BlobStream.open(Crypto.key(d.secret), "phonelock/file/${m.getString("id")}", input, out, MAX_UPLOAD)
                        }
                    }
                } finally {
                    h.disconnect()
                }
                setTransfer(d.id, "Opening $name…", busy = true)
                showInViewer(d.id, file, name)
            } catch (e: Throwable) {
                Log.w(TAG, "open: $e")
                file.delete()
                setTransfer(d.id, "Couldn't open $name")
            }
        }
    }

    /** Hands [file] to a viewer app on the main thread; says so when no app can open its type. */
    private fun showInViewer(dev: String, file: java.io.File, name: String) {
        val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.opened", file)
        val ext = name.substringAfterLast('.', "").lowercase()
        val type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        val view = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, type)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                app.startActivity(view)
                setTransfer(dev, "Opened $name (not saved)")
            } catch (e: android.content.ActivityNotFoundException) {
                file.delete()
                setTransfer(dev, "No app on this phone can open $name. Use Save to Downloads instead.")
            }
        }
    }
}
