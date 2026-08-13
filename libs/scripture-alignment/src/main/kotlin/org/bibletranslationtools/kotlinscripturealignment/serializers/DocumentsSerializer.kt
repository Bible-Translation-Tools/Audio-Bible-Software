package org.bibletranslationtools.kotlinscripturealignment.serializers

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import org.bibletranslationtools.kotlinscripturealignment.model.DocumentReference
import org.bibletranslationtools.kotlinscripturealignment.model.Documents
import org.bibletranslationtools.kotlinscripturealignment.model.DocumentsList
import org.bibletranslationtools.kotlinscripturealignment.model.DocumentsMap

/**
 * Replaces the Jackson DocumentsSerializer/deserializer pair. Both directions branch on JSON
 * shape rather than on a discriminator, matching the on-disk format.
 */
object DocumentsSerializer : KSerializer<Documents> {

    private val listSerializer = ListSerializer(DocumentReference.serializer())
    private val mapSerializer = MapSerializer(String.serializer(), DocumentReference.serializer())

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("org.bibletranslationtools.kotlinscripturealignment.model.Documents")

    override fun serialize(encoder: Encoder, value: Documents) {
        val out = encoder as? JsonEncoder
            ?: throw IllegalStateException("Documents can only be written to JSON")
        when (value) {
            is DocumentsList -> out.encodeSerializableValue(listSerializer, value.list)
            is DocumentsMap -> out.encodeSerializableValue(mapSerializer, value.map)
        }
    }

    override fun deserialize(decoder: Decoder): Documents {
        val input = decoder as? JsonDecoder
            ?: throw IllegalStateException("Documents can only be read from JSON")
        return when (val element = input.decodeJsonElement()) {
            is JsonArray -> DocumentsList(input.json.decodeFromJsonElement(listSerializer, element))
            is JsonObject -> DocumentsMap(input.json.decodeFromJsonElement(mapSerializer, element))
            // The old deserializer returned null for anything else; the field itself is nullable,
            // so an unexpected shape surfaces as a decode failure rather than silent data loss.
            else -> throw IllegalArgumentException("`documents` must be an array or an object")
        }
    }
}
