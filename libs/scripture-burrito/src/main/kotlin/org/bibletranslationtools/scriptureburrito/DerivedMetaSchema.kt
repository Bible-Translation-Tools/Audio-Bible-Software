package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

import org.bibletranslationtools.scriptureburrito.Category
import org.bibletranslationtools.scriptureburrito.MetaVersionSchema
import org.bibletranslationtools.scriptureburrito.NormalizationSchema
import java.util.*


@Serializable
@SerialName("derived")
class DerivedMetaSchema(
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
    override var comments: MutableList<String> = ArrayList()
) : Meta() {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DerivedMetaSchema) return false
        if (!super.equals(other)) return false
        return true
    }

    override fun toString(): String {
        return "DerivedMetaSchema(category:derived, dateCreated=$dateCreated, version=$version, generator=$generator, defaultLocale='$defaultLocale', normalization=$normalization, comments=$comments)"
    }
}
