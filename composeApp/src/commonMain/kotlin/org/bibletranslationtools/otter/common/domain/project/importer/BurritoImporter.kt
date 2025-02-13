package org.bibletranslationtools.otter.common.domain.project.importer

import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import org.bibletranslationtools.otter.common.domain.resourcecontainer.ImportResult
import org.bibletranslationtools.otter.common.domain.resourcecontainer.burrito.BurritoToResourceContainerConverter
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import java.io.File
import javax.inject.Inject

class BurritoImporter @Inject constructor(
    private val directoryProvider: IDirectoryProvider,
    private val converter: BurritoToResourceContainerConverter,
): IProjectImporter {

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
                val tempRc = directoryProvider.createTempFile("burrito_converted_rc", ".zip")
                converter.convert(burrito, tempRc)
                tempRc
            }
            .flatMap { fileToImport ->
                next?.import(fileToImport, callback, options)
                    ?: Single.just(ImportResult.FAILED)
            }
            .subscribeOn(Schedulers.io())
    }

    fun setNext(next: RCImporter) {
        this.next = next
    }
}