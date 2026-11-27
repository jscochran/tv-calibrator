package tvcal.android

import android.app.Application
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import tvcal.android.app.AppModel
import tvcal.android.app.Chime
import tvcal.android.app.Notifications
import tvcal.android.app.Prefs
import tvcal.android.app.SuccessSound
import tvcal.android.camera.AndroidApproximateSource
import tvcal.android.camera.CameraMeter
import tvcal.android.camera.CameraMeters
import tvcal.android.profiles.DeviceModel
import tvcal.android.profiles.LinksService
import tvcal.android.profiles.ProfileService
import tvcal.android.storage.TVStore
import java.io.File

/**
 * TV Calibrator for Android (a port of TV Calibrator by Slidebean). Owns the app-wide singletons, each
 * created on first use. Compose reaches them through [LocalApp]: `val app = LocalApp.current; app.model`.
 */
class TVCalApp : Application() {
    /** App-wide scope for long work (sweeps, live loops, refreshes). File IO switches to Dispatchers.IO. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val prefs: Prefs by lazy { Prefs.open(this) }
    val store: TVStore by lazy { TVStore(File(filesDir, "TVs")) }
    /** The camera meter (Camera2Meter once P4 lands; see CameraMeters.create). */
    val camera: CameraMeter by lazy { CameraMeters.create(this, prefs) }
    val profiles: ProfileService by lazy {
        ProfileService(
            dir = File(filesDir, "Profiles"),
            builtIn = ProfileService.builtIn(readAsset("builtin-profiles.json")),
            model = DeviceModel.identifier,
        ).also { it.attachApproximate(AndroidApproximateSource(this, DeviceModel.identifier, DeviceModel.name)) }
    }
    val links: LinksService by lazy {
        LinksService(cacheFile = File(filesDir, "patterns.json"), bundled = { readAsset("patterns.json") })
    }
    val notifications: Notifications by lazy { Notifications(this, prefs) }
    val chime: Chime by lazy { Chime(prefs, SuccessSound(this)) }
    val model: AppModel by lazy { AppModel(store, camera, profiles, links, notifications, prefs, scope) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    /** An asset's bytes, or null if it's missing. */
    fun readAsset(name: String): ByteArray? = runCatching { assets.open(name).use { it.readBytes() } }.getOrNull()

    companion object {
        lateinit var instance: TVCalApp
            private set
    }
}

/** The application object, provided at the root of setContent. */
val LocalApp = staticCompositionLocalOf<TVCalApp> { error("LocalApp is not provided") }
