package tvcal.android.profiles

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tvcal.kit.SignalMode
import java.io.File
import java.io.IOException

class PatternLinksTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = "https://example.test/server/"

    private fun decode(s: String) = PatternLinks.decode(s.toByteArray())

    @Test fun decodesAFullFile() {
        val l = decode(
            """
            {"format": 1, "channel": "https://www.youtube.com/@slidebean", "help": "https://help.test/",
             "newsletter": "https://news.test/", "explainer": "https://youtu.be/abc", "instagram": "https://ig.test/sb",
             "playlists": {"sdr": "https://youtube.com/playlist?list=SDR", "hdr10": "https://youtube.com/playlist?list=HDR"},
             "downloads": {"hdr10p": "https://dl.test/hdr10p.zip", "dv": "https://dl.test/dv.zip"},
             "videos": {"SDR 01 - White (bright end)": "https://youtu.be/w1"},
             "extra": {"ignored": true}}
            """.trimIndent()
        )!!
        assertEquals("https://youtube.com/playlist?list=SDR", l.playlist(SignalMode.SDR))
        assertEquals("https://youtube.com/playlist?list=HDR", l.playlist(SignalMode.HDR10))
        assertNull(l.playlist(SignalMode.DV))
        assertEquals("https://dl.test/dv.zip", l.download(SignalMode.DV))
        assertEquals("https://dl.test/hdr10p.zip", l.download(SignalMode.HDR10P))
        assertEquals("https://youtu.be/w1", l.video("SDR 01 - White (bright end)"))
        assertNull(l.video("SDR 02"))
        assertEquals("https://help.test/", l.helpURL)
        assertEquals("https://youtu.be/abc", l.explainerURL)
        assertEquals("https://ig.test/sb", l.instagramURL)
        assertEquals("https://news.test/", l.newsletterURL)
    }

    @Test fun lenientDecode() {
        assertEquals(PatternLinks(), decode("{}"))
        assertNull(decode("[]"))
        assertNull(decode("not json"))
        val l = decode(
            """{"format": "two", "channel": 5, "help": null, "playlists": {"sdr": 7, "hdr10": "https://y.test/h"},
                "downloads": "nope", "videos": null}"""
        )!!
        assertEquals(1, l.format)
        assertNull(l.channel)
        assertNull(l.help)
        assertEquals(mapOf("hdr10" to "https://y.test/h"), l.playlists)
        assertTrue(l.downloads.isEmpty())
        assertTrue(l.videos.isEmpty())
        assertEquals(2, decode("""{"format": 2.0}""")!!.format)
    }

    @Test fun httpsOnly() {
        assertEquals("https://a.test/x", PatternLinks.httpsOrNull("https://a.test/x"))
        assertNull(PatternLinks.httpsOrNull("http://a.test/x"))
        assertNull(PatternLinks.httpsOrNull("javascript:alert(1)"))
        assertNull(PatternLinks.httpsOrNull("a.test/x"))
        assertNull(PatternLinks.httpsOrNull("https://a b"))
        assertNull(PatternLinks.httpsOrNull(""))
        assertNull(PatternLinks.httpsOrNull(null))

        val l = PatternLinks(
            channel = "http://channel.test",
            help = "ftp://help.test",
            playlists = mapOf("sdr" to "http://insecure.test"),
            downloads = mapOf("dv" to ""),
            videos = mapOf("v" to "http://v.test"),
            explainer = "http://e.test",
        )
        assertNull(l.playlist(SignalMode.SDR))
        assertNull(l.download(SignalMode.DV))
        assertNull(l.video("v"))
        assertNull(l.helpURL)
        assertNull(l.explainerURL)
    }

    @Test fun helpFallsBackToTheChannel() {
        assertEquals("https://c.test/", PatternLinks(channel = "https://c.test/").helpURL)
        assertEquals("https://c.test/", PatternLinks(help = "http://h.test/", channel = "https://c.test/").helpURL)
        assertNull(PatternLinks().helpURL)
    }

    @Test fun fallbackURLs() {
        val empty = PatternLinks()
        assertEquals("https://www.instagram.com/slidebean/", empty.instagramURL)
        assertEquals(
            "https://untangled.slidebean.com/?utm_source=tv-calibrator&utm_medium=app&utm_campaign=calibration-complete",
            empty.newsletterURL,
        )
        val insecure = PatternLinks(instagram = "http://ig.test", newsletter = "http://n.test")
        assertEquals("https://www.instagram.com/slidebean/", insecure.instagramURL)
        assertEquals(empty.newsletterURL, insecure.newsletterURL)
        assertNull(empty.explainerURL)
    }

    @Test fun patternSources() {
        assertTrue(SignalMode.SDR.onYouTube)
        assertTrue(SignalMode.HDR10.onYouTube)
        assertFalse(SignalMode.HDR10P.onYouTube)
        assertFalse(SignalMode.DV.onYouTube)
        assertEquals("in the SDR playlist on YouTube", SignalMode.SDR.patternSource)
        assertEquals("in the HDR10 playlist on YouTube", SignalMode.HDR10.patternSource)
        assertEquals("from the HDR10+ download (USB stick or a media app)", SignalMode.HDR10P.patternSource)
        assertEquals("from the Dolby Vision download (USB stick or a media app)", SignalMode.DV.patternSource)
    }

    // ---- LinksService ----

    private val bundledJson = """{"format": 1, "help": "https://bundled.test/"}""".toByteArray()
    private val serverJson = """{"format": 1, "help": "https://server.test/"}""".toByteArray()

    private fun cache() = File(tmp.root, "patterns.json")

    @Test fun startsFromTheCacheThenTheBundleThenDefaults() {
        assertEquals("https://bundled.test/", LinksService(cache(), { bundledJson }, server, LinksHttp { error("no") }).links.help)
        cache().writeBytes("""{"help": "https://cached.test/"}""".toByteArray())
        assertEquals("https://cached.test/", LinksService(cache(), { bundledJson }, server, LinksHttp { error("no") }).links.help)
        cache().writeBytes("garbage".toByteArray())
        assertEquals("https://bundled.test/", LinksService(cache(), { bundledJson }, server, LinksHttp { error("no") }).links.help)
        assertEquals(PatternLinks(), LinksService(cache(), { null }, server, LinksHttp { error("no") }).links)
    }

    @Test fun refreshDownloadsAndCaches() = runTest {
        val asked = mutableListOf<String>()
        val s = LinksService(cache(), { bundledJson }, server, LinksHttp { url -> asked += url; HttpResponse(200, serverJson) })
        s.refresh()
        assertEquals(listOf("${server}patterns.json"), asked)
        assertEquals("https://server.test/", s.links.help)
        assertTrue(cache().readBytes().contentEquals(serverJson))
        // The next launch starts from the cache.
        assertEquals("https://server.test/", LinksService(cache(), { bundledJson }, server, LinksHttp { error("no") }).links.help)
    }

    @Test fun refreshKeepsTheLinksOnProblems() = runTest {
        val notFound = LinksService(cache(), { bundledJson }, server, LinksHttp { HttpResponse(404, serverJson) })
        notFound.refresh()
        assertEquals("https://bundled.test/", notFound.links.help)

        val bad = LinksService(cache(), { bundledJson }, server, LinksHttp { HttpResponse(200, "<html>".toByteArray()) })
        bad.refresh()
        assertEquals("https://bundled.test/", bad.links.help)

        val offline = LinksService(cache(), { bundledJson }, server, LinksHttp { throw IOException("offline") })
        offline.refresh()
        assertEquals("https://bundled.test/", offline.links.help)

        var called = false
        val noServer = LinksService(cache(), { bundledJson }, null, LinksHttp { called = true; HttpResponse(200, serverJson) })
        noServer.refresh()
        assertFalse(called)
        assertEquals("https://bundled.test/", noServer.links.help)
        assertFalse(cache().exists())
    }
}

/** A fake HttpGet from a plain function (no suspend SAM conversion needed). */
private class LinksHttp(val answer: (String) -> HttpResponse) : HttpGet {
    override suspend fun get(url: String): HttpResponse = answer(url)
}
