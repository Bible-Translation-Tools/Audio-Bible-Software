package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import org.bibletranslationtools.scriptureburrito.DateSerializer
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.SerialName

import org.bibletranslationtools.scriptureburrito.Category
import org.bibletranslationtools.scriptureburrito.MetaVersionSchema
import org.bibletranslationtools.scriptureburrito.NormalizationSchema
import java.util.*

@Serializable
@JsonClassDiscriminator("category")
// Properties are abstract rather than constructor parameters: kotlinx requires every
// primary-constructor parameter of a @Serializable class to be a property, and the
// subtypes forward their arguments here rather than declaring their own. Making the base
// abstract and the subtypes `override` keeps every public signature identical.
sealed class Meta {
    @Serializable(with = DateSerializer::class)
    @SerialName("dateCreated")
    abstract var dateCreated: Date

    @SerialName("version")
    abstract var version: MetaVersionSchema

    @SerialName("generator")
    abstract var generator: SoftwareAndUserInfoSchema?

    @SerialName("defaultLocale")
    abstract var defaultLocale: String

    @SerialName("normalization")
    abstract var normalization: NormalizationSchema?

    @SerialName("comments")
    abstract var comments: MutableList<String>


    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Meta) return false

        if (dateCreated != other.dateCreated) return false
        if (version != other.version) return false
        if (generator != other.generator) return false
        if (defaultLocale != other.defaultLocale) return false
        if (normalization != other.normalization) return false
        if (comments != other.comments) return false

        return true
    }

    override fun hashCode(): Int {
        var result = dateCreated.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + (generator?.hashCode() ?: 0)
        result = 31 * result + defaultLocale.hashCode()
        result = 31 * result + (normalization?.hashCode() ?: 0)
        result = 31 * result + comments.hashCode()
        return result
    }

    override fun toString(): String {
        return "Meta(dateCreated=$dateCreated, version=$version, generator=$generator, defaultLocale='$defaultLocale', normalization=$normalization, comments=$comments)"
    }
}
