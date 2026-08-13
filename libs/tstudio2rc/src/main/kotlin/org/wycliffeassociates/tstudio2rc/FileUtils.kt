package org.wycliffeassociates.tstudio2rc

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

internal const val MANIFEST_YAML = "manifest.yaml"

internal fun loadJson(path: String): JsonNode {
    val mapper = ObjectMapper(JsonFactory())
        .registerKotlinModule()

    return mapper.readTree(File(path))
}

// Returns true if the specified path looks like a collection of chapter folders
internal fun isBookFolder(path: String): Boolean {
    return File(path).resolve("front").isDirectory || File(path).resolve("01").isDirectory
}

internal fun zipDirectory(sourceDir: File, zipFile: File) {
    zipFile.createNewFile()
    ZipOutputStream(zipFile.outputStream()).use { zos ->
        sourceDir.walkTopDown().forEach { file ->
            if (file.isFile) {
                val entryPath = file.relativeTo(sourceDir).invariantSeparatorsPath
                val zipEntry = ZipEntry(entryPath)
                zos.putNextEntry(zipEntry)
                file.inputStream().use { input ->
                    input.copyTo(zos)
                }
            }
        }
    }
}

internal fun unzipFile(file: File, destinationDir: File) {
    ZipFile(file).use { zip ->
        zip.entries().asSequence().forEach { entry ->
            val entryDestination = destinationDir.resolve(entry.name)
            entryDestination.parentFile.mkdirs()
            if (entry.isDirectory) {
                entryDestination.mkdir()
            } else {
                zip.getInputStream(entry).use { input ->
                    Files.newOutputStream(entryDestination.toPath()).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}