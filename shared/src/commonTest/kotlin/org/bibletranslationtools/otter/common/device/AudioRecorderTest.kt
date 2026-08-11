package org.bibletranslationtools.otter.common.device

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class AudioRecorderTest {
    @Test
    fun testRecordingStream() = runTest {
        val source = MockAudioSource()
        val recorder = AudioRecorder(source, this)
        val spec = AudioSpec()

        recorder.start(spec)

        // Collect the first chunk of audio emitted
        val firstChunk = recorder.audioStream.first()

        assertTrue(firstChunk.isNotEmpty(), "Recorder should emit audio data")
        assertTrue(source.isStarted, "Source should be started")

        recorder.stop()
        assertTrue(!source.isOpen, "Source should be closed after stop")
    }
}