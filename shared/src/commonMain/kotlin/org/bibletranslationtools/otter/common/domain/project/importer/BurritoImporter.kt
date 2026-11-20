package org.bibletranslationtools.otter.common.domain.project.importer

import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.bibletranslationtools.otter.common.domain.resourcecontainer.burrito.BurritoToResourceContainerConverter
import org.slf4j.LoggerFactory
import java.io.File
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BurritoImporter(
    private val directoryProvider: IDirectoryProvider,
    private val converter: BurritoToResourceContainerConverter,
): IProjectImporter {

    private data class ConversionResult(
        val output: File? = null,
        val result: ImportResult? = null
    )

    private val logger = LoggerFactory.getLogger(this.javaClass)
    private var next: RCImporter? = null

    override fun import(
        burrito: File,
        callback: ProjectImporterCallback?,
        options: ImportOptions?
    ): Single<ImportResult> {
        return Single
            .fromCallable {
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
                    return@fromCallable ConversionResult(result = ImportResult.FAILED)
                }
                ConversionResult(output = tempRc)
            }
            .flatMap { conversion ->
                conversion.result?.let { earlyResult ->
                    return@flatMap Single.just(earlyResult)
                }
                next?.import(conversion.output!!, callback, options)
                    ?: Single.just(ImportResult.FAILED)
            }
            .subscribeOn(Schedulers.io())
    }

    override suspend fun importSuspend(
        burrito: File,
        callback: ProjectImporterCallback?,
        options: ImportOptions?
    ): ImportResult {
        val convertedRc = withContext(Dispatchers.IO) { convert(burrito, callback) }
            ?: return ImportResult.FAILED
        return next?.importSuspend(convertedRc, callback, options) ?: ImportResult.FAILED
    }

    fun setNext(next: RCImporter) {
        this.next = next
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

}
