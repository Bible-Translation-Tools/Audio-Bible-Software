package org.bibletranslationtools.otter.common.io

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import java.io.File


suspend fun saveAudioToFile(
    platformFile: PlatformFile,
    targetDirectory: File
): File {
    // 1. Ensure the directory exists
    if (!targetDirectory.exists()) {
        targetDirectory.mkdirs()
    }

    // 2. Create the destination file using the original name
    val destinationFile = File(targetDirectory, platformFile.name)

    // 3. Read the bytes and write them to our java.io.File
    val bytes = platformFile.readBytes()
    destinationFile.writeBytes(bytes)

    return destinationFile
}