package org.bibletranslationtools.scriptureburrito.flavor.parascriptural

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.SerialName



@Serializable
class WordAlignmentSchema {

    @SerialName("name")
    var name: JsonElement? = null

    @SerialName("autoAlignerVersion")
    var autoAlignerVersion: String? = null

    @SerialName("stopWords")
    var stopWords: Boolean? = null

    @SerialName("stemmer")
    private var stemmer: Stemmer? = null

    @SerialName("manualAlignment")
    private var manualAlignment: ManualAlignment? = null

    fun getStemmer(): Stemmer? {
        return stemmer
    }

    fun setStemmer(stemmer: Stemmer?) {
        this.stemmer = stemmer
    }

    fun getManualAlignment(): ManualAlignment? {
        return manualAlignment
    }

    fun setManualAlignment(manualAlignment: ManualAlignment?) {
        this.manualAlignment = manualAlignment
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WordAlignmentSchema) return false

        if (name != other.name) return false
        if (autoAlignerVersion != other.autoAlignerVersion) return false
        if (stopWords != other.stopWords) return false
        if (stemmer != other.stemmer) return false
        if (manualAlignment != other.manualAlignment) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name?.hashCode() ?: 0
        result = 31 * result + (autoAlignerVersion?.hashCode() ?: 0)
        result = 31 * result + (stopWords?.hashCode() ?: 0)
        result = 31 * result + (stemmer?.hashCode() ?: 0)
        result = 31 * result + (manualAlignment?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "WordAlignmentSchema(name=$name, autoAlignerVersion=$autoAlignerVersion, stopWords=$stopWords, stemmer=$stemmer, manualAlignment=$manualAlignment)"
    }
}
