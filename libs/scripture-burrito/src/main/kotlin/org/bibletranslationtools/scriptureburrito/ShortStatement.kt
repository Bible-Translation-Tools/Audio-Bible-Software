package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName


@Serializable
class ShortStatement(
    @SerialName("statement")
    var statement: String,

    @SerialName("lang")
    var lang: String? = null,

    @SerialName("mimetype")
    var mimetype: String? = null
) {
    override fun toString(): String {
        return "ShortStatement(statement=$statement, lang=$lang, mimetype=$mimetype)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ShortStatement

        if (statement != other.statement) return false
        if (lang != other.lang) return false
        if (mimetype != other.mimetype) return false

        return true
    }

    override fun hashCode(): Int {
        var result = statement.hashCode()
        result = 31 * result + (lang?.hashCode() ?: 0)
        result = 31 * result + (mimetype?.hashCode() ?: 0)
        return result
    }
}
