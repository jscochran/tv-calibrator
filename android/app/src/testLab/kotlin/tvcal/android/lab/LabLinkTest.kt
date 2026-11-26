package tvcal.android.lab

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tvcal.android.app.MemoryStore
import tvcal.android.app.PrefKeys
import tvcal.android.app.Prefs
import tvcal.android.camera.CameraMeter
import tvcal.android.camera.CameraReading
import tvcal.android.camera.DarkLevel
import tvcal.kit.CameraSettings
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.Collections
import kotlin.concurrent.thread

/** Lab mode's protocol (LabLink.swift's behaviour) against a fake of the lab tool's phone routes (/api/phone/...). */
class LabLinkTest {
    private val device = LabDevice(model = "samsung/e2q", name = "Galaxy S24+", android = "16")

    // MARK: fakes

    private class FakeCamera(var ready_: Boolean = true) : CameraMeter {
        override val available = true
        override val running get() = ready_
        override val busy = false
        override val message = CameraMeter.READY_POINT
        override val dark: DarkLevel? = DarkLevel(listOf(1.0, 1.0, 1.0), listOf(2.0, 2.0, 2.0))
        override val lastReading: CameraReading? = null
        override val logLines: List<String> = emptyList()
        override var onLog: ((String) -> Unit)? = null
        override val ready get() = ready_
        override val settings = CameraSettings()
        override val capturedSize = ""
        var answer: Pair<CameraReading?, String?> = reading() to null
        var measureMs = 0L
        var measures = 0
        override fun log(line: String) { onLog?.invoke("camera: $line") }
        override suspend fun start(aperture: Double?) {}
        override fun stop() {}
        override suspend fun measureDark() {}
        override suspend fun measure(): Pair<CameraReading?, String?> {
            measures++
            if (measureMs > 0) delay(measureMs)
            return answer
        }
    }

    private class FakeDiscovery : LabDiscovery {
        var starts = 0
        var stops = 0
        var onChange: ((List<FoundComputer>) -> Unit)? = null
        override fun start(onChange: (List<FoundComputer>) -> Unit) { starts++; this.onChange = onChange }
        override fun stop() { stops++; onChange = null }
    }

    /** One request the phone made. */
    data class Req(val method: String, val path: String, val query: Map<String, String>, val rawQuery: String, val body: JsonObject?)

    /** The lab tool's phone routes (tvcal_app.py do_GET / do_POST), in memory. */
    private class FakeLabTool : LabHttp {
        var code = "4821"
        val tokens = mutableSetOf<String>()
        var seq = 0L
        var measure = 1_000L
        val requests = mutableListOf<Req>()
        /** Throws this instead of answering (unreachable / timeout), by host. */
        val failHosts = mutableMapOf<String, Exception>()
        var failAll: Exception? = null

        fun of(path: String) = requests.filter { it.path == path }

        override suspend fun get(url: String) = handle("GET", url, null)
        override suspend fun post(url: String, json: String) = handle("POST", url, json)

        private fun handle(method: String, url: String, json: String?): LabResponse {
            val rest = url.removePrefix("http://")
            val hostPort = rest.substringBefore('/')
            val host = hostPort.substringBeforeLast(':')
            failAll?.let { throw it }
            failHosts[host]?.let { throw it }
            val pathAndQuery = "/" + rest.substringAfter('/')
            val path = pathAndQuery.substringBefore('?')
            val raw = if ('?' in pathAndQuery) pathAndQuery.substringAfter('?') else ""
            val query = parseQs(raw)
            val body = json?.let { Json.parseToJsonElement(it).jsonObject }
            requests += Req(method, path, query, raw, body)
            if (path == "/api/phone/pair") {
                if (body?.get("code")?.jsonPrimitive?.content?.trim() != code) {
                    return LabResponse(403, """{"error": "That code doesn't match. Check the code shown in TV Calibrator on the computer."}""")
                }
                val tok = "0123456789abcdef0123456789abcdef"
                tokens += tok
                return LabResponse(200, """{"token": "$tok", "mac": "JEFF-PC"}""")
            }
            if (query["t"] !in tokens) return LabResponse(403, """{"error": "Pair this device first."}""")
            return when (path) {
                "/api/phone/pattern" -> LabResponse(200, """{"seq": $seq, "pattern": {"kind": "black"}, "measure": $measure}""")
                "/api/phone/ack", "/api/phone/reading", "/api/phone/log" -> LabResponse(200, """{"ok": true}""")
                else -> LabResponse(404, """{"error": "not found"}""")
            }
        }
    }

    private fun link(
        scope: CoroutineScope,
        tool: LabHttp,
        camera: CameraMeter = FakeCamera(),
        prefs: Prefs = Prefs(MemoryStore()),
        discovery: LabDiscovery = FakeDiscovery(),
    ) = LabLink(camera, prefs, scope, device, tool, discovery)

    private fun TestScope.newLink(
        tool: FakeLabTool,
        camera: CameraMeter = FakeCamera(),
        prefs: Prefs = Prefs(MemoryStore()),
        discovery: LabDiscovery = FakeDiscovery(),
    ) = link(backgroundScope, tool, camera, prefs, discovery)

    // MARK: pairing

    @Test fun pairingSavesTheComputerAndStartsFollowingIt() = runTest {
        val tool = FakeLabTool()
        val prefs = Prefs(MemoryStore())
        val discovery = FakeDiscovery()
        val lab = newLink(tool, prefs = prefs, discovery = discovery)
        lab.start()
        assertEquals(1, discovery.starts)
        assertFalse(lab.isPaired)
        assertEquals("your computer", lab.macName)

        assertNull(lab.pair("10.0.1.67:8766", "4821"))
        assertTrue(lab.isPaired)
        assertEquals("JEFF-PC", lab.macName)
        assertEquals(8766, lab.port)
        assertEquals("10.0.1.67", prefs.string(PrefKeys.LAB_HOST))
        assertEquals(8766, prefs.int(PrefKeys.LAB_PORT, 0))
        assertEquals("0123456789abcdef0123456789abcdef", prefs.string(PrefKeys.LAB_TOKEN))
        assertEquals("JEFF-PC", prefs.string(PrefKeys.LAB_MAC_NAME))
        assertEquals(1, discovery.stops)

        val pair = tool.of("/api/phone/pair").single()
        assertEquals("POST", pair.method)
        val b = pair.body!!
        assertEquals("4821", b.str("code"))
        assertEquals("Galaxy S24+", b.str("device"))
        assertEquals("samsung/e2q", b.str("model"))
        assertEquals("Galaxy S24+", b.str("name"))
        assertEquals("16", b.str("android"))
        assertFalse(b.containsKey("ios"))

        runCurrent()
        assertTrue(tool.of("/api/phone/pattern").isNotEmpty())
        assertTrue(lab.connected)
        lab.stop()
    }

    @Test fun aWrongCodeShowsTheComputersText() = runTest {
        val tool = FakeLabTool()
        val lab = newLink(tool)
        assertEquals(
            "That code doesn't match. Check the code shown in TV Calibrator on the computer.",
            lab.pair("10.0.1.67", "1111"),
        )
        assertFalse(lab.isPaired)
        runCurrent()
        assertTrue(tool.of("/api/phone/pattern").isEmpty())
    }

    @Test fun aRefusalWithoutAnErrorTextAndABadAddress() = runTest {
        val http = object : LabHttp {
            override suspend fun get(url: String) = LabResponse(500, "oops")
            override suspend fun post(url: String, json: String) = LabResponse(500, "<html>")
        }
        val lab = link(backgroundScope, http)
        assertEquals("The computer didn't accept the code.", lab.pair("10.0.1.67", "1234"))
        assertEquals("That address doesn't look right.", lab.pair("10.0.1.67:99999", "1234"))
        assertEquals("That address doesn't look right.", lab.pair("", "1234"))
        assertEquals("That address doesn't look right.", lab.pair("bad host!", "1234"))
    }

    @Test fun anUnreachableComputerTriesTheHostNameNext() = runTest {
        val tool = FakeLabTool()
        tool.failHosts["10.0.1.99"] = ConnectException("failed to connect")
        val lab = newLink(tool)
        val pc = FoundComputer(name = "TV Calibrator on JEFF-PC", ip = "10.0.1.99", port = 8766, hostName = "JEFF-PC.local")
        assertNull(lab.pair(pc, "4821"))
        assertEquals("JEFF-PC.local", lab.host)
        assertEquals(8766, lab.port)

        // Both unreachable: the unreachable text, after trying both.
        val tool2 = FakeLabTool()
        tool2.failAll = ConnectException("refused")
        val lab2 = newLink(tool2)
        assertEquals(
            "Couldn't reach the computer. Check that TV Calibrator is open on it and both are on the same Wi-Fi.",
            lab2.pair(pc, "4821"),
        )
        assertFalse(lab2.isPaired)
    }

    @Test fun aWrongCodeDoesNotTryTheHostName() = runTest {
        val tool = FakeLabTool()
        val lab = newLink(tool)
        val pc = FoundComputer(name = "PC", ip = "10.0.1.67", port = 8765, hostName = "pc.local")
        assertEquals(
            "That code doesn't match. Check the code shown in TV Calibrator on the computer.",
            lab.pair(pc, "0000"),
        )
        assertEquals(1, tool.of("/api/phone/pair").size)
    }

    @Test fun aSavedPairingIsFollowedAtOnce() = runTest {
        val store = MemoryStore()
        val prefs = Prefs(store)
        prefs.setString(PrefKeys.LAB_HOST, "10.0.1.67")
        prefs.setString(PrefKeys.LAB_TOKEN, "0123456789abcdef0123456789abcdef")
        prefs.setString(PrefKeys.LAB_MAC_NAME, "JEFF-PC")
        val tool = FakeLabTool().apply { tokens += "0123456789abcdef0123456789abcdef" }
        val discovery = FakeDiscovery()
        val lab = newLink(tool, prefs = prefs, discovery = discovery)
        assertTrue(lab.isPaired)
        assertEquals(8765, lab.port)          // lab.port 0 / missing means 8765
        assertEquals("JEFF-PC", lab.macName)
        lab.start()
        runCurrent()
        assertEquals(0, discovery.starts)
        assertTrue(lab.connected)
        lab.stop()
        assertFalse(lab.connected)
    }

    // MARK: polling

    @Test fun pollingSendsTheEncodedQueryAndAcksEachNewPatternOnce() = runTest {
        val tool = FakeLabTool()
        val camera = FakeCamera(ready_ = false)
        val lab = newLink(tool, camera = camera)
        lab.pair("10.0.1.67", "4821")
        runCurrent()
        val first = tool.of("/api/phone/pattern").first()
        assertEquals(
            "t=0123456789abcdef0123456789abcdef&ext=0&device=Galaxy%20S24%2B&cam=0&model=samsung%2Fe2q" +
                "&name=Galaxy%20S24%2B&android=16",
            first.rawQuery,
        )
        assertEquals("samsung/e2q", first.query["model"])
        assertEquals("Galaxy S24+", first.query["name"])
        assertEquals("Galaxy S24+", first.query["device"])
        // seq 0 is new (lastSeq starts at -1): acked once.
        assertEquals(listOf(0L), tool.of("/api/phone/ack").map { it.body!!.long("seq") })
        assertEquals("0123456789abcdef0123456789abcdef", tool.of("/api/phone/ack").single().query["t"])

        // 120 ms between polls while connected.
        val before = tool.of("/api/phone/pattern").size
        advanceTimeBy(1_200)
        runCurrent()
        val polls = tool.of("/api/phone/pattern").size - before
        assertTrue("polls in 1.2 s: $polls", polls in 9..11)
        assertEquals(1, tool.of("/api/phone/ack").size)      // the same seq isn't acked again

        tool.seq = 7
        camera.ready_ = true
        advanceTimeBy(130)
        runCurrent()
        assertEquals(listOf(0L, 7L), tool.of("/api/phone/ack").map { it.body!!.long("seq") })
        assertEquals("1", tool.of("/api/phone/pattern").last().query["cam"])
        lab.stop()
    }

    @Test fun aMeasurementRequestIsAnsweredExactlyOnce() = runTest {
        val tool = FakeLabTool()
        val camera = FakeCamera()
        val lab = newLink(tool, camera = camera)
        lab.pair("10.0.1.67", "4821")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, camera.measures)
        val reading = tool.of("/api/phone/reading").single()
        assertEquals("0123456789abcdef0123456789abcdef", reading.query["t"])
        val b = reading.body!!
        assertEquals(
            setOf("seq", "ok", "model", "name", "android", "rgb", "duration", "uneven", "peak", "iso", "format", "lens"),
            b.keys,
        )
        assertEquals(1_000L, b.long("seq"))
        assertEquals(true, b["ok"]!!.jsonPrimitive.content.toBooleanStrict())
        assertEquals("samsung/e2q", b.str("model"))
        assertEquals("Galaxy S24+", b.str("name"))
        assertEquals("16", b.str("android"))
        assertEquals(listOf(120.5, 240.25, 60.125), b["rgb"]!!.jsonArray.map { it.jsonPrimitive.content.toDouble() })
        assertEquals(0.004, b.dbl("duration"), 0.0)
        assertEquals(0.012, b.dbl("uneven"), 0.0)
        assertEquals(0.45, b.dbl("peak"), 0.0)
        assertEquals(50.0, b.dbl("iso"), 0.0)
        assertEquals("4080x3060 RGGB", b.str("format"))
        assertEquals("Back camera 0", b.str("lens"))
        assertEquals(1, lab.readingsSent)

        // A new request number is answered once more.
        tool.measure = 1_001
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, camera.measures)
        assertEquals(listOf(1_000L, 1_001L), tool.of("/api/phone/reading").map { it.body!!.long("seq") })
        assertEquals(2, lab.readingsSent)
        lab.stop()
    }

    @Test fun aVariableApertureIsSent() = runTest {
        val tool = FakeLabTool()
        val camera = FakeCamera().apply { answer = reading(aperture = 1.7) to null }
        val lab = newLink(tool, camera = camera)
        lab.pair("10.0.1.67", "4821")
        runCurrent()
        assertEquals(1.7, tool.of("/api/phone/reading").single().body!!.dbl("aperture"), 0.0)
        lab.stop()
    }

    @Test fun requestsWhileMeasuringAreSkippedThenTheLatestIsAnswered() = runTest {
        val tool = FakeLabTool()
        val camera = FakeCamera().apply { measureMs = 2_000 }
        val lab = newLink(tool, camera = camera)
        lab.pair("10.0.1.67", "4821")
        runCurrent()
        assertEquals(1, camera.measures)
        tool.measure = 1_001
        advanceTimeBy(500)
        runCurrent()
        tool.measure = 1_002
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, camera.measures)                 // busy: not asked again
        advanceTimeBy(1_300)
        runCurrent()
        assertEquals(2, camera.measures)
        assertEquals(listOf(1_000L), tool.of("/api/phone/reading").map { it.body!!.long("seq") })
        advanceTimeBy(2_100)
        runCurrent()
        assertEquals(listOf(1_000L, 1_002L), tool.of("/api/phone/reading").map { it.body!!.long("seq") })
        lab.stop()
    }

    @Test fun aFailedReadingSendsTheProblem() = runTest {
        val tool = FakeLabTool()
        val camera = FakeCamera().apply { answer = null to CameraMeter.TOO_BRIGHT }
        val lab = newLink(tool, camera = camera)
        lab.pair("10.0.1.67", "4821")
        runCurrent()
        val b = tool.of("/api/phone/reading").single().body!!
        assertEquals(setOf("seq", "ok", "model", "name", "android", "error"), b.keys)
        assertEquals("false", b["ok"]!!.jsonPrimitive.content)
        assertEquals(CameraMeter.TOO_BRIGHT, b.str("error"))
        assertEquals(0, lab.readingsSent)
        lab.stop()
    }

    @Test fun aForbiddenPollForgetsTheComputer() = runTest {
        val tool = FakeLabTool()
        val prefs = Prefs(MemoryStore())
        val discovery = FakeDiscovery()
        val lab = newLink(tool, prefs = prefs, discovery = discovery)
        lab.start()
        lab.pair("10.0.1.67", "4821")
        runCurrent()
        assertTrue(lab.connected)
        tool.tokens.clear()                               // the computer's "Forget phones"
        advanceTimeBy(200)
        runCurrent()
        assertFalse(lab.isPaired)
        assertFalse(lab.connected)
        assertNull(lab.host)
        assertNull(lab.token)
        for (key in listOf(PrefKeys.LAB_HOST, PrefKeys.LAB_PORT, PrefKeys.LAB_TOKEN, PrefKeys.LAB_MAC_NAME)) {
            assertFalse(key, prefs.store.contains(key))
        }
        assertEquals(2, discovery.starts)                 // browsing again
        val polls = tool.of("/api/phone/pattern").size
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(polls, tool.of("/api/phone/pattern").size)   // stopped polling
        lab.stop()
    }

    @Test fun aTimeoutShowsTheFirewallHintAndSlowsDown() = runTest {
        val tool = FakeLabTool()
        val lab = newLink(tool)
        lab.pair("10.0.1.67:8766", "4821")
        runCurrent()
        assertTrue(lab.connected)
        tool.failAll = SocketTimeoutException("timeout")
        advanceTimeBy(130)
        runCurrent()
        assertFalse(lab.connected)
        assertEquals(
            "No answer from 10.0.1.67:8766. Check the computer's firewall and that both are on the same Wi-Fi.",
            lab.lastError,
        )
        tool.failAll = null
        val n = tool.of("/api/phone/pattern").size
        advanceTimeBy(900)
        runCurrent()
        assertEquals(n, tool.of("/api/phone/pattern").size)       // waits 1 s after an error
        advanceTimeBy(200)
        runCurrent()
        assertTrue(lab.connected)
        assertEquals("", lab.lastError)
        tool.failAll = ConnectException("refused")
        advanceTimeBy(130)
        runCurrent()
        assertEquals("Could not connect to the server.", lab.lastError)
        lab.stop()
    }

    @Test fun aPollAnswerThatIsNotJsonReadsAsNotReachable() = runTest {
        var answer: LabResponse? = null
        val tool = FakeLabTool()
        val http = object : LabHttp {
            override suspend fun get(url: String) = answer ?: tool.get(url)
            override suspend fun post(url: String, json: String) = tool.post(url, json)
        }
        val lab = link(backgroundScope, http)
        lab.pair("10.0.1.67", "4821")
        runCurrent()
        assertTrue(lab.connected)
        answer = LabResponse(200, "<html><body>Sign in to the Wi-Fi</body></html>")   // another server on that address
        advanceTimeBy(130)
        runCurrent()
        assertFalse(lab.connected)
        assertEquals(LabLink.NOT_JSON, lab.lastError)
        answer = LabResponse(200, "[1, 2]")             // JSON, but not an object: just waits (Swift)
        advanceTimeBy(1_100)
        runCurrent()
        assertFalse(lab.connected)
        assertEquals(LabLink.NOT_JSON, lab.lastError)
        answer = null
        advanceTimeBy(1_100)
        runCurrent()
        assertTrue(lab.connected)
        assertEquals("", lab.lastError)
        lab.stop()
    }

    @Test fun stoppingInTheBackgroundStopsPollingAndStartingFollowsAgain() = runTest {
        val tool = FakeLabTool()
        val discovery = FakeDiscovery()
        val lab = newLink(tool, discovery = discovery)
        lab.start()
        lab.pair("10.0.1.67", "4821")
        runCurrent()
        assertTrue(lab.connected)
        lab.stop()                                        // ON_STOP: the lab tool drops the phone after 3 s
        assertFalse(lab.connected)
        val polls = tool.of("/api/phone/pattern").size
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(polls, tool.of("/api/phone/pattern").size)
        lab.start()                                       // ON_START: still paired, so it follows again
        runCurrent()
        assertTrue(lab.connected)
        assertTrue(tool.of("/api/phone/pattern").size > polls)
        assertEquals(1, discovery.starts)                 // no browsing while paired
        lab.stop()
    }

    @Test fun stoppingWhileUnpairedStopsBrowsing() = runTest {
        val discovery = FakeDiscovery()
        val lab = newLink(FakeLabTool(), discovery = discovery)
        lab.start()
        assertEquals(1, discovery.starts)
        lab.stop()
        assertEquals(1, discovery.stops)                  // NSD stopped, multicast lock released
        lab.start()
        assertEquals(2, discovery.starts)
        lab.stop()
    }

    @Test fun cameraLogLinesAreForwardedOnlyWhileConnected() = runTest {
        val tool = FakeLabTool()
        val camera = FakeCamera(ready_ = false)
        val lab = newLink(tool, camera = camera)
        lab.start()
        camera.log("before pairing")
        runCurrent()
        assertTrue(tool.of("/api/phone/log").isEmpty())
        lab.pair("10.0.1.67", "4821")
        runCurrent()
        camera.log("Dark at 1/30 s: 1.0 1.0 1.0")
        runCurrent()
        val log = tool.of("/api/phone/log").single().body!!
        assertEquals("camera: Dark at 1/30 s: 1.0 1.0 1.0", log.str("line"))
        assertEquals("samsung/e2q", log.str("model"))
        lab.stop()
        assertNull(camera.onLog)
    }

    @Test fun foundComputersAreSortedByName() = runTest {
        val discovery = FakeDiscovery()
        val lab = newLink(FakeLabTool(), discovery = discovery)
        lab.start()
        discovery.onChange!!(listOf(FoundComputer("TV Calibrator on Zed", "10.0.1.9", 8765), FoundComputer("TV Calibrator on Amy", "10.0.1.8", 8765)))
        assertEquals(listOf("TV Calibrator on Amy", "TV Calibrator on Zed"), lab.found.map { it.name })
    }

    // MARK: addresses and encoding

    @Test fun typedAddresses() {
        assertEquals("10.0.1.67" to 8765, LabLink.parseAddress("10.0.1.67"))
        assertEquals("10.0.1.67" to 8766, LabLink.parseAddress(" 10.0.1.67:8766 "))
        assertEquals("pc.local" to 8765, LabLink.parseAddress("http://pc.local/"))
        assertEquals("pc.local" to 9000, LabLink.parseAddress("http://pc.local:9000/api"))
        assertEquals("fe80::1" to 8765, LabLink.parseAddress("fe80::1"))
        assertEquals("fe80::1" to 8766, LabLink.parseAddress("[fe80::1]:8766"))
        assertNull(LabLink.parseAddress(""))
        assertNull(LabLink.parseAddress("10.0.1.67:"))
        assertNull(LabLink.parseAddress("10.0.1.67:0"))
        assertNull(LabLink.parseAddress("10.0.1.67:abc"))
        assertNull(LabLink.parseAddress("my pc"))
        assertEquals("http://[fe80::1]:8766/api/phone/pair", LabLink.url("fe80::1", 8766, "/api/phone/pair"))
    }

    @Test fun queryValuesAreEncoded() {
        assertEquals("samsung%2Fe2q", LabLink.encode("samsung/e2q"))
        assertEquals("Galaxy%20S24%2B", LabLink.encode("Galaxy S24+"))
        assertEquals("Jeff%E2%80%99s%20Pixel%20%26%20co%3D1", LabLink.encode("Jeff’s Pixel & co=1"))
        assertEquals("A-Z_a.z~09", LabLink.encode("A-Z_a.z~09"))
    }

    // MARK: a real socket, a server that parses like the Python one

    @Test fun overRealHttpWithTheRealHttpLayer() = runBlocking<Unit> {
        val server = MiniLabServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val lab = link(scope, UrlConnectionLabHttp(), FakeCamera(), Prefs(MemoryStore()), LabDiscovery.None)
            assertEquals(
                "That code doesn't match. Check the code shown in TV Calibrator on the computer.",
                lab.pair("127.0.0.1:${server.port}", "9999"),
            )
            assertNull(lab.pair("127.0.0.1:${server.port}", "4821"))
            val reading = withTimeout(10_000) { server.reading.await() }
            assertEquals(1_000L, reading.long("seq"))
            assertEquals("Galaxy S24+", reading.str("name"))
            assertFalse(reading.containsKey("aperture"))
            val poll = server.requests.first { it.path == "/api/phone/pattern" }
            // parse_qs semantics ("+" is a space): the "+" and "/" must arrive encoded.
            assertEquals("samsung/e2q", poll.query["model"])
            assertEquals("Galaxy S24+", poll.query["name"])
            assertEquals("16", poll.query["android"])
            assertEquals("0", poll.query["ext"])
            assertTrue(server.requests.any { it.path == "/api/phone/ack" })

            // The computer forgets the phone: 403 → forget.
            server.forgetAll = true
            withTimeout(10_000) { while (lab.isPaired) delay(20) }
            assertFalse(lab.connected)

            // Nothing listening: unreachable.
            server.close()
            assertEquals(LabLink.UNREACHABLE, lab.pair("127.0.0.1:${server.port}", "4821"))
            lab.stop()
        } finally {
            scope.cancel()
            server.close()
        }
    }

    /** A tiny HTTP/1.1 server on localhost with the lab tool's phone routes (one request per connection). */
    private class MiniLabServer {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int = socket.localPort
        val requests: MutableList<Req> = Collections.synchronizedList(mutableListOf())
        val reading = CompletableDeferred<JsonObject>()
        @Volatile var forgetAll = false
        private val token = "fedcba9876543210fedcba9876543210"

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val s = try { socket.accept() } catch (e: Exception) { break }
                    thread(isDaemon = true) {
                        s.use { c ->
                            c.soTimeout = 5_000
                            val input = BufferedInputStream(c.getInputStream())
                            val line = readLine(input) ?: return@use
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val h = readLine(input) ?: break
                                if (h.isEmpty()) break
                                headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim()
                            }
                            val n = headers["content-length"]?.toIntOrNull() ?: 0
                            val bodyBytes = ByteArray(n)
                            var read = 0
                            while (read < n) {
                                val k = input.read(bodyBytes, read, n - read)
                                if (k < 0) break
                                read += k
                            }
                            val (method, target) = line.split(' ').let { it[0] to it[1] }
                            val path = target.substringBefore('?')
                            val raw = if ('?' in target) target.substringAfter('?') else ""
                            val body = if (n > 0) Json.parseToJsonElement(bodyBytes.toString(Charsets.UTF_8)).jsonObject else null
                            requests += Req(method, path, parseQs(raw), raw, body)
                            val (status, text) = route(path, parseQs(raw), body)
                            val out = text.toByteArray(Charsets.UTF_8)
                            val head = "HTTP/1.1 $status X\r\nContent-Type: application/json; charset=utf-8\r\n" +
                                "Content-Length: ${out.size}\r\nConnection: close\r\n\r\n"
                            c.getOutputStream().apply { write(head.toByteArray()); write(out); flush() }
                        }
                    }
                }
            }
        }

        private fun route(path: String, q: Map<String, String>, b: JsonObject?): Pair<Int, String> {
            if (path == "/api/phone/pair") {
                return if (b?.str("code") == "4821") 200 to """{"token": "$token", "mac": "JEFF-PC"}"""
                else 403 to """{"error": "That code doesn't match. Check the code shown in TV Calibrator on the computer."}"""
            }
            if (forgetAll || q["t"] != token) return 403 to """{"error": "Pair this device first."}"""
            return when (path) {
                "/api/phone/pattern" -> 200 to """{"seq": 3, "pattern": {"kind": "black"}, "measure": 1000}"""
                "/api/phone/reading" -> {
                    b?.let { reading.complete(it) }
                    200 to """{"ok": true}"""
                }
                else -> 200 to """{"ok": true}"""
            }
        }

        private fun readLine(input: InputStream): String? {
            val buf = ByteArrayOutputStream()
            while (true) {
                val c = input.read()
                if (c < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
                if (c == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
                buf.write(c)
            }
        }

        fun close() {
            try { socket.close() } catch (e: Exception) { }
        }
    }

    companion object {
        fun reading(aperture: Double? = null) = CameraReading(
            rgb = listOf(120.5, 240.25, 60.125),
            duration = 0.004,
            uneven = 0.012,
            peak = 0.45,
            settings = CameraSettings(iso = 50.0, format = "4080x3060 RGGB", lens = "Back camera 0", aperture = aperture),
            capturedAt = 12.0,
        )

        /** Python parse_qs: "&"-separated, "+" is a space, %XX decoded. */
        fun parseQs(raw: String): Map<String, String> =
            if (raw.isEmpty()) emptyMap() else raw.split('&').filter { it.isNotEmpty() }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }

        fun JsonObject.str(key: String): String = (this[key] as JsonPrimitive).content
        fun JsonObject.long(key: String): Long = (this[key] as JsonPrimitive).content.toLong()
        fun JsonObject.dbl(key: String): Double = (this[key] as JsonPrimitive).content.toDouble()
    }
}
