package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName


@Serializable
class License {
    
    @SerialName("url")
    var url: String? = null

    @SerialName("ingredient")
    var ingredient: String? = null
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as License

        if (url != other.url) return false
        if (ingredient != other.ingredient) return false

        return true
    }

    override fun hashCode(): Int {
        var result = url?.hashCode() ?: 0
        result = 31 * result + (ingredient?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "License(url=$url, ingredient=$ingredient)"
    }
}
