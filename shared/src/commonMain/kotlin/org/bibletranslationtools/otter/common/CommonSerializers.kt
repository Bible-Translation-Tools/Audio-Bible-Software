package org.bibletranslationtools.otter.common

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalDate

/** A File as its path string, which is how Jackson rendered one by default. */
object FileSerializer : KSerializer<File> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.io.File", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: File) = encoder.encodeString(value.path)
    override fun deserialize(decoder: Decoder): File = File(decoder.decodeString())
}

/** ISO-8601 (`2024-01-31`) — LocalDate.toString()/parse are already that format. */
object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.LocalDate", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

/**
 * Reads a JSON string OR number as a String.
 *
 * ulb_versification.json stores verse counts as bare numbers (`"gen": [31, 25, ...]`) while
 * MaxVerses is typed `Map<String, List<String>>`. Jackson coerced number to String silently;
 * kotlinx is strict and fails with "Expected quotation mark '"', but had '3'". Narrower than
 * setting isLenient on the whole codec, which would also start accepting malformed JSON elsewhere.
 */
object CoercingStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("CoercedString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)

    override fun deserialize(decoder: Decoder): String {
        val input = decoder as? JsonDecoder ?: return decoder.decodeString()
        return input.decodeJsonElement().jsonPrimitive.content
    }
}
