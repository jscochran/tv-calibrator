package tvcal.android.storage

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

// JSON as the iPhone app writes it: JSONEncoder with [.prettyPrinted, .sortedKeys] and .iso8601 dates.
// Files written here and by the iPhone app are interchangeable.

/** Swift `.iso8601`: "2025-11-25T18:28:00Z", whole seconds, UTC. Reads fractional seconds and offsets too. */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("tvcal.Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(isoString(value))
    override fun deserialize(decoder: Decoder): Instant =
        parseIso(decoder.decodeString()) ?: throw IllegalArgumentException("Not an ISO 8601 date")
}

/** Swift `UUID` in JSON: the uppercase string, e.g. "E621E1F8-C36C-495A-93FC-0C247A3E6E5F". */
object UUIDSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("tvcal.UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.upper)
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

/** Swift `uuid.uuidString`: uppercase. Use for folder names, pref keys and notification ids. */
val UUID.upper: String get() = toString().uppercase(Locale.ROOT)

fun isoString(i: Instant): String = DateTimeFormatter.ISO_INSTANT.format(i.truncatedTo(ChronoUnit.SECONDS))

fun parseIso(s: String): Instant? =
    runCatching { Instant.parse(s) }.getOrNull() ?: runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull()

/**
 * The store's Json: pretty (2-space indent like Swift), defaults written, nil optionals left out (Swift
 * `encodeIfPresent`), unknown keys ignored. Decoding with it is only "mostly" lenient (missing fields
 * take defaults, nulls and unknown enum values are coerced); [StoreDecoding] does the full Swift-style
 * per-field leniency for the saved files.
 */
@OptIn(ExperimentalSerializationApi::class)
val StoreJson: Json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/** Swift `.sortedKeys`: every object's keys sorted (recursively), arrays kept in order. */
fun sortKeys(e: JsonElement): JsonElement = when (e) {
    is JsonObject -> JsonObject(e.entries.sortedBy { it.key }.associateTo(LinkedHashMap()) { it.key to sortKeys(it.value) })
    is JsonArray -> JsonArray(e.map { sortKeys(it) })
    else -> e
}

/** Encodes like the iPhone app's JSONEncoder: pretty, sorted keys, ISO dates. */
fun <T> encodeSorted(serializer: SerializationStrategy<T>, value: T): String =
    StoreJson.encodeToString(JsonElement.serializer(), sortKeys(StoreJson.encodeToJsonElement(serializer, value)))
