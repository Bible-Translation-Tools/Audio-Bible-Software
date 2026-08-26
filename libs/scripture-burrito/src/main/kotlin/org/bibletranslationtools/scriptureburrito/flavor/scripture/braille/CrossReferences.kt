package org.bibletranslationtools.scriptureburrito.flavor.scripture.braille

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName


@Serializable
class CrossReferences {
    @SerialName("emphasizedWord")
    var emphasizedWord: String? = null

    @SerialName("emphasizedPassageStart")
    var emphasizedPassageStart: String? = null

    @SerialName("emphasizedPassageEnd")
    var emphasizedPassageEnd: String? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CrossReferences) return false

        if (emphasizedWord != other.emphasizedWord) return false
        if (emphasizedPassageStart != other.emphasizedPassageStart) return false
        if (emphasizedPassageEnd != other.emphasizedPassageEnd) return false

        return true
    }

    override fun hashCode(): Int {
        var result = emphasizedWord?.hashCode() ?: 0
        result = 31 * result + (emphasizedPassageStart?.hashCode() ?: 0)
        result = 31 * result + (emphasizedPassageEnd?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "CrossReferences(emphasizedWord=$emphasizedWord, emphasizedPassageStart=$emphasizedPassageStart, emphasizedPassageEnd=$emphasizedPassageEnd)"
    }
}
