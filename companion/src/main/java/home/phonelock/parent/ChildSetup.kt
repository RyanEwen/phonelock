package home.phonelock.parent

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.os.Build
import home.phonelock.parent.adb.AdbConnection
import home.phonelock.parent.adb.AdbDiscovery
import home.phonelock.parent.adb.AdbEndpoint
import home.phonelock.parent.adb.AdbIdentity
import home.phonelock.parent.adb.AdbPairing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64

enum class StepState { WAITING, RUNNING, DONE, FAILED, SKIPPED }

/** One line of the setup progress list; [detail] explains a failure or what was skipped. */
data class SetupStep(val label: String, val state: StepState = StepState.WAITING, val detail: String? = null)

/**
 * A setup in progress or finished. [deviceId] is the device added to this app (null when none was, e.g.
 * the device already belonged to another parent). [outcome] is the closing message.
 */
data class SetupRun(
    val steps: List<SetupStep>,
    val finished: Boolean = false,
    val failed: Boolean = false,
    val deviceId: String? = null,
    val outcome: String? = null,
    /** Whether the last step really switched debugging off (for the closing message). */
    val debuggingOff: Boolean = false,
)

/**
 * Sets up a child's device from this phone over Android's Wireless debugging, doing what
 * `tools/configure-lock.sh` and the README's adb steps do from a computer: install the lock app, make it
 * device owner, pair it with this app, turn on the features that need a one-time grant, then switch
 * debugging off. Works without a factory reset as long as no accounts are signed in on the device.
 *
 * A device that already runs PhoneLock as device owner is "repaired" instead: only the one-time grants are
 * re-applied (e.g. after an update lost one), and its pairing is left alone.
 */
object ChildSetup {
    private const val LOCK_PACKAGE = "home.phonelock"
    private const val ADMIN = "home.phonelock/.AdminReceiver"
    private const val CONFIG = "-n home.phonelock/.ConfigReceiver -a home.phonelock.CONFIG"
    /** The lock app needs Android 13 (its minSdk); Wireless debugging itself arrived in 11. */
    private const val MIN_SDK = 33
    /** How long to look for the device's debugging port on the network after pairing. */
    private const val FIND_CONNECT_PORT_MS = 20_000L
    /** How long to wait for a newly paired device's first message through the hub. */
    private const val FIRST_REPLY_MS = 60_000L

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _run = MutableStateFlow<SetupRun?>(null)
    val run: StateFlow<SetupRun?> = _run.asStateFlow()

    /**
     * What the parent entered. [existing] is set when fixing a device already in this app. [connectPort] is
     * the device's debugging port if typed in (otherwise found on the network). [turnOffDebugging] ends
     * setup by locking adb away; it only happens once the device is confirmed reachable through the hub.
     */
    data class Request(
        val host: String,
        val pairPort: Int,
        val code: String,
        val childName: String,
        val parentName: String,
        val pin: String,
        val existing: Device? = null,
        val connectPort: Int? = null,
        val turnOffDebugging: Boolean = true,
    )

    /** The features that need a one-time grant over adb, as (what it's for, shell command). */
    private val GRANTS = listOf(
        // Added to whatever accessibility services are on (e.g. TalkBack), not replacing them.
        "Screen view" to "s=\$(settings get secure enabled_accessibility_services); " +
            "case \"\$s\" in *$LOCK_PACKAGE/*) ;; ''|null) settings put secure enabled_accessibility_services $LOCK_PACKAGE/$LOCK_PACKAGE.ScreenService ;; " +
            "*) settings put secure enabled_accessibility_services \"\$s:$LOCK_PACKAGE/$LOCK_PACKAGE.ScreenService\" ;; esac" +
            " && settings put secure accessibility_enabled 1",
        "Keeping screen view on" to "pm grant $LOCK_PACKAGE android.permission.WRITE_SECURE_SETTINGS",
        "Ringing through Do Not Disturb" to "cmd notification allow_dnd $LOCK_PACKAGE",
        "Screening incoming calls" to "cmd role add-role-holder android.app.role.CALL_SCREENING $LOCK_PACKAGE",
        "Limiting outgoing calls" to "cmd role add-role-holder android.app.role.CALL_REDIRECTION $LOCK_PACKAGE",
        "Call log for parents" to "pm grant $LOCK_PACKAGE android.permission.READ_CALL_LOG",
        "App use for parents" to "appops set $LOCK_PACKAGE GET_USAGE_STATS allow",
        "Files for parents" to "appops set $LOCK_PACKAGE MANAGE_EXTERNAL_STORAGE allow",
        "Working while idle (battery saver exemption)" to "dumpsys deviceidle whitelist +$LOCK_PACKAGE",
    )

    /** This phone's adb key and certificate, kept so a later "Fix setup" on the same device may skip pairing. */
    private val identity by lazy { AdbIdentity.load(app) }

    fun init(c: Context) {
        app = c.applicationContext
    }

    /** Clears a finished run so the screen starts fresh. */
    fun reset() {
        if (_run.value?.finished != false) _run.value = null
    }

    /** Starts a setup; progress appears in [run]. Ignored while one is already running. */
    fun start(req: Request) {
        if (_run.value?.let { !it.finished } == true) return
        _run.value = SetupRun(STEPS.map { SetupStep(it) })
        scope.launch {
            try {
                perform(req)
            } catch (e: Exception) {
                Log.w(TAG, "setup: $e")
                failRunning(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // ---------- the steps ----------

    private val STEPS = listOf(
        "Pair with the device",
        "Connect",
        "Check the device",
        "Download the lock app",
        "Install the lock app",
        "Make it the device owner",
        "Connect it to this app",
        "Turn on features",
        "Turn off debugging",
    )

    private suspend fun perform(req: Request) {
        // 1. Pair (Android shows the code and a pairing port while "Pair device with pairing code" is open).
        step(0) {
            try {
                AdbPairing.pair(identity, req.host, req.pairPort, req.code.trim(), "phonelock@" + Build.MODEL.replace(' ', '-'))
            } catch (e: AdbPairing.PairingRefused) {
                throw SetupError(e.message ?: "Pairing refused")
            }
            null
        }

        // 2. Connect on the device's debugging port, which differs from the pairing port.
        lateinit var adb: AdbConnection
        step(1) {
            val port = req.connectPort ?: findConnectPort(req.host)
                ?: throw SetupError("Couldn't find the device's debugging port. Keep Wireless debugging open on it and try again.")
            adb = AdbConnection.connect(identity, req.host, port)
            val ok = runCatching { adb.shell("echo ok").output.trim() == "ok" }.getOrDefault(false)
            if (!ok) {
                adb.close()
                throw SetupError("The device didn't answer")
            }
            null
        }

        adb.use { device ->
            // 3. What state is it in? A fresh device gets everything. One PhoneLock already manages is either
            // this app's (an earlier run stopped part way, or Fix setup: carry on), another parent's (only its
            // features are checked), or not paired yet (pairing continues).
            var owned = false
            var mine: Device? = null
            var othersDevice = false
            step(2) {
                val sdk = device.shell("getprop ro.build.version.sdk").output.trim().toIntOrNull() ?: 0
                if (sdk < MIN_SDK) throw SetupError("This device runs an Android version older than 13")
                when (deviceOwner(device)) {
                    LOCK_PACKAGE -> {
                        owned = true
                        // Fail closed: a missing reply must not be read as "not paired" and overwrite a pairing.
                        val st = status(device)
                        if (!st.contains("owner=true") || !st.contains("paired=")) {
                            throw SetupError("PhoneLock on the device didn't answer. Try again.")
                        }
                        val hash = Regex("topic=(\\w+)").find(st)?.groupValues?.get(1)
                        mine = Hub.devices.value.firstOrNull { topicHash(it.topic) == hash }
                        val paired = st.contains("paired=true")
                        if (req.existing != null && mine?.id != req.existing.id) {
                            throw SetupError("This isn't ${req.existing.name}'s device. Open Wireless debugging on the right one.")
                        }
                        othersDevice = paired && mine == null
                        when {
                            req.existing != null -> "Checking its features"
                            mine != null -> "Already connected to this app: carrying on"
                            othersDevice -> "Set up by another parent: checking its features only"
                            else -> "PhoneLock is installed but wasn't connected: continuing from there"
                        }
                    }
                    null -> {
                        if (req.existing != null) throw SetupError("PhoneLock isn't set up on this device. " +
                            "Use Add a child's device instead.")
                        val accounts = Regex("Accounts: (\\d+)").find(device.shell("dumpsys account").output)
                            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        if (accounts > 0) throw SetupError("Remove the accounts on the device first (Settings > " +
                            "Passwords & accounts); add them back afterwards in parent mode")
                        val users = device.shell("pm list users").output.lines().count { it.contains("UserInfo{") }
                        if (users > 1) throw SetupError("Remove the device's other users first (Settings > System > Multiple users)")
                        null
                    }
                    else -> throw SetupError("Another app already manages this device (device owner)")
                }
            }

            // 4 to 6. Install and take ownership: only for a device PhoneLock doesn't manage yet.
            if (owned) {
                listOf(3, 4, 5).forEach { skip(it, "Already done") }
            } else {
                lateinit var apk: File
                step(3) { apk = downloadLockApp(); null }
                step(4) { device.install(apk); null }
                step(5) {
                    val out = device.shell("dpm set-device-owner $ADMIN")
                    if (!out.allOutput.contains("Success")) throw SetupError(out.allOutput.trim().lines().lastOrNull() ?: "Refused")
                    null
                }
            }

            // 7. Connect it to this app (unless it already is, or belongs to another parent), then confirm it
            // really answers through the hub: nothing below may lock adb away from a device nobody can reach.
            val dev: String? = req.existing?.id ?: mine?.id
            var reachable = false
            when {
                dev != null -> {
                    set(6, StepState.RUNNING, "Checking it answers…")
                    reachable = awaitReply(dev)
                    if (reachable) set(6, StepState.DONE, "Already connected") else set(6, StepState.FAILED, NOT_ANSWERING)
                    _run.update { it?.copy(deviceId = dev) }
                }
                othersDevice -> skip(6, "Connected to another parent")
                else -> {
                    set(6, StepState.RUNNING, null)
                    val id = pair(device, req)
                    reachable = awaitReply(id)
                    if (reachable) set(6, StepState.DONE, null) else set(6, StepState.FAILED, NOT_ANSWERING)
                }
            }

            // 8. One-time grants. A refused one is reported but doesn't stop setup (the rest still work).
            set(7, StepState.RUNNING, null)
            val refused = GRANTS.filter { (_, cmd) -> device.shell(cmd).exitCode != 0 }.map { it.first }
            if (refused.isEmpty()) set(7, StepState.DONE, null)
            else set(7, StepState.FAILED, "Not turned on: " + refused.joinToString(", "))

            // 9. Lock adb away again (this ends the connection), only once the device has answered this app
            // through the hub, and never on another parent's device.
            when {
                !req.turnOffDebugging -> skip(8, "Left on, as chosen")
                othersDevice -> skip(8, "Left on: it's another parent's device")
                !reachable -> skip(8, "Left on, because the device hasn't answered through the internet yet")
                else -> {
                    set(8, StepState.RUNNING, null)
                    // Either the receiver confirms, or the connection drops as adb switches off: both mean done.
                    val reply = runCatching {
                        broadcastReply(device.shell("am broadcast $CONFIG --es cmd debug --es value off").output)
                    }
                    if (reply.isFailure || reply.getOrNull()?.startsWith("allow_debugging=false") == true) {
                        set(8, StepState.DONE, null)
                    } else {
                        set(8, StepState.FAILED, "The device didn't confirm; debugging may still be on")
                    }
                }
            }
        }

        val added = _run.value?.deviceId
        val r = _run.value
        val debugOff = r?.steps?.getOrNull(8)?.state == StepState.DONE
        val grantsFailed = r?.steps?.getOrNull(7)?.state == StepState.FAILED
        _run.update {
            it?.copy(finished = true, failed = false, deviceId = added, debuggingOff = debugOff, outcome = when {
                req.existing != null -> "Setup checked and fixed."
                added == null -> "This device was already set up by another parent. Ask them to share it with you " +
                    "(device settings > Share with another parent)."
                else -> "${req.childName} is set up and connected to this app."
            } + if (grantsFailed) " Some features couldn't be turned on; see above." else "")
        }
    }

    private const val NOT_ANSWERING = "It hasn't answered through the internet yet. Check its connection; debugging stays on until it does."

    /**
     * Hands the new device its pairing and parent PIN in one go, after first saving it in this app, so the
     * secret is never on the device without a parent holding it too. Returns its id.
     */
    private fun pair(device: AdbConnection, req: Request): String {
        val d = Setup.newDevice(req.childName)
        Hub.setParentName(req.parentName)
        Hub.addDevice(d)
        _run.update { it?.copy(deviceId = d.id) }
        val pairing = org.json.JSONObject().put("server", d.server).put("topic", d.topic).put("secret", d.secret)
            .put("name", d.name).put("pin", req.pin)
        val reply = runCatching {
            broadcastReply(device.shell("am broadcast $CONFIG --es cmd pair --es value ${quote(pairing.toString())}").output)
        }.getOrNull()
        if (reply?.startsWith("paired") != true) {
            // Refused outright: nothing was stored on the device, so take it back out of this app.
            if (reply != null) {
                Hub.removeDevice(d.id)
                _run.update { it?.copy(deviceId = null) }
            }
            throw SetupError("The device didn't accept the pairing: ${reply ?: "no answer"}. Try again.")
        }
        return d.id
    }

    /**
     * Asks [dev] for its state and waits for an answer through the hub, judged by this phone's clock when it
     * arrives (an answer from before now doesn't count).
     */
    private suspend fun awaitReply(dev: String): Boolean {
        val since = System.currentTimeMillis()
        Hub.refresh(dev)
        return withTimeoutOrNull(FIRST_REPLY_MS) {
            Hub.vitals.first { (it[dev]?.heard ?: 0L) >= since }
            true
        } ?: false
    }

    /** The lock app's `status` line ("owner=true paired=false topic=…"), or "" if it didn't answer. */
    private fun status(device: AdbConnection): String =
        broadcastReply(device.shell("am broadcast $CONFIG --es cmd status").output) ?: ""

    /** The same short fingerprint of a pairing topic as the lock app's ConfigReceiver.topicHash. */
    private fun topicHash(topic: String): String =
        MessageDigest.getInstance("SHA-256").digest(topic.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    /** The receiver's reply from `am broadcast` output: `Broadcast completed: result=1, data="…"`. */
    private fun broadcastReply(out: String): String? =
        Regex("result=1, data=\"(.*)\"", RegexOption.DOT_MATCHES_ALL).find(out)?.groupValues?.get(1)

    /** The package of the device owner, or null when there is none. */
    private fun deviceOwner(device: AdbConnection): String? {
        val dump = device.shell("dumpsys device_policy").output
        val section = dump.substringAfter("Device Owner:", "").takeIf { it.isNotEmpty() } ?: return null
        return Regex("package=([\\w.]+)").find(section.take(600))?.groupValues?.get(1)
    }

    /** The device's debugging (connect) port on [host], found on the local network. */
    private suspend fun findConnectPort(host: String): Int? {
        val discovery = AdbDiscovery(app)
        discovery.start()
        try {
            // Compare addresses, not strings ("192.168.1.20" typed vs resolved forms of the same host).
            val target = runCatching { java.net.InetAddress.getByName(host) }.getOrNull()
            fun same(e: AdbEndpoint) = !e.pairing && (e.host == host ||
                (target != null && runCatching { java.net.InetAddress.getByName(e.host) == target }.getOrDefault(false)))
            return withTimeoutOrNull(FIND_CONNECT_PORT_MS) {
                discovery.found.first { l -> l.any(::same) }.first(::same).port
            }
        } finally {
            discovery.close()
        }
    }

    /**
     * Downloads the latest lock app release and checks it before it goes anywhere near the child's device:
     * it must be the lock app and signed with the PhoneLock key.
     */
    private fun downloadLockApp(): File {
        val f = File(app.cacheDir, "lock-release.apk")
        val h = URL(Setup.LOCK_APK_URL).openConnection() as HttpURLConnection
        h.instanceFollowRedirects = true
        h.connectTimeout = 20_000
        h.readTimeout = 60_000
        try {
            if (h.responseCode != 200) throw SetupError("Download failed (HTTP ${h.responseCode})")
            h.inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
        } finally {
            h.disconnect()
        }
        val pi = app.packageManager.getPackageArchiveInfo(f.path, PackageManager.GET_SIGNING_CERTIFICATES)
        val certs = pi?.signingInfo?.apkContentsSigners.orEmpty()
        val sha = certs.map {
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(it.toByteArray()))
        }
        if (pi?.packageName != LOCK_PACKAGE || sha.singleOrNull() != Setup.LOCK_SIGNATURE_SHA256) {
            f.delete()
            throw SetupError("The download isn't a genuine PhoneLock lock app")
        }
        return f
    }

    // ---------- progress ----------

    /** A failure with a message meant for the parent. */
    private class SetupError(message: String) : Exception(message)

    /** Runs step [i]; its result, if any, becomes the step's note. A thrown error fails it and the run. */
    private inline fun step(i: Int, block: () -> String?) {
        set(i, StepState.RUNNING, null)
        val note = block()
        set(i, StepState.DONE, note)
    }

    private fun skip(i: Int, why: String) = set(i, StepState.SKIPPED, why)

    private fun set(i: Int, state: StepState, detail: String?) = _run.update { r ->
        r?.copy(steps = r.steps.mapIndexed { k, s -> if (k == i) s.copy(state = state, detail = detail) else s })
    }

    /** Marks the running step failed with [message] and ends the run. */
    private fun failRunning(message: String) = _run.update { r ->
        r?.copy(
            steps = r.steps.map { if (it.state == StepState.RUNNING) it.copy(state = StepState.FAILED, detail = message) else it },
            finished = true,
            failed = true,
            outcome = message,
        )
    }

    /** [s] as one single-quoted shell word. */
    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
