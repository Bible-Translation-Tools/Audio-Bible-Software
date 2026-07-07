package org.bibletranslationtools.bttrecorder2.ui.playback

/** Formats a playback position as HH:MM:SS (shared by the VM and leaf composables). */
fun formatPlaybackTime(ms: Int): String {
    val seconds = ms / 1000
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return String.format("%02d:%02d:%02d", h, m, s)
}
