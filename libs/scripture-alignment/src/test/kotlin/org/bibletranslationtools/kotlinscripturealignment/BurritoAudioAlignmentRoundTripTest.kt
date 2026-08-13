package org.bibletranslationtools.kotlinscripturealignment


import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import org.bibletranslationtools.kotlinscripturealignment.model.BurritoAudioAlignment
import kotlinx.serialization.json.Json

/** Mirrors the codec the library writes with, so round-trip comparisons stay meaningful. */
private val TEST_JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }

class BurritoAudioAlignmentRoundTripTest {

    

    @Test
    fun testAudioExample1RoundTrip() {
        val resource = javaClass.classLoader.getResource("audio-example1.json")
        val originalFile = File(resource!!.file)
        val originalAlignment = BurritoAudioAlignment.load(originalFile)

        val tempOutputFile = File.createTempFile("audio_example1_output", ".json")
        originalAlignment.write(tempOutputFile)

        val deserializedAlignment = BurritoAudioAlignment.load(tempOutputFile)
        
        val originalJson = TEST_JSON.encodeToString(BurritoAudioAlignment.serializer(), originalAlignment)
        val deserializedJson = TEST_JSON.encodeToString(BurritoAudioAlignment.serializer(), deserializedAlignment)

        val originalJsonNode = TEST_JSON.parseToJsonElement(originalJson)
        val deserializedJsonNode = TEST_JSON.parseToJsonElement(deserializedJson)

        assertEquals(originalJsonNode, deserializedJsonNode)

        tempOutputFile.delete()
    }

    @Test
    fun testAudioExample2RoundTrip() {
        val resource = javaClass.classLoader.getResource("audio-example2.json")
        val originalFile = File(resource!!.file)
        val originalAlignment = BurritoAudioAlignment.load(originalFile)

        val tempOutputFile = File.createTempFile("audio_example2_output", ".json")
        originalAlignment.write(tempOutputFile)

        val deserializedAlignment = BurritoAudioAlignment.load(tempOutputFile)
        
        val originalJson = TEST_JSON.encodeToString(BurritoAudioAlignment.serializer(), originalAlignment)
        val deserializedJson = TEST_JSON.encodeToString(BurritoAudioAlignment.serializer(), deserializedAlignment)

        val originalJsonNode = TEST_JSON.parseToJsonElement(originalJson)
        val deserializedJsonNode = TEST_JSON.parseToJsonElement(deserializedJson)

        assertEquals(originalJsonNode, deserializedJsonNode)

        tempOutputFile.delete()
    }

    @Test
    fun testApmExampleRoundTrip() {
        val resource = javaClass.classLoader.getResource("apm_example.json")
        val originalFile = File(resource!!.file)
        val originalAlignment = BurritoAudioAlignment.load(originalFile)

        val tempOutputFile = File.createTempFile("apm_example_output", ".json")
        originalAlignment.write(tempOutputFile)

        val deserializedAlignment = BurritoAudioAlignment.load(tempOutputFile)
        
        val originalJson = TEST_JSON.encodeToString(BurritoAudioAlignment.serializer(), originalAlignment)
        val deserializedJson = TEST_JSON.encodeToString(BurritoAudioAlignment.serializer(), deserializedAlignment)

        val originalJsonNode = TEST_JSON.parseToJsonElement(originalJson)
        val deserializedJsonNode = TEST_JSON.parseToJsonElement(deserializedJson)

        assertEquals(originalJsonNode, deserializedJsonNode)

        tempOutputFile.delete()
    }
}
