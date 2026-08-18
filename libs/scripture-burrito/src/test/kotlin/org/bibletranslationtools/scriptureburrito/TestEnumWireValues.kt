package org.bibletranslationtools.scriptureburrito

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The generated enums write wire values that differ from their Kotlin names — `_1_0_0` is
 * `"1.0.0"`, `COMMON_LITERARY` is `"common-literary"`, `ADLM` is `"adlm"`.
 *
 * Under Jackson that mapping came from `@JsonValue` on `value()` and `@JsonCreator` on
 * `fromValue()`. kotlinx reads neither, and without `@SerialName` it falls back to the Kotlin
 * constant name — so importing a real burrito failed with `MetaVersionSchema does not contain
 * element with name '1.0.0'`.
 */
class TestEnumWireValues {

    @Test
    fun `meta version uses its dotted value`() {
        assertEquals("\"1.0.0\"", Json.encodeToString(MetaVersionSchema.serializer(), MetaVersionSchema._1_0_0))
        assertEquals(
            MetaVersionSchema._1_0_0,
            Json.decodeFromString(MetaVersionSchema.serializer(), "\"1.0.0\"")
        )
    }

    @Test
    fun `a numeric region code keeps its leading zeroes`() {
        assertEquals("\"001\"", Json.encodeToString(Unm49Schema.serializer(), Unm49Schema._001))
        assertEquals(Unm49Schema._001, Json.decodeFromString(Unm49Schema.serializer(), "\"001\""))
    }

    /**
     * The invariant, checked against the sources rather than a hand-written list.
     *
     * These enums are generated from the burrito JSON schemas, so the failure mode is a
     * regenerated file quietly reintroducing constants with no `@SerialName` — 158 of them were
     * missing at once after the kotlinx migration. Nothing but a sweep notices that, and reading
     * the sources catches every enum in the library including ones added later.
     */
    @Test
    fun `every generated enum constant declares its wire value`() {
        val constant = Regex(
            """^[ \t]*(?:@SerialName\("((?:[^"\\]|\\.)*)"\)[ \t]*\r?\n[ \t]*)?""" +
                """([A-Za-z_][A-Za-z0-9_]*)\("((?:[^"\\]|\\.)*)"\)""",
            RegexOption.MULTILINE
        )

        val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        assertEquals("expected to find the generated sources", true, sources.isNotEmpty())

        val offenders = mutableListOf<String>()
        for (file in sources.filter { it.readText().contains("enum class") }) {
            for (match in constant.findAll(file.readText())) {
                val (serialName, name, wire) = match.destructured
                if (name == wire) continue
                if (serialName != wire) {
                    offenders += "${file.name}: $name should carry @SerialName(\"$wire\")"
                }
            }
        }
        assertEquals(emptyList<String>(), offenders)
    }
}
