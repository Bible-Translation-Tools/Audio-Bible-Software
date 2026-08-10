package org.bibletranslationtools.otter.common.device

import kotlinx.coroutines.flow.StateFlow

/**
 * The observations [AudioTransportHarness] needs from whichever mock sink a test is driving, so the
 * harness's helpers (`awaitWrites`, `framesWritten`, `sinkCalls`, the hold/release gates) work the same
 * against a simple paced sink and a buffered one.
 */
interface ObservableAudioSink : AudioSink {

    /** Number of completed [write] calls — one per buffer the playback loop has pushed. */
    val writes: StateFlow<Int>

    /** Total frames pushed across every play session, never reset. */
    val framesWritten: StateFlow<Long>

    /** Lifecycle calls (`open`, `start`, `stop`, `drain`, `flush`, `close`) in order, writes excluded. */
    val calls: StateFlow<List<String>>

    /** True once [open] has been entered — so a test can know the player holds its mutex. */
    val openEntered: StateFlow<Boolean>

    /** Makes the next [open] block until [releaseOpen] is called. */
    fun holdOpen()

    fun releaseOpen()
}
