package org.bibletranslationtools.otter.common.domain.project.importer

import io.reactivex.Single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx2.rxSingle
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.bibletranslationtools.otter.common.domain.resourcecontainer.burrito.BurritoToResourceContainerConverter
import org.slf4j.LoggerFactory
import java.io.File

class BurritoImporter(
    private val directoryProvider: IDirectoryProvider,
    private val converter: BurritoToResourceContainerConverter,
): IProjectImporter {

    private val logger = LoggerFactory.getLogger(this.javaClass)
    private var next: RCImporter? = null

    override fun import(
        burrito: File,
        callback: ProjectImporterCallback?,
        options: ImportOptions?
    ): Single<ImportResult> = rxSingle { importSuspend(burrito, callback, options) }

    override suspend fun importSuspend(
        burrito: File,
        callback: ProjectImporterCallback?,
        options: ImportOptions?
    ): ImportResult {
        val convertedRc = withContext(Dispatchers.IO) { convert(burrito, callback) }
            ?: return ImportResult.FAILED
        return next?.importSuspend(convertedRc, callback, options) ?: ImportResult.FAILED
    }

    /** The resource container [burrito] converts to, or null when it can't be converted. */
    private fun convert(burrito: File, callback: ProjectImporterCallback?): File? {
        callback?.onNotifyProgress(
            localizeKey = "converting_file",
            percent = 10.0
        )
        val tempRc = directoryProvider.createTempFile("${burrito.nameWithoutExtension}_converted_rc", ".zip")
        val converted = converter.convert(burrito, tempRc)
        if (!converted || !tempRc.exists()) {
            val cause = converter.lastConversionError
            if (cause != null) {
                logger.error("Burrito conversion failed for {}", burrito.absolutePath, cause)
            } else {
                logger.error("Burrito conversion failed for {} with no exception detail", burrito.absolutePath)
            }
            return null
        }
        return tempRc
    }

    fun setNext(next: RCImporter) {
        this.next = next
    }
}
