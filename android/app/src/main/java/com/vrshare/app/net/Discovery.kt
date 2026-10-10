package com.vrshare.app.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.vrshare.app.core.AppState
import com.vrshare.app.core.Identity
import com.vrshare.app.core.PeerDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * mDNS announce + browse for `_vrshare._tcp.local` (spec §1).
 *
 * The phone announces itself (id/name/fp/os/v TXT records) so the desktop can
 * see it, and browses for desktops. TXT `v` values we don't understand are
 * ignored. Devices gone from mDNS for > 30 s are marked offline by AppState.
 *
 * Note: the phone runs no server in v1; it still announces the protocol port
 * 53417 per the spec (the desktop never dials back in v1).
 */
object Discovery {
    private const val SERVICE_TYPE = "_vrshare._tcp."
    private const val PORT = 53417

    private var nsd: NsdManager? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var registeredName: String? = null
    private var scope: CoroutineScope? = null
    private val serviceNameToId = mutableMapOf<String, String>()

    @Synchronized
    fun start(ctx: Context) {
        if (scope != null) return
        val appCtx = ctx.applicationContext
        nsd = appCtx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifi = appCtx.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("VrShareDiscovery").apply {
            setReferenceCounted(true)
            acquire()
        }
        register()
        browse()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { s ->
            s.launch {
                while (isActive) {
                    delay(5000)
                    AppState.sweepOffline()
                }
            }
        }
    }

    /** Re-announce (e.g. after the device name changed in Settings). */
    @Synchronized
    fun reregister() {
        try {
            nsd?.unregisterService(regListener)
        } catch (_: Exception) { }
        register()
    }

    @Synchronized
    fun stop() {
        scope?.cancel()
        scope = null
        try { nsd?.unregisterService(regListener) } catch (_: Exception) { }
        try { nsd?.stopServiceDiscovery(discoveryListener) } catch (_: Exception) { }
        try { if (multicastLock?.isHeld == true) multicastLock?.release() } catch (_: Exception) { }
    }

    private fun serviceInfo(): NsdServiceInfo {
        val id = Identity.info
        return NsdServiceInfo().apply {
            serviceName = id.name
            serviceType = SERVICE_TYPE
            port = PORT
            setAttribute("id", id.id)
            setAttribute("name", id.name)
            setAttribute("fp", id.fp)
            setAttribute("os", "android")
            setAttribute("v", "1")
        }
    }

    private fun register() {
        try {
            nsd?.registerService(serviceInfo(), NsdManager.PROTOCOL_DNS_SD, regListener)
        } catch (_: Exception) { }
    }

    private fun browse() {
        try {
            nsd?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (_: Exception) { }
    }

    private val regListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            registeredName = info.serviceName
        }

        override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}
        override fun onServiceUnregistered(info: NsdServiceInfo) {}
        override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
    }

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo, code: Int) {}

        override fun onServiceResolved(info: NsdServiceInfo) {
            val attrs = info.attributes
            fun attr(k: String): String = attrs[k]?.let { String(it, Charsets.UTF_8) } ?: ""
            val id = attr("id")
            if (id.isEmpty() || id == Identity.info.id) return // skip self echo
            if (attr("v") != "1") return // forward-compat: ignore unknown versions
            val host = info.host?.hostAddress ?: return
            serviceNameToId[info.serviceName] = id
            AppState.upsertPeer(
                PeerDevice(
                    id = id,
                    name = attr("name").ifEmpty { id.take(8) },
                    fp = attr("fp"),
                    os = attr("os").ifEmpty { "unknown" },
                    host = host,
                    port = info.port,
                )
            )
        }
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

        override fun onServiceFound(service: NsdServiceInfo) {
            if (service.serviceName == registeredName) return
            try {
                nsd?.resolveService(service, resolveListener)
            } catch (_: Exception) { }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            serviceNameToId[service.serviceName]?.let { AppState.markOffline(it) }
        }
    }
}
