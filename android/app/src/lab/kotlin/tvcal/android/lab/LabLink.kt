package tvcal.android.lab

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import tvcal.android.app.PrefKeys
import tvcal.android.app.Prefs
import tvcal.android.camera.CameraMeter
import tvcal.android.camera.CameraReading
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

// Lab mode (lab flavor only): matching a new phone model to the pro calibrator. Port of Lab/LabLink.swift.
//
// The lab tool on a computer (tvcal_app.py, with the Display Plus HL) runs "Train the iPhone camera": it plays
// patches, measures each with the HL, and asks the paired phone for a camera reading of the same patch. This
// file is the phone's side of that conversation (/api/phone/*), taking readings with the app's own CameraMeter,
// so the profile that comes out matches exactly what the standard build runs. Texts say "computer" where the
// Swift says "Mac".

/** A lab tool found on the Wi-Fi (Swift FoundMac): its _tvcal._tcp TXT record's ip / port / host. */
data class FoundComputer(
    val name: String,
    val ip: String,
    val port: Int,
    val hostName: String? = null,
) {
    val id: String get() = name + ip
}

/** What the phone tells the lab tool about itself. */
data class LabDevice(
    /** DeviceModel.identifier, e.g. "samsung/e2q" (the profile key). */
    val model: String,
    /** A friendly name, e.g. "Galaxy S24+" (Settings.Global.DEVICE_NAME, else Build.MODEL). */
    val name: String,
    /** Build.VERSION.RELEASE, e.g. "16". */
    val android: String,
)

/** An HTTP answer: status code and body text (the error body for 4xx/5xx). */
class LabResponse(val status: Int, val body: String)

/** The HTTP layer (injected so LabLink is JVM-testable). Throws IOException when the computer can't be reached. */
interface LabHttp {
    suspend fun get(url: String): LabResponse
    suspend fun post(url: String, json: String): LabResponse
}

/** HttpURLConnection on Dispatchers.IO with 3 s timeouts (Swift: ephemeral URLSession, timeoutIntervalForRequest 3). */
class UrlConnectionLabHttp(private val timeoutMs: Int = 3_000) : LabHttp {
    override suspend fun get(url: String): LabResponse = request(url, null)
    override suspend fun post(url: String, json: String): LabResponse = request(url, json)

    private suspend fun request(url: String, json: String?): LabResponse = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.useCaches = false
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", "application/json")
            if (json != null) {
                val bytes = json.toByteArray(Charsets.UTF_8)
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            } else {
                c.requestMethod = "GET"
            }
            val status = c.responseCode
            val stream = if (status >= 400) c.errorStream else c.inputStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            LabResponse(status, body)
        } finally {
            c.disconnect()
        }
    }
}

/** Finding lab tools on the Wi-Fi (NSD in the app; a fake in tests). [onChange] is called on the main thread. */
interface LabDiscovery {
    fun start(onChange: (List<FoundComputer>) -> Unit)
    fun stop()

    /** No discovery (tests; phones without NSD): only the typed address works. */
    object None : LabDiscovery {
        override fun start(onChange: (List<FoundComputer>) -> Unit) {}
        override fun stop() {}
    }
}

/** The pauses between polls (injected so tests can run on virtual time or count them). */
fun interface LabClock {
    suspend fun sleep(ms: Long)

    companion object {
        val Real = LabClock { delay(it) }
    }
}

/**
 * The phone's side of the lab protocol (Swift LabLink, an ObservableObject): Compose snapshot state, used from the
 * main thread ([scope] = the app scope, Dispatchers.Main.immediate). Pairing is kept in [prefs]
 * (lab.host, lab.port, lab.token, lab.macName, as on the iPhone).
 */
class LabLink(
    val camera: CameraMeter,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    val device: LabDevice,
    private val http: LabHttp = UrlConnectionLabHttp(),
    private val discovery: LabDiscovery = LabDiscovery.None,
    private val clock: LabClock = LabClock.Real,
) {
    companion object {
        const val DEFAULT_PORT = 8765
        const val DEFAULT_NAME = "your computer"
        const val UNREACHABLE =
            "Couldn't reach the computer. Check that TV Calibrator is open on it and both are on the same Wi-Fi."
        const val NOT_ACCEPTED = "The computer didn't accept the code."
        const val BAD_ADDRESS = "That address doesn't look right."
        /** Swift JSONSerialization's localizedDescription for a body that isn't JSON (e.g. an HTML page). */
        const val NOT_JSON = "The data couldn't be read because it isn't in the correct format."

        /** Pauses (Swift: 120 ms while following the computer, 1 s after an error or without a pairing). */
        const val POLL_MS = 120L
        const val RETRY_MS = 1_000L

        fun timeoutText(host: String, port: Int): String =
            "No answer from $host:$port. Check the computer's firewall and that both are on the same Wi-Fi."

        /**
         * A typed computer address: "10.0.1.67", "10.0.1.67:8766", "http://pc.local:8765/", "[fe80::1]:8765".
         * Returns (host, port), port [DEFAULT_PORT] when none is given, or null when it doesn't look right.
         */
        fun parseAddress(text: String): Pair<String, Int>? {
            var s = text.trim()
            for (scheme in listOf("http://", "https://")) {
                if (s.startsWith(scheme, ignoreCase = true)) s = s.substring(scheme.length)
            }
            s = s.substringBefore('/')
            if (s.isEmpty()) return null
            val host: String
            val portText: String?
            if (s.startsWith("[")) {
                val end = s.indexOf(']')
                if (end < 0) return null
                host = s.substring(1, end)
                val rest = s.substring(end + 1)
                portText = when {
                    rest.isEmpty() -> null
                    rest.startsWith(":") -> rest.substring(1)
                    else -> return null
                }
            } else if (s.count { it == ':' } == 1) {
                host = s.substringBefore(':')
                portText = s.substringAfter(':')
            } else {
                host = s          // a name, an IPv4 address or a bare IPv6 address
                portText = null
            }
            if (host.isEmpty() || !host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == ':' || it == '%' }) {
                return null
            }
            val port = if (portText == null) DEFAULT_PORT else portText.toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return host to port
        }

        /** Percent-encodes a query value (RFC 3986 unreserved characters stay; "+" becomes %2B, "/" %2F). */
        fun encode(value: String): String {
            val out = StringBuilder()
            for (b in value.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                val ch = c.toChar()
                if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
                    out.append(ch)
                } else {
                    out.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
                }
            }
            return out.toString()
        }

        /** "http://host:port/path?k=v&…", values encoded, IPv6 literals in brackets. */
        fun url(host: String, port: Int, path: String, query: List<Pair<String, String>> = emptyList()): String {
            val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            val q = if (query.isEmpty()) "" else "?" + query.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
            return "http://$h:$port$path$q"
        }

        /** Swift `error.localizedDescription` for the URLSession errors a phone on the LAN meets. */
        fun describe(e: Exception): String = when (e) {
            is ConnectException, is NoRouteToHostException -> "Could not connect to the server."
            is UnknownHostException -> "A server with the specified hostname could not be found."
            is SocketTimeoutException -> "The request timed out."
            is IOException -> e.message?.takeIf { it.isNotBlank() } ?: "The network connection was lost."
            else -> e.message?.takeIf { it.isNotBlank() } ?: e.toString()
        }

        private val json = Json { ignoreUnknownKeys = true }

        private fun parseObject(text: String): JsonObject? =
            try {
                json.parseToJsonElement(text) as? JsonObject
            } catch (e: Exception) {
                null
            }

        /** A JSON number (not a string) that is a whole number. */
        private fun JsonObject.long(key: String): Long? =
            (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    var found: List<FoundComputer> by mutableStateOf(emptyList())
        private set
    var host: String? by mutableStateOf(null)
        private set
    var port: Int by mutableIntStateOf(DEFAULT_PORT)
        private set
    var token: String? by mutableStateOf(null)
        private set
    var macName: String by mutableStateOf(DEFAULT_NAME)
        private set
    var connected: Boolean by mutableStateOf(false)
        private set
    var lastError: String by mutableStateOf("")
        private set
    var readingsSent: Int by mutableIntStateOf(0)
        private set

    private var pollJob: Job? = null
    private var browsing = false
    private var lastSeq = -1L
    private var lastMeasure = -1L
    private var measuring = false

    init {
        host = prefs.string(PrefKeys.LAB_HOST)
        val p = prefs.int(PrefKeys.LAB_PORT, 0)
        port = if (p == 0) DEFAULT_PORT else p
        token = prefs.string(PrefKeys.LAB_TOKEN)
        macName = prefs.string(PrefKeys.LAB_MAC_NAME) ?: DEFAULT_NAME
    }

    val isPaired: Boolean get() = host != null && token != null

    /**
     * The Lab mode screen appeared, or the app came back to the foreground: follow the paired computer, or look
     * for one. Forwards camera log lines. (On the iPhone the OS suspends a backgrounded app, so its polls stop and
     * the lab tool drops the phone after 3 s; LabModeView calls [stop] on ON_STOP to do the same here.)
     */
    fun start() {
        if (isPaired) startPolling() else startBrowsing()
        camera.onLog = { line -> sendLog(line) }
    }

    /** The Lab mode screen went away, or the app went to the background (stops polling and browsing). */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
        stopBrowsing()
        connected = false
        camera.onLog = null
    }

    // MARK: finding the computer (a Mac advertises _tvcal._tcp with its address; Windows can't, so it's typed)

    fun startBrowsing() {
        stopBrowsing()
        browsing = true
        discovery.start { list -> if (browsing) found = list.sortedBy { it.name } }
    }

    private fun stopBrowsing() {
        if (browsing) discovery.stop()
        browsing = false
    }

    /** Pairs with the code shown in the lab tool. Tries the TXT ip, then the host name. Null on success, or a message. */
    suspend fun pair(computer: FoundComputer, code: String): String? {
        val candidates = listOfNotNull(computer.ip, computer.hostName)
        var lastProblem: String? = null
        for (candidate in candidates) {
            val problem = pair(candidate, computer.port, code) ?: return null
            lastProblem = problem
            if (problem != UNREACHABLE) return problem
        }
        return lastProblem
    }

    /** Pairs with a typed address (IP or IP:port, default port 8765). */
    suspend fun pair(address: String, code: String): String? {
        val (h, p) = parseAddress(address) ?: return BAD_ADDRESS
        return pair(h, p, code)
    }

    suspend fun pair(host: String, port: Int, code: String): String? {
        if (parseAddress(host) == null || port !in 1..65535) return BAD_ADDRESS
        val body = buildJsonObject {
            put("code", code)
            put("device", device.name)
            put("model", device.model)
            put("name", device.name)
            put("android", device.android)
        }
        return try {
            val r = http.post(url(host, port, "/api/phone/pair"), body.toString())
            val obj = parseObject(r.body)
            if (r.status == 200) {
                // Swift: a 200 whose body isn't JSON throws, which reads as "couldn't reach".
                obj ?: return UNREACHABLE
                val tok = obj.string("token")
                if (tok != null) {
                    this.host = host
                    this.port = port
                    this.token = tok
                    this.macName = obj.string("mac") ?: host
                    prefs.setString(PrefKeys.LAB_HOST, host)
                    prefs.setInt(PrefKeys.LAB_PORT, port)
                    prefs.setString(PrefKeys.LAB_TOKEN, tok)
                    prefs.setString(PrefKeys.LAB_MAC_NAME, macName)
                    stopBrowsing()
                    startPolling()
                    return null
                }
            }
            obj?.string("error") ?: NOT_ACCEPTED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UNREACHABLE
        }
    }

    /** Forgets the computer (the user asked, or it answered 403) and looks for one again. */
    fun forget() {
        pollJob?.cancel()
        pollJob = null
        host = null
        token = null
        connected = false
        for (key in listOf(PrefKeys.LAB_HOST, PrefKeys.LAB_PORT, PrefKeys.LAB_TOKEN, PrefKeys.LAB_MAC_NAME)) prefs.remove(key)
        startBrowsing()
    }

    // MARK: following the computer

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                val pause = pollOnce()
                clock.sleep(pause)
            }
        }
    }

    /** One heartbeat; returns the pause before the next, in ms. */
    internal suspend fun pollOnce(): Long {
        val host = host
        val token = token
        if (host == null || token == null) return RETRY_MS
        val port = port
        val target = url(
            host, port, "/api/phone/pattern",
            listOf(
                "t" to token,
                "ext" to "0",
                "device" to device.name,
                "cam" to if (camera.ready) "1" else "0",
                "model" to device.model,
                "name" to device.name,
                "android" to device.android,
            ),
        )
        return try {
            val r = http.get(target)
            if (r.status == 403) {          // the computer forgot this phone
                forget()
                return RETRY_MS
            }
            // Swift: a body that isn't JSON throws (→ the catch below: not reachable, with the error); valid JSON
            // that isn't an object just waits 1 s.
            val element = try {
                json.parseToJsonElement(r.body)
            } catch (e: Exception) {
                connected = false
                lastError = NOT_JSON
                return RETRY_MS
            }
            val obj = element as? JsonObject ?: return RETRY_MS
            connected = true
            lastError = ""
            val seq = obj.long("seq")
            if (seq != null && seq != lastSeq) {
                lastSeq = seq
                acknowledge(seq)
            }
            val m = obj.long("measure")
            if (m != null && m > lastMeasure && !measuring) {
                lastMeasure = m
                measuring = true
                scope.launch { answerMeasurement(m) }
            }
            POLL_MS
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            connected = false
            lastError = if (e is SocketTimeoutException) timeoutText(host, port) else describe(e)
            RETRY_MS
        }
    }

    private suspend fun post(path: String, body: JsonObject) {
        val host = host ?: return
        val token = token ?: return
        try {
            http.post(url(host, port, path, listOf("t" to token)), body.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Swift: _ = try? await session.data(for:)
        }
    }

    private suspend fun acknowledge(seq: Long) {
        post("/api/phone/ack", buildJsonObject { put("seq", seq) })
    }

    /** The reading body (Swift answerMeasurement): "android" instead of "ios"; aperture only on variable lenses. */
    internal fun readingBody(seq: Long, reading: CameraReading?, problem: String?): JsonObject = buildJsonObject {
        put("seq", seq)
        put("ok", reading != null)
        put("model", device.model)
        put("name", device.name)
        put("android", device.android)
        if (reading != null) {
            putJsonArray("rgb") { reading.rgb.forEach { add(it) } }
            put("duration", reading.duration)
            put("uneven", reading.uneven)
            put("peak", reading.peak)
            put("iso", reading.settings.iso ?: 0.0)
            put("format", reading.settings.format ?: "")
            put("lens", reading.settings.lens ?: "")
            reading.settings.aperture?.let { put("aperture", it) }
        } else {
            put("error", problem ?: "No reading.")
        }
    }

    /** The computer asked for a camera reading: take it and send the raw numbers back. */
    private suspend fun answerMeasurement(seq: Long) {
        try {
            val (reading, problem) = try {
                camera.measure()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null to (e.message ?: "No reading.")
            }
            if (reading != null) readingsSent += 1
            post("/api/phone/reading", readingBody(seq, reading, problem))
        } finally {
            measuring = false
        }
    }

    /** Sends a line to the lab tool's phone.log, for troubleshooting (only while connected). */
    fun sendLog(line: String) {
        if (!connected) return
        scope.launch { post("/api/phone/log", buildJsonObject { put("line", line); put("model", device.model) }) }
    }
}
