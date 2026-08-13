package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName


@Serializable(with = LanguagesSerializer::class)
class Languages: ArrayList<LanguageSchema>()

@Serializable
class LanguageSchema(
    @SerialName("tag")
    var tag: String,
    
    @SerialName("name")
    var name: HashMap<String, String> = hashMapOf()
) {

    @SerialName("numberingSystem")
    var numberingSystem: NumberingSystemSchema? = null
    
    @SerialName("rod")
    var rod: String? = null

    @SerialName("scriptDirection")
    var scriptDirection: ScriptDirection? = null

    enum class NumberingSystemSchema(private val value: String) {
        ADLM("adlm"),
        AHOM("ahom"),
        ARAB("arab"),
        ARABEXT("arabext"),
        ARMN("armn"),
        ARMNLOW("armnlow"),
        BALI("bali"),
        BENG("beng"),
        BHKS("bhks"),
        BRAH("brah"),
        CAKM("cakm"),
        CHAM("cham"),
        CYRL("cyrl"),
        DEVA("deva"),
        ETHI("ethi"),
        FINANCE("finance"),
        FULLWIDE("fullwide"),
        GEOR("geor"),
        GONG("gong"),
        GONM("gonm"),
        GREK("grek"),
        GREKLOW("greklow"),
        GUJR("gujr"),
        GURU("guru"),
        HANIDAYS("hanidays"),
        HANIDEC("hanidec"),
        HANS("hans"),
        HANSFIN("hansfin"),
        HANT("hant"),
        HANTFIN("hantfin"),
        HEBR("hebr"),
        HMNG("hmng"),
        HMNP("hmnp"),
        JAVA("java"),
        JPAN("jpan"),
        JPANFIN("jpanfin"),
        JPANYEAR("jpanyear"),
        KALI("kali"),
        KHMR("khmr"),
        KNDA("knda"),
        LANA("lana"),
        LANATHAM("lanatham"),
        LAOO("laoo"),
        LATN("latn"),
        LEPC("lepc"),
        LIMB("limb"),
        MATHBOLD("mathbold"),
        MATHDBL("mathdbl"),
        MATHMONO("mathmono"),
        MATHSANB("mathsanb"),
        MATHSANS("mathsans"),
        MLYM("mlym"),
        MODI("modi"),
        MONG("mong"),
        MROO("mroo"),
        MTEI("mtei"),
        MYMR("mymr"),
        MYMRSHAN("mymrshan"),
        MYMRTLNG("mymrtlng"),
        NATIVE("native"),
        NEWA("newa"),
        NKOO("nkoo"),
        OLCK("olck"),
        ORYA("orya"),
        OSMA("osma"),
        ROHG("rohg"),
        ROMAN("roman"),
        ROMANLOW("romanlow"),
        SAUR("saur"),
        SHRD("shrd"),
        SIND("sind"),
        SINH("sinh"),
        SORA("sora"),
        SUND("sund"),
        TAKR("takr"),
        TALU("talu"),
        TAML("taml"),
        TAMLDEC("tamldec"),
        TELU("telu"),
        THAI("thai"),
        TIRH("tirh"),
        TIBT("tibt"),
        TRADITIO("traditio"),
        VAII("vaii"),
        WARA("wara"),
        WCHO("wcho");

        override fun toString(): String {
            return this.value
        }

        fun value(): String {
            return this.value
        }

        companion object {
            private val CONSTANTS: MutableMap<String, NumberingSystemSchema> = HashMap()

            init {
                for (c in values()) {
                    CONSTANTS[c.value] = c
                }
            }

            fun fromValue(value: String): NumberingSystemSchema {
                val constant = CONSTANTS[value]
                requireNotNull(constant) { value }
                return constant
            }
        }
    }

    enum class ScriptDirection(private val value: String) {
        LTR("ltr"),
        RTL("rtl");

        override fun toString(): String {
            return this.value
        }

        fun value(): String {
            return this.value
        }

        companion object {
            private val CONSTANTS: MutableMap<String, ScriptDirection> = HashMap()

            init {
                for (c in values()) {
                    CONSTANTS[c.value] = c
                }
            }

            fun fromValue(value: String): ScriptDirection {
                val constant = CONSTANTS[value]
                requireNotNull(constant) { value }
                return constant
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as LanguageSchema

        if (tag != other.tag) return false
        if (name != other.name) return false
        if (numberingSystem != other.numberingSystem) return false
        if (rod != other.rod) return false
        if (scriptDirection != other.scriptDirection) return false

        return true
    }

    override fun hashCode(): Int {
        var result = tag.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (numberingSystem?.hashCode() ?: 0)
        result = 31 * result + (rod?.hashCode() ?: 0)
        result = 31 * result + (scriptDirection?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "LanguageSchema(tag='$tag', name=$name, numberingSystem=$numberingSystem, rod=$rod, scriptDirection=$scriptDirection)"
    }
}
