package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.KSerializer
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * ISO-8601 instants, matching what Jackson's default Date handling produced for `dateCreated`.
 * Fixed to UTC and a fixed Locale so output does not vary with the host machine.
 */
object DateSerializer : KSerializer<Date> {
    private val format
        get() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.util.Date", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Date) = encoder.encodeString(format.format(value))

    override fun deserialize(decoder: Decoder): Date = format.parse(decoder.decodeString())
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
