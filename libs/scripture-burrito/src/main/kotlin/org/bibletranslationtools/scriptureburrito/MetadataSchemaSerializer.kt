package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Chooses the metadata subtype from `meta.category`, replacing MetadataDeserializer.
 *
 * The old deserializer rebuilt every field by hand to do this; here only the type decision is
 * hand-written and the generated serializers do the rest, so the two cannot drift apart.
 */
object MetadataSchemaSerializer : JsonContentPolymorphicSerializer<MetadataSchema>(MetadataSchema::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<MetadataSchema> {
        val category = element.jsonObject["meta"]?.jsonObject?.get("category")?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing required field: meta.category")
        return when (category) {
            "source" -> SourceMetadataSchema.serializer()
            "derived" -> DerivedMetadataSchema.serializer()
            "template" -> TemplateMetadataSchema.serializer()
            else -> throw IllegalArgumentException("Unsupported metadata category: $category")
        }
    }
}
