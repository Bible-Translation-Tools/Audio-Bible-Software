package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName


@Serializable
enum class Format(private val value: String) {
    @SerialName("scripture burrito")
    SCRIPTURE_BURRITO("scripture burrito");

    override fun toString(): String {
        return this.value
    }

    fun value(): String {
        return this.value
    }

    companion object {
        private val CONSTANTS: MutableMap<String, Format> = HashMap()

        init {
            for (c in values()) {
                org.bibletranslationtools.scriptureburrito.Format.Companion.CONSTANTS[c.value] = c
            }
        }

        fun fromValue(value: String): Format {
            val constant = org.bibletranslationtools.scriptureburrito.Format.Companion.CONSTANTS[value]
            requireNotNull(constant) { value }
            return constant
        }
    }
}