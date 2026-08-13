package org.wycliffeassociates.tstudio2rc

enum class TstudioFileFormat(val extension: String) {
    TSTUDIO("tstudio"),
    ZIP("zip");

    companion object {
        private val extensionList: List<String> = values().map { it.extension }

        fun isSupported(extension: String) = extension.lowercase() in extensionList
    }
}