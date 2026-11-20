package org.bibletranslationtools.otter.common.domain.project.importer

import io.reactivex.Single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx2.rxSingle
import kotlinx.coroutines.withContext
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.bibletranslationtools.otter.common.api.persistence.ITempFileProvider
import org.wycliffeassociates.tstudio2rc.Tstudio2RcConverter
import java.io.File

class TstudioImporter(
    private val directoryProvider: ITempFileProvider
) : IProjectImporter {
    private var next: RCImporter? = null

    override fun import(
        file: File,
        callback: ProjectImporterCallback?,
        options: ImportOptions?
    ): Single<ImportResult> = rxSingle { importSuspend(file, callback, options) }

    override suspend fun importSuspend(
        file: File,
        callback: ProjectImporterCallback?,
        options: ImportOptions?
    ): ImportResult {
        val rcFile = withContext(Dispatchers.IO) {
            callback?.onNotifyProgress(
                localizeKey = "converting_file",
                percent = 10.0
            )
            Tstudio2RcConverter.convertFileToRC(file, directoryProvider.tempDirectory)
        }
        return next?.importSuspend(rcFile, callback, options) ?: ImportResult.FAILED
    }

    fun setNext(next: RCImporter) {
        this.next = next
    }
}