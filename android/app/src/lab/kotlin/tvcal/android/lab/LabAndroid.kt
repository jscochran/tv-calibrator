package tvcal.android.lab

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import tvcal.android.TVCalApp
import tvcal.android.profiles.DeviceModel
import java.net.Inet4Address

// The Android side of Lab mode (lab flavor only): NSD discovery of the lab tool, the phone's names, and the
// one LabLink per process (Swift AppModel.lab, a lazy var).

/** The phone as the lab tool records it: model key, a friendly name, the Android version. */
fun androidLabDevice(context: Context): LabDevice {
    val friendly = try {
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
    } catch (e: Exception) {
        null
    }?.trim()?.takeIf { it.isNotEmpty() }
        ?: Build.MODEL?.takeIf { it.isNotBlank() }
        ?: DeviceModel.identifier
    return LabDevice(model = DeviceModel.identifier, name = friendly, android = Build.VERSION.RELEASE ?: "")
}

/** The app's LabLink (created on first use, kept for the process so "Readings sent" survives navigation). */
object Lab {
    private var link: LabLink? = null

    fun link(app: TVCalApp): LabLink =
        link ?: LabLink(
            camera = app.camera,
            prefs = app.prefs,
            scope = app.scope,
            device = androidLabDevice(app),
            http = UrlConnectionLabHttp(),
            discovery = NsdLabDiscovery(app),
        ).also { link = it }
}

/**
 * Browses _tvcal._tcp with NsdManager (Swift NWBrowser .bonjourWithTXTRecord), holding a Wi-Fi multicast lock
 * while browsing. Each service is resolved (one at a time: NsdManager refuses parallel resolves) for its TXT
 * record: ip (required, else the resolved IPv4 address), port (default 8765) and host.
 */
class NsdLabDiscovery(context: Context) : LabDiscovery {
    private val app = context.applicationContext
    private val nsd: NsdManager? = app.getSystemService(NsdManager::class.java)
    private val wifi: WifiManager? = app.getSystemService(WifiManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private var lock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private var onChange: ((List<FoundComputer>) -> Unit)? = null
    /** Bumped by start/stop: callbacks from an older browse are ignored. */
    private var generation = 0
    private val found = LinkedHashMap<String, FoundComputer>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    /** The resolve in flight (API 34+ can cancel it in [stop]). */
    private var resolveListener: NsdManager.ResolveListener? = null
    /** Handler token for the FAILURE_ALREADY_ACTIVE retries (cancelled by stop). */
    private val retryToken = Any()

    override fun start(onChange: (List<FoundComputer>) -> Unit) {
        stop()
        val manager = nsd ?: return
        this.onChange = onChange
        val gen = ++generation
        lock = wifi?.createMulticastLock("tvcal-lab")?.apply {
            setReferenceCounted(false)
            try {
                acquire()
            } catch (e: Exception) {
                // Without the lock some phones drop the multicast answers; browsing still runs.
            }
        }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { if (gen == generation) enqueue(info) }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    if (gen != generation) return@post
                    queue.removeAll { it.serviceName == info.serviceName }
                    if (found.remove(info.serviceName) != null) publish()
                }
            }
        }
        listener = l
        try {
            manager.discoverServices("_tvcal._tcp", NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            listener = null
        }
    }

    override fun stop() {
        generation++
        listener?.let { l -> try { nsd?.stopServiceDiscovery(l) } catch (e: Exception) { } }
        listener = null
        // API 34+ can cancel a resolve in flight. On 29–33 it can't: it may still be pending when browsing starts
        // again, and the next resolve then fails with FAILURE_ALREADY_ACTIVE (retried in resolveNext).
        resolveListener?.let { r ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try { nsd?.stopServiceResolution(r) } catch (e: Exception) { }
            }
        }
        resolveListener = null
        main.removeCallbacksAndMessages(retryToken)
        queue.clear()
        resolving = false
        found.clear()
        onChange = null
        lock?.let { l -> try { if (l.isHeld) l.release() } catch (e: Exception) { } }
        lock = null
    }

    private fun publish() {
        onChange?.invoke(found.values.toList())
    }

    private fun enqueue(info: NsdServiceInfo) {
        queue.addLast(info)
        resolveNext()
    }

    @Suppress("DEPRECATION")      // resolveService: still works on API 34+, and is the only way on 29–33
    private fun resolveNext() {
        if (resolving) return
        val manager = nsd ?: return
        val next = queue.removeFirstOrNull() ?: return
        resolving = true
        val gen = generation
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                main.post {
                    if (gen != generation) return@post
                    finishResolve(this)
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                        // API 29–33: a resolve from an earlier browse is still pending (one per app at a time).
                        // onServiceFound won't come again for this browse, so keep the service and try later.
                        queue.addFirst(next)
                        main.postDelayed({ if (gen == generation) resolveNext() }, retryToken, RESOLVE_RETRY_MS)
                    } else {
                        resolveNext()
                    }
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val result = toComputer(serviceInfo)
                main.post {
                    if (gen != generation) return@post
                    finishResolve(this)
                    if (result != null) {
                        found[result.name] = result
                        publish()
                    }
                    resolveNext()
                }
            }
        }
        resolveListener = listener
        try {
            manager.resolveService(next, listener)
        } catch (e: Exception) {
            resolveListener = null
            resolving = false
            resolveNext()
        }
    }

    private fun finishResolve(listener: NsdManager.ResolveListener) {
        if (resolveListener === listener) resolveListener = null
        resolving = false
    }

    private companion object {
        const val RESOLVE_RETRY_MS = 300L
    }

    private fun toComputer(info: NsdServiceInfo): FoundComputer? {
        val txt = info.attributes ?: emptyMap()
        fun text(key: String): String? = txt[key]?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() }
        @Suppress("DEPRECATION")
        val resolved = (info.host as? Inet4Address)?.hostAddress
        val ip = text("ip") ?: resolved ?: return null
        return FoundComputer(
            name = info.serviceName,
            ip = ip,
            port = text("port")?.toIntOrNull() ?: LabLink.DEFAULT_PORT,
            hostName = text("host"),
        )
    }
}
