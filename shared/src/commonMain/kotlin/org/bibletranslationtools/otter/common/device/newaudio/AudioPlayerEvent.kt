package org.bibletranslationtools.otter.common.device.newaudio

/**
 * Events emitted by the player to observers.
 */
sealed class AudioPlayerEvent {
    object Load : AudioPlayerEvent()
    object Play : AudioPlayerEvent()
    object Pause : AudioPlayerEvent()
    object Stop : AudioPlayerEvent()
    object Complete : AudioPlayerEvent()
    data class Error(val message: String, val cause: Throwable? = null) : AudioPlayerEvent()
}