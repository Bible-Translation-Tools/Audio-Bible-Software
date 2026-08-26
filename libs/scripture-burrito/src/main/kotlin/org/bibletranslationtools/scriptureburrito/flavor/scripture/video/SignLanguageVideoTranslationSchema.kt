package org.bibletranslationtools.scriptureburrito.flavor.scripture.video

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.SerialName

import org.bibletranslationtools.scriptureburrito.flavor.FlavorSchema
import org.bibletranslationtools.scriptureburrito.flavor.scripture.audio.Formats

@Serializable
@SerialName("signLanguageVideoTranslation")
class SignLanguageVideoTranslationSchema: FlavorSchema() {

    @SerialName("contentByChapter")
    var contentByChapter: Boolean? = null

    @SerialName("formats")
    private var formats: Formats? = null

    @SerialName("conventions")
    private var conventions: JsonElement? = null

    fun getFormats(): Formats? {
        return formats
    }

    fun setFormats(formats: Formats?) {
        this.formats = formats
    }

    fun getConventions(): JsonElement? {
        return conventions
    }

    fun setConventions(conventions: JsonElement?) {
        this.conventions = conventions
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignLanguageVideoTranslationSchema) return false

        if (contentByChapter != other.contentByChapter) return false
        if (formats != other.formats) return false
        if (conventions != other.conventions) return false

        return true
    }

    override fun hashCode(): Int {
        var result = 0
        result = 31 * result + (contentByChapter?.hashCode() ?: 0)
        result = 31 * result + (formats?.hashCode() ?: 0)
        result = 31 * result + (conventions?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "SignLanguageVideoTranslationSchema(contentByChapter=$contentByChapter, formats=$formats, conventions=$conventions)"
    }
}
