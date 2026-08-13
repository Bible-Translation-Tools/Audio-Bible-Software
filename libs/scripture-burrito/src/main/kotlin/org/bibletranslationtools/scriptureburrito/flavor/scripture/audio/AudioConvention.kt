package org.bibletranslationtools.scriptureburrito.flavor.scripture.audio

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName


@Serializable
class AudioConventions {
    @SerialName("contentResourcesByChapter")
    var contentResourcesByChapter: String? = null

    @SerialName("bookDirs")
    var bookDirs: String? = null
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioConventions) return false

        if (contentResourcesByChapter != other.contentResourcesByChapter) return false
        if (bookDirs != other.bookDirs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = contentResourcesByChapter?.hashCode() ?: 0
        result = 31 * result + (bookDirs?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String {
        return "AudioConventions(contentResourcesByChapter=$contentResourcesByChapter, bookDirs=$bookDirs)"
    }
}
