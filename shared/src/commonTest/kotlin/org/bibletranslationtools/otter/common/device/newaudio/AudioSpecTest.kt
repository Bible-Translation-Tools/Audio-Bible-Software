package org.bibletranslationtools.otter.common.device.newaudio

import kotlin.test.Test
import kotlin.test.assertEquals

class AudioSpecTest {

    @Test
    fun testBytesPerFrameCalculation() {
        val spec16Mono = AudioSpec(sampleRate = 44100, bitDepth = 16, channels = 1)
        assertEquals(2, spec16Mono.bytesPerFrame)

        val spec24Stereo = AudioSpec(sampleRate = 48000, bitDepth = 24, channels = 2)
        assertEquals(6, spec24Stereo.bytesPerFrame) // 3 bytes per sample * 2 channels
    }

    @Test
    fun testTimeToFrameConversion() {
        val spec = AudioSpec(sampleRate = 44100, bitDepth = 16, channels = 1)
        val ms = 1000
        val expectedFrames = 44100L

        assertEquals(expectedFrames, spec.msToFrames(ms))
        assertEquals(ms, spec.framesToMs(expectedFrames))
    }

    @Test
    fun test48kConversion() {
        val spec = AudioSpec(sampleRate = 48000)
        assertEquals(48000L, spec.msToFrames(1000))
    }
}