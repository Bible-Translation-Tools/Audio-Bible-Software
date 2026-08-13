package org.wycliffeassociates.tstudio2rc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.wycliffeassociates.resourcecontainer.entity.Manifest
import org.wycliffeassociates.tstudio2rc.TextToUSFM.Companion.getVersification
import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory

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
        val mapper = ObjectMapper(YAMLFactory())
            .registerKotlinModule()
        mapper.writeValue(manifestFile, manifest)

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
        val mapper = ObjectMapper(YAMLFactory())
            .registerKotlinModule()
        mapper.writeValue(manifestFile, manifest)

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
                ZipFile(file).use {
                    it.entries().asSequence().any { entry ->
                        entry.name.contains("manifest.json")
                    }
                }
            }

            file.isDirectory -> file.walk().any { it.name == "manifest.json" }

            else -> false
        }
    }
}
