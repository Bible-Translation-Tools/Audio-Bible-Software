package org.bibletranslationtools.otter.common.domain.wacs.fake

import org.bibletranslationtools.otter.common.api.persistence.IAppDirectories
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.bibletranslationtools.otter.common.data.audio.BookMarker
import org.bibletranslationtools.otter.common.data.audio.ChapterMarker
import org.bibletranslationtools.otter.common.data.audio.VerseMarker
import org.bibletranslationtools.otter.common.domain.audio.OratureAudioFile
import java.io.File

/** Temp files under [root], for the use cases that stage work there. */
class TestTempFiles(root: File) : ITempFileProvider {
    override val tempDirectory: File = File(root, "temp").apply { mkdirs() }

    override fun createTempFile(prefix: String, suffix: String?): File = File.createTempFile(prefix, suffix, tempDirectory)

    override fun cleanTempDirectory() {
        tempDirectory.listFiles()?.forEach { it.deleteRecursively() }
    }
}

/** App directories under [root], for the use cases that cache clones there. */
class TestAppDirectories(private val root: File) : IAppDirectories {
    override fun getUserDataDirectory(appendedPath: String) = dir("user/$appendedPath")
    override fun getAppDataDirectory(appendedPath: String) = dir("app/$appendedPath")
    override val databaseDirectory get() = dir("database")
    override val versificationDirectory get() = dir("versification")
    override val audioPluginDirectory get() = dir("plugins")
    override val userProfileImageDirectory get() = dir("profile-images")
    override val userProfileAudioDirectory get() = dir("profile-audio")
    override val logsDirectory get() = dir("logs")
    override val cacheDirectory get() = dir("cache")

    private fun dir(path: String) = File(root, path).apply { mkdirs() }
}

/**
 * A chapter take as the recorder leaves one: a WAV at [file] with book, chapter and verse markers
 * ([verses] of them). No audio frames, which publishing doesn't need.
 */
fun chapterWav(file: File, book: String, chapter: Int, verses: Int = 3): File {
    file.parentFile?.mkdirs()
    val audio = OratureAudioFile(file, 1, 44100, 16)
    audio.addMarker(BookMarker(book, 0))
    audio.addMarker(ChapterMarker(chapter, 0))
    (1..verses).forEach { audio.addMarker(VerseMarker(it, it, 0)) }
    audio.update()
    return file
}
