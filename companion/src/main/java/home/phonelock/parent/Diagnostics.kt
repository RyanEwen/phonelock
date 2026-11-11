package home.phonelock.parent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/** One line of a diagnostics report: [ok] true/false for a requirement, null for plain information. */
data class DiagCheck(val name: String, val ok: Boolean?, val value: String?)

/** A device's answer to "Send diagnostics", received at [ts] (the device's clock). */
data class DiagReport(val ts: Long, val checks: List<DiagCheck>, val log: String)

/**
 * "Send diagnostics" (see PROTOCOL.md, `diag`): asks a device for its checklist and recent log, and keeps
 * the latest answer in memory. Only answers to this app's own requests are kept, so one parent asking
 * doesn't fill the other's phone. The wording of each check comes from the device.
 */
object Diagnostics {
    private val _reports = MutableStateFlow<Map<String, DiagReport>>(emptyMap())
    val reports: StateFlow<Map<String, DiagReport>> = _reports.asStateFlow()
    /** Device -> id of the request in flight, cleared when its answer arrives. */
    private val _waiting = MutableStateFlow<Map<String, String>>(emptyMap())
    val waiting: StateFlow<Map<String, String>> = _waiting.asStateFlow()

    fun request(dev: String) {
        val m = Hub.msg("diag")
        val id = m.getString("id")
        _waiting.update { it + (dev to id) }
        Hub.send(dev, m)
        // Give up waiting after a minute (device offline), so "Ask again" works again.
        main.postDelayed({ _waiting.update { if (it[dev] == id) it - dev else it } }, 60_000)
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Drops [dev]'s report (the device was removed). */
    fun forget(dev: String) {
        _reports.update { it - dev }
        _waiting.update { it - dev }
    }

    /** A `diag` message from [dev]; ignored unless it answers this app's latest request. */
    fun handle(dev: String, m: JSONObject) {
        if (_waiting.value[dev] != m.optString("req")) return
        val a = m.optJSONArray("checks")
        val checks = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }.map {
            DiagCheck(it.optString("name"), if (it.has("ok")) it.optBoolean("ok") else null,
                it.optString("value").takeIf { v -> v.isNotEmpty() })
        }
        _reports.update { it + (dev to DiagReport(m.optLong("ts"), checks, m.optString("log"))) }
        _waiting.update { it - dev }
    }
}
