package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

import org.bibletranslationtools.scriptureburrito.Format

@Serializable
class DerivedMetadataSchema(
    @SerialName("format")
    override var format: org.bibletranslationtools.scriptureburrito.Format,

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
    @SerialName("promotion")
    var promotion: PromotionSchema? = null


    @SerialName("recipe")
    var recipe: MutableList<RecipeSchema>? = ArrayList()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DerivedMetadataSchema) return false
        if (!super.equals(other)) return false

        if (promotion != other.promotion) return false
        if (recipe != other.recipe) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + (promotion?.hashCode() ?: 0)
        result = 31 * result + (recipe?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "DerivedMetadataSchema(promotion=$promotion, recipe=$recipe, format=$format, meta=$meta, copyright=$copyright, idAuthorities=$idAuthorities, identification=$identification, confidential=$confidential, type=$type, relationships=$relationships, languages=$languages, targetAreas=$targetAreas, agencies=$agencies, ingredients=$ingredients, localizedNames=$localizedNames)"
    }
}
