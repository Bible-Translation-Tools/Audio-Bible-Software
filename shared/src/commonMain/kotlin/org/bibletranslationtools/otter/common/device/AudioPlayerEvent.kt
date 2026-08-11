package org.bibletranslationtools.otter.common.device

/**
 * Events emitted by the player to observers.
 */
sealed class AudioPlayerEvent {
    /**
     * An event together with the connection whose playback it describes.
     *
     * The worker is shared by every connection — playback, source audio, narration, take previews —
     * and emits onto one stream. Without an owner, a `Complete` raised for one connection reaches
     * every other connection's host, which cannot tell it apart from its own: the host then parks its
     * display at the end of a take that is still mid-playback. The owner is stamped at emission time,
     * because by the time a collector runs, the hardware may already belong to someone else.
     */
    internal data class Owned(val owner: Int?, val event: AudioPlayerEvent)

    object Load : AudioPlayerEvent()
    object Play : AudioPlayerEvent()
    object Pause : AudioPlayerEvent()
    object Stop : AudioPlayerEvent()
    object Complete : AudioPlayerEvent()
    data class Error(val message: String, val cause: Throwable? = null) : AudioPlayerEvent()
}