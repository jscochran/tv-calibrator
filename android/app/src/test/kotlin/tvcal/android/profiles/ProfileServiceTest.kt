package tvcal.android.profiles

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tvcal.android.storage.PanelType
import tvcal.kit.BuiltInProfiles
import tvcal.kit.CameraProfile
import tvcal.kit.PanelMatrix
import tvcal.kit.ProfileCatalog
import tvcal.kit.ProfileJson
import tvcal.kit.ProfileStats
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.time.Instant

class ProfileServiceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val me = "google/husky"
    private val server = "https://example.test/server/"
    private val identity = listOf(listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0))
    private val fixedNow = Instant.parse("2026-11-25T10:00:00Z")

    private fun prof(
        model: String = me,
        version: Int = 1,
        name: String = "Pixel Test",
        matrix: List<List<Double>> = identity,
        panels: Map<String, PanelMatrix>? = null,
        unseen: Double? = null,
    ) = CameraProfile(model = model, name = name, version = version, matrix = matrix, panels = panels,
        stats = ProfileStats(unseen = unseen))

    private fun json(p: CameraProfile) = ProfileJson.encodeToString(CameraProfile.serializer(), p).toByteArray()

    private fun dir(): File = File(tmp.root, "Profiles")

    private fun write(name: String, p: CameraProfile) {
        dir().mkdirs()
        File(dir(), name).writeBytes(json(p))
    }

    /** Answers from a URL map; anything else is a 404. Records every URL asked for. */
    private class FakeHttp(val answers: Map<String, () -> HttpResponse>) : HttpGet {
        val asked = mutableListOf<String>()
        override suspend fun get(url: String): HttpResponse {
            asked += url
            return answers[url]?.invoke() ?: HttpResponse(404, ByteArray(0))
        }
    }

    private fun ok(bytes: ByteArray) = { HttpResponse(200, bytes) }

    private fun catalog(vararg entries: ProfileCatalog.Entry, minAppFormat: Int? = null): ByteArray =
        ProfileJson.encodeToString(
            ProfileCatalog.serializer(),
            ProfileCatalog(format = 1, updated = "2026-11-01", minAppFormat = minAppFormat, profiles = entries.toList()),
        ).toByteArray()

    private fun entry(model: String = me, version: Int, sha: String, file: String = "profiles/google-husky-v$version.json") =
        ProfileCatalog.Entry(model = model, name = "Pixel Test", version = version, file = file, sha256 = sha)

    private fun service(
        builtIn: BuiltInProfiles = BuiltInProfiles(),
        http: HttpGet = FakeHttp(emptyMap()),
        server: String? = this.server,
    ) = ProfileService(dir(), builtIn, me, server, http) { fixedNow }

    // ---- choose() ----

    @Test fun noProfilesMeansNoneAndChecking() {
        val s = service()
        assertNull(s.profile)
        assertNull(s.source)
        assertEquals(ProfileService.Status.Checking, s.status)
        assertEquals("None", s.profileLabel)
        assertTrue(dir().isDirectory)
    }

    @Test fun importedBeatsEverything() {
        write("imported.json", prof(version = 0, name = "Imported"))
        write("current.json", prof(version = 5))
        val s = service(BuiltInProfiles(standard = "other/x", profiles = listOf(prof(version = 9), prof(model = "other/x"))))
        assertEquals(ProfileService.Source.IMPORTED, s.source)
        assertEquals("Imported", s.profile?.name)
        assertTrue(s.isImported)
        assertEquals(ProfileService.Status.Ready, s.status)
    }

    @Test fun invalidImportedIsIgnored() {
        write("imported.json", prof(matrix = listOf(listOf(1.0, 0.0), listOf(0.0, 1.0))))
        val s = service(BuiltInProfiles(profiles = listOf(prof(version = 2))))
        assertEquals(ProfileService.Source.BUILT_IN, s.source)
    }

    @Test fun newerDownloadBeatsBuiltIn() {
        write("current.json", prof(version = 3))
        val s = service(BuiltInProfiles(profiles = listOf(prof(version = 2))))
        assertEquals(ProfileService.Source.DOWNLOADED, s.source)
        assertEquals(3, s.profile?.version)
    }

    @Test fun newerBuiltInBeatsDownload() {
        write("current.json", prof(version = 2))
        val s = service(BuiltInProfiles(profiles = listOf(prof(version = 4))))
        assertEquals(ProfileService.Source.BUILT_IN, s.source)
        assertEquals(4, s.profile?.version)
    }

    @Test fun tieGoesToBuiltIn() {
        write("current.json", prof(version = 2, name = "Downloaded"))
        val s = service(BuiltInProfiles(profiles = listOf(prof(version = 2, name = "Shipped"))))
        assertEquals(ProfileService.Source.BUILT_IN, s.source)
        assertEquals("Shipped", s.profile?.name)
    }

    @Test fun downloadForAnotherModelIsIgnored() {
        write("current.json", prof(model = "samsung/e2q", version = 7))
        val s = service(BuiltInProfiles(standard = "apple/std", profiles = listOf(prof(model = "apple/std", name = "Std"))))
        assertEquals(ProfileService.Source.STANDARD, s.source)
        assertTrue(s.isStandard)
    }

    @Test fun downloadOnlyIsUsed() {
        write("current.json", prof(version = 1))
        val s = service()
        assertEquals(ProfileService.Source.DOWNLOADED, s.source)
    }

    @Test fun standardWhenNoOwnProfile() {
        val s = service(BuiltInProfiles(standard = "apple/std", profiles = listOf(prof(model = "apple/std", name = "Std"))))
        assertEquals(ProfileService.Source.STANDARD, s.source)
        assertEquals(ProfileService.Status.Ready, s.status)
    }

    @Test fun missingStandardMeansNone() {
        val s = service(BuiltInProfiles(standard = "apple/gone", profiles = listOf(prof(model = "apple/std"))))
        assertNull(s.profile)
    }

    @Test fun builtInDecodesAssetOrDefaults() {
        val b = ProfileService.builtIn("""{"format":1,"standard":null,"profiles":[]}""".toByteArray())
        assertEquals(BuiltInProfiles(), b)
        assertEquals(BuiltInProfiles(), ProfileService.builtIn(null))
        assertEquals(BuiltInProfiles(), ProfileService.builtIn("not json".toByteArray()))
    }

    // ---- refresh() ----

    @Test fun noServerIsNotConfiguredOrReady() = runTest {
        val a = service(server = null)
        a.refresh()
        assertEquals(ProfileService.Status.NotConfigured, a.status)
        assertEquals("This build has no camera profiles and no profile server (PROFILE_SERVER).", a.summary)

        val b = service(BuiltInProfiles(profiles = listOf(prof())), server = null)
        b.refresh()
        assertEquals(ProfileService.Status.Ready, b.status)
    }

    @Test fun newerAppFormatNeeded() = runTest {
        val http = FakeHttp(mapOf("${server}index.json" to ok(catalog(entry(version = 1, sha = "x"), minAppFormat = 2))))
        val s = service(http = http)
        s.refresh()
        assertEquals(
            ProfileService.Status.Offline("The profile server needs a newer version of the app. Update TV Calibrator."),
            s.status,
        )
        assertNull(s.catalog)
        assertNull(s.lastChecked)
        assertFalse(File(dir(), "catalog.json").exists())
    }

    @Test fun newerAppFormatNeededKeepsReadyWithAProfile() = runTest {
        val http = FakeHttp(mapOf("${server}index.json" to ok(catalog(minAppFormat = 2))))
        val s = service(BuiltInProfiles(profiles = listOf(prof())), http = http)
        s.refresh()
        assertEquals(ProfileService.Status.Ready, s.status)
    }

    @Test fun unsupportedModel() = runTest {
        val http = FakeHttp(mapOf("${server}index.json" to ok(catalog(entry(model = "samsung/e2q", version = 1, sha = "x")))))
        val s = service(http = http)
        s.refresh()
        assertEquals(ProfileService.Status.Unsupported, s.status)
        assertEquals(
            "This phone (google/husky) isn't supported yet: its camera hasn't been matched to the calibrator. You can still try the demo.",
            s.summary,
        )
        assertNotNull(s.catalog)
        assertEquals(fixedNow, s.lastChecked)
        assertTrue(File(dir(), "catalog.json").exists())
        // A fresh service reads the saved catalog.
        assertEquals("2026-11-01", service().catalog?.updated)
    }

    @Test fun unsupportedModelWithStandardIsReady() = runTest {
        val http = FakeHttp(mapOf("${server}index.json" to ok(catalog())))
        val s = service(BuiltInProfiles(standard = "apple/std", profiles = listOf(prof(model = "apple/std"))), http = http)
        s.refresh()
        assertEquals(ProfileService.Status.Ready, s.status)
        assertEquals(ProfileService.Source.STANDARD, s.source)
    }

    @Test fun downloadsANewerVersionWhenTheShaMatches() = runTest {
        val body = json(prof(version = 3, name = "Pixel 8 Pro"))
        val http = FakeHttp(mapOf(
            "${server}index.json" to ok(catalog(entry(version = 3, sha = sha256Hex(body).uppercase()))),
            "${server}profiles/google-husky-v3.json" to ok(body),
        ))
        val s = service(BuiltInProfiles(profiles = listOf(prof(version = 2))), http = http)
        assertEquals(ProfileService.Source.BUILT_IN, s.source)
        s.refresh()
        assertEquals(ProfileService.Source.DOWNLOADED, s.source)
        assertEquals(3, s.profile?.version)
        assertEquals(ProfileService.Status.Ready, s.status)
        assertEquals("Pixel 8 Pro v3 (downloaded)", s.profileLabel)
        assertTrue(File(dir(), "current.json").exists())
        assertEquals(listOf("${server}index.json", "${server}profiles/google-husky-v3.json"), http.asked)
    }

    @Test fun absoluteEntryFileResolves() = runTest {
        val body = json(prof(version = 1))
        val http = FakeHttp(mapOf(
            "${server}index.json" to ok(catalog(entry(version = 1, sha = sha256Hex(body), file = "https://cdn.test/p.json"))),
            "https://cdn.test/p.json" to ok(body),
        ))
        val s = service(http = http)
        s.refresh()
        assertEquals(ProfileService.Source.DOWNLOADED, s.source)
    }

    @Test fun shaMismatchKeepsWhatThePhoneHas() = runTest {
        val body = json(prof(version = 3))
        val http = FakeHttp(mapOf(
            "${server}index.json" to ok(catalog(entry(version = 3, sha = "00".repeat(32)))),
            "${server}profiles/google-husky-v3.json" to ok(body),
        ))
        val none = service(http = http)
        none.refresh()
        assertNull(none.profile)
        val st = none.status
        assertTrue(st is ProfileService.Status.Offline)
        assertTrue((st as ProfileService.Status.Offline).why.startsWith("Couldn't reach the profile server: "))
        assertEquals(st.why, none.summary)
        assertFalse(File(dir(), "current.json").exists())

        val withStd = service(BuiltInProfiles(standard = "apple/std", profiles = listOf(prof(model = "apple/std"))), http = http)
        withStd.refresh()
        assertEquals(ProfileService.Status.Ready, withStd.status)
        assertEquals(ProfileService.Source.STANDARD, withStd.source)
    }

    @Test fun downloadedProfileForAnotherModelIsRejected() = runTest {
        val body = json(prof(model = "samsung/e2q", version = 3))
        val http = FakeHttp(mapOf(
            "${server}index.json" to ok(catalog(entry(version = 3, sha = sha256Hex(body)))),
            "${server}profiles/google-husky-v3.json" to ok(body),
        ))
        val s = service(http = http)
        s.refresh()
        assertTrue(s.status is ProfileService.Status.Offline)
        assertNull(s.profile)
    }

    @Test fun ownVersionUpToDateSkipsTheDownload() = runTest {
        val http = FakeHttp(mapOf("${server}index.json" to ok(catalog(entry(version = 2, sha = "x")))))
        val s = service(BuiltInProfiles(profiles = listOf(prof(version = 2))), http = http)
        s.refresh()
        assertEquals(ProfileService.Status.Ready, s.status)
        assertEquals(listOf("${server}index.json"), http.asked)
    }

    @Test fun offlineErrors() = runTest {
        val down = ServiceHttp { throw UnknownHostException("example.test") }
        val s = service(http = down)
        s.refresh()
        assertEquals(
            ProfileService.Status.Offline("Couldn't reach the profile server: The Internet connection appears to be offline."),
            s.status,
        )

        val notFound = service(http = FakeHttp(emptyMap()))
        notFound.refresh()
        assertEquals(
            ProfileService.Status.Offline("Couldn't reach the profile server: The server returned a bad response."),
            notFound.status,
        )

        val garbage = service(http = FakeHttp(mapOf("${server}index.json" to ok("<html>".toByteArray()))))
        garbage.refresh()
        assertEquals(
            ProfileService.Status.Offline(
                "Couldn't reach the profile server: The data couldn't be read because it isn't in the correct format."
            ),
            garbage.status,
        )

        val other = service(BuiltInProfiles(profiles = listOf(prof())), http = ServiceHttp { throw IOException("boom") })
        other.refresh()
        assertEquals(ProfileService.Status.Ready, other.status)
    }

    @Test fun importedSkipsRefresh() = runTest {
        write("imported.json", prof(version = 0))
        val http = FakeHttp(emptyMap())
        val s = service(http = http)
        s.refresh()
        assertTrue(http.asked.isEmpty())
        assertEquals(ProfileService.Status.Ready, s.status)
    }

    @Test fun clearImportedFallsBackAndRefreshes() = runTest {
        write("imported.json", prof(version = 0))
        val http = FakeHttp(mapOf("${server}index.json" to ok(catalog())))
        val s = service(BuiltInProfiles(profiles = listOf(prof(version = 2))), http = http)
        assertTrue(s.isImported)
        s.clearImported()
        assertEquals(ProfileService.Source.BUILT_IN, s.source)
        assertFalse(File(dir(), "imported.json").exists())
        assertEquals(listOf("${server}index.json"), http.asked)
    }

    // ---- import ----

    @Test fun importsAServerProfileForThisModel() {
        val s = service()
        s.importProfile(json(prof(version = 4, name = "Lab")))
        assertEquals(ProfileService.Source.IMPORTED, s.source)
        assertEquals("Lab v4 (imported)", s.profileLabel)
        assertTrue(File(dir(), "imported.json").exists())
    }

    @Test fun importRejectsAnotherModel() {
        val s = service()
        try {
            s.importProfile(json(prof(model = "samsung/e2q", name = "")))
            fail("expected a Problem")
        } catch (e: ProfileImport.Problem) {
            assertEquals("That profile is for samsung/e2q, not this phone (google/husky).", e.text)
        }
        try {
            s.importProfile(json(prof(model = "samsung/e2q", name = "Galaxy S24")))
            fail("expected a Problem")
        } catch (e: ProfileImport.Problem) {
            assertEquals("That profile is for Galaxy S24, not this phone (google/husky).", e.text)
        }
    }

    @Test fun parsesTheLabExport() {
        val export = """
            {"tvcal_camera_profile": 1, "model": "google/husky", "name": "Pixel 8 Pro",
             "M": [[1, 0, 0], [0, 1.5, 0], [0, 0, 2]],
             "camera": {"iso": 55, "format": "4080x3060 RGGB", "lens": "Back camera 0"},
             "stats": {"de_avg": 1.2, "unseen": 2.5, "tvs": {"Sony A95": {}, "LG C3": {}}},
             "sessions": []}
        """.trimIndent().toByteArray()
        val p = ProfileImport.parse(export)
        assertEquals("google/husky", p.model)
        assertEquals("Pixel 8 Pro", p.name)
        assertEquals(0, p.version)
        assertEquals(1.5, p.matrix[1][1], 0.0)
        assertEquals(55.0, p.camera.iso)
        assertEquals("4080x3060 RGGB", p.camera.format)
        assertEquals("Back camera 0", p.camera.lens)
        assertNull(p.camera.aperture)
        assertEquals(1.2, p.stats.deAvg)
        assertEquals(2.5, p.stats.unseen)
        assertEquals(listOf("LG C3", "Sony A95"), p.stats.tvs)
    }

    @Test fun importProblems() {
        fun problem(text: String): String = try {
            ProfileImport.parse(text.toByteArray())
            "no problem"
        } catch (e: ProfileImport.Problem) {
            e.text
        }
        assertEquals("That isn't a TV Calibrator camera profile.", problem("""{"model": "google/husky"}"""))
        assertEquals("That isn't a TV Calibrator camera profile.", problem("""{"tvcal_camera_profile": 1}"""))
        assertEquals("That isn't a TV Calibrator camera profile.", problem("[1, 2]"))
        assertEquals(
            "That profile has no trained matrix yet. Train it on at least one TV first.",
            problem("""{"tvcal_camera_profile": 1, "model": "m"}"""),
        )
        assertEquals(
            "That profile has no trained matrix yet. Train it on at least one TV first.",
            problem("""{"tvcal_camera_profile": 1, "model": "m", "M": [[1, 0], [0, 1]]}"""),
        )
        assertEquals(
            "The data couldn't be read because it isn't in the correct format.",
            problem("not json"),
        )
    }

    // ---- texts ----

    @Test fun labelsAndSummaries() {
        val std = service(BuiltInProfiles(standard = "apple/std", profiles = listOf(prof(model = "apple/std", name = "iPhone 17 Pro", version = 4))))
        assertEquals("iPhone 17 Pro v4 (standard profile)", std.profileLabel)
        assertEquals(
            "This phone (google/husky) doesn't have its own camera profile yet, so it uses the standard one (iPhone 17 Pro). " +
                "Readings are approximate. When a profile for this model is ready, it downloads automatically.",
            std.summary,
        )

        val own = service(BuiltInProfiles(profiles = listOf(prof(name = "", version = 2, unseen = 1.5))))
        assertEquals("google/husky v2 (built in)", own.profileLabel)
        assertEquals("google/husky, profile v2 - accuracy: Very good", own.summary)

        val none = service()
        assertEquals("Checking for this phone's camera profile…", none.summary)

        val imp = service()
        imp.importProfile(json(prof(name = "Lab", version = 1)))
        assertEquals("Lab, profile v1 (imported) - accuracy: Not rated", imp.summary)
    }

    @Test fun marginDE() {
        assertNull(service().marginDE(PanelType.WOLED))

        val std = service(BuiltInProfiles(standard = "apple/std", profiles = listOf(prof(model = "apple/std"))))
        assertEquals(3.0, std.marginDE(PanelType.WOLED)!!, 0.0)

        val panels = mapOf(
            "woled" to PanelMatrix(matrix = identity, deAvg = 0.6),     // 2.4 → 2 → 1, at least 1
            "qdlcd" to PanelMatrix(matrix = identity, deAvg = 0.625),   // 2.5 → 3 (half away from zero) → 1.5
            "rgbled" to PanelMatrix(matrix = identity, deAvg = 1.3),    // 5.2 → 5 → 2.5
            "lcd" to PanelMatrix(matrix = identity, deAvg = 1.0),       // 4 → 2
            "plasma" to PanelMatrix(matrix = identity),                 // no deAvg → 3
        )
        val s = service(BuiltInProfiles(profiles = listOf(prof(panels = panels))))
        assertEquals(3.0, s.marginDE(PanelType.UNKNOWN)!!, 0.0)
        assertEquals(3.0, s.marginDE(PanelType.QDOLED)!!, 0.0)
        assertEquals(3.0, s.marginDE(PanelType.PLASMA)!!, 0.0)
        assertEquals(1.0, s.marginDE(PanelType.WOLED)!!, 0.0)
        assertEquals(1.5, s.marginDE(PanelType.QDLCD)!!, 0.0)
        assertEquals(2.5, s.marginDE(PanelType.RGBLED)!!, 0.0)
        assertEquals(2.0, s.marginDE(PanelType.LCD)!!, 0.0)
    }

    @Test fun marginNotes() {
        assertNull(service().marginNote(PanelType.WOLED))

        val std = service(BuiltInProfiles(standard = "apple/std", profiles = listOf(prof(model = "apple/std"))))
        assertEquals(
            "This phone has no camera profile of its own yet and uses the standard one, so its readings are " +
                "approximate: differences under about 3 dE may not be real.",
            std.marginNote(PanelType.WOLED),
        )

        val panels = mapOf(
            "qdlcd" to PanelMatrix(matrix = identity, deAvg = 0.625),
            "lcd" to PanelMatrix(matrix = identity, deAvg = 1.0),
        )
        val s = service(BuiltInProfiles(profiles = listOf(prof(panels = panels))))
        assertEquals(
            "The app doesn't know what kind of screen this is, so the readings are approximate. " +
                "Choose its screen type in Screen settings for accurate readings.",
            s.marginNote(PanelType.UNKNOWN),
        )
        assertEquals(
            "This phone's camera profile hasn't been matched on a OLED screen yet, so the readings " +
                "are approximate: differences under about 3 dE may not be real.",
            s.marginNote(PanelType.WOLED),
        )
        assertEquals(
            "A phone isn't a professional meter. On Quantum dot LED or mini-LED screens this one's readings typically land " +
                "within about 1.5 dE of one, so smaller differences may not be real.",
            s.marginNote(PanelType.QDLCD),
        )
        assertEquals(
            "A phone isn't a professional meter. On LED (LCD) screens this one's readings typically land " +
                "within about 2 dE of one, so smaller differences may not be real.",
            s.marginNote(PanelType.LCD),
        )
    }
}

/** A fake HttpGet from a plain function (no suspend SAM conversion needed). */
private class ServiceHttp(val answer: (String) -> HttpResponse) : HttpGet {
    override suspend fun get(url: String): HttpResponse = answer(url)
}
