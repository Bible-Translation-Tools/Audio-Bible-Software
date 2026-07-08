package org.bibletranslationtools.otter.common.domain.project.importer

class BurritoImporterFactory(
    private val burritoImporter: BurritoImporter,
    private val existingProjectImporter: ExistingSourceImporter,
    private val newSourceImporter: NewSourceImporter
) : IProjectImporterFactory {

    private val importer: BurritoImporter by lazy {
        val importer1 = burritoImporter
        val importer2 = existingProjectImporter
        val importer3 = newSourceImporter

        importer1.setNext(importer2)
        importer2.setNext(importer3)

        importer1
    }

    override fun makeImporter(): IProjectImporter = importer
}