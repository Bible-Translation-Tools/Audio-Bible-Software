package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

import java.util.HashMap

@Serializable
enum class NormalizationSchema(private val value: String) {
    @SerialName("NFC")
    NFC("NFC"),
    @SerialName("NFD")
    NFD("NFD"),
    @SerialName("NFKC")
    NFKC("NFKC"),
    @SerialName("NFKD")
    NFKD("NFKD");

    override fun toString(): String {
        return this.value
    }

    fun value(): String {
        return this.value
    }

    companion object {
        private val CONSTANTS: MutableMap<String, NormalizationSchema> = HashMap()

        init {
            for (c in values()) {
                CONSTANTS[c.value] = c
            }
        }

        fun fromValue(value: String): NormalizationSchema {
            val constant = CONSTANTS[value]
            requireNotNull(constant) { value }
            return constant
        }
    }
}