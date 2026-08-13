package org.bibletranslationtools.kotlinscripturealignment.serializers

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.bibletranslationtools.kotlinscripturealignment.model.Documents
import org.bibletranslationtools.kotlinscripturealignment.model.Group
import org.bibletranslationtools.kotlinscripturealignment.model.Record

/**
 * Mirrors the Jackson GroupSerializer: `documents` only when non-null, `records` ALWAYS — even
 * when empty, which is why this cannot be left to encodeDefaults.
 */
object GroupSerializer : KSerializer<Group> {

    private val records = ListSerializer(Record.serializer())

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("org.bibletranslationtools.kotlinscripturealignment.model.Group")

    override fun serialize(encoder: Encoder, value: Group) {
        val out = encoder as? JsonEncoder
            ?: throw IllegalStateException("Group can only be written to JSON")
        out.encodeJsonElement(
            buildJsonObject {
                value.documents?.let {
                    put("documents", out.json.encodeToJsonElement(DocumentsSerializer, it))
                }
                put("records", out.json.encodeToJsonElement(records, value.records))
            }
        )
    }

    override fun deserialize(decoder: Decoder): Group {
        val input = decoder as? JsonDecoder
            ?: throw IllegalStateException("Group can only be read from JSON")
        val obj = input.decodeJsonElement() as? JsonObject
            ?: throw IllegalArgumentException("a group must be an object")
        return Group(
            documents = obj["documents"]?.let {
                input.json.decodeFromJsonElement(DocumentsSerializer, it)
            },
            records = obj["records"]?.let { input.json.decodeFromJsonElement(records, it) } ?: listOf()
        )
    }
}
