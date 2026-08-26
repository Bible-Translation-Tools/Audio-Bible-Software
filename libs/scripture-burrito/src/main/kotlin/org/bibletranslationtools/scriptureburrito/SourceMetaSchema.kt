package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

import org.bibletranslationtools.scriptureburrito.MetaVersionSchema
import org.bibletranslationtools.scriptureburrito.NormalizationSchema
import org.bibletranslationtools.scriptureburrito.Category
import java.util.*


@Serializable
@SerialName("source")
class SourceMetaSchema(
    @Serializable(with = DateSerializer::class)
    @SerialName("dateCreated")
    override var dateCreated: Date,

    @SerialName("version")
    override var version: MetaVersionSchema,

    @SerialName("generator")
    override var generator: SoftwareAndUserInfoSchema?,

    @SerialName("defaultLocale")
    override var defaultLocale: String,

    @SerialName("normalization")
    override var normalization: NormalizationSchema? = null,

    @SerialName("comments")
    override var comments: MutableList<String> = ArrayList()
) : Meta() {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SourceMetaSchema) return false
        if (!super.equals(other)) return false
        return true
    }

    override fun toString(): String {
        return "SourceMetaSchema(category:source, dateCreated=$dateCreated, version=$version, generator=$generator, defaultLocale='$defaultLocale', normalization=$normalization, comments=$comments)"
    }
}