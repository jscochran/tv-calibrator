package tvcal.android.profiles

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import tvcal.kit.SignalMode
import java.io.File
import java.net.URI

/**
 * Where users get the pattern videos (patterns.json on the server, a copy in assets). URLs are Strings
 * (open with Intent.ACTION_VIEW); only https URLs are ever returned.
 * JSON `{format, channel?, help?, newsletter?, explainer?, instagram?, playlists{sdr,hdr10}, downloads{hdr10p,dv}, videos{file title: url}}`.
 */
@Serializable
data class PatternLinks(
    val format: Int = 1,
    /** The YouTube channel or a help page. */
    val channel: String? = null,
    val help: String? = null,
    /** The Untangled newsletter's sign-up page, offered when a calibration is finished. */
    val newsletter: String? = null,
    /** The Slidebean video that explains calibration and this app. */
    val explainer: String? = null,
    /** Slidebean's Instagram, for questions (Settings › About). */
    val instagram: String? = null,
    /** Signal mode ("sdr", "hdr10") -> playlist URL. */
    val playlists: Map<String, String> = emptyMap(),
    /** Signal mode ("hdr10p", "dv") -> download URL. */
    val downloads: Map<String, String> = emptyMap(),
    /** Video title (pattern-pack file name, e.g. "SDR 01 - White (bright end)") -> YouTube URL. */
    val videos: Map<String, String> = emptyMap(),
) {
    fun playlist(mode: SignalMode): String? = httpsOrNull(playlists[mode.rawValue])
    fun download(mode: SignalMode): String? = httpsOrNull(downloads[mode.rawValue])
    fun video(file: String): String? = httpsOrNull(videos[file])
    val helpURL: String? get() = httpsOrNull(help) ?: httpsOrNull(channel)
    /** null until its link is filled in (the app then leaves the video out). */
    val explainerURL: String? get() = httpsOrNull(explainer)
    /** The server's link, or "https://www.instagram.com/slidebean/". */
    val instagramURL: String? get() = httpsOrNull(instagram) ?: INSTAGRAM
    /** The server's link, or the built-in Untangled URL. */
    val newsletterURL: String? get() = httpsOrNull(newsletter) ?: NEWSLETTER

    companion object {
        private const val INSTAGRAM = "https://www.instagram.com/slidebean/"
        private const val NEWSLETTER =
            "https://untangled.slidebean.com/?utm_source=tv-calibrator&utm_medium=app&utm_campaign=calibration-complete"

        /** Lenient decode (missing or bad fields take defaults); null when not a JSON object. */
        fun decode(data: ByteArray): PatternLinks? {
            val obj = try {
                Json.parseToJsonElement(data.toString(Charsets.UTF_8)) as? JsonObject
            } catch (e: Exception) {
                null
            } ?: return null
            return PatternLinks(
                format = obj["format"].intValue() ?: 1,
                channel = obj["channel"].stringValue(),
                help = obj["help"].stringValue(),
                newsletter = obj["newsletter"].stringValue(),
                explainer = obj["explainer"].stringValue(),
                instagram = obj["instagram"].stringValue(),
                playlists = obj["playlists"].stringMap(),
                downloads = obj["downloads"].stringMap(),
                videos = obj["videos"].stringMap(),
            )
        }

        /** "https" URL or null (Swift `url(_:)`). */
        fun httpsOrNull(s: String?): String? {
            if (s.isNullOrEmpty()) return null
            val uri = try {
                URI(s)
            } catch (e: Exception) {
                return null
            }
            return if (uri.scheme == "https") s else null
        }

        private fun JsonElement?.stringValue(): String? {
            val prim = this as? JsonPrimitive ?: return null
            return if (prim.isString) prim.content else null
        }

        /** Swift decodes Int from 1 or 1.0. */
        private fun JsonElement?.intValue(): Int? {
            val prim = this as? JsonPrimitive ?: return null
            if (prim.isString) return null
            prim.intOrNull?.let { return it }
            val d = prim.doubleOrNull ?: return null
            return if (d % 1.0 == 0.0 && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE) d.toInt() else null
        }

        /** The string-valued entries of an object (other values are dropped); empty when not an object. */
        private fun JsonElement?.stringMap(): Map<String, String> {
            val o = this as? JsonObject ?: return emptyMap()
            val out = LinkedHashMap<String, String>()
            for ((k, v) in o) v.stringValue()?.let { out[k] = it }
            return out
        }
    }
}

/** SDR and HDR10 patterns are on YouTube; HDR10+ and Dolby Vision are downloads. */
val SignalMode.onYouTube: Boolean get() = this == SignalMode.SDR || this == SignalMode.HDR10

/** Where a pattern file comes from, in words. */
val SignalMode.patternSource: String
    get() = if (onYouTube) "in the $label playlist on YouTube" else "from the $label download (USB stick or a media app)"

/**
 * The pattern links: the cache (filesDir/patterns.json), else the bundled assets/patterns.json, else
 * PatternLinks(). refresh() downloads `<server>patterns.json` (200 only) and writes the cache.
 */
class LinksService(
    /** filesDir/patterns.json. */
    private val cacheFile: File,
    /** Reads assets/patterns.json (null if missing). */
    private val bundled: () -> ByteArray?,
    private val server: String? = Config.server,
    private val http: HttpGet = UrlConnectionHttp,
) {
    var links: PatternLinks by mutableStateOf(PatternLinks())
        private set

    init {
        val cached = try {
            if (cacheFile.isFile) PatternLinks.decode(cacheFile.readBytes()) else null
        } catch (e: Exception) {
            null
        }
        val shipped = if (cached != null) {
            null
        } else {
            try {
                bundled()?.let { PatternLinks.decode(it) }
            } catch (e: Exception) {
                null
            }
        }
        links = cached ?: shipped ?: PatternLinks()
    }

    /** Never throws (except coroutine cancellation): keeps the cached or bundled links on any problem. */
    suspend fun refresh() {
        val base = server ?: return
        try {
            val response = http.get(Config.resolve(base, "patterns.json"))
            if (response.status != 200) return
            val l = PatternLinks.decode(response.body) ?: return
            links = l
            withContext(Dispatchers.IO) {
                try {
                    ProfileService.writeAtomic(cacheFile, response.body)
                } catch (e: Exception) {
                    // Swift: try? write — the links stay in memory.
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // keep the cached or bundled links
        }
    }
}
