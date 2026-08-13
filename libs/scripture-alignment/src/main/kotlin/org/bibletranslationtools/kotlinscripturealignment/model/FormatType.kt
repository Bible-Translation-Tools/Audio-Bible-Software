package org.bibletranslationtools.kotlinscripturealignment.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * @SerialName replaces the @JsonValue/@JsonCreator pair: kotlinx encodes an enum entry by its
 * serial name and decodes the same way, which is what value()/fromValue() were doing by hand.
 * Those two stay public because callers use them directly.
 */
@Serializable
enum class FormatType(private val value: String) {
    @SerialName("alignment")
    ALIGNMENT("alignment");

    override fun toString(): String {
        return this.value
    }

    fun value(): String {
        return this.value
    }

    companion object {
        private val CONSTANTS: MutableMap<String, FormatType> = HashMap()

        init {
            for (c in values()) {
                CONSTANTS[c.value] = c
            }
        }

        fun fromValue(value: String): FormatType {
            val constant = CONSTANTS[value]
            requireNotNull(constant) { value }
            return constant
        }
    }
}
