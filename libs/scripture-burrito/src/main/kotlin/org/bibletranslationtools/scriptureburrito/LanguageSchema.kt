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
        @SerialName("adlm")
        ADLM("adlm"),
        @SerialName("ahom")
        AHOM("ahom"),
        @SerialName("arab")
        ARAB("arab"),
        @SerialName("arabext")
        ARABEXT("arabext"),
        @SerialName("armn")
        ARMN("armn"),
        @SerialName("armnlow")
        ARMNLOW("armnlow"),
        @SerialName("bali")
        BALI("bali"),
        @SerialName("beng")
        BENG("beng"),
        @SerialName("bhks")
        BHKS("bhks"),
        @SerialName("brah")
        BRAH("brah"),
        @SerialName("cakm")
        CAKM("cakm"),
        @SerialName("cham")
        CHAM("cham"),
        @SerialName("cyrl")
        CYRL("cyrl"),
        @SerialName("deva")
        DEVA("deva"),
        @SerialName("ethi")
        ETHI("ethi"),
        @SerialName("finance")
        FINANCE("finance"),
        @SerialName("fullwide")
        FULLWIDE("fullwide"),
        @SerialName("geor")
        GEOR("geor"),
        @SerialName("gong")
        GONG("gong"),
        @SerialName("gonm")
        GONM("gonm"),
        @SerialName("grek")
        GREK("grek"),
        @SerialName("greklow")
        GREKLOW("greklow"),
        @SerialName("gujr")
        GUJR("gujr"),
        @SerialName("guru")
        GURU("guru"),
        @SerialName("hanidays")
        HANIDAYS("hanidays"),
        @SerialName("hanidec")
        HANIDEC("hanidec"),
        @SerialName("hans")
        HANS("hans"),
        @SerialName("hansfin")
        HANSFIN("hansfin"),
        @SerialName("hant")
        HANT("hant"),
        @SerialName("hantfin")
        HANTFIN("hantfin"),
        @SerialName("hebr")
        HEBR("hebr"),
        @SerialName("hmng")
        HMNG("hmng"),
        @SerialName("hmnp")
        HMNP("hmnp"),
        @SerialName("java")
        JAVA("java"),
        @SerialName("jpan")
        JPAN("jpan"),
        @SerialName("jpanfin")
        JPANFIN("jpanfin"),
        @SerialName("jpanyear")
        JPANYEAR("jpanyear"),
        @SerialName("kali")
        KALI("kali"),
        @SerialName("khmr")
        KHMR("khmr"),
        @SerialName("knda")
        KNDA("knda"),
        @SerialName("lana")
        LANA("lana"),
        @SerialName("lanatham")
        LANATHAM("lanatham"),
        @SerialName("laoo")
        LAOO("laoo"),
        @SerialName("latn")
        LATN("latn"),
        @SerialName("lepc")
        LEPC("lepc"),
        @SerialName("limb")
        LIMB("limb"),
        @SerialName("mathbold")
        MATHBOLD("mathbold"),
        @SerialName("mathdbl")
        MATHDBL("mathdbl"),
        @SerialName("mathmono")
        MATHMONO("mathmono"),
        @SerialName("mathsanb")
        MATHSANB("mathsanb"),
        @SerialName("mathsans")
        MATHSANS("mathsans"),
        @SerialName("mlym")
        MLYM("mlym"),
        @SerialName("modi")
        MODI("modi"),
        @SerialName("mong")
        MONG("mong"),
        @SerialName("mroo")
        MROO("mroo"),
        @SerialName("mtei")
        MTEI("mtei"),
        @SerialName("mymr")
        MYMR("mymr"),
        @SerialName("mymrshan")
        MYMRSHAN("mymrshan"),
        @SerialName("mymrtlng")
        MYMRTLNG("mymrtlng"),
        @SerialName("native")
        NATIVE("native"),
        @SerialName("newa")
        NEWA("newa"),
        @SerialName("nkoo")
        NKOO("nkoo"),
        @SerialName("olck")
        OLCK("olck"),
        @SerialName("orya")
        ORYA("orya"),
        @SerialName("osma")
        OSMA("osma"),
        @SerialName("rohg")
        ROHG("rohg"),
        @SerialName("roman")
        ROMAN("roman"),
        @SerialName("romanlow")
        ROMANLOW("romanlow"),
        @SerialName("saur")
        SAUR("saur"),
        @SerialName("shrd")
        SHRD("shrd"),
        @SerialName("sind")
        SIND("sind"),
        @SerialName("sinh")
        SINH("sinh"),
        @SerialName("sora")
        SORA("sora"),
        @SerialName("sund")
        SUND("sund"),
        @SerialName("takr")
        TAKR("takr"),
        @SerialName("talu")
        TALU("talu"),
        @SerialName("taml")
        TAML("taml"),
        @SerialName("tamldec")
        TAMLDEC("tamldec"),
        @SerialName("telu")
        TELU("telu"),
        @SerialName("thai")
        THAI("thai"),
        @SerialName("tirh")
        TIRH("tirh"),
        @SerialName("tibt")
        TIBT("tibt"),
        @SerialName("traditio")
        TRADITIO("traditio"),
        @SerialName("vaii")
        VAII("vaii"),
        @SerialName("wara")
        WARA("wara"),
        @SerialName("wcho")
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
        @SerialName("ltr")
        LTR("ltr"),
        @SerialName("rtl")
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
