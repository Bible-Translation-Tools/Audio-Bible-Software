package org.bibletranslationtools.scriptureburrito.flavor.scripture.audio

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName


@Serializable
class AudioFormat(
    @SerialName("compression")
    var compression: Compression
) {

    @SerialName("trackConfiguration")
    var trackConfiguration: TrackConfiguration? = null

    @SerialName("bitRate")
    var bitRate: Int? = null

    @SerialName("bitDepth")
    var bitDepth: Int? = null

    @SerialName("samplingRate")
    var samplingRate: Int? = null

    @SerialName("timingDir")
    var timingDir: String? = null
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioFormat) return false

        if (compression != other.compression) return false
        if (trackConfiguration != other.trackConfiguration) return false
        if (bitRate != other.bitRate) return false
        if (bitDepth != other.bitDepth) return false
        if (samplingRate != other.samplingRate) return false
        if (timingDir != other.timingDir) return false

        return true
    }

    override fun hashCode(): Int {
        var result = compression.hashCode()
        result = 31 * result + (trackConfiguration?.hashCode() ?: 0)
        result = 31 * result + (bitRate ?: 0)
        result = 31 * result + (bitDepth ?: 0)
        result = 31 * result + (samplingRate ?: 0)
        result = 31 * result + (timingDir?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "AudioFormat(compression=$compression, trackConfiguration=$trackConfiguration, bitRate=$bitRate, bitDepth=$bitDepth, samplingRate=$samplingRate, timingDir=$timingDir)"
    }
}