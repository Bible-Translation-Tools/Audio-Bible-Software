package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

import org.bibletranslationtools.scriptureburrito.Format


@Serializable
class TemplateMetadataSchema(
    @SerialName("format")
    override var format: Format,

    @SerialName("meta")
    override var meta: Meta,

    @SerialName("idAuthorities")
    override var idAuthorities: IdAuthoritiesSchema? = null,

    @SerialName("identification")
    override var identification: IdentificationSchema? = null,

    @SerialName("confidential")
    override var confidential: Boolean? = null,

    @SerialName("type")
    override var type: TypeSchema?,

    @SerialName("copyright")
    override var copyright: CopyrightSchema,

    @SerialName("relationships")
    override var relationships: MutableList<RelationshipSchema> = ArrayList(),

    @SerialName("languages")
    override var languages: Languages = Languages(),

    @SerialName("targetAreas")
    override var targetAreas: MutableList<TargetAreaSchema> = ArrayList(),

    @SerialName("agencies")
    override var agencies: MutableList<AgencySchema> = ArrayList(),

    @SerialName("ingredients")
    override var ingredients: IngredientsSchema = IngredientsSchema(),

    @SerialName("localizedNames")
    override var localizedNames: LocalizedNamesSchema = LocalizedNamesSchema()
) : MetadataSchema() {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TemplateMetadataSchema) return false
        if (!super.equals(other)) return false
        return true
    }

    override fun toString(): String {
        return "TemplateMetadataSchema(format=$format, meta=$meta, copyright=$copyright, idAuthorities=$idAuthorities, identification=$identification, confidential=$confidential, type=$type, relationships=$relationships, languages=$languages, targetAreas=$targetAreas, agencies=$agencies, ingredients=$ingredients, localizedNames=$localizedNames)"
    }
}
