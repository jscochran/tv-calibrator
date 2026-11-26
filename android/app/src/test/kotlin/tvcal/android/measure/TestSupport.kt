package tvcal.android.measure

import kotlinx.coroutines.CoroutineScope
import tvcal.android.app.AppModel
import tvcal.android.app.MemoryStore
import tvcal.android.app.Prefs
import tvcal.android.app.Reminders
import tvcal.android.camera.UnavailableCameraMeter
import tvcal.android.profiles.HttpGet
import tvcal.android.profiles.HttpResponse
import tvcal.android.profiles.LinksService
import tvcal.android.profiles.ProfileService
import tvcal.android.storage.TVStore
import tvcal.kit.BuiltInProfiles
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Records the reminders the guided flow asks for. */
class FakeReminders : Reminders {
    val scheduled = mutableListOf<Pair<String, String>>()
    val cancelled = mutableListOf<String>()
    override fun scheduleRecheck(tvID: String, tvName: String) {
        scheduled += tvID to tvName
    }

    override fun cancelRecheck(tvID: String) {
        cancelled += tvID
    }
}

/** Never online: these tests don't refresh profiles or links. */
private val offline = object : HttpGet {
    override suspend fun get(url: String): HttpResponse = throw IOException("offline in tests")
}

/** An AppModel on a temporary folder, no camera, no profile server, the given scope as the app scope. */
fun testModel(scope: CoroutineScope, dir: File = Files.createTempDirectory("tvcal-test").toFile()): Pair<AppModel, FakeReminders> {
    val reminders = FakeReminders()
    val model = AppModel(
        store = TVStore(File(dir, "TVs")),
        camera = UnavailableCameraMeter(),
        profiles = ProfileService(
            dir = File(dir, "Profiles"),
            builtIn = BuiltInProfiles(),
            model = "test/device",
            server = null,
            http = offline,
        ),
        links = LinksService(cacheFile = File(dir, "patterns.json"), bundled = { null }, server = null, http = offline),
        reminders = reminders,
        prefs = Prefs(MemoryStore()),
        scope = scope,
    )
    return model to reminders
}
