package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable



@Serializable
enum class Unm49Schema(private val value: String) {
    @SerialName("001")
    _001("001"),
    @SerialName("002")
    _002("002"),
    @SerialName("003")
    _003("003"),
    @SerialName("005")
    _005("005"),
    @SerialName("009")
    _009("009"),
    @SerialName("011")
    _011("011"),
    @SerialName("013")
    _013("013"),
    @SerialName("014")
    _014("014"),
    @SerialName("015")
    _015("015"),
    @SerialName("017")
    _017("017"),
    @SerialName("018")
    _018("018"),
    @SerialName("019")
    _019("019"),
    @SerialName("021")
    _021("021"),
    @SerialName("024")
    _024("024"),
    @SerialName("029")
    _029("029"),
    @SerialName("030")
    _030("030"),
    @SerialName("034")
    _034("034"),
    @SerialName("035")
    _035("035"),
    @SerialName("039")
    _039("039"),
    @SerialName("053")
    _053("053"),
    @SerialName("054")
    _054("054"),
    @SerialName("057")
    _057("057"),
    @SerialName("061")
    _061("061"),
    @SerialName("142")
    _142("142"),
    @SerialName("143")
    _143("143"),
    @SerialName("145")
    _145("145"),
    @SerialName("150")
    _150("150"),
    @SerialName("151")
    _151("151"),
    @SerialName("154")
    _154("154"),
    @SerialName("155")
    _155("155"),
    @SerialName("202")
    _202("202"),
    @SerialName("419")
    _419("419"),
    @SerialName("496")
    _496("496"),
    @SerialName("554")
    _554("554"),
    @SerialName("591")
    _591("591"),
    @SerialName("756")
    _756("756"),
    @SerialName("830")
    _830("830");

    override fun toString(): String {
        return this.value
    }

    fun value(): String {
        return this.value
    }

    companion object {
        private val CONSTANTS: MutableMap<String, Unm49Schema> = HashMap()

        init {
            for (c in values()) {
                CONSTANTS[c.value] = c
            }
        }

        fun fromValue(value: String): Unm49Schema {
            val constant = CONSTANTS[value]
            requireNotNull(constant) { value }
            return constant
        }
    }
}
