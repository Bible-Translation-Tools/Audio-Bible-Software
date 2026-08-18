package org.bibletranslationtools.scriptureburrito.flavor.scripture.print

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.SerialName

import org.bibletranslationtools.scriptureburrito.flavor.FlavorSchema

@Serializable
@SerialName("typesetScripture")
class TypesetScriptureSchema: FlavorSchema() {

    @SerialName("contentType")
    var contentType: String? = null

    @SerialName("pod")
    var pod: Boolean? = null

    @SerialName("pageCount")
    var pageCount: Int? = null

    @SerialName("width")
    var width: String? = null

    @SerialName("height")
    var height: String? = null

    @SerialName("scale")
    var scale: String? = null

    @SerialName("orientation")
    var orientation: Orientation? = null

    @SerialName("colorSpace")
    var colorSpace: ColorSpace? = null

    @SerialName("edgeSpace")
    private var edgeSpace: EdgeSpace? = null

    @SerialName("fonts")
    var fonts: MutableList<String>? = ArrayList()

    @SerialName("conventions")
    private var conventions: JsonElement? = null

    fun getEdgeSpace(): EdgeSpace? {
        return edgeSpace
    }

    fun setEdgeSpace(edgeSpace: EdgeSpace?) {
        this.edgeSpace = edgeSpace
    }

    fun getConventions(): JsonElement? {
        return conventions
    }

    fun setConventions(conventions: JsonElement?) {
        this.conventions = conventions
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TypesetScriptureSchema) return false

        if (contentType != other.contentType) return false
        if (pod != other.pod) return false
        if (pageCount != other.pageCount) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (scale != other.scale) return false
        if (orientation != other.orientation) return false
        if (colorSpace != other.colorSpace) return false
        if (edgeSpace != other.edgeSpace) return false
        if (fonts != other.fonts) return false
        if (conventions != other.conventions) return false

        return true
    }

    override fun hashCode(): Int {
        var result = 0
        result = 31 * result + (contentType?.hashCode() ?: 0)
        result = 31 * result + (pod?.hashCode() ?: 0)
        result = 31 * result + (pageCount ?: 0)
        result = 31 * result + (width?.hashCode() ?: 0)
        result = 31 * result + (height?.hashCode() ?: 0)
        result = 31 * result + (scale?.hashCode() ?: 0)
        result = 31 * result + (orientation?.hashCode() ?: 0)
        result = 31 * result + (colorSpace?.hashCode() ?: 0)
        result = 31 * result + (edgeSpace?.hashCode() ?: 0)
        result = 31 * result + (fonts?.hashCode() ?: 0)
        result = 31 * result + (conventions?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "TypesetScriptureSchema(contentType=$contentType, pod=$pod, pageCount=$pageCount, width=$width, height=$height, scale=$scale, orientation=$orientation, colorSpace=$colorSpace, edgeSpace=$edgeSpace, fonts=$fonts, conventions=$conventions)"
    }


    enum class ColorSpace(private val value: String) {
        @SerialName("cmyk")
        CMYK("cmyk"),
        @SerialName("rgb")
        RGB("rgb");

        override fun toString(): String {
            return this.value
        }

        fun value(): String {
            return this.value
        }

        companion object {
            private val CONSTANTS: MutableMap<String, ColorSpace> = HashMap()

            init {
                for (c in values()) {
                    CONSTANTS[c.value] = c
                }
            }

            fun fromValue(value: String): ColorSpace {
                val constant = CONSTANTS[value]
                requireNotNull(constant) { value }
                return constant
            }
        }
    }

    enum class Orientation(private val value: String) {
        @SerialName("portrait")
        PORTRAIT("portrait"),
        @SerialName("landscape")
        LANDSCAPE("landscape");

        override fun toString(): String {
            return this.value
        }

        fun value(): String {
            return this.value
        }

        companion object {
            private val CONSTANTS: MutableMap<String, Orientation> = HashMap()

            init {
                for (c in values()) {
                    CONSTANTS[c.value] = c
                }
            }

            fun fromValue(value: String): Orientation {
                val constant = CONSTANTS[value]
                requireNotNull(constant) { value }
                return constant
            }
        }
    }
}
