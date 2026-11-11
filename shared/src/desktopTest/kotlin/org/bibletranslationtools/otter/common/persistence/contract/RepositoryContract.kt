package org.bibletranslationtools.otter.common.persistence.contract

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.bibletranslationtools.otter.common.NoStrayErrorsRule
import org.bibletranslationtools.otter.common.api.persistence.IDirectoryProvider
import org.bibletranslationtools.otter.common.data.primitives.Collection
import org.bibletranslationtools.otter.common.data.primitives.Content
import org.bibletranslationtools.otter.common.data.primitives.Language
import org.bibletranslationtools.otter.common.data.primitives.ResourceMetadata
import org.bibletranslationtools.otter.common.data.primitives.Take
import org.bibletranslationtools.otter.common.persistence.entities.CollectionEntity
import org.bibletranslationtools.otter.common.persistence.entities.ContentEntity
import org.bibletranslationtools.otter.common.persistence.entities.ResourceMetadataEntity
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.CollectionMapper
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.ContentMapper
import org.bibletranslationtools.otter.common.persistence.repositories.mapping.ResourceMetadataMapper
import org.bibletranslationtools.otter.common.persistence.DesktopDirectoryProvider
import org.bibletranslationtools.otter.common.persistence.characterization.AbstractDatabaseCharacterizationTest
import org.bibletranslationtools.otter.common.persistence.database.dao.DaoProvider
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.JdbcDatabaseDriverFactory
import org.bibletranslationtools.otter.common.persistence.database.sqldelight.SqlDelightAppDatabase
import org.bibletranslationtools.otter.common.persistence.entities.LanguageEntity
import org.bibletranslationtools.shared.di.koin.sharedCommonModules
import org.junit.Rule
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.io.File
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/**
 * How a contract test calls a repository method: through its Rx form, or through its suspend twin.
 * A test names both; the binding picks one. The assertions never know which ran.
 */
interface Binding {
    fun <T : Any> single(rx: () -> Single<T>, suspend: suspend () -> T): T
    fun <T : Any> maybe(rx: () -> Maybe<T>, suspend: suspend () -> T?): T?
    fun completable(rx: () -> Completable, suspend: suspend () -> Unit)
    fun <T : Any> list(rx: () -> Observable<T>, flow: () -> Flow<T>): List<T>
}

object RxBinding : Binding {
    override fun <T : Any> single(rx: () -> Single<T>, suspend: suspend () -> T): T = unwrapped { rx().blockingGet() }
    override fun <T : Any> maybe(rx: () -> Maybe<T>, suspend: suspend () -> T?): T? = unwrapped { rx().blockingGet() }
    override fun completable(rx: () -> Completable, suspend: suspend () -> Unit) = unwrapped { rx().blockingAwait() }
    override fun <T : Any> list(rx: () -> Observable<T>, flow: () -> Flow<T>): List<T> =
        unwrapped { rx().toList().blockingGet() }

    /** A blocking Rx call wraps a checked exception in a bare RuntimeException. Compare what was thrown. */
    private fun <T> unwrapped(call: () -> T): T = try {
        call()
    } catch (e: RuntimeException) {
        throw e.cause?.takeIf { e.javaClass == RuntimeException::class.java } ?: e
    }
}

object SuspendBinding : Binding {
    override fun <T : Any> single(rx: () -> Single<T>, suspend: suspend () -> T): T = runBlocking { suspend() }
    override fun <T : Any> maybe(rx: () -> Maybe<T>, suspend: suspend () -> T?): T? = runBlocking { suspend() }
    override fun completable(rx: () -> Completable, suspend: suspend () -> Unit) = runBlocking { suspend() }
    override fun <T : Any> list(rx: () -> Observable<T>, flow: () -> Flow<T>): List<T> = runBlocking { flow().toList() }
}

/**
 * A fresh database with foreign keys enforced, as the apps open it. In a file it has one connection
 * per thread, which is what the apps run on; in memory every thread shares one connection.
 */
class JdbcBackend(private val inFile: Boolean) : AbstractDatabaseCharacterizationTest.DatabaseBackend {
    private val open = mutableMapOf<DaoProvider, Pair<SqlDriver, File?>>()

    override fun createDatabase(): DaoProvider {
        val file = if (inFile) File.createTempFile("contract", ".sqlite").also { it.delete() } else null
        val driver = if (file != null) {
            JdbcDatabaseDriverFactory().create(file)
        } else {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties().apply { put("foreign_keys", "true") })
        }
        return SqlDelightAppDatabase.createFresh(driver).also { open[it] = driver to file }
    }

    override fun destroyDatabase(db: DaoProvider) {
        open.remove(db)?.let { (driver, file) ->
            driver.close()
            file?.delete()
        }
    }
}

private val inMemory = JdbcBackend(inFile = false)
private val inFile = JdbcBackend(inFile = true)

enum class ContractMode(val binding: Binding, val backend: AbstractDatabaseCharacterizationTest.DatabaseBackend) {
    RX_IN_MEMORY(RxBinding, inMemory),
    SUSPEND_IN_MEMORY(SuspendBinding, inMemory),
    RX_FILE(RxBinding, inFile),
    SUSPEND_FILE(SuspendBinding, inFile),
}

/**
 * A real SQLite database under the production Koin graph, in a temp directory: what a repository
 * test needs and nothing more. Only the two platform ports are supplied here.
 */
abstract class KoinDatabaseTest : AbstractDatabaseCharacterizationTest() {

    @get:Rule
    val noStrayErrors = NoStrayErrorsRule()

    protected lateinit var koin: Koin
    protected lateinit var root: File

    /** The database the graph is given. A test overrides this to put a spy between the two. */
    protected open fun daoProvider(): DaoProvider = db

    @BeforeTest
    fun startGraph() {
        root = File.createTempFile("contract", "").also {
            it.delete()
            it.mkdirs()
        }
        val provider = DesktopDirectoryProvider(
            appName = "OratureContractTest",
            pathSeparator = File.separator,
            userHome = root.absolutePath,
            windowsAppData = root.absolutePath,
            osName = System.getProperty("os.name").uppercase()
        )
        val database = daoProvider()
        koin = startKoin {
            modules(
                sharedCommonModules + module {
                    single<DaoProvider> { database }
                    single<IDirectoryProvider> { provider }
                }
            )
        }.koin
    }

    @AfterTest
    fun stopGraph() {
        stopKoin()
        root.deleteRecursively()
    }

    protected fun LanguageEntity.model() = Language(slug, name, anglicizedName, direction, gateway == 1, region, id)

    protected fun ResourceMetadataEntity.model(language: LanguageEntity): ResourceMetadata =
        ResourceMetadataMapper().mapFromEntity(this, language.model())

    protected fun CollectionEntity.model(metadata: ResourceMetadata? = null): Collection =
        CollectionMapper().mapFromEntity(this, metadata)

    protected fun ContentEntity.model(selectedTake: Take? = null): Content =
        ContentMapper(db.contentTypeDao).mapFromEntity(this, selectedTake)

    /** A file of the `:shared` module, by its path from the module directory. */
    protected fun moduleFile(path: String): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null && !File(dir, "src/commonMain/kotlin").isDirectory) dir = dir.parentFile
        return File(dir, path)
    }
}

/**
 * Base of the repository contract suites: the proof that a repository's Rx methods and its suspend
 * methods do the same thing.
 *
 * Each suite runs four times, once per [ContractMode], against a real SQLite database and the
 * production Koin graph. While both implementations exist, both must pass the same assertions.
 * When the Rx methods are deleted, the Rx modes go and the suspend ones stay as regression tests.
 */
@RunWith(Parameterized::class)
abstract class RepositoryContract(private val mode: ContractMode) : KoinDatabaseTest() {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun modes(): List<ContractMode> = ContractMode.values().toList()
    }

    override val backend get() = mode.backend
    protected val via get() = mode.binding
}
