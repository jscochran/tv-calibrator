package tvcal.android.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import tvcal.android.BuildConfig
import tvcal.android.camera.ApertureLock
import tvcal.android.camera.CameraMeter
import tvcal.android.camera.CameraXYZMeter
import tvcal.android.profiles.LinksService
import tvcal.android.profiles.ProfileService
import tvcal.android.storage.Brand
import tvcal.android.storage.Multipoint
import tvcal.android.storage.TVRecord
import tvcal.android.storage.TVStore
import tvcal.android.storage.upper
import tvcal.kit.ColourMenu
import tvcal.kit.Meter
import tvcal.kit.PatternSequence
import tvcal.kit.ShownPatch
import tvcal.kit.SimLiveMeter
import tvcal.kit.SimMeter
import tvcal.kit.SimTV

/** App version for Settings › About ("v (build)") and the export. */
object AppInfo {
    val version: String get() = BuildConfig.VERSION_NAME
    val build: Int get() = BuildConfig.VERSION_CODE
}

/**
 * Root state (Swift AppModel): the TV list plus the app-wide services. One instance, created by TVCalApp;
 * reach it in Compose with `LocalApp.current.model`. Must be used from the main thread.
 */
class AppModel(
    val store: TVStore,
    val camera: CameraMeter,
    val profiles: ProfileService,
    val links: LinksService,
    /** Re-check reminders (Notifications on the phone; a fake in tests). */
    val reminders: Reminders,
    val prefs: Prefs,
    /** App-wide scope: SupervisorJob + Dispatchers.Main.immediate. Long work (sweeps, live loops) runs here. */
    val scope: CoroutineScope,
) {
    companion object {
        /** Swift `#if LAB_MODE`. */
        val isLabBuild: Boolean get() = BuildConfig.LAB_MODE
    }

    /** Sorted by `created` (as loaded); add() appends. Immutable list, replaced on every change. */
    var tvs: List<TVRecord> by mutableStateOf(emptyList())
        private set

    /** Demo SimTVs by "<UUID uppercase>-<hdr>" (Swift key "\(uuid)-\(hdr)", hdr = "true"/"false"). */
    private val sims = HashMap<String, SimTV>()

    init {
        tvs = store.loadTVs()
    }

    // TVs

    fun add(tv: TVRecord) {
        store.save(tv)
        tvs = tvs + tv
    }

    fun update(tv: TVRecord) {
        store.save(tv)
        val i = tvs.indexOfFirst { it.id == tv.id }
        if (i >= 0) tvs = tvs.toMutableList().also { it[i] = tv }
    }

    /** Cancels the TV's re-check reminder, deletes its folder, removes it from [tvs]. */
    fun delete(tv: TVRecord) {
        reminders.cancelRecheck(tv.id.upper)
        store.delete(tv)
        tvs = tvs.filter { it.id != tv.id }
    }

    /** Adds "Demo Screen" / "Demo Screen N" (samsung, 20 points, RGB sliders, isDemo) and returns it. */
    fun addDemoTV(): TVRecord {
        val n = tvs.count { it.isDemo }
        val tv = TVRecord(
            name = if (n == 0) "Demo Screen" else "Demo Screen ${n + 1}",
            brand = Brand.SAMSUNG,
            multipoint = Multipoint.TWENTY,
            colourMenu = ColourMenu.RGB_SLIDERS,
            isDemo = true,
        )
        add(tv)
        return tv
    }

    /** Profiles refresh, then links refresh (Swift's launch `.task`). Call once from the activity. */
    suspend fun refreshAll() {
        profiles.refresh()
        links.refresh()
    }

    // demo TVs

    /**
     * The demo TV's simulator (cached). Seed = uuid bytes 0..2 as (b0 shl 16) or (b1 shl 8) or b2 (unsigned).
     * When there's no saved state: s = s.copy(p20Shift = 0.10) and save it.
     */
    fun simTV(tv: TVRecord, hdr: Boolean): SimTV {
        val key = "${tv.id.upper}-$hdr"
        sims[key]?.let { return it }
        // Swift tv.id.uuid.0/.1/.2: the three most significant bytes of the UUID.
        val seed = ((tv.id.mostSignificantBits ushr 40) and 0xFFFFFFL).toULong()
        val saved = store.simState(tv, hdr)
        val s = SimTV(hdr = hdr, state = saved, seed = seed)
        if (saved == null) {
            s.s = s.s.copy(p20Shift = 0.10)          // demo: the multipoint controls act 10% above their label
            store.saveSim(s.s, tv, hdr)
        }
        sims[key] = s
        return s
    }

    /** Demo: "press the remote" (changes in clicks), then save the sim state. */
    fun demoApply(tv: TVRecord, hdr: Boolean, changes: Map<String, Int>) {
        val s = simTV(tv, hdr)
        s.apply(changes)
        store.saveSim(s.s, tv, hdr)
    }

    // meters

    /**
     * Why the camera can't measure yet, or null when it's ready. Order: no profile → profiles.summary;
     * `!camera.available` → CameraMeter.UNAVAILABLE (Android, until P4); not running → "Start the camera
     * (Camera setup)."; no dark level → "Measure the camera's darkness once (Camera setup).".
     */
    val cameraBlocker: String?
        get() {
            if (profiles.profile == null) return profiles.summary
            if (!camera.available) return CameraMeter.UNAVAILABLE
            if (!camera.running) return "Start the camera (Camera setup)."
            if (camera.dark == null) return "Measure the camera's darkness once (Camera setup)."
            return null
        }

    /** Starts the camera at the profile's aperture (or ApertureLock.defaultFNumber in the Lab build / no profile). */
    suspend fun startCamera() {
        // Readings must be taken the way the profile was trained: at the aperture it records, or with the
        // aperture left alone if it records none. The Lab build always locks one (the profile's, or the
        // default f-stop) on variable-aperture lenses: an unlocked aperture can change how much light the
        // camera collects between training sessions. Fixed-aperture phones ignore it.
        val recorded = profiles.profile?.camera?.aperture
        if (recorded != null) {
            camera.start(aperture = recorded)
        } else if (isLabBuild || profiles.profile == null) {
            camera.start(aperture = ApertureLock.defaultFNumber)
        } else {
            camera.start(aperture = null)
        }
    }

    /**
     * A meter that follows a pattern video. Demo: SimMeter(simTV, sequence, playAt = 2.0, realTime = 0.1,
     * seed = unixSeconds % 100000). Real: null while [cameraBlocker] != null, else CameraXYZMeter.
     */
    fun videoMeter(tv: TVRecord, hdr: Boolean, sequence: PatternSequence): Meter? {
        if (tv.isDemo) {
            return SimMeter(
                tv = simTV(tv, hdr), sequence = sequence, playAt = 2.0, realTime = 0.1,
                seed = ((System.currentTimeMillis() / 1000) % 100_000).toULong(),
            )
        }
        return cameraXYZMeter(tv)
    }

    /** A meter for the live guided steps. Demo: SimLiveMeter(simTV, shown). Real: as [videoMeter]. */
    fun liveMeter(tv: TVRecord, hdr: Boolean, shown: ShownPatch): Meter? {
        if (tv.isDemo) return SimLiveMeter(tv = simTV(tv, hdr), shown = shown)
        return cameraXYZMeter(tv)
    }

    private fun cameraXYZMeter(tv: TVRecord): Meter? {
        if (cameraBlocker != null) return null
        val p = profiles.profile ?: return null
        return CameraXYZMeter(camera = camera, profile = p, checkSettings = !profiles.isStandard, panel = tv.panel.profileKey)
    }
}
