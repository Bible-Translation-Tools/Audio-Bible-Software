package org.bibletranslationtools.scriptureburrito.flavor.scripture.text

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.SerialName

import org.bibletranslationtools.scriptureburrito.flavor.FlavorSchema

@Serializable
@SerialName("textTranslation")
class TextTranslationSchema: FlavorSchema() {

    @SerialName("projectType")
    var projectType: ProjectType? = null

    @SerialName("translationType")
    var translationType: TranslationType? = null

    @SerialName("audience")
    var audience: Audience? = null

    @SerialName("usfmVersion")
    var usfmVersion: String? = null

    @SerialName("conventions")
    private var conventions: JsonElement? = null

    fun getConventions(): JsonElement? {
        return conventions
    }

    fun setConventions(conventions: JsonElement?) {
        this.conventions = conventions
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TextTranslationSchema) return false

        if (projectType != other.projectType) return false
        if (translationType != other.translationType) return false
        if (audience != other.audience) return false
        if (usfmVersion != other.usfmVersion) return false
        if (conventions != other.conventions) return false

        return true
    }

    override fun hashCode(): Int {
        var result = 0
        result = 31 * result + (projectType?.hashCode() ?: 0)
        result = 31 * result + (translationType?.hashCode() ?: 0)
        result = 31 * result + (audience?.hashCode() ?: 0)
        result = 31 * result + (usfmVersion?.hashCode() ?: 0)
        result = 31 * result + (conventions?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "TextTranslationSchema(projectType=$projectType, translationType=$translationType, audience=$audience, usfmVersion=$usfmVersion, conventions=$conventions)"
    }

    enum class Audience(private val value: String) {
        BASIC("basic"),
        COMMON("common"),
        COMMON_LITERARY("common-literary"),
        LITERARY("literary"),
        LITURGICAL("liturgical"),
        CHILDREN("children");

        override fun toString(): String {
            return this.value
        }

        fun value(): String {
            return this.value
        }

        companion object {
            private val CONSTANTS: MutableMap<String, Audience> = HashMap()

            init {
                for (c in values()) {
                    CONSTANTS[c.value] = c
                }
            }

            fun fromValue(value: String): Audience {
                val constant = CONSTANTS[value]
                requireNotNull(constant) { value }
                return constant
            }
        }
    }

    enum class ProjectType(private val value: String) {
        STANDARD("standard"),
        DAUGHTER("daughter"),
        STUDY_BIBLE("studyBible"),
        STUDY_BIBLE_ADDITIONS("studyBibleAdditions"),
        BACK_TRANSLATION("backTranslation"),
        AUXILIARY("auxiliary"),
        TRANSLITERATION_MANUAL("transliterationManual"),
        TRANSLITERATION_WITH_ENCODER("transliterationWithEncoder");

        override fun toString(): String {
            return this.value
        }

        fun value(): String {
            return this.value
        }

        companion object {
            private val CONSTANTS: MutableMap<String, ProjectType> = HashMap()

            init {
                for (c in values()) {
                    CONSTANTS[c.value] = c
                }
            }

            fun fromValue(value: String): ProjectType {
                val constant = CONSTANTS[value]
                requireNotNull(constant) { value }
                return constant
            }
        }
    }

    enum class TranslationType(private val value: String) {
        FIRST_TRANSLATION("firstTranslation"),
        NEW_TRANSLATION("newTranslation"),
        REVISION("revision"),
        STUDY_OR_HELP_MATERIAL("studyOrHelpMaterial");

        override fun toString(): String {
            return this.value
        }

        fun value(): String {
            return this.value
        }

        companion object {
            private val CONSTANTS: MutableMap<String, TranslationType> = HashMap()

            init {
                for (c in values()) {
                    CONSTANTS[c.value] = c
                }
            }

            fun fromValue(value: String): TranslationType {
                val constant = CONSTANTS[value]
                requireNotNull(constant) { value }
                return constant
            }
        }
    }
}
