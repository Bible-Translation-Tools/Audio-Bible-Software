package org.bibletranslationtools.scriptureburrito.flavor.scripture.braille

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName


@Serializable
class Page {

    @SerialName("charsPerLine")
    var charsPerLine: Double? = null

    @SerialName("linesPerPage")
    var linesPerPage: Double? = null

    @SerialName("defaultMarginWidth")
    var defaultMarginWidth: Double? = null

    @SerialName("versoLastLineBlank")
    var versoLastLineBlank: Boolean? = null

    @SerialName("carryLines")
    var carryLines: Double? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Page) return false

        if (charsPerLine != other.charsPerLine) return false
        if (linesPerPage != other.linesPerPage) return false
        if (defaultMarginWidth != other.defaultMarginWidth) return false
        if (versoLastLineBlank != other.versoLastLineBlank) return false
        if (carryLines != other.carryLines) return false

        return true
    }

    override fun hashCode(): Int {
        var result = charsPerLine?.hashCode() ?: 0
        result = 31 * result + (linesPerPage?.hashCode() ?: 0)
        result = 31 * result + (defaultMarginWidth?.hashCode() ?: 0)
        result = 31 * result + (versoLastLineBlank?.hashCode() ?: 0)
        result = 31 * result + (carryLines?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "Page(charsPerLine=$charsPerLine, linesPerPage=$linesPerPage, defaultMarginWidth=$defaultMarginWidth, versoLastLineBlank=$versoLastLineBlank, carryLines=$carryLines)"
    }
}
