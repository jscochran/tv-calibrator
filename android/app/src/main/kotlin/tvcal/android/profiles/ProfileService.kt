package tvcal.android.profiles

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import tvcal.android.camera.ApproximateResult
import tvcal.android.camera.ApproximateSource
import tvcal.android.camera.ProvisionalProfile
import tvcal.android.storage.PanelType
import tvcal.kit.BuiltInProfiles
import tvcal.kit.CameraProfile
import tvcal.kit.CameraSettings
import tvcal.kit.ProfileCatalog
import tvcal.kit.ProfileJson
import tvcal.kit.ProfileStats
import tvcal.kit.cfmt
import tvcal.kit.swiftRounded
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlin.math.max

/**
 * Picks this phone's camera profile (port of Profiles/ProfileService.swift; rules in the port map §6):
 * 1. imported.json (Lab), 2. this model's own profile (built in or Profiles/current.json, newer wins, ties →
 * built in), 3. the built-in standard profile, 4. Android: the approximate profile made from the phone's own
 * camera colour data (ProvisionalProfile, design §13.7), 5. none. `refresh()` asks the server for a newer one.
 * Files: `<dir>/catalog.json`, `<dir>/current.json`, `<dir>/imported.json` (dir = filesDir/Profiles), JSON via
 * tvcal.kit.ProfileJson. User-visible text says "phone" where the Swift says "iPhone".
 */
class ProfileService(
    /** filesDir/Profiles (created if missing). */
    private val dir: File,
    /** assets/builtin-profiles.json (no Android profiles yet: format 1, no standard, empty list). */
    val builtIn: BuiltInProfiles,
    /** DeviceModel.identifier, e.g. "samsung/e2q". */
    val model: String,
    /** Config.server; null = offline (status NotConfigured or Ready). */
    private val server: String? = Config.server,
    private val http: HttpGet = UrlConnectionHttp,
    private val now: () -> Instant = { Instant.now() },
) {
    sealed class Status {
        data object Checking : Status()
        data object Ready : Status()
        /** No profile for this model, and no standard profile to fall back on. */
        data object Unsupported : Status()
        /** Couldn't reach the server and there's no profile to use. */
        data class Offline(val why: String) : Status()
        data object NotConfigured : Status()
    }

    enum class Source {
        /** This model's profile, shipped inside the app. */
        BUILT_IN,
        /** This model's profile, downloaded from the server. */
        DOWNLOADED,
        /** Imported from a file (Lab build). */
        IMPORTED,
        /** Another model's profile, used because this model has none yet. */
        STANDARD,
        /** Android: made from this phone's own camera colour data (ProvisionalProfile), when nothing else exists. */
        APPROXIMATE,
    }

    var profile: CameraProfile? by mutableStateOf(null)
        private set
    var source: Source? by mutableStateOf(null)
        private set
    var status: Status by mutableStateOf(Status.Checking)
        private set
    var catalog: ProfileCatalog? by mutableStateOf(null)
        private set
    var lastChecked: Instant? by mutableStateOf(null)
        private set

    /** The profile came from a file, not the app or the server. */
    val isImported: Boolean get() = source == Source.IMPORTED
    /** Using the standard profile because this model has none of its own: readings are approximate. */
    val isStandard: Boolean get() = source == Source.STANDARD
    /** Android: using the profile made from the phone's own colour data (lowest priority). */
    val isApproximate: Boolean get() = source == Source.APPROXIMATE
    /** Whether meters compare the camera's settings with the profile's (off for standard and approximate). */
    val checksSettings: Boolean get() = !isStandard && !isApproximate

    /**
     * Android: no profile yet, but an approximate one can be made once the CAMERA permission is granted
     * (the Camera setup screen then lets the camera start). Set by choose() (agent (c)).
     */
    var approximateNeedsPermission: Boolean by mutableStateOf(false)
        private set

    /** Android: where the approximate profile comes from (TVCalApp attaches AndroidApproximateSource). */
    private var approximateSource: ApproximateSource? = null

    init {
        runCatching { dir.mkdirs() }
        catalog = load(ProfileCatalog.serializer(), "catalog.json")
        choose()
    }

    private fun <T> load(serializer: KSerializer<T>, name: String): T? {
        val file = File(dir, name)
        return try {
            if (!file.isFile) null else ProfileJson.decodeFromString(serializer, file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    private fun <T> save(serializer: KSerializer<T>, value: T, name: String) {
        try {
            writeAtomic(File(dir, name), ProfileJson.encodeToString(serializer, value).toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            // Swift: try? write — a failed save is ignored.
        }
    }

    /** This model's own profile, built in or downloaded, whichever is newer (ties → built in). */
    private val ownProfile: Pair<CameraProfile, Source>?
        get() {
            val shipped = builtIn.profile(model)
            val downloaded = load(CameraProfile.serializer(), "current.json")?.takeIf { it.model == model && it.isValid }
            return when {
                shipped != null && downloaded != null ->
                    if (downloaded.version > shipped.version) downloaded to Source.DOWNLOADED else shipped to Source.BUILT_IN
                shipped != null -> shipped to Source.BUILT_IN
                downloaded != null -> downloaded to Source.DOWNLOADED
                else -> null
            }
        }

    /** Picks the profile to use from what's on the phone. No network. */
    private fun choose() {
        val imported = load(CameraProfile.serializer(), "imported.json")?.takeIf { it.isValid }
        if (imported != null) {
            use(imported, Source.IMPORTED)
        } else {
            val own = ownProfile
            val standard = builtIn.standardProfile
            if (own != null) {
                use(own.first, own.second)
            } else if (standard != null) {
                use(standard, Source.STANDARD)
            } else {
                val approximate = loadApproximate()
                if (approximate != null) {
                    use(approximate, Source.APPROXIMATE)
                } else {
                    profile = null
                    source = null
                }
            }
        }
        // A trained or standard profile makes the approximate one (and its permission) irrelevant.
        if (source != Source.APPROXIMATE && profile != null) approximateNeedsPermission = false
        status = if (profile == null) Status.Checking else Status.Ready
    }

    /**
     * Android: the approximate step (lowest priority). Ready → its profile; NeedsPermission → null and
     * [approximateNeedsPermission]; Unavailable (or no source, or a source that throws) → null.
     */
    private fun loadApproximate(): CameraProfile? {
        val src = approximateSource ?: return null
        val result = try {
            src.load()
        } catch (e: Exception) {
            ApproximateResult.Unavailable(e.message ?: "error")
        }
        approximateNeedsPermission = result is ApproximateResult.NeedsPermission
        return (result as? ApproximateResult.Ready)?.profile?.takeIf { it.isValid }
    }

    /**
     * Android: attaches the approximate-profile source (lowest priority) and re-picks. Design §13.7: choose()
     * then falls back to it after imported, own and standard (agent (c) adds that step).
     */
    fun attachApproximate(source: ApproximateSource) {
        approximateSource = source
        choose()
    }

    /**
     * Android: asks the approximate source again (after the CAMERA permission was granted or the camera started)
     * and re-picks without disturbing a status a refresh already settled. Called by AppModel.startCamera.
     */
    fun reloadApproximate() {
        if (approximateSource == null) return
        // Trained profiles (imported, built in, downloaded) and the standard one always win.
        if (profile != null && !isApproximate) return
        val p = loadApproximate() ?: return      // NeedsPermission / Unavailable: the status stays as it is
        if (profile != p || source != Source.APPROXIMATE) use(p, Source.APPROXIMATE)
        status = Status.Ready
    }

    private fun use(p: CameraProfile, from: Source) {
        profile = p
        source = from
    }

    /**
     * Asks the server whether there's a profile for this model that the phone doesn't have yet (a model
     * newer than this app, or a retrained version). Never throws (except coroutine cancellation); whatever
     * happens, the phone keeps a usable profile. Calls run one at a time (a second tap waits for the first).
     */
    suspend fun refresh() = refreshLock.withLock { refreshLocked() }

    /** Serialises refresh(): overlapping launch / "Check again" / settings refreshes would race on the files. */
    private val refreshLock = Mutex()

    private suspend fun refreshLocked() {
        if (isImported) return
        val base = server ?: run {
            status = if (profile == null) Status.NotConfigured else Status.Ready
            return
        }
        if (profile == null) status = Status.Checking
        try {
            val index = http.get(Config.resolve(base, "index.json"))
            if (index.status != 200) throw BadServerResponse()
            val cat = ProfileJson.decodeFromString(ProfileCatalog.serializer(), index.body.toString(Charsets.UTF_8))
            val minFormat = cat.minAppFormat
            if (minFormat != null && minFormat > Config.catalogFormat) {
                if (profile == null) {
                    status = Status.Offline("The profile server needs a newer version of the app. Update TV Calibrator.")
                }
                return
            }
            catalog = cat
            withContext(Dispatchers.IO) { save(ProfileCatalog.serializer(), cat, "catalog.json") }
            lastChecked = now()
            val entry = cat.entry(model) ?: run {
                status = if (profile == null) Status.Unsupported else Status.Ready
                return
            }
            val own = ownProfile
            if (own != null && own.first.version >= entry.version) {
                // A save whose choose() was skipped (or any other drift) leaves memory behind the files: re-pick.
                if (profile != own.first || source != own.second) choose()
                status = Status.Ready
                return
            }
            val fileURL = try {
                Config.resolve(base, entry.file)
            } catch (e: IllegalArgumentException) {
                throw BadURL()
            }
            val presp = http.get(fileURL)
            if (presp.status != 200) throw BadServerResponse()
            val digest = sha256Hex(presp.body)
            if (digest != entry.sha256.lowercase()) throw CannotDecodeContent()
            val p = ProfileJson.decodeFromString(CameraProfile.serializer(), presp.body.toString(Charsets.UTF_8))
            if (p.model != model || !p.isValid) throw CannotDecodeContent()
            // Swift saves and chooses back to back; keep the pair together even if the caller is cancelled.
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { save(CameraProfile.serializer(), p, "current.json") }
                choose()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline or a bad download: keep using what the phone has.
            status = if (profile == null) {
                Status.Offline("Couldn't reach the profile server: ${describe(e)}")
            } else {
                Status.Ready
            }
        }
    }

    /**
     * Imports a profile file: one from the profile server, or a "camera profile.json" exported by the
     * lab tool on a computer (Camera profiles > Export); the UI reads the picked document's bytes. Used to
     * try a newly trained model before publishing it. Throws [ProfileImport.Problem] (message = text to show).
     */
    fun importProfile(data: ByteArray) {
        val p = ProfileImport.parse(data)
        if (p.model != model) {
            throw ProfileImport.Problem(
                "That profile is for ${if (p.name.isEmpty()) p.model else p.name}, not this phone ($model)."
            )
        }
        save(CameraProfile.serializer(), p, "imported.json")
        choose()
    }

    /** Stops using the imported profile, then refresh(). */
    suspend fun clearImported() {
        runCatching { File(dir, "imported.json").delete() }
        choose()
        refresh()
    }

    /** e.g. "Galaxy S24+ v1 (downloaded)"; "None" without a profile. */
    val profileLabel: String
        get() {
            val p = profile ?: return "None"
            // The approximate profile's name already says what it is (and it has no version worth showing).
            if (isApproximate) return if (p.name.isEmpty()) p.model else p.name
            val from = when (source) {
                Source.BUILT_IN -> "built in"
                Source.DOWNLOADED -> "downloaded"
                Source.IMPORTED -> "imported"
                Source.STANDARD -> "standard profile"
                Source.APPROXIMATE, null -> ""
            }
            return "${if (p.name.isEmpty()) p.model else p.name} v${p.version}" + (if (from.isEmpty()) "" else " ($from)")
        }

    /**
     * The phone's margin of error on this kind of screen, in dE, for the band around a score's marker:
     * about twice the profile's average error, and 3 when the profile is only approximate here. Null
     * without a profile.
     */
    fun marginDE(panel: PanelType): Double? {
        val p = profile ?: return null
        if (isApproximate) return ProvisionalProfile.MARGIN_DE
        if (isStandard || panel == PanelType.UNKNOWN) return 3.0
        val de = p.panel(panel.profileKey)?.deAvg ?: return 3.0
        return max(1.0, swiftRounded(de * 4) / 2)
    }

    /**
     * How exact this phone's readings are, for the results: a phone isn't a professional meter, and its
     * profile says how close it got to one in training. Null when there's nothing to say.
     */
    fun marginNote(panel: PanelType): String? {
        val p = profile ?: return null
        if (isApproximate) {
            return "This phone has no camera profile of its own yet and uses an approximate one made from its " +
                "camera's own colour data, so its readings are approximate: differences under about " +
                cfmt(ProvisionalProfile.MARGIN_DE, 0) + " dE may not be real."
        }
        if (isStandard) {
            return "This phone has no camera profile of its own yet and uses the standard one, so its readings are " +
                "approximate: differences under about 3 dE may not be real."
        }
        if (panel == PanelType.UNKNOWN) {
            return "The app doesn't know what kind of screen this is, so the readings are approximate. " +
                "Choose its screen type in Screen settings for accurate readings."
        }
        val de = p.panel(panel.profileKey)?.deAvg
            ?: return "This phone's camera profile hasn't been matched on a ${panel.label} screen yet, so the readings " +
                "are approximate: differences under about 3 dE may not be real."
        // Typical readings land within about twice the average error; round to half a dE, at least 1.
        val margin = max(1.0, swiftRounded(de * 4) / 2)
        return "A phone isn't a professional meter. On ${panel.label} screens this one's readings typically land " +
            "within about " + cfmt(margin, if (margin % 1.0 == 0.0) 0 else 1) +
            " dE of one, so smaller differences may not be real."
    }

    /** Short text for the settings and camera screens (and AppModel.cameraBlocker when there's no profile). */
    val summary: String
        get() {
            // Android: no profile yet, but the approximate one is only waiting for the CAMERA permission.
            if (profile == null && approximateNeedsPermission && status !is Status.Offline) {
                return "This phone ($model) doesn't have its own camera profile yet. Start the camera to use an " +
                    "approximate one made from its camera's own colour data."
            }
            return statusSummary
        }

    private val statusSummary: String
        get() = when (val s = status) {
            Status.Checking -> "Checking for this phone's camera profile…"
            Status.Ready -> {
                val p = profile
                if (p == null) {
                    "Ready."
                } else {
                    val name = if (p.name.isEmpty()) p.model else p.name
                    if (isApproximate) {
                        "This phone ($model) doesn't have its own camera profile yet, so it uses an approximate one " +
                            "made from its camera's own colour data. Readings are approximate. When a profile for " +
                            "this model is ready, it downloads automatically."
                    } else if (isStandard) {
                        "This phone ($model) doesn't have its own camera profile yet, so it uses the standard one ($name). " +
                            "Readings are approximate. When a profile for this model is ready, it downloads automatically."
                    } else {
                        "$name, profile v${p.version}${if (isImported) " (imported)" else ""} - accuracy: ${p.accuracyWord}"
                    }
                }
            }
            Status.Unsupported ->
                "This phone ($model) isn't supported yet: its camera hasn't been matched to the calibrator. You can still try the demo."
            is Status.Offline -> s.why
            Status.NotConfigured -> "This build has no camera profiles and no profile server (PROFILE_SERVER)."
        }

    // Stand-ins for the URLErrors the Swift throws; their messages are what the user sees.
    private class BadServerResponse : IOException("The server returned a bad response.")
    private class BadURL : IOException("unsupported URL")
    private class CannotDecodeContent : IOException("The downloaded profile couldn't be checked.")

    companion object {
        /** Decodes builtin-profiles.json; BuiltInProfiles() when null or unreadable. */
        fun builtIn(bytes: ByteArray?): BuiltInProfiles {
            if (bytes == null) return BuiltInProfiles()
            return try {
                ProfileJson.decodeFromString(BuiltInProfiles.serializer(), bytes.toString(Charsets.UTF_8))
            } catch (e: Exception) {
                BuiltInProfiles()
            }
        }

        /** The text after "Couldn't reach the profile server: " (Swift error.localizedDescription). */
        internal fun describe(e: Throwable): String = when (e) {
            is UnknownHostException, is ConnectException, is NoRouteToHostException ->
                "The Internet connection appears to be offline."
            is SocketTimeoutException -> "The request timed out."
            is SerializationException, is IllegalArgumentException ->
                "The data couldn't be read because it isn't in the correct format."
            else -> e.message?.takeIf { it.isNotBlank() } ?: "The network connection was lost."
        }

        /**
         * Writes to a unique temporary file beside the target and renames it over the target (Swift `.atomic`).
         * The target is never deleted first: an atomic move replaces an existing file, so concurrent writers
         * each leave a complete file and the last move wins.
         */
        internal fun writeAtomic(file: File, bytes: ByteArray) {
            val parent = file.absoluteFile.parentFile
            parent?.mkdirs()
            val tmp = File.createTempFile(file.name + ".", ".tmp", parent)
            try {
                tmp.writeBytes(bytes)
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }
    }
}

/** Reads profile files in either format (a server CameraProfile, or the lab tool's export). */
object ProfileImport {
    class Problem(val text: String) : Exception(text)

    private val plainJson = Json

    /** Throws [Problem] with the Swift texts ("That isn't a TV Calibrator camera profile." etc.). */
    fun parse(data: ByteArray): CameraProfile {
        val text = data.toString(Charsets.UTF_8)
        val direct = try {
            ProfileJson.decodeFromString(CameraProfile.serializer(), text)
        } catch (e: Exception) {
            null
        }
        if (direct != null && direct.isValid) return direct

        // The lab tool's export: {"tvcal_camera_profile": 1, "model", "name", "M", "camera", "stats", "sessions"}
        val element = try {
            plainJson.parseToJsonElement(text)
        } catch (e: Exception) {
            // Swift: JSONSerialization throws its own error here.
            throw Problem("The data couldn't be read because it isn't in the correct format.")
        }
        val obj = element as? JsonObject
        val model = obj?.get("model").stringValue()
        if (obj == null || !obj.containsKey("tvcal_camera_profile") || model == null) {
            throw Problem("That isn't a TV Calibrator camera profile.")
        }
        val m = obj["M"].matrixValue()
        if (m == null || m.size != 3 || !m.all { it.size == 3 }) {
            throw Problem("That profile has no trained matrix yet. Train it on at least one TV first.")
        }
        val cam = obj["camera"] as? JsonObject ?: JsonObject(emptyMap())
        val stats = obj["stats"] as? JsonObject ?: JsonObject(emptyMap())
        val tvs = (stats["tvs"] as? JsonObject)?.keys?.sorted() ?: emptyList()
        val settings = CameraSettings(
            iso = cam["iso"].numberValue(),
            format = cam["format"].stringValue(),
            lens = cam["lens"].stringValue(),
            aperture = cam["aperture"].numberValue(),
        )
        val p = CameraProfile(
            model = model,
            name = obj["name"].stringValue() ?: "",
            version = 0,
            matrix = m,
            camera = settings,
            stats = ProfileStats(deAvg = stats["de_avg"].numberValue(), unseen = stats["unseen"].numberValue(), tvs = tvs),
        )
        if (!p.isValid) throw Problem("That profile's matrix isn't usable.")
        return p
    }

    /** Swift `as? String`. */
    private fun JsonElement?.stringValue(): String? {
        val prim = this as? JsonPrimitive ?: return null
        return if (prim.isString) prim.content else null
    }

    /** Swift `(x as? NSNumber)?.doubleValue`: JSON numbers (and booleans, which bridge to NSNumber). */
    private fun JsonElement?.numberValue(): Double? {
        val prim = this as? JsonPrimitive ?: return null
        if (prim.isString) return null
        return when (prim.content) {
            "true" -> 1.0
            "false" -> 0.0
            else -> prim.doubleOrNull
        }
    }

    /** Swift `as? [[Double]]`: every row an array, every cell a number; null otherwise. */
    private fun JsonElement?.matrixValue(): List<List<Double>>? {
        val rows = this as? JsonArray ?: return null
        return rows.map { row ->
            val cells = row as? JsonArray ?: return null
            cells.map { cell -> cell.numberValue() ?: return null }
        }
    }
}
