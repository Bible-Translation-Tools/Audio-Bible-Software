package org.bibletranslationtools.orature.ui.narration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.data.workbook.Chapter
import org.bibletranslationtools.otter.common.data.workbook.Workbook
import org.bibletranslationtools.otter.common.device.AudioPlayerConnection
import org.bibletranslationtools.otter.common.device.AudioPlayerConnectionFactory
import org.bibletranslationtools.otter.common.device.AudioRecorderConnection
import org.bibletranslationtools.otter.common.device.AudioRecorderConnectionFactory
import org.bibletranslationtools.otter.common.domain.audio.AudioBouncer
import org.bibletranslationtools.otter.common.domain.narration.AudioFileUtils
import org.bibletranslationtools.otter.common.domain.narration.Narration
import org.bibletranslationtools.otter.common.domain.narration.SplitAudioOnCues
import java.util.concurrent.atomic.AtomicInteger

/**
 * Builds a [Narration] for a (workbook, chapter). The JVM app used a Dagger `@AssistedFactory`
 * (`NarrationFactory`), which is NOT bound in this port's Koin graph — so we construct the
 * `Narration` directly here, resolving its non-assisted deps from Koin and wrapping the shared
 * device factories in [AudioRecorderConnection] / [AudioPlayerConnection] (the same connection
 * pattern the recorder/playback ViewModels use — `Narration` was migrated to the
 * `otter.common.device` layer, so the connections satisfy it directly). Registered as a Koin `single`.
 */
class OratureNarrationFactory(
    private val directoryProvider: ITempFileProvider,
    private val splitAudioOnCues: SplitAudioOnCues,
    private val audioFileUtils: AudioFileUtils,
    private val audioBouncer: AudioBouncer,
    private val recorderConnectionFactory: AudioRecorderConnectionFactory,
    private val playerConnectionFactory: AudioPlayerConnectionFactory
) {
    private val connectionIds = AtomicInteger(0)

    /**
     * Create a fresh [Narration] whose recorder/player connections live on [scope] (typically
     * the owning ViewModel's `viewModelScope`). Call [Narration.initialize] before use, and
     * [Narration.close] when done.
     */
    fun create(workbook: Workbook, chapter: Chapter, scope: CoroutineScope): Narration {
        val id = connectionIds.incrementAndGet()
        val recorder = AudioRecorderConnection(id, recorderConnectionFactory, scope)
        // Run audio-control ops off the UI thread (matches the recorder's PlaybackViewModel);
        // keeps sink open/seek work off AWT-EventQueue.
        val player = AudioPlayerConnection(id, playerConnectionFactory, scope, Dispatchers.Default)
        return Narration(
            directoryProvider,
            splitAudioOnCues,
            audioFileUtils,
            audioBouncer,
            recorder,
            player,
            workbook,
            chapter
        )
    }
}
