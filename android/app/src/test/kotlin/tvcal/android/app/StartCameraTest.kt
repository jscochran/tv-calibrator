package tvcal.android.app

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tvcal.android.camera.ApertureLock
import tvcal.android.camera.ApproximateResult
import tvcal.android.camera.ApproximateSource
import tvcal.android.camera.CameraMeter
import tvcal.android.camera.CameraReading
import tvcal.android.camera.DarkLevel
import tvcal.android.measure.FakeReminders
import tvcal.android.profiles.HttpGet
import tvcal.android.profiles.HttpResponse
import tvcal.android.profiles.LinksService
import tvcal.android.profiles.ProfileService
import tvcal.android.storage.TVStore
import tvcal.kit.BuiltInProfiles
import tvcal.kit.CameraProfile
import tvcal.kit.CameraSettings
import java.io.File
import java.io.IOException

/** AppModel.startCamera: the aperture follows the profile that takes the readings (the approximate one once it replaces a standard stand-in). */
class StartCameraTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Records every start/stop; [onStart] runs inside start (e.g. the permission arriving as the camera opens). */
    private class FakeCamera(val onStart: () -> Unit = {}) : CameraMeter {
        val starts = mutableListOf<Double?>()
        var stops = 0
        override val available = true
        override var running = false
        override val busy = false
        override val message = CameraMeter.OFF
        override val dark: DarkLevel? = null
        override val lastReading: CameraReading? = null
        override val logLines = mutableListOf<String>()
        override var onLog: ((String) -> Unit)? = null
        override val ready get() = running && dark != null
        override val settings = CameraSettings()
        override val capturedSize = ""
        override fun log(line: String) {
            logLines += line
        }
        override suspend fun start(aperture: Double?) {
            if (running) return
            starts += aperture
            onStart()
            running = true
        }
        override fun stop() {
            stops++
            running = false
        }
        override suspend fun measureDark() {}
        override suspend fun measure(): Pair<CameraReading?, String?> = null to CameraMeter.NOT_READY
    }

    private class FakeApprox(var result: ApproximateResult) : ApproximateSource {
        override fun load(): ApproximateResult = result
    }

    private val offline = object : HttpGet {
        override suspend fun get(url: String): HttpResponse = throw IOException("offline in tests")
    }

    private val identity = listOf(listOf(1.0, 0.0, 0.0), listOf(0.0, 1.0, 0.0), listOf(0.0, 0.0, 1.0))

    /** Another model's standard profile, trained on a fixed lens (no aperture recorded), as samsung/e2q. */
    private val standard = BuiltInProfiles(
        standard = "samsung/e2q",
        profiles = listOf(CameraProfile(model = "samsung/e2q", name = "Galaxy S24+", version = 1, matrix = identity)),
    )

    /** This phone's approximate profile, built for a variable lens locked at f/1.7. */
    private val approximate = CameraProfile(
        model = "test/variable", name = "Test (approximate: phone's own colour data)", version = 0,
        matrix = identity, camera = CameraSettings(iso = 50.0, aperture = 1.7),
    )

    private fun model(scope: TestScope, camera: CameraMeter, profiles: ProfileService): AppModel {
        val dir = tmp.newFolder()
        return AppModel(
            store = TVStore(File(dir, "TVs")),
            camera = camera,
            profiles = profiles,
            links = LinksService(cacheFile = File(dir, "patterns.json"), bundled = { null }, server = null, http = offline),
            reminders = FakeReminders(),
            prefs = Prefs(MemoryStore()),
            scope = scope,
        )
    }

    private fun profiles(): ProfileService =
        ProfileService(dir = File(tmp.newFolder(), "Profiles"), builtIn = standard, model = "test/variable", server = null, http = offline)

    @Test fun theApproximateProfileReplacesTheStandInBeforeTheApertureIsChosen() = runTest {
        val src = FakeApprox(ApproximateResult.NeedsPermission)
        val profiles = profiles().also { it.attachApproximate(src) }
        assertEquals(ProfileService.Source.STANDARD, profiles.source)
        val camera = FakeCamera()
        val app = model(this, camera, profiles)

        // The user tapped Start and allowed the camera (rememberCameraStarter), then startCamera runs.
        src.result = ApproximateResult.Ready(approximate)
        app.startCamera()

        assertEquals(ProfileService.Source.APPROXIMATE, profiles.source)
        assertEquals(listOf<Double?>(1.7), camera.starts)
        assertEquals(0, camera.stops)
    }

    @Test fun aSwapWhileStartingRestartsAtTheNewAperture() = runTest {
        val src = FakeApprox(ApproximateResult.NeedsPermission)
        val profiles = profiles().also { it.attachApproximate(src) }
        // The approximate profile only becomes available as the camera opens.
        val camera = FakeCamera(onStart = { src.result = ApproximateResult.Ready(approximate) })
        val app = model(this, camera, profiles)

        app.startCamera()

        assertEquals(ProfileService.Source.APPROXIMATE, profiles.source)
        val first = if (AppModel.isLabBuild) ApertureLock.defaultFNumber else null
        assertEquals(listOf(first, 1.7), camera.starts)
        assertEquals(1, camera.stops)
        assertEquals(true, camera.running)
    }

    @Test fun noRestartWhenTheApertureStaysTheSame() = runTest {
        val profiles = profiles().also { it.attachApproximate(FakeApprox(ApproximateResult.Ready(approximate))) }
        assertEquals(ProfileService.Source.APPROXIMATE, profiles.source)
        val camera = FakeCamera()
        val app = model(this, camera, profiles)

        app.startCamera()

        assertEquals(listOf<Double?>(1.7), camera.starts)
        assertEquals(0, camera.stops)
    }

    @Test fun theStandardProfileAloneLeavesTheApertureAlone() = runTest {
        val profiles = profiles().also { it.attachApproximate(FakeApprox(ApproximateResult.Unavailable("no colour data"))) }
        val camera = FakeCamera()
        val app = model(this, camera, profiles)

        app.startCamera()

        assertEquals(ProfileService.Source.STANDARD, profiles.source)
        val expected = if (AppModel.isLabBuild) ApertureLock.defaultFNumber else null
        assertEquals(listOf(expected), camera.starts)
        assertEquals(0, camera.stops)
    }
}
