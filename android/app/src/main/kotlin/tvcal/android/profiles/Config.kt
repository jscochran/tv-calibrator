package tvcal.android.profiles

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tvcal.android.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

object Config {
    /**
     * The profile server (BuildConfig.PROFILE_SERVER, this fork's server/ folder): index.json, profiles/<name>.json
     * and patterns.json resolve relative to it. https only, ends with "/"; null means offline (no downloads).
     */
    val server: String? = normalise(BuildConfig.PROFILE_SERVER)

    /** The catalog format this app reads. */
    const val catalogFormat = 1

    val serverConfigured: Boolean get() = server != null

    /** https only, no "REPLACE-WITH" placeholder host, a trailing "/" added; null otherwise. */
    fun normalise(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val withSlash = if (text.endsWith("/")) text else "$text/"
        val uri = runCatching { URI(withSlash) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (uri.scheme != "https" || host.contains("REPLACE-WITH")) return null
        return withSlash
    }

    /** `URL(string: path, relativeTo: server).absoluteURL`. */
    fun resolve(server: String, path: String): String = URI(server).resolve(path).toString()
}

/** This phone's model key for camera profiles, e.g. "samsung/e2q". Profiles are per model. */
object DeviceModel {
    /** Build.MANUFACTURER lowercased + "/" + Build.DEVICE. */
    val identifier: String by lazy { identifier(Build.MANUFACTURER ?: "", Build.DEVICE ?: "") }

    /** Marketing-ish name for text, e.g. "SM-S926U" (Build.MODEL). */
    val name: String by lazy { Build.MODEL ?: identifier }

    fun identifier(manufacturer: String, device: String): String = manufacturer.lowercase(Locale.ROOT) + "/" + device
}

/** An HTTP GET answer: status code and body. */
class HttpResponse(val status: Int, val body: ByteArray)

/** Plain GET; throws IOException when the server can't be reached. Injected so services are JVM-testable. */
fun interface HttpGet {
    suspend fun get(url: String): HttpResponse
}

/** HttpURLConnection on Dispatchers.IO, 60 s timeouts (URLSession.shared's default request timeout), no caching. */
object UrlConnectionHttp : HttpGet {
    override suspend fun get(url: String): HttpResponse = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 60_000
            c.readTimeout = 60_000
            c.useCaches = false
            val status = c.responseCode
            val body = (if (status in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            HttpResponse(status, body)
        } catch (e: IOException) {
            throw e
        } finally {
            c.disconnect()
        }
    }
}

/** Lowercase hex SHA-256 (CryptoKit SHA256 → MessageDigest). */
fun sha256Hex(data: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 0xff) }
