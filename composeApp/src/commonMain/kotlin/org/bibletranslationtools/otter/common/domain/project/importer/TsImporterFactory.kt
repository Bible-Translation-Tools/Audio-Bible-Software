package org.bibletranslationtools.otter.common.domain.project.importer

class TsImporterFactory(
    private val tstudioImporter: TstudioImporter,
    private val existingProjectImporter: ExistingSourceImporter,
    private val newSourceImporter: NewSourceImporter
) : IProjectImporterFactory {

    private val importer: TstudioImporter by lazy {
        // ts file is converted to RC and then passed to source importers
        val importer1 = tstudioImporter
        val importer2 = existingProjectImporter
        val importer3 = newSourceImporter

        importer1.setNext(importer2)
        importer2.setNext(importer3)

        importer1
    }

    override fun makeImporter(): IProjectImporter = importer
}