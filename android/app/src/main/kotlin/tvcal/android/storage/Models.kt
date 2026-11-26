package tvcal.android.storage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import tvcal.kit.CameraProfile
import tvcal.kit.ColourMenu
import tvcal.kit.Measurements
import tvcal.kit.Vec3
import java.time.Instant
import java.util.UUID
import kotlin.math.floor

// Port of the models in Storage/TVStore.swift. Swift structs are value types: these are immutable data
// classes, changed with copy(). JSON field names and shapes are exactly the iPhone app's.

/**
 * One TV the user calibrates. File: TVs/<UUID uppercase>/tv.json
 * `{brand, colourMenu, colourMenuKnown, created, id, isDemo, multipoint, multipointKnown, name, panel}`.
 */
@Serializable
data class TVRecord(
    @Serializable(with = UUIDSerializer::class) val id: UUID = UUID.randomUUID(),
    val name: String,
    val brand: Brand = Brand.OTHER,
    val multipoint: Multipoint = Multipoint.TWENTY,
    val colourMenu: ColourMenu = ColourMenu.HSL,
    /** False until the user has told us what the TV's menus look like ("Not sure" when adding the TV). */
    val multipointKnown: Boolean = true,
    val colourMenuKnown: Boolean = true,
    /** The kind of screen, which picks the camera's conversion. */
    val panel: PanelType = PanelType.UNKNOWN,
    /** A simulated TV for trying the app without a real one. */
    val isDemo: Boolean = false,
    @Serializable(with = InstantSerializer::class) val created: Instant = Instant.now(),
)

/** The guided steps, in order. FINE (multipoint fine-tune) is reached from the Check step. JSON: raw value. */
@Serializable
enum class GuideStep(val rawValue: String) {
    @SerialName("setup") SETUP("setup"),
    @SerialName("before") BEFORE("before"),
    @SerialName("bright") BRIGHT("bright"),
    @SerialName("dark") DARK("dark"),
    @SerialName("check") CHECK("check"),
    @SerialName("colours") COLOURS("colours"),
    @SerialName("after") AFTER("after"),
    @SerialName("fine") FINE("fine");

    val id: String get() = rawValue
    val label: String
        get() = when (this) {
            SETUP -> "Set up"
            BEFORE -> "Before"
            BRIGHT -> "Bright end"
            DARK -> "Dark end"
            CHECK -> "Check"
            COLOURS -> "Colours"
            AFTER -> "After"
            FINE -> "Fine-tune"
        }

    /** The next step in [stepper], or null after the last (and for FINE). */
    val next: GuideStep?
        get() {
            val i = stepper.indexOf(this)
            return if (i < 0 || i + 1 >= stepper.size) null else stepper[i + 1]
        }

    companion object {
        /** The steps shown in the step bar, in order (no FINE). */
        val stepper: List<GuideStep> = listOf(SETUP, BEFORE, BRIGHT, DARK, CHECK, COLOURS, AFTER)
        fun from(raw: String): GuideStep? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** JSON `{step, done[], colours[], limits[], colour?, finePid?}`. */
@Serializable
data class GuideProgress(
    val step: GuideStep = GuideStep.SETUP,
    val done: List<GuideStep> = emptyList(),
    /** Colours finished (or marked "as close as it gets"). */
    val colours: List<String> = emptyList(),
    /** Steps and colours that hit the TV's limit. */
    val limits: List<String> = emptyList(),
    val colour: String? = null,
    val finePid: String? = null,
)

/** JSON `{started, before?, after?, finished?}`. */
@Serializable
data class GuidedSession(
    @Serializable(with = InstantSerializer::class) val started: Instant = Instant.now(),
    val before: Measurements? = null,
    val after: Measurements? = null,
    @Serializable(with = InstantSerializer::class) val finished: Instant? = null,
)

/** JSON `{base?, nudged?, overrides{pid: Int}}`. overrides may hold "_skip": 0 (finder skipped). */
@Serializable
data class FinderData(
    val base: Measurements? = null,
    val nudged: Measurements? = null,
    /** Grey id -> TV control the user found by hand. */
    val overrides: Map<String, Int> = emptyMap(),
)

/**
 * One TV's guided calibration for one signal mode. File: TVs/<UUID>/guide-<sdr|hdr10|hdr10p|dv>.json
 * `{check?, finder, progress, sessions[]}`.
 */
@Serializable
data class GuideState(
    val sessions: List<GuidedSession> = emptyList(),
    /** The latest Check step measurement, so its result is still there after leaving the step. */
    val check: Measurements? = null,
    val finder: FinderData = FinderData(),
    val progress: GuideProgress = GuideProgress(),
) {
    /** Swift tuple `reference: (whiteY, basis)`. */
    data class Reference(val whiteY: Double?, val basis: List<Vec3>?)

    /**
     * This TV's own white and primaries, from the latest After/Before check (newest session first, After
     * before Before): whiteY = first "W0".y; basis = first measurement with all of RED, GREEN, BLUE.
     */
    val reference: Reference
        get() {
            val checks = sessions.asReversed().flatMap { listOfNotNull(it.after, it.before) }
            val white = checks.firstNotNullOfOrNull { it["W0"]?.y }
            val basis = checks.asSequence()
                .map { m -> listOf("RED", "GREEN", "BLUE").mapNotNull { m[it] } }
                .firstOrNull { it.size == 3 }
            return Reference(white, basis)
        }
}

/** The troubleshooting export: `{exported, app, tv, phone, profile?, guided{modeRaw: GuideState}}`. */
@Serializable
data class TVExport(
    @Serializable(with = InstantSerializer::class) val exported: Instant,
    /** App version (BuildConfig.VERSION_NAME). */
    val app: String,
    val tv: TVRecord,
    /** DeviceModel.identifier. */
    val phone: String,
    val profile: CameraProfile? = null,
    val guided: Map<String, GuideState>,
)

/**
 * Swift's hand-written lenient decoders: every field reads what's there and takes its default when it is
 * missing OR bad (wrong type, unknown enum value, a nested object that doesn't decode). Use these to read
 * the saved files, never plain StoreJson.decodeFromString (which rejects a whole file over one bad field).
 */
object StoreDecoding {
    /**
     * TVRecord: `id` and `name` are required (null if missing/bad); brand, multipoint, colourMenu and panel are
     * fully lenient (Swift `try?`). multipointKnown, colourMenuKnown, isDemo and created are Swift
     * `try c.decodeIfPresent(...) ?? default`: missing or null takes the default, but a value of the wrong
     * type fails the whole record, exactly like the iPhone app.
     */
    fun tvRecord(e: JsonElement): TVRecord? {
        val o = e as? JsonObject ?: return null
        val id = uuid(o["id"]) ?: return null
        val name = string(o["name"]) ?: return null
        val multipointKnown = o.present("multipointKnown")?.let { bool(it) ?: return null } ?: true
        val colourMenuKnown = o.present("colourMenuKnown")?.let { bool(it) ?: return null } ?: true
        val isDemo = o.present("isDemo")?.let { bool(it) ?: return null } ?: false
        val created = o.present("created")?.let { instant(it) ?: return null } ?: Instant.now()
        return TVRecord(
            id = id,
            name = name,
            brand = o.present("brand")?.let { string(it) }?.let { Brand.from(it) } ?: Brand.OTHER,
            multipoint = o.present("multipoint")?.let { int(it) }?.let { Multipoint.from(it) } ?: Multipoint.TWENTY,
            colourMenu = o.present("colourMenu")?.let { string(it) }?.let { ColourMenu.from(it) } ?: ColourMenu.HSL,
            multipointKnown = multipointKnown,
            colourMenuKnown = colourMenuKnown,
            panel = o.present("panel")?.let { string(it) }?.let { PanelType.from(it) } ?: PanelType.UNKNOWN,
            isDemo = isDemo,
            created = created,
        )
    }

    /** A file that isn't a JSON object reads as a fresh GuideState() (Swift: the decode fails, `?? GuideState()`). */
    fun guideState(e: JsonElement): GuideState {
        val o = e as? JsonObject ?: return GuideState()
        return GuideState(
            // Each session must be an object; one that isn't loses the whole list (Swift array decoding).
            sessions = o.present("sessions")?.let { s -> list(s) { guidedSessionOrNull(it) } } ?: emptyList(),
            check = o.present("check")?.let { measurements(it) },
            finder = o.present("finder")?.let { finderDataOrNull(it) } ?: FinderData(),
            progress = o.present("progress")?.let { guideProgressOrNull(it) } ?: GuideProgress(),
        )
    }

    fun guideProgress(e: JsonElement): GuideProgress = guideProgressOrNull(e) ?: GuideProgress()
    fun guidedSession(e: JsonElement): GuidedSession = guidedSessionOrNull(e) ?: GuidedSession()
    fun finderData(e: JsonElement): FinderData = finderDataOrNull(e) ?: FinderData()

    // Swift `init(from:)` needs a keyed container: anything but an object fails (null here), and the
    // caller then takes its default.

    private fun guideProgressOrNull(e: JsonElement): GuideProgress? {
        val o = e as? JsonObject ?: return null
        return GuideProgress(
            step = o.present("step")?.let { string(it) }?.let { GuideStep.from(it) } ?: GuideStep.SETUP,
            done = o.present("done")?.let { d -> list(d) { s -> string(s)?.let { GuideStep.from(it) } } } ?: emptyList(),
            colours = o.present("colours")?.let { c -> list(c) { string(it) } } ?: emptyList(),
            limits = o.present("limits")?.let { l -> list(l) { string(it) } } ?: emptyList(),
            colour = o.present("colour")?.let { string(it) },
            finePid = o.present("finePid")?.let { string(it) },
        )
    }

    private fun guidedSessionOrNull(e: JsonElement): GuidedSession? {
        val o = e as? JsonObject ?: return null
        return GuidedSession(
            started = o.present("started")?.let { instant(it) } ?: Instant.now(),
            before = o.present("before")?.let { measurements(it) },
            after = o.present("after")?.let { measurements(it) },
            finished = o.present("finished")?.let { instant(it) },
        )
    }

    private fun finderDataOrNull(e: JsonElement): FinderData? {
        val o = e as? JsonObject ?: return null
        return FinderData(
            base = o.present("base")?.let { measurements(it) },
            nudged = o.present("nudged")?.let { measurements(it) },
            overrides = o.present("overrides")?.let { intMap(it) } ?: emptyMap(),
        )
    }

    // Field readers, each null when the value is missing or of the wrong type (Swift's typeMismatch).

    /** The value for [key], with an explicit JSON null treated as missing (Swift `decodeIfPresent`). */
    private fun JsonObject.present(key: String): JsonElement? = this[key]?.takeUnless { it is JsonNull }

    private fun string(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun bool(e: JsonElement): Boolean? =
        (e as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.content?.toBooleanStrictOrNull()

    /** A JSON number that is a whole Int ("3" or "3.0"); strings and booleans are not Ints. */
    private fun int(e: JsonElement): Int? {
        val p = (e as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull } ?: return null
        p.content.toIntOrNull()?.let { return it }
        val d = p.content.toDoubleOrNull() ?: return null
        return if (d.isFinite() && d == floor(d) && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE) d.toInt() else null
    }

    /** Swift `UUID(uuidString:)`: the 8-4-4-4-12 hex form only (java's fromString accepts more). */
    private fun uuid(e: JsonElement?): UUID? =
        string(e)?.takeIf { UUID_FORM.matches(it) }?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private val UUID_FORM = Regex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")

    private fun instant(e: JsonElement): Instant? = string(e)?.let { parseIso(it) }

    private fun measurements(e: JsonElement): Measurements? =
        runCatching { StoreJson.decodeFromJsonElement(Measurements.serializer(), e) }.getOrNull()

    /** A JSON array whose every element reads; one bad element fails the whole array (null). */
    private inline fun <T> list(e: JsonElement, item: (JsonElement) -> T?): List<T>? {
        val a = e as? JsonArray ?: return null
        val out = ArrayList<T>(a.size)
        for (x in a) out.add(item(x) ?: return null)
        return out.toList()
    }

    /** Swift `[String: Int]`: an object whose every value is an Int, else null. */
    private fun intMap(e: JsonElement): Map<String, Int>? {
        val o = e as? JsonObject ?: return null
        val out = LinkedHashMap<String, Int>()
        for ((k, v) in o) out[k] = int(v) ?: return null
        return out.toMap()
    }
}
