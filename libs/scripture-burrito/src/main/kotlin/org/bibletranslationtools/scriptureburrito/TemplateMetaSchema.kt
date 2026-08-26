package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

import org.bibletranslationtools.scriptureburrito.MetaVersionSchema
import org.bibletranslationtools.scriptureburrito.NormalizationSchema
import org.bibletranslationtools.scriptureburrito.Category
import java.util.*


@Serializable
@SerialName("template")
class TemplateMetaSchema(
    @Serializable(with = DateSerializer::class)
    @SerialName("dateCreated")
    override var dateCreated: Date,

    @SerialName("version")
    override var version: MetaVersionSchema,

    @SerialName("generator")
    override var generator: SoftwareAndUserInfoSchema? = null,

    @SerialName("defaultLocale")
    override var defaultLocale: String,

    @SerialName("normalization")
    override var normalization: NormalizationSchema? = null,

    @SerialName("comments")
    override var comments: MutableList<String> = ArrayList(),

    @SerialName("templateName")
    var templateName: LocalizedText
) : Meta() {

    override fun toString(): String {
        return "TemplateMetaSchema(category:template, templateName=$templateName, dateCreated=$dateCreated, version=$version, generator=$generator, defaultLocale='$defaultLocale', normalization=$normalization, comments=$comments)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TemplateMetaSchema) return false
        if (!super.equals(other)) return false

        if (templateName != other.templateName) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + templateName.hashCode()
        return result
    }
}