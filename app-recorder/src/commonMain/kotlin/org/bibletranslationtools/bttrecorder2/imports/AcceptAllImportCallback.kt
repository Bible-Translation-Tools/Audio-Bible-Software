package org.bibletranslationtools.bttrecorder2.imports

import io.reactivex.Single
import org.bibletranslationtools.otter.common.data.workbook.WorkbookDescriptor
import org.bibletranslationtools.otter.common.domain.project.importer.ImportCallbackParameter
import org.bibletranslationtools.otter.common.domain.project.importer.ImportOptions
import org.bibletranslationtools.otter.common.domain.project.importer.ProjectImporterCallback

/**
 * A non-interactive importer callback: it asks the user nothing and reports which project was
 * imported through [onImported].
 *
 * It exists only because the importer reveals the imported project no other way, and supplying a
 * callback at all is what makes that report happen. Every answer here therefore has to match what
 * the importer does when given no callback, or providing one silently changes the import:
 *
 *  - **chapter selection** must answer with every chapter offered. Returning no chapters is read as
 *    "the user chose none" and aborts the whole import, which is only reachable when the project
 *    already exists — so the same file imports the first time and fails on the second.
 *  - **overwrite confirmation** must answer yes, which is what the importer assumes without a
 *    callback.
 *
 * Anything genuinely needing a decision from the user belongs in a callback that asks them, not
 * here.
 */
class AcceptAllImportCallback(
    private val onImported: (WorkbookDescriptor) -> Unit = {}
) : ProjectImporterCallback {

    override fun onRequestUserInput(): Single<ImportOptions> =
        Single.just(ImportOptions(confirmed = true))

    override fun onRequestUserInput(parameter: ImportCallbackParameter): Single<ImportOptions> =
        Single.just(ImportOptions(chapters = parameter.options, confirmed = true))

    override fun onNotifyProgress(localizeKey: String?, message: String?, percent: Double?) = Unit

    override fun onNotifySuccess(
        language: String?,
        project: String?,
        workbookDescriptor: WorkbookDescriptor?
    ) {
        workbookDescriptor?.let(onImported)
    }

    override fun onError(filePath: String) = Unit
}
