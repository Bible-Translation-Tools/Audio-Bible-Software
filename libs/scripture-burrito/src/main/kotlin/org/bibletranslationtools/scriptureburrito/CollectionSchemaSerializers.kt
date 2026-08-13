package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import org.bibletranslationtools.scriptureburrito.flavor.scripture.audio.AudioFormat
import org.bibletranslationtools.scriptureburrito.flavor.scripture.audio.Formats

/**
 * The schema model expresses several of its types as subclasses of HashMap/ArrayList rather than
 * as classes with properties. Jackson serialized those structurally; kotlinx needs to be told, so
 * each gets a serializer that delegates to the matching Map/List serializer and rebuilds the
 * concrete subclass on the way back in.
 */

object LocalizedNamesSchemaSerializer : KSerializer<LocalizedNamesSchema> {
    private val delegate = MapSerializer(String.serializer(), LocalizedText.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: LocalizedNamesSchema) = delegate.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): LocalizedNamesSchema =
        LocalizedNamesSchema().apply { putAll(delegate.deserialize(decoder)) }
}

object IngredientsSchemaSerializer : KSerializer<IngredientsSchema> {
    private val delegate = MapSerializer(String.serializer(), IngredientSchema.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: IngredientsSchema) = delegate.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): IngredientsSchema =
        IngredientsSchema().apply { putAll(delegate.deserialize(decoder)) }
}

object PrimaryIdentificationSerializer : KSerializer<PrimaryIdentification> {
    private val delegate = MapSerializer(String.serializer(), JsonElement.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: PrimaryIdentification) = delegate.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): PrimaryIdentification =
        PrimaryIdentification().apply { putAll(delegate.deserialize(decoder)) }
}

object IdAuthoritiesSchemaSerializer : KSerializer<IdAuthoritiesSchema> {
    private val delegate = MapSerializer(String.serializer(), IdAuthority.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: IdAuthoritiesSchema) = delegate.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): IdAuthoritiesSchema =
        IdAuthoritiesSchema().apply { putAll(delegate.deserialize(decoder)) }
}

object FormatsSerializer : KSerializer<Formats> {
    private val delegate = MapSerializer(String.serializer(), AudioFormat.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: Formats) = delegate.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): Formats =
        Formats().apply { putAll(delegate.deserialize(decoder)) }
}

object ScopeSchemaSerializer : KSerializer<ScopeSchema> {
    private val delegate = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: ScopeSchema) = delegate.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): ScopeSchema =
        ScopeSchema().apply {
            delegate.deserialize(decoder).forEach { (k, v) -> put(k, v.toMutableList()) }
        }
}

object RelationshipsSerializer : KSerializer<Relationships> {
    private val delegate = ListSerializer(RelationshipSchema.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: Relationships) = delegate.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): Relationships =
        Relationships().apply { addAll(delegate.deserialize(decoder)) }
}

object LanguagesSerializer : KSerializer<Languages> {
    private val delegate = ListSerializer(LanguageSchema.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: Languages) = delegate.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): Languages =
        Languages().apply { addAll(delegate.deserialize(decoder)) }
}

object TargetAreasSerializer : KSerializer<TargetAreas> {
    private val delegate = ListSerializer(TargetAreaSchema.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: TargetAreas) = delegate.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): TargetAreas =
        TargetAreas().apply { addAll(delegate.deserialize(decoder)) }
}

object AgenciesSerializer : KSerializer<Agencies> {
    private val delegate = ListSerializer(AgencySchema.serializer())
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: Agencies) = delegate.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): Agencies =
        Agencies().apply { addAll(delegate.deserialize(decoder)) }
}
