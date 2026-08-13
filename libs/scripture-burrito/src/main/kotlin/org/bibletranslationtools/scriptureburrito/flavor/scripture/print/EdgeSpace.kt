package org.bibletranslationtools.scriptureburrito.flavor.scripture.print

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName


@Serializable
class EdgeSpace {
    @SerialName("top")
    var top: String? = null

    @SerialName("bottom")
    var bottom: String? = null

    @SerialName("inside")
    var inside: String? = null

    @SerialName("outside")
    var outside: String? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EdgeSpace) return false

        if (top != other.top) return false
        if (bottom != other.bottom) return false
        if (inside != other.inside) return false
        if (outside != other.outside) return false

        return true
    }

    override fun hashCode(): Int {
        var result = top?.hashCode() ?: 0
        result = 31 * result + (bottom?.hashCode() ?: 0)
        result = 31 * result + (inside?.hashCode() ?: 0)
        result = 31 * result + (outside?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "EdgeSpace(top=$top, bottom=$bottom, inside=$inside, outside=$outside)"
    }
}
