package org.bibletranslationtools.scriptureburrito

import org.bibletranslationtools.scriptureburrito.flavor.FlavorType
import org.bibletranslationtools.scriptureburrito.flavor.scripture.audio.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Jackson's JsonNode supported ["key"], [0] and asText() directly. These three keep every
// assertion below written the same way against kotlinx's JsonElement.
private operator fun JsonElement.get(key: String): JsonElement = jsonObject.getValue(key)
private operator fun JsonElement.get(index: Int): JsonElement = jsonArray[index]
private fun JsonElement.asText(): String = jsonPrimitive.content

class TestMetadataSubtypes {

    val copyright = CopyrightSchema().apply {
        this.shortStatements = mutableListOf(ShortStatement("CC BY-SA", "en"))
    }

    val formats = Formats().apply {
        this["format-wav"] = AudioFormat(Compression.WAV)
        this["format-mp3"] = AudioFormat(Compression.MP3)
    }
    val audioFlavor = TypeSchema(
        FlavorType(
            name = Flavor.SCRIPTURE,
            flavor = AudioFlavorSchema(),
            currentScope = ScopeSchema().apply {
                this["GEN"] = mutableListOf("1")
            }
        )
    )

    val enLanguage = Languages().apply {
        add(
            LanguageSchema("en")
        )
    }

    val derivedAudio = DerivedMetadataSchema(
        Format.SCRIPTURE_BURRITO,
        DerivedMetaSchema(
            dateCreated = Date.from(Instant.now()),
            version = MetaVersionSchema._1_0_0,
            defaultLocale = "en",
            generator = SoftwareAndUserInfoSchema().apply {
                softwareName = "test"
                softwareVersion = "1.0.0"
            }
        ),
        IdAuthoritiesSchema(),
        IdentificationSchema(),
        confidential = false,
        copyright = copyright,
        type = audioFlavor,
        languages = enLanguage
    )

    val sourceAudio = SourceMetadataSchema(
        Format.SCRIPTURE_BURRITO,
        SourceMetaSchema(
            dateCreated = Date.from(Instant.now()),
            version = MetaVersionSchema._1_0_0,
            defaultLocale = "en",
            generator = SoftwareAndUserInfoSchema().apply {
                softwareName = "test"
                softwareVersion = "1.0.0"
            }
        ),
        IdAuthoritiesSchema(),
        IdentificationSchema(),
        confidential = false,
        copyright = copyright,
        type = audioFlavor,
        languages = enLanguage
    )

    val templateAudio = TemplateMetadataSchema(
        Format.SCRIPTURE_BURRITO,
        TemplateMetaSchema(
            dateCreated = Date.from(Instant.now()),
            version = MetaVersionSchema._1_0_0,
            defaultLocale = "en",
            templateName = LocalizedText(short = hashMapOf("en" to "testTemplate")),
            generator = SoftwareAndUserInfoSchema().apply {
                softwareName = "test"
                softwareVersion = "1.0.0"
            }
        ),
        IdAuthoritiesSchema(),
        IdentificationSchema(),
        confidential = false,
        copyright = copyright,
        type = audioFlavor,
        languages = enLanguage
    )


    @Test
    fun testSerializesToSourceMetadata() {
        val audio = BURRITO_JSON.encodeToString(MetadataSchema.serializer(), sourceAudio)
        val read = BURRITO_JSON.decodeFromString(SourceMetadataSchema.serializer(), audio)
        assert(read is SourceMetadataSchema)
    }

    @Test
    fun testSerializesToTemplateMetadata() {
        val audio = BURRITO_JSON.encodeToString(MetadataSchema.serializer(), templateAudio)
        val read = BURRITO_JSON.decodeFromString(TemplateMetadataSchema.serializer(), audio)
        assert(read is TemplateMetadataSchema)
    }

    @Test
    fun testSerializesToDerivedMetadata() {
        val audio = BURRITO_JSON.encodeToString(MetadataSchema.serializer(), derivedAudio)
        val read = BURRITO_JSON.decodeFromString(DerivedMetadataSchema.serializer(), audio)
        assert(read is DerivedMetadataSchema)
    }

    @Test
    fun testDeserializesToSourceMetadata() {
        val audio = BURRITO_JSON.encodeToString(MetadataSchema.serializer(), sourceAudio)
        val read = BURRITO_JSON.parseToJsonElement(audio)
        assert(read["format"].asText() == Format.SCRIPTURE_BURRITO.value())
        assert(read["meta"]["category"].asText() == "source")
        assert(read["type"]["flavorType"]["name"].asText() == "scripture")
        assert(read["type"]["flavorType"]["flavor"]["name"].asText() == "audioTranslation")
        assert(read["languages"][0]["tag"].asText() == "en")
    }

    @Test
    fun testDeserializesToTemplateMetadata() {
        val audio = BURRITO_JSON.encodeToString(MetadataSchema.serializer(), templateAudio)
        val read = BURRITO_JSON.parseToJsonElement(audio)
        assert(read["format"].asText() == Format.SCRIPTURE_BURRITO.value())
        assert(read["meta"]["category"].asText() == "template")
        assert(read["type"]["flavorType"]["name"].asText() == "scripture")
        assert(read["type"]["flavorType"]["flavor"]["name"].asText() == "audioTranslation")
        assert(read["languages"][0]["tag"].asText() == "en")
    }

    @Test
    fun testDeserializesToDerivedMetadata() {
        val audio = BURRITO_JSON.encodeToString(MetadataSchema.serializer(), derivedAudio)
        val read = BURRITO_JSON.parseToJsonElement(audio)
        assert(read["format"].asText() == Format.SCRIPTURE_BURRITO.value())
        assert(read["meta"]["category"].asText() == "derived")
        assert(read["type"]["flavorType"]["name"].asText() == "scripture")
        assert(read["type"]["flavorType"]["flavor"]["name"].asText() == "audioTranslation")
        assert(read["languages"][0]["tag"].asText() == "en")
    }
}