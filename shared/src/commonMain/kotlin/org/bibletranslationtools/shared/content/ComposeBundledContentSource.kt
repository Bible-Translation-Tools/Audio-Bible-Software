package org.bibletranslationtools.shared.content

import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.api.io.IBundledContentSource
import org.bibletranslationtools.shared.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * [IBundledContentSource] over Compose Multiplatform resources.
 *
 * The bundled GL zips, langnames catalog, source manifests, and versification json live in
 * `shared/src/commonMain/composeResources/files/`, which Compose packs into its own resource
 * store. That store is NOT on the JVM classpath, so `ClassLoader.getSystemResourceAsStream`
 * returns null for these files and only [Res.readBytes] can reach them. Keeping that
 * knowledge here is the whole point of the port: this is the single place in the module that
 * needs to know the content is Compose-packaged.
 */
class ComposeBundledContentSource : IBundledContentSource {

    @OptIn(ExperimentalResourceApi::class)
    override suspend fun read(path: String): ByteArray = Res.readBytes(path)

    override fun readBlocking(path: String): ByteArray = runBlocking { read(path) }
}
