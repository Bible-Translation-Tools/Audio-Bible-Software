package org.bibletranslationtools.scriptureburrito.flavor.scripture.braille

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.SerialName

import org.bibletranslationtools.scriptureburrito.flavor.FlavorSchema

@Serializable
@SerialName("embossedBrailleScripture")
class EmbossedBrailleScriptureSchema: FlavorSchema() {

    @SerialName("isContracted")
    var isContracted: Boolean? = null

    @SerialName("processor")
    private var processor: Processor? = null

    @SerialName("hyphenationDictionary")
    private var hyphenationDictionary: HyphenationDictionary? = null

    @SerialName("numberSign")
    private var numberSign: NumberSign? = null

    @SerialName("continuousPoetry")
    private var continuousPoetry: ContinuousPoetry? = null

    @SerialName("content")
    private var content: Content? = null

    @SerialName("page")
    private var page: Page? = null

    @SerialName("conventions")
    private var conventions: JsonElement? = null

    fun getProcessor(): Processor? {
        return processor
    }

    fun setProcessor(processor: Processor?) {
        this.processor = processor
    }

    fun getHyphenationDictionary(): HyphenationDictionary? {
        return hyphenationDictionary
    }

    fun setHyphenationDictionary(hyphenationDictionary: HyphenationDictionary?) {
        this.hyphenationDictionary = hyphenationDictionary
    }

    fun getNumberSign(): NumberSign? {
        return numberSign
    }

    fun setNumberSign(numberSign: NumberSign?) {
        this.numberSign = numberSign
    }

    fun getContinuousPoetry(): ContinuousPoetry? {
        return continuousPoetry
    }

    fun setContinuousPoetry(continuousPoetry: ContinuousPoetry?) {
        this.continuousPoetry = continuousPoetry
    }

    fun getContent(): Content? {
        return content
    }

    fun setContent(content: Content?) {
        this.content = content
    }

    fun getPage(): Page? {
        return page
    }

    fun setPage(page: Page?) {
        this.page = page
    }

    fun getConventions(): JsonElement? {
        return conventions
    }

    fun setConventions(conventions: JsonElement) {
        this.conventions = conventions
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbossedBrailleScriptureSchema) return false
        if (!super.equals(other)) return false

        if (isContracted != other.isContracted) return false
        if (processor != other.processor) return false
        if (hyphenationDictionary != other.hyphenationDictionary) return false
        if (numberSign != other.numberSign) return false
        if (continuousPoetry != other.continuousPoetry) return false
        if (content != other.content) return false
        if (page != other.page) return false
        if (conventions != other.conventions) return false

        return true
    }

    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + (isContracted?.hashCode() ?: 0)
        result = 31 * result + (processor?.hashCode() ?: 0)
        result = 31 * result + (hyphenationDictionary?.hashCode() ?: 0)
        result = 31 * result + (numberSign?.hashCode() ?: 0)
        result = 31 * result + (continuousPoetry?.hashCode() ?: 0)
        result = 31 * result + (content?.hashCode() ?: 0)
        result = 31 * result + (page?.hashCode() ?: 0)
        result = 31 * result + (conventions?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "EmbossedBrailleScriptureSchema(isContracted=$isContracted, processor=$processor, hyphenationDictionary=$hyphenationDictionary, numberSign=$numberSign, continuousPoetry=$continuousPoetry, content=$content, page=$page, conventions=$conventions)"
    }
}
