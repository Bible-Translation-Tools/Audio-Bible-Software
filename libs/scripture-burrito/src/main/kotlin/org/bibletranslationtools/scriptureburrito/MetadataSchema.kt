package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName


/**
 * Polymorphic on a NESTED field — `meta.category` — which is why this uses a content-based
 * selector rather than a discriminator. Jackson expressed the same thing with a hand-written
 * MetadataDeserializer; kotlinx has JsonContentPolymorphicSerializer for exactly this shape.
 *
 * Note there is no injected discriminator on the way out either: the old writer emitted the
 * concrete subtype's own fields and nothing else, and `category` travels inside `meta`.
 */
@Serializable(with = MetadataSchemaSerializer::class)
abstract class MetadataSchema {
    @SerialName("format")
    abstract var format: Format

    @SerialName("meta")
    abstract var meta: Meta

    @SerialName("copyright")
    abstract var copyright: CopyrightSchema

    @SerialName("idAuthorities")
    abstract var idAuthorities: IdAuthoritiesSchema?

    @SerialName("identification")
    abstract var identification: IdentificationSchema?

    @SerialName("confidential")
    abstract var confidential: Boolean?

    @SerialName("type")
    abstract var type: TypeSchema?

    @SerialName("relationships")
    abstract var relationships: MutableList<RelationshipSchema>

    @SerialName("languages")
    abstract var languages: Languages

    @SerialName("targetAreas")
    abstract var targetAreas: MutableList<TargetAreaSchema>

    @SerialName("agencies")
    abstract var agencies: MutableList<AgencySchema>

    @SerialName("ingredients")
    abstract var ingredients: IngredientsSchema

    @SerialName("localizedNames")
    abstract var localizedNames: LocalizedNamesSchema

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MetadataSchema) return false

        if (format != other.format) return false
        if (meta != other.meta) return false
        if (copyright != other.copyright) return false
        if (idAuthorities != other.idAuthorities) return false
        if (identification != other.identification) return false
        if (confidential != other.confidential) return false
        if (type != other.type) return false
        if (relationships != other.relationships) return false
        if (languages != other.languages) return false
        if (targetAreas != other.targetAreas) return false
        if (agencies != other.agencies) return false
        if (ingredients != other.ingredients) return false
        if (localizedNames != other.localizedNames) return false

        return true
    }

    override fun hashCode(): Int {
        var result = format.hashCode()
        result = 31 * result + meta.hashCode()
        result = 31 * result + copyright.hashCode()
        result = 31 * result + (idAuthorities?.hashCode() ?: 0)
        result = 31 * result + (identification?.hashCode() ?: 0)
        result = 31 * result + (confidential?.hashCode() ?: 0)
        result = 31 * result + (type?.hashCode() ?: 0)
        result = 31 * result + relationships.hashCode()
        result = 31 * result + languages.hashCode()
        result = 31 * result + targetAreas.hashCode()
        result = 31 * result + agencies.hashCode()
        result = 31 * result + ingredients.hashCode()
        result = 31 * result + localizedNames.hashCode()
        return result
    }

    override fun toString(): String {
        return "MetadataSchema(format=$format, meta=$meta, copyright=$copyright, idAuthorities=$idAuthorities, identification=$identification, confidential=$confidential, type=$type, relationships=$relationships, languages=$languages, targetAreas=$targetAreas, agencies=$agencies, ingredients=$ingredients, localizedNames=$localizedNames)"
    }
}