package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName



@Serializable
class ReferenceFormatSchema {
    @SerialName("noSpaceBetweenBookAndChapter")
    var noSpaceBetweenBookAndChapter: Boolean? = null

    @SerialName("chapterVerseSeparator")
    var chapterVerseSeparator: String? = null

    @SerialName("rangeIndicator")
    var rangeIndicator: String? = null

    @SerialName("sequenceIndicator")
    var sequenceIndicator: String? = null

    @SerialName("chapterRangeSeparator")
    var chapterRangeSeparator: String? = null

    @SerialName("chapterNumberSeparator")
    var chapterNumberSeparator: String? = null

    @SerialName("bookSequenceSeparator")
    var bookSequenceSeparator: String? = null

    @SerialName("referenceExtraMaterial")
    var referenceExtraMaterial: MutableList<String>? = ArrayList()

    @SerialName("referenceFinalPunctuation")
    var referenceFinalPunctuation: String? = null

    @SerialName("bookSourceForMarkerXt")
    var bookSourceForMarkerXt: String? = null

    @SerialName("bookSourceForMarkerR")
    var bookSourceForMarkerR: String? = null
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReferenceFormatSchema) return false

        if (noSpaceBetweenBookAndChapter != other.noSpaceBetweenBookAndChapter) return false
        if (chapterVerseSeparator != other.chapterVerseSeparator) return false
        if (rangeIndicator != other.rangeIndicator) return false
        if (sequenceIndicator != other.sequenceIndicator) return false
        if (chapterRangeSeparator != other.chapterRangeSeparator) return false
        if (chapterNumberSeparator != other.chapterNumberSeparator) return false
        if (bookSequenceSeparator != other.bookSequenceSeparator) return false
        if (referenceExtraMaterial != other.referenceExtraMaterial) return false
        if (referenceFinalPunctuation != other.referenceFinalPunctuation) return false
        if (bookSourceForMarkerXt != other.bookSourceForMarkerXt) return false
        if (bookSourceForMarkerR != other.bookSourceForMarkerR) return false

        return true
    }

    override fun hashCode(): Int {
        var result = noSpaceBetweenBookAndChapter?.hashCode() ?: 0
        result = 31 * result + (chapterVerseSeparator?.hashCode() ?: 0)
        result = 31 * result + (rangeIndicator?.hashCode() ?: 0)
        result = 31 * result + (sequenceIndicator?.hashCode() ?: 0)
        result = 31 * result + (chapterRangeSeparator?.hashCode() ?: 0)
        result = 31 * result + (chapterNumberSeparator?.hashCode() ?: 0)
        result = 31 * result + (bookSequenceSeparator?.hashCode() ?: 0)
        result = 31 * result + (referenceExtraMaterial?.hashCode() ?: 0)
        result = 31 * result + (referenceFinalPunctuation?.hashCode() ?: 0)
        result = 31 * result + (bookSourceForMarkerXt?.hashCode() ?: 0)
        result = 31 * result + (bookSourceForMarkerR?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "ReferenceFormatSchema(noSpaceBetweenBookAndChapter=$noSpaceBetweenBookAndChapter, chapterVerseSeparator=$chapterVerseSeparator, rangeIndicator=$rangeIndicator, sequenceIndicator=$sequenceIndicator, chapterRangeSeparator=$chapterRangeSeparator, chapterNumberSeparator=$chapterNumberSeparator, bookSequenceSeparator=$bookSequenceSeparator, referenceExtraMaterial=$referenceExtraMaterial, referenceFinalPunctuation=$referenceFinalPunctuation, bookSourceForMarkerXt=$bookSourceForMarkerXt, bookSourceForMarkerR=$bookSourceForMarkerR)"
    }
}
