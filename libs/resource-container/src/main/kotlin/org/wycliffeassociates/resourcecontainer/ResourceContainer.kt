package org.wycliffeassociates.resourcecontainer

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import org.wycliffeassociates.resourcecontainer.entity.Content
import org.wycliffeassociates.resourcecontainer.entity.Manifest
import org.wycliffeassociates.resourcecontainer.entity.MediaManifest
import org.wycliffeassociates.resourcecontainer.entity.Project
import org.wycliffeassociates.resourcecontainer.errors.OutdatedRCException
import org.wycliffeassociates.resourcecontainer.errors.RCException
import org.wycliffeassociates.resourcecontainer.errors.UnsupportedRCException
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.Reader


const val MEDIA_FILENAME = "media.yaml"
const val MANIFEST_FILENAME = "manifest.yaml"
const val CONFIG_FILENAME = "config.yaml"

/**
 * The YAML codec for manifest.yaml and media.yaml, replacing
 * `ObjectMapper(YAMLFactory()).registerKotlinModule()`.
 *
 * - `strictMode = false` is the old class-level `@JsonIgnoreProperties(ignoreUnknown = true)`:
 *   manifests carry keys this library does not model, and an unknown key must not fail a read.
 * - `encodeDefaults = true` is the old `setSerializationInclusion(Include.NON_NULL)` — everything
 *   non-null gets written. Where a field should be omitted instead, the property carries
 *   `@EncodeDefault(NEVER)` (see Project, which was `@JsonInclude(NON_EMPTY)`), because kaml has
 *   no per-instance null/empty inclusion setting.
 *
 * KNOWN DIFFERENCE: kaml has no `coerceInputValues`, so a manifest key present with an explicit
 * `null` now fails the read where Jackson coerced it to the property default. That coercion was
 * Project's `@JsonCreator` workaround for jackson-module-kotlin#87. WA-generated manifests do not
 * emit explicit nulls, but a hand-edited one could.
 */
private val YAML = Yaml(
    configuration = YamlConfiguration(strictMode = false, encodeDefaults = true)
)

interface Config {
    fun read(reader: Reader): Config
    fun write(writer: OutputStream)
}

/**
 *  This is an object that holds resource until it is closed. It is strongly advised to
 *  use within a disposable use() block or manually invoke the close() method.
 */
class ResourceContainer private constructor(val file: File, var config: Config? = null) : AutoCloseable {

    lateinit var manifest: Manifest
    var media: MediaManifest? = null

    val accessor: IResourceContainerAccessor = when {
        // file may not exist at creation of a rc with .zip suffix in file path
        file.extension == "zip" -> ZipAccessor(file)
        file.isFile && isZipFile() -> ZipAccessor(file)
        else -> DirectoryAccessor(file)
    }

    /**
     * Tests the local file header signature rather than asking a MIME-detection framework.
     *
     * This replaces `MediaType.parse(Tika().detect(file)) == MediaType.APPLICATION_ZIP`. Tika was
     * a ~400-class dependency answering a four-byte question, and it could not ship on Android
     * below API 26 without repackaging: org.apache.tika.io.MappedBufferCleaner uses
     * MethodHandle.invoke, which D8 refuses to dex.
     *
     * PK\x03\x04 is a local file header, i.e. an archive with at least one entry. An empty archive
     * starts PK\x05\x06 and a spanned one PK\x07\x08; neither is a resource container, and Tika
     * would not have classified them as APPLICATION_ZIP by content either.
     */
    private fun isZipFile(): Boolean {
        return try {
            file.inputStream().use { stream ->
                val header = ByteArray(4)
                stream.read(header) == 4 &&
                    header[0] == 'P'.code.toByte() &&
                    header[1] == 'K'.code.toByte() &&
                    header[2].toInt() == 3 &&
                    header[3].toInt() == 4
            }
        } catch (e: IOException) {
            false
        }
    }

    private fun read(): Manifest {
        if (accessor.fileExists(MANIFEST_FILENAME)) {
            manifest = accessor.getReader(MANIFEST_FILENAME).use {
                YAML.decodeFromString(Manifest.serializer(), it.readText())
            }
            config?.let {
                if (accessor.fileExists(CONFIG_FILENAME)) {
                    this.config = it.read(accessor.getReader(CONFIG_FILENAME))
                }
            }
            if (accessor.fileExists(MEDIA_FILENAME)) {
                this.media = accessor.getReader(MEDIA_FILENAME).use {
                    YAML.decodeFromString(MediaManifest.serializer(), it.readText())
                }
            }
            return manifest
        } else {
            throw IOException("Missing manifest.yaml")
        }
    }

    fun write() {
        writeManifest()
        for (p in manifest.projects) {
            if (p.path.isNotEmpty()) {
                //writeTableOfContents(p)
            }
        }
        media?.let {
            writeMedia()
        }
    }

    fun writeManifest() {
        accessor.initWrite()
        accessor.write(MANIFEST_FILENAME) { writeManifest(it) }
    }

    private fun writeManifest(writer: OutputStream) {
        // The stream is deliberately NOT closed here — the accessor owns it. This is what
        // JsonGenerator.Feature.AUTO_CLOSE_TARGET being disabled used to buy; kaml never closes
        // the caller's stream, so writing the bytes and flushing is the whole equivalent.
        writer.write(YAML.encodeToString(Manifest.serializer(), manifest).toByteArray())
        writer.flush()
    }

    fun writeMedia() {
        accessor.initWrite()
        accessor.write(MEDIA_FILENAME) { writeMedia(it) }
    }

    private fun writeMedia(writer: OutputStream) {
        media?.let {
            writer.write(YAML.encodeToString(MediaManifest.serializer(), it).toByteArray())
            writer.flush()
        }
    }

    fun writeConfig() {
        config?.let { config ->
            if (accessor.fileExists(CONFIG_FILENAME)) {
                accessor.write(CONFIG_FILENAME) { config.write(it) }
            }
        }
    }

    /**
     * @param file the file to copy into the resource container
     * @param pathInRC the path in the rc to write to (should include file name)
     *
     * Adds a file to the Resource Container (such as adding media like audio or images)
     */
    fun addFileToContainer(file: File, pathInRC: String) {
        accessor.write(pathInRC) { ofs ->
            file.inputStream().use { ifs ->
                ifs.copyTo(ofs)
            }
        }
    }

    /**
     *  @since 0.8.0
     */
    fun getProjectContent(projectIdentifier: String? = null, extension: String): Content? {
        val project = project(projectIdentifier) ?: return null

        val contentStreams = accessor.getInputStreams(project.path, extension)
        return if (contentStreams.any()) {
            Content(project, contentStreams)
        } else {
            null
        }
    }

    /**
     * @param files a map that includes the path where the file should be
     * placed within the Resource Container as well as the file to insert
     *
     * Adds a files to the Resource Container (such as adding media like audio or images)
     */
    fun addFilesToContainer(files: Map<String, File>) {
        val map = files.entries.associate { (pathInRC, file) ->
            pathInRC to { ofs: OutputStream ->
                file.inputStream().use { ifs ->
                    ifs.copyTo(ofs)
                }
                Unit
            }
        }
        accessor.write(map)
    }

    fun resource() = Resource(
        manifest.dublinCore.identifier,
        manifest.dublinCore.title,
        manifest.dublinCore.type,
        manifest.checking.checkingLevel,
        manifest.dublinCore.version
    )

    fun project(identifier: String? = null): Project? {
        if (manifest.projects.isEmpty()) {
            return null
        }

        if (!identifier.isNullOrEmpty()) {
            for (p in manifest.projects) {
                if (p.identifier == identifier) {
                    return p
                }
            }
        } else if (manifest.projects.size == 1) {
            return manifest.projects[0]
        } else {
            throw RCException("Multiple projects found. Specify the project identifier.")
        }

        return null
    }

    fun projectIds(): List<String> = manifest.projects.map(Project::identifier)

    fun projectCount(): Int = manifest.projects.size

    fun conformsTo(): String = manifest.dublinCore.conformsTo.replace(Regex("^rc"), "")

    /**
     * Convenience method to get the type of the resource container.
     *
     * @return the RC type
     */
    fun type(): String = this.manifest.dublinCore.type

    companion object {

        /**
         * Serialize a manifest to [out] without needing a container on disk. Exists so
         * ManifestInclusionTest can assert which keys reach the file; the instance methods above
         * delegate the same encoding through the accessor.
         */
        internal fun writeManifestTo(out: OutputStream, manifest: Manifest) {
            out.write(YAML.encodeToString(Manifest.serializer(), manifest).toByteArray())
            out.flush()
        }

        /** Media counterpart of [writeManifestTo]. */
        internal fun writeMediaTo(out: OutputStream, media: MediaManifest) {
            out.write(YAML.encodeToString(MediaManifest.serializer(), media).toByteArray())
            out.flush()
        }


        const val conformsTo = "0.2"

        fun create(file: File, init: ResourceContainer.() -> Unit): ResourceContainer {
            val rc = ResourceContainer(file)
            rc.init()
            if (rc.conformsTo().isEmpty()) {
                rc.manifest.dublinCore.conformsTo = conformsTo
            }
            return rc
        }

        fun load(dir: File, config: Config, strict: Boolean = true): ResourceContainer =
            load(dir, strict, config)

        fun load(dir: File, strict: Boolean = true, config: Config? = null): ResourceContainer {
            val rc = ResourceContainer(dir, config)
            rc.read()

            if (strict) {
                if (Semver.gt(rc.conformsTo(), conformsTo)) {
                    throw UnsupportedRCException("Found " + rc.conformsTo() + " but expected " + conformsTo)
                }
                if (Semver.lt(rc.conformsTo(), conformsTo)) {
                    throw OutdatedRCException("Found " + rc.conformsTo() + " but expected " + conformsTo)
                }
            }

            return rc
        }
    }

    override fun close() {
        accessor.close()
    }
}

data class Resource(
    val slug: String,
    val title: String,
    val type: String,
    val checkingLevel: String,
    val version: String
)
