package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.bibletranslationtools.scriptureburrito.flavor.FlavorSchema
import org.bibletranslationtools.scriptureburrito.flavor.scripture.audio.AudioFlavorSchema
import org.bibletranslationtools.scriptureburrito.flavor.scripture.braille.EmbossedBrailleScriptureSchema
import org.bibletranslationtools.scriptureburrito.flavor.scripture.print.TypesetScriptureSchema
import org.bibletranslationtools.scriptureburrito.flavor.scripture.text.TextTranslationSchema
import org.bibletranslationtools.scriptureburrito.flavor.scripture.video.SignLanguageVideoTranslationSchema
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * ISO-8601 dates and instants, as they actually occur in burritos in the wild.
 *
 * READING accepts every shape the spec permits, because a single wrapper carries more than one:
 * the wrapper's own metadata dates a full instant at microsecond precision
 * (`2026-06-05T13:28:14.620908Z`) while the audio and text burritos inside it date `2026-06-05`.
 *
 * This used to be one `SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")`, which got both wrong.
 * The date-only form threw `Unparseable date`, and because the failure happened while reading the
 * inner metadata it was reported as "Could not find both audio and text burritos in wrapper" —
 * the import failed for what looked like a missing-file reason. The instant was worse: SimpleDateFormat
 * is lenient, so `.SSS` swallowed all six digits of `620908` as MILLISECONDS and parsed that timestamp
 * to 13:38:34.908, ten minutes late, with no error at all.
 *
 * java.time parses these by the actual grammar rather than by a fixed field width, so fractional
 * seconds of any length are handled correctly. Jackson's StdDateFormat accepted the same range,
 * which is why none of this surfaced before the kotlinx-serialization migration. On Android this
 * relies on core library desugaring, already required by minSdk 24.
 *
 * WRITING keeps emitting a full UTC instant with milliseconds — unchanged, and the one format, so
 * a burrito Orature has written stays byte-comparable with what it wrote before.
 */
object DateSerializer : KSerializer<Date> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.util.Date", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Date) =
        encoder.encodeString(WRITE_FORMAT.withZone(ZoneOffset.UTC).format(value.toInstant()))

    override fun deserialize(decoder: Decoder): Date {
        val text = decoder.decodeString().trim()
        return Date.from(parseInstant(text) ?: throw SerializationException("Unparseable date: \"$text\""))
    }

    private fun parseInstant(text: String): Instant? {
        // Ordered widest-first: an offset instant is the common case, and the date-only branch must
        // not be reached by anything carrying a time.
        runCatching { return Instant.parse(text) }                                  // ...Z
        runCatching { return OffsetDateTime.parse(text).toInstant() }               // ...+05:30
        // No zone in the string. The burrito spec's dates are UTC, and assuming the host's zone
        // would make the same file parse differently on different machines.
        runCatching { return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC) }  // ...T13:28:14
        runCatching { return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant() } // 2026-06-05
        return null
    }

    private val WRITE_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
}

/**
 * Registers the FlavorSchema subtypes. Jackson took this list from @JsonSubTypes; kotlinx needs it
 * explicitly because the hierarchy cannot be sealed — its members are spread across sibling
 * packages. Meta and MetadataSchema do NOT appear here: Meta is sealed, and MetadataSchema uses a
 * content-based selector on meta.category.
 */
val burritoSerializersModule = SerializersModule {
    polymorphic(FlavorSchema::class) {
        subclass(TextTranslationSchema::class)
        subclass(AudioFlavorSchema::class)
        subclass(SignLanguageVideoTranslationSchema::class)
        subclass(EmbossedBrailleScriptureSchema::class)
        subclass(TypesetScriptureSchema::class)
    }
}

/**
 * The metadata.json codec, replacing the per-call ObjectMapper wiring in BurritoContainer.
 *
 * `ignoreUnknownKeys` covers the old @JsonIgnoreProperties(ignoreUnknown = true), and
 * `explicitNulls = false` reproduces Include.NON_NULL, which the schema classes relied on
 * throughout — the model is overwhelmingly nullable and a burrito must not carry a wall of
 * `"key": null`.
 */
val BURRITO_JSON = Json {
    serializersModule = burritoSerializersModule
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    prettyPrint = true
}
