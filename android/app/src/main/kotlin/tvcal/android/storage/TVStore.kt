package tvcal.android.storage

import kotlinx.serialization.SerializationStrategy
import tvcal.kit.CameraProfile
import tvcal.kit.SignalMode
import tvcal.kit.SimTV
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * Everything is kept as JSON files in `<root>/<UUID uppercase>/` (root = filesDir/TVs), written with
 * [encodeSorted] (pretty, sorted keys, ISO dates) and read leniently with [StoreDecoding]. No android.*
 * imports: plain java.io, so it is JVM-testable. All calls are synchronous file IO (small files); call from
 * the main thread like the Swift app does, or wrap in Dispatchers.IO.
 *
 * Files: tv.json, guide-<mode raw>.json, sim-sdr.json / sim-hdr.json (SimTV.State), log.md (appended text).
 */
class TVStore(
    /** filesDir/TVs. Created if missing. */
    val root: File,
    /** Clock for the export's `exported` date (tests can fix it). */
    private val now: () -> Instant = { Instant.now() },
) {
    init {
        runCatching { root.mkdirs() }
    }

    /** `<root>/<UUID uppercase>`, created if missing. */
    fun folder(tv: TVRecord): File {
        val d = File(root, tv.id.upper)
        runCatching { d.mkdirs() }
        return d
    }

    /** The file's text, or null when it is missing or unreadable (Swift `try? Data(contentsOf:)`). */
    private fun readText(file: File): String? = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()

    /** Writes [value] like the iPhone's JSONEncoder, atomically (Swift `.atomic`). Errors are ignored (`try?`). */
    private fun <T> write(serializer: SerializationStrategy<T>, value: T, file: File): Boolean {
        val text = runCatching { encodeSorted(serializer, value) }.getOrNull() ?: return false
        return writeAtomic(file, text)
    }

    /** Write to a temporary file beside [file], then move it over [file]. */
    private fun writeAtomic(file: File, text: String): Boolean {
        val dir = file.absoluteFile.parentFile ?: return false
        val tmp = File(dir, ".${file.name}.tmp")
        return try {
            dir.mkdirs()
            tmp.writeText(text, Charsets.UTF_8)
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            true
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            false
        }
    }

    // TVs

    /** Deletes folders without tv.json, reads every tv.json leniently, sorted by `created`. */
    fun loadTVs(): List<TVRecord> {
        val dirs = root.listFiles()?.toList() ?: emptyList()
        // A folder with no tv.json is left over from a deleted screen: tidy it away.
        for (d in dirs) {
            if (!File(d, "tv.json").exists()) runCatching { d.deleteRecursively() }
        }
        return dirs.mapNotNull { d ->
            val text = readText(File(d, "tv.json")) ?: return@mapNotNull null
            runCatching { StoreDecoding.tvRecord(StoreJson.parseToJsonElement(text)) }.getOrNull()
        }.sortedBy { it.created }
    }

    fun save(tv: TVRecord) {
        write(TVRecord.serializer(), tv, File(folder(tv), "tv.json"))
    }

    /** Removes the TV's whole folder. */
    fun delete(tv: TVRecord) {
        runCatching { File(root, tv.id.upper).deleteRecursively() }
    }

    // guided (per signal mode)

    private fun guideFile(tv: TVRecord, mode: SignalMode): File = File(folder(tv), "guide-${mode.rawValue}.json")

    /** guide-<mode>.json, or a fresh GuideState() when missing or unreadable. */
    fun guideState(tv: TVRecord, mode: SignalMode): GuideState {
        val text = readText(guideFile(tv, mode)) ?: return GuideState()
        return runCatching { StoreDecoding.guideState(StoreJson.parseToJsonElement(text)) }.getOrNull() ?: GuideState()
    }

    fun save(st: GuideState, tv: TVRecord, mode: SignalMode) {
        write(GuideState.serializer(), st, guideFile(tv, mode))
    }

    // demo TVs

    private fun simFile(tv: TVRecord, hdr: Boolean): File = File(folder(tv), if (hdr) "sim-hdr.json" else "sim-sdr.json")

    /** The demo TV's saved state (Swift's synthesized decoder: strict, null when anything is missing or bad). */
    fun simState(tv: TVRecord, hdr: Boolean): SimTV.State? {
        val text = readText(simFile(tv, hdr)) ?: return null
        return runCatching { StoreJson.decodeFromString(SimTV.State.serializer(), text) }.getOrNull()
    }

    fun saveSim(s: SimTV.State, tv: TVRecord, hdr: Boolean) {
        write(SimTV.State.serializer(), s, simFile(tv, hdr))
    }

    // readable log + export

    /** Appends UTF-8 text to log.md (creating it). */
    fun appendLog(tv: TVRecord, text: String) {
        val file = File(folder(tv), "log.md")
        runCatching { FileOutputStream(file, true).use { it.write(text.toByteArray(Charsets.UTF_8)) } }
    }

    /**
     * One file with everything about this TV, to share for troubleshooting: writes
     * `<outDir>/<name with "/" -> "-"> - TV Calibrator export.json` ([TVExport]) and returns it, or null on
     * failure. Only modes with sessions or a finder base are included in `guided`.
     * outDir is a FileProvider-shared cache folder (e.g. cacheDir/exports); appVersion = BuildConfig.VERSION_NAME;
     * phone = DeviceModel.identifier.
     */
    fun export(tv: TVRecord, profile: CameraProfile?, appVersion: String, phone: String, outDir: File): File? {
        val guided = LinkedHashMap<String, GuideState>()
        for (mode in SignalMode.entries) {
            val st = guideState(tv, mode)
            if (st.sessions.isNotEmpty() || st.finder.base != null) guided[mode.rawValue] = st
        }
        val e = TVExport(
            exported = now(),
            app = appVersion,
            tv = tv,
            phone = phone,
            profile = profile,
            guided = guided.toMap(),
        )
        val safe = tv.name.replace("/", "-")
        val file = File(outDir, "$safe - TV Calibrator export.json")
        val text = runCatching { encodeSorted(TVExport.serializer(), e) }.getOrNull() ?: return null
        // Swift ignores a failed write and still returns the URL; here a failed write returns null (the KDoc
        // contract), so the share sheet never offers a missing file.
        return if (writeAtomic(file, text)) file else null
    }
}
