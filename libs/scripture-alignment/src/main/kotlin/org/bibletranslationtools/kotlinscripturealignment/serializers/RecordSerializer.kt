package org.bibletranslationtools.kotlinscripturealignment.serializers

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import org.bibletranslationtools.kotlinscripturealignment.model.Record

/**
 * Mirrors the Jackson RecordSerializer: a key is written only when its list is present AND
 * non-empty. That is stricter than the shared Json instance's explicitNulls/encodeDefaults, which
 * would still write an explicitly-empty `timecode: []`, so the rule stays hand-written.
 */
object RecordSerializer : KSerializer<Record> {

    private val strings = ListSerializer(String.serializer())
    private val stringLists = ListSerializer(ListSerializer(String.serializer()))

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("org.bibletranslationtools.kotlinscripturealignment.model.Record")

    override fun serialize(encoder: Encoder, value: Record) {
        val out = encoder as? JsonEncoder
            ?: throw IllegalStateException("Record can only be written to JSON")
        out.encodeJsonElement(
            buildJsonObject {
                value.timecode?.takeIf { it.isNotEmpty() }?.let {
                    put("timecode", out.json.encodeToJsonElement(strings, it))
                }
                value.textReference?.takeIf { it.isNotEmpty() }?.let {
                    put("text-reference", out.json.encodeToJsonElement(strings, it))
                }
                value.references.takeIf { it.isNotEmpty() }?.let {
                    put("references", out.json.encodeToJsonElement(stringLists, it))
                }
            }
        )
    }

    override fun deserialize(decoder: Decoder): Record {
        val input = decoder as? JsonDecoder
            ?: throw IllegalStateException("Record can only be read from JSON")
        val obj = input.decodeJsonElement() as? JsonObject
            ?: throw IllegalArgumentException("a record must be an object")
        return Record(
            timecode = obj["timecode"]?.let { input.json.decodeFromJsonElement(strings, it) },
            textReference = obj["text-reference"]?.let { input.json.decodeFromJsonElement(strings, it) },
            references = obj["references"]?.let { input.json.decodeFromJsonElement(stringLists, it) } ?: listOf()
        )
    }
}
