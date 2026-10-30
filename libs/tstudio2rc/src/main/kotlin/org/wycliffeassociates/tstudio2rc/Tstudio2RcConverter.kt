package org.wycliffeassociates.tstudio2rc

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import org.wycliffeassociates.resourcecontainer.entity.Manifest
import org.wycliffeassociates.tstudio2rc.TextToUSFM.Companion.getVersification
import org.wycliffeassociates.tstudio2rc.entity.ProjectManifest
import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory

/**
 * Writes the resource-container manifest.yaml this converter produces. Mirrors the codec in
 * :libs:resource-container — encodeDefaults reproduces Jackson's default inclusion, and the
 * entity classes there carry @EncodeDefault(NEVER) where a key should be omitted instead.
 */
private val RC_YAML = Yaml(configuration = YamlConfiguration(strictMode = false, encodeDefaults = true))

/**
 * API for converting a tstudio project to resource container(s).
 */
object Tstudio2RcConverter {

    private val verseCounts = getVersification()

    fun convertFileToRC(inputFile: File, outputDir: File): File {
        outputDir.mkdirs()
        val tempDir = createTempDirectory(outputDir.toPath(), "extract-temp").toFile()

        val rcConvertDir = outputDir.resolve(inputFile.nameWithoutExtension)
        val outputFile = outputDir.resolve("${inputFile.nameWithoutExtension}.zip")
        rcConvertDir.mkdirs()

        val sourceDir = extractTstudio(inputFile, tempDir)
        val converter = TextToUSFM()
        converter.convertFolder(sourceDir, rcConvertDir.absolutePath)

        // manifest.yaml
        val manifest = buildManifest(sourceDir)
        val manifestFile = rcConvertDir.resolve(MANIFEST_YAML)
        manifestFile.writeText(RC_YAML.encodeToString(Manifest.serializer(), manifest))

        zipDirectory(rcConvertDir, outputFile)

        tempDir.deleteRecursively()
        rcConvertDir.deleteRecursively()

        return outputFile
    }

    /**
     * Converts the project's root directory to a Resource Container.
     * The directory should contain a manifest file and chapter directories.
     */
    fun convertDirToRC(inputDir: File, outputDir: File): File {
        val outputRc = outputDir.resolve("${inputDir.name}.zip")

        val tempConvertDir = outputDir.resolve(inputDir.name)
        tempConvertDir.mkdirs()

        val converter = TextToUSFM()
        converter.convertFolder(inputDir.invariantSeparatorsPath, tempConvertDir.invariantSeparatorsPath)

        // manifest.yaml
        val manifest = buildManifest(inputDir.invariantSeparatorsPath)
        val manifestFile = tempConvertDir.resolve(MANIFEST_YAML)
        manifestFile.writeText(RC_YAML.encodeToString(Manifest.serializer(), manifest))

        zipDirectory(tempConvertDir, outputRc)
        tempConvertDir.deleteRecursively()

        return outputRc
    }

    // source: txt2USFM-RC.py
    private fun makeUsfmFilename(bookSlug: String): String {
        val bookId = bookSlug.uppercase()

        val num = verseCounts[bookId]?.usfmNumber ?: ""
        return if (num.isNotEmpty()) {
            "$num-$bookId.usfm"
        } else {
            val pathComponents = File("").absolutePath.split("/")
            "${pathComponents.last()}.usfm"
        }
    }

    // constructs the manifest based on the manifest.json in project directory
    private fun buildManifest(dir: String): Manifest {
        val tstudioMetadata = TstudioMetadata(dir)
        val manifest = tstudioMetadata.rcManifest()
        manifest.dublinCore.creator = "BTT-Writer"

        val project = tstudioMetadata.rcProject
        val projectSlug = project.identifier
        val projectPath = "./${makeUsfmFilename(projectSlug)}"
        val anthology = if ((verseCounts[projectSlug.uppercase()]?.sort ?: 0) < 40) "ot" else "nt"

        manifest.projects.forEach { p ->
            if (p.identifier == projectSlug) {
                p.title = project.title.ifEmpty { projectSlug }
                p.path = projectPath
                p.sort = verseCounts[projectSlug.uppercase()]?.sort ?: 0
                p.versification = "ufw"
                p.categories = listOf("bible-$anthology")
            }
        }

        return manifest
    }

    // unzip project and returns the extracted path
    private fun extractTstudio(file: File, destinationDir: File): String {
        unzipFile(file, destinationDir)
        return prepareProjectDir(destinationDir)!!
    }

    private fun prepareProjectDir(dir: File): String? {
        // remove .git directory
        dir.walk().firstOrNull { path ->
            if (path.isDirectory && path.name == ".git") {
                path.deleteRecursively()
                true // stop when encountering .git folder
            } else {
                false
            }
        }

        return dir.walk()
            .firstOrNull { f ->
                f.isDirectory && isBookFolder(f.invariantSeparatorsPath)
            }
            ?.invariantSeparatorsPath
    }

    fun isValidFormat(file: File): Boolean {
        return when {
            file.isFile && TstudioFileFormat.isSupported(file.extension) -> {
                runCatching {
                    ZipFile(file).use { zip ->
                        zip.entries()
                            .asSequence()
                            .filter { !it.isDirectory && File(it.name).name == MANIFEST_JSON }
                            .any { entry ->
                                zip.getInputStream(entry).use { isTstudioManifest(it.readBytes().decodeToString()) }
                            }
                    }
                }.getOrDefault(false)
            }

            file.isDirectory -> file.walk()
                .filter { it.isFile && it.name == MANIFEST_JSON }
                .any { isTstudioManifest(it.readText()) }

            else -> false
        }
    }

    /**
     * A tstudio project is identified by a `manifest.json` that actually deserializes into a
     * [ProjectManifest] — the same schema (and codec) the converter reads in [TstudioMetadata].
     *
     * Matching on the file name alone (the previous behavior) misclassified any archive that
     * merely contained a file called `manifest.json`, e.g. a legacy BTT Recorder project or an
     * unrelated zip. Reusing the converter's own decode keeps detection in lockstep with what
     * [convertFileToRC] can actually convert: if this returns true, the manifest is one the
     * converter can read; if the manifest is not tstudio-shaped, the decode fails and this
     * returns false rather than claiming the file as tstudio.
     */
    private fun isTstudioManifest(text: String): Boolean =
        runCatching { JSON.decodeFromString(ProjectManifest.serializer(), text) }.isSuccess
}
