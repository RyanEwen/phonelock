package home.phonelock.parent.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable
import java.net.Inet4Address

/** A device advertising Wireless debugging: [pairing] for "Pair device with pairing code", else its debugging port. */
internal data class AdbEndpoint(val name: String, val host: String, val port: Int, val pairing: Boolean)

/**
 * Finds devices on the local network that have Wireless debugging on, the way Android Studio does: they
 * advertise `_adb-tls-pairing._tcp` while the pairing code is showing, and `_adb-tls-connect._tcp` while
 * Wireless debugging is on. Uses Android's built-in service discovery (no permissions needed).
 */
internal class AdbDiscovery(context: Context) : Closeable {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
    private val main = context.applicationContext.mainExecutor
    private val _found = MutableStateFlow<List<AdbEndpoint>>(emptyList())
    /** Endpoints currently seen; entries go away when the device stops advertising them. */
    val found: StateFlow<List<AdbEndpoint>> = _found.asStateFlow()

    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    /** Android 14+: one info callback per service, by name and kind, removed when the service goes away. */
    private val infoCallbacks = HashMap<String, NsdManager.ServiceInfoCallback>()
    /** Android 13 resolves one service at a time; the rest wait here. */
    private val pending = ArrayDeque<Pair<NsdServiceInfo, Boolean>>()
    private var resolving = false
    @Volatile private var closed = false

    fun start() {
        for ((type, pairing) in listOf(PAIRING to true, CONNECT to false)) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) = resolve(info, pairing)
                override fun onServiceLost(info: NsdServiceInfo) {
                    _found.update { l -> l.filterNot { it.name == info.serviceName && it.pairing == pairing } }
                    if (Build.VERSION.SDK_INT >= 34) synchronized(infoCallbacks) {
                        infoCallbacks.remove(key(info, pairing))?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
                    }
                }
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "discovery $errorCode")
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            listeners += l
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l) }
                .onFailure { Log.w(TAG, "discovery: $it") }
        }
    }

    private fun key(info: NsdServiceInfo, pairing: Boolean) = "${info.serviceName}/$pairing"

    /** Looks up the address and port of a found service, preferring IPv4. */
    private fun resolve(info: NsdServiceInfo, pairing: Boolean) {
        if (closed) return
        if (Build.VERSION.SDK_INT >= 34) {
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceUpdated(s: NsdServiceInfo) {
                    usable(s.hostAddresses)?.let { add(AdbEndpoint(s.serviceName, it, s.port, pairing)) }
                }
                override fun onServiceLost() {}
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                override fun onServiceInfoCallbackUnregistered() {}
            }
            synchronized(infoCallbacks) {
                if (infoCallbacks.containsKey(key(info, pairing))) return
                infoCallbacks[key(info, pairing)] = cb
            }
            runCatching { nsd.registerServiceInfoCallback(info, main, cb) }
        } else {
            synchronized(pending) {
                pending.addLast(info to pairing)
                if (resolving) return
                resolving = true
            }
            resolveNext()
        }
    }

    /** Android 13: resolves queued services one after another (it refuses a second at the same time). */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val next = synchronized(pending) {
            pending.removeFirstOrNull().also { if (it == null) resolving = false }
        } ?: return
        if (closed) return
        val (info, pairing) = next
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onServiceResolved(s: NsdServiceInfo) {
                usable(listOfNotNull(s.host))?.let { add(AdbEndpoint(s.serviceName, it, s.port, pairing)) }
                resolveNext()
            }
            override fun onResolveFailed(s: NsdServiceInfo, errorCode: Int) {
                // Busy: try this one again after the rest.
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) synchronized(pending) { pending.addLast(s to pairing) }
                resolveNext()
            }
        })
    }

    /** An address we can connect to: IPv4 first, else IPv6 that isn't link-local (those need a scope). */
    private fun usable(addresses: List<java.net.InetAddress>): String? =
        (addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull { !it.isLinkLocalAddress })?.hostAddress

    private fun add(e: AdbEndpoint) {
        if (closed) return
        _found.update { l -> l.filterNot { it.name == e.name && it.pairing == e.pairing } + e }
    }

    override fun close() {
        if (closed) return
        closed = true
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        if (Build.VERSION.SDK_INT >= 34) synchronized(infoCallbacks) {
            infoCallbacks.values.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
            infoCallbacks.clear()
        }
    }

    companion object {
        private const val TAG = "PhoneLockAdb"
        private const val PAIRING = "_adb-tls-pairing._tcp"
        private const val CONNECT = "_adb-tls-connect._tcp"
    }
}
