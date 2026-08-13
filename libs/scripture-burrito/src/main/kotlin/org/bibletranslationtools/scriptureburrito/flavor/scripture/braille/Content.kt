package org.bibletranslationtools.scriptureburrito.flavor.scripture.braille

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName


@Serializable
class Content {

    @SerialName("chapterNumberStyle")
    var chapterNumberStyle: ChapterNumberStyle? = null

    @SerialName("chapterHeadingsNumberFirst")
    var chapterHeadingsNumberFirst: Boolean? = null

    @SerialName("versedParagraphs")
    var versedParagraphs: Boolean? = null

    @SerialName("verseSeparator")
    var verseSeparator: String? = null

    @SerialName("includeIntros")
    var includeIntros: Boolean? = null

    @SerialName("footnotes")
    private var footnotes: Footnotes? = null

    @SerialName("characterStyles")
    private var characterStyles: CharacterStyles? = null

    @SerialName("crossReferences")
    private var crossReferences: CrossReferences? = null

    fun getFootnotes(): Footnotes? {
        return footnotes
    }

    fun setFootnotes(footnotes: Footnotes?) {
        this.footnotes = footnotes
    }

    fun getCharacterStyles(): CharacterStyles? {
        return characterStyles
    }

    fun setCharacterStyles(characterStyles: CharacterStyles?) {
        this.characterStyles = characterStyles
    }

    fun getCrossReferences(): CrossReferences? {
        return crossReferences
    }

    fun setCrossReferences(crossReferences: CrossReferences?) {
        this.crossReferences = crossReferences
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Content) return false

        if (chapterNumberStyle != other.chapterNumberStyle) return false
        if (chapterHeadingsNumberFirst != other.chapterHeadingsNumberFirst) return false
        if (versedParagraphs != other.versedParagraphs) return false
        if (verseSeparator != other.verseSeparator) return false
        if (includeIntros != other.includeIntros) return false
        if (footnotes != other.footnotes) return false
        if (characterStyles != other.characterStyles) return false
        if (crossReferences != other.crossReferences) return false

        return true
    }

    override fun hashCode(): Int {
        var result = chapterNumberStyle?.hashCode() ?: 0
        result = 31 * result + (chapterHeadingsNumberFirst?.hashCode() ?: 0)
        result = 31 * result + (versedParagraphs?.hashCode() ?: 0)
        result = 31 * result + (verseSeparator?.hashCode() ?: 0)
        result = 31 * result + (includeIntros?.hashCode() ?: 0)
        result = 31 * result + (footnotes?.hashCode() ?: 0)
        result = 31 * result + (characterStyles?.hashCode() ?: 0)
        result = 31 * result + (crossReferences?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "Content(chapterNumberStyle=$chapterNumberStyle, chapterHeadingsNumberFirst=$chapterHeadingsNumberFirst, versedParagraphs=$versedParagraphs, verseSeparator=$verseSeparator, includeIntros=$includeIntros, footnotes=$footnotes, characterStyles=$characterStyles, crossReferences=$crossReferences)"
    }

    enum class ChapterNumberStyle(private val value: String) {
        UPPER("upper"),
        LOWER("lower");

        override fun toString(): String {
            return this.value
        }

        fun value(): String {
            return this.value
        }

        companion object {
            private val CONSTANTS: MutableMap<String, ChapterNumberStyle> = HashMap()

            init {
                for (c in values()) {
                    CONSTANTS[c.value] = c
                }
            }

            fun fromValue(value: String): ChapterNumberStyle {
                val constant = CONSTANTS[value]
                requireNotNull(constant) { value }
                return constant
            }
        }
    }
}
