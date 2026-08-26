package org.bibletranslationtools.kotlinscripturealignment.serializers

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bibletranslationtools.kotlinscripturealignment.model.BurritoAudioAlignment
import org.bibletranslationtools.kotlinscripturealignment.model.FormatType
import org.bibletranslationtools.kotlinscripturealignment.model.Group
import org.bibletranslationtools.kotlinscripturealignment.model.Record

/**
 * Replaces the Jackson BurritoAudioAlignmentSerializer + BurritoAudioAlignmentDeserializer pair.
 *
 * The document is one shape OR the other: when `groups` is present and non-empty it carries the
 * documents and records, and the top-level `documents`/`records` keys are omitted entirely.
 * `roles` rides along with whichever branch applies, and at top level only when the records
 * actually use positional `references`.
 */
object BurritoAudioAlignmentSerializer : KSerializer<BurritoAudioAlignment> {

    private val records = ListSerializer(Record.serializer())
    private val groups = ListSerializer(Group.serializer())
    private val strings = ListSerializer(String.serializer())

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("org.bibletranslationtools.kotlinscripturealignment.model.BurritoAudioAlignment")

    override fun serialize(encoder: Encoder, value: BurritoAudioAlignment) {
        val out = encoder as? JsonEncoder
            ?: throw IllegalStateException("BurritoAudioAlignment can only be written to JSON")
        out.encodeJsonElement(
            buildJsonObject {
                put("format", JsonPrimitive(value.format.value()))
                put("version", JsonPrimitive(value.version))
                put("type", JsonPrimitive(value.type))

                val groupList = value.groups
                if (groupList.isNullOrEmpty()) {
                    value.documents?.let {
                        put("documents", out.json.encodeToJsonElement(DocumentsSerializer, it))
                    }
                    if (value.records.isNotEmpty()) {
                        put("records", out.json.encodeToJsonElement(records, value.records))
                        if (value.roles != null && value.records.firstOrNull()?.references?.isNotEmpty() == true) {
                            put("roles", out.json.encodeToJsonElement(strings, value.roles!!))
                        }
                    }
                } else {
                    put("groups", out.json.encodeToJsonElement(groups, groupList))
                    value.roles?.let { put("roles", out.json.encodeToJsonElement(strings, it)) }
                }
            }
        )
    }

    override fun deserialize(decoder: Decoder): BurritoAudioAlignment {
        val input = decoder as? JsonDecoder
            ?: throw IllegalStateException("BurritoAudioAlignment can only be read from JSON")
        val obj = input.decodeJsonElement() as? JsonObject
            ?: throw IllegalArgumentException("an alignment document must be an object")

        val format = obj["format"]?.jsonPrimitive?.content
            ?.let { FormatType.fromValue(it) } ?: FormatType.ALIGNMENT
        val version = obj["version"]?.jsonPrimitive?.content ?: "0.3"
        val type = obj["type"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing required field: type")

        return BurritoAudioAlignment(
            format,
            version,
            type,
            obj["documents"]?.let { input.json.decodeFromJsonElement(DocumentsSerializer, it) },
            obj["roles"]?.let { input.json.decodeFromJsonElement(strings, it) },
            (obj["records"] as? JsonArray)?.let { input.json.decodeFromJsonElement(records, it) } ?: listOf(),
            (obj["groups"] as? JsonArray)?.let { input.json.decodeFromJsonElement(groups, it) }
        )
    }
}
