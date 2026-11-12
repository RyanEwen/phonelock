package home.phonelock.parent

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/**
 * Updates this app (GitHub build) from the public PhoneLock releases, the same place the lock app updates
 * from. A small version file (parent-version.json: {versionCode, sha256, size}) is checked when the app
 * comes to the front, at most every few hours; a newer version is announced once in the snackbar and offered
 * in Settings. Nothing is downloaded until the parent taps Update (the APK is large), and Android always asks
 * them to confirm the install, since this app isn't a device owner.
 *
 * The download must match the advertised checksum, be this app, be the advertised newer version, and be
 * signed with the same key as the installed app; otherwise it is deleted and never installed.
 */
object AppUpdate {
    const val supported = true

    private const val TAG = "AppUpdate"
    private const val VERSION_FILE = "parent-version.json"
    private const val APK_FILE = "PhoneLock-parent.apk"
    private const val CHECK_EVERY_MS = 6 * 3600_000L
    /** Far above the real size (about 55 MB), only to stop a runaway download. */
    private const val MAX_APK = 200L * 1024 * 1024

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs by lazy { app.getSharedPreferences("appupdate", Context.MODE_PRIVATE) }

    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Called once at app start; shows a newer version found by an earlier check straight away. */
    fun init(c: Context) {
        app = c.applicationContext
        _state.value = UpdateState(installed = installedVersion(), latest = prefs.getInt("latest", 0),
            size = prefs.getLong("size", 0))
    }

    /** Checks for a newer release if the last check was long enough ago (the app came to the front). */
    fun checkIfDue() {
        if (System.currentTimeMillis() - prefs.getLong("checked", 0) >= CHECK_EVERY_MS) check(asked = false)
    }

    /**
     * Fetches the newest release's version file. [asked] is true when the parent tapped "Check for update",
     * so the result is always said; a background check speaks only to announce a version once.
     */
    fun check(asked: Boolean) {
        scope.launch {
            try {
                val v = fetchVersion()
                prefs.edit().putLong("checked", System.currentTimeMillis()).apply()
                if (v == null) {
                    // Releases from before this updater have no version file for this app.
                    if (asked) Undo.notify("No update for this app has been published yet")
                    return@launch
                }
                remember(v)
                val st = _state.value
                when {
                    !st.available -> if (asked) Undo.notify("This app is up to date (version 1.0.${st.installed})")
                    asked -> offer(st.latest)
                    prefs.getInt("announced", 0) != st.latest -> {
                        prefs.edit().putInt("announced", st.latest).apply()
                        offer(st.latest)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "check: $e")
                if (asked) Undo.notify("Couldn't check for an update: ${e.message ?: "no connection"}")
            }
        }
    }

    /** Downloads the newer version, verifies it, and hands it to Android to install (which asks the parent). */
    fun install() {
        if (_state.value.busy || !_state.value.available) return
        _state.update { it.copy(busy = true, progress = 0f) }
        scope.launch {
            try {
                val apk = downloadVerified()
                _state.update { it.copy(progress = null) }
                commit(apk)
                // Android now shows its confirmation; the result comes to [Result].
            } catch (e: Exception) {
                Log.w(TAG, "install: $e")
                failed(e.message ?: "download failed")
            }
        }
    }

    // ---------- steps ----------

    /** The newest release's version file, or null when that release has none (HTTP 404). */
    private fun fetchVersion(): JSONObject? {
        val f = File(app.cacheDir, VERSION_FILE)
        return try {
            Releases.download(Releases.LATEST + VERSION_FILE, f, maxBytes = 64 * 1024)
            JSONObject(f.readText())
        } catch (e: FileNotFoundException) {
            null
        } finally {
            f.delete()
        }
    }

    /** Stores what [v] advertises, so Settings shows it now and after a restart. */
    private fun remember(v: JSONObject) {
        val latest = v.getInt("versionCode")
        prefs.edit().putInt("latest", latest).putString("sha256", v.getString("sha256"))
            .putLong("size", v.optLong("size")).apply()
        _state.update { it.copy(installed = installedVersion(), latest = latest, size = v.optLong("size")) }
    }

    /** Announces version [latest] with an Update button. */
    private fun offer(latest: Int) = Undo.action("Version 1.0.$latest of this app is available", "Update") { install() }

    /** Downloads the advertised APK and returns it only if every check passes; otherwise deletes it and throws. */
    private fun downloadVerified(): File {
        val latest = _state.value.latest
        val apk = File(app.cacheDir, "parent-update.apk")
        val sha = Releases.download(Releases.LATEST + APK_FILE, apk, MAX_APK) { p ->
            _state.update { it.copy(progress = p) }
        }

        val info = Releases.inspect(app.packageManager, apk)
        val ownSigner = Releases.installedSigner(app.packageManager, app.packageName)
        val problem = when {
            !sha.equals(prefs.getString("sha256", ""), ignoreCase = true) ->
                "the download doesn't match the release (it may have changed since the check)"
            info == null -> "the download isn't an app"
            info.packageName != app.packageName -> "the download is a different app"
            info.versionCode.toInt() != latest || latest <= installedVersion() -> "the download is a different version"
            ownSigner == null || info.signer != ownSigner -> "the download isn't signed by PhoneLock"
            else -> null
        }
        if (problem != null) {
            apk.delete()
            // Forget the check, so the next one fetches the version file again.
            prefs.edit().remove("checked").apply()
            throw IllegalStateException(problem)
        }
        return apk
    }

    /** Writes [apk] into an install session and commits it; Android replies through [Result]. */
    private fun commit(apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            // Lets Android skip its prompt once this app is the one that installed itself last time; the first
            // update (over a build installed some other way) is always confirmed.
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val reply = Intent(app, Result::class.java)
            // Mutable: the installer adds its status (and the confirmation screen) to this intent.
            val pending = PendingIntent.getBroadcast(app, id, reply,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
        apk.delete()
    }

    /** Ends a failed or cancelled update and says why. */
    private fun failed(why: String) {
        _state.update { it.copy(busy = false, progress = null) }
        Undo.notify("Couldn't update this app: $why")
    }

    private fun installedVersion(): Int =
        app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode.toInt()

    /**
     * Android's answers about an install session: it needs the parent's confirmation (shown now, since they
     * just tapped Update in the app), it failed, or the parent cancelled. A successful update replaces this
     * app, so there's nothing to do for that (the app restarts; BootReceiver restarts the service).
     */
    class Result : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return failed("no confirmation screen")
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> {}
                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    _state.update { it.copy(busy = false, progress = null) }
                    Undo.notify("Update cancelled")
                }
                else -> failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "install failed")
            }
        }
    }
}
