# Phase 6 (refactor) handoff — hoist DB construction out of DI, make the backend switchable

**Audience:** the implementing agent. **Reviewer:** Opus. **Read first:** `docs/jooq-to-sqldelight-migration-plan.md`.
This is a **structural refactor only** — no behavior changes. Do NOT delete jOOQ; it stays for the init
benchmark, as the characterization oracle, and as a documented fallback. Work on branch
`migrate-jooq-to-sqldelight`; do not commit.

### BUILD DISCIPLINE (important)
Run **one gradle invocation at a time**. Never start a second gradle build while one is running — it
corrupts the incremental build (a prior agent hit stale-class dex failures that way). Run gradle in the
background with a log file and poll with an until-loop (`until grep -qE "BUILD SUCCESSFUL|BUILD FAILED" "$LOG"; do sleep 10; done`);
foreground times out at 2 min. After each run, actually read the result before moving on. Any instrumented
test method/class name must be a legal DEX SimpleName (Android 7 / pre-DEX-040): **no spaces / no backtick
names** — use camelCase (the existing android tests already do).

## The three changes the user asked for

1. The `single<DaoProvider>` blocks in `SharedModules.desktop.kt` / `SharedModules.android.kt` contain all
   the driver construction (JdbcSqliteDriver / dumb AndroidSqliteDriver callback / open / Mac path rewrite).
   **Move that into classes**; the DI should just wire them.
2. **Don't hardcode the database file name** (`"tr.sqlite"` is currently repeated inline in both DI modules
   and the jOOQ binding). Centralize it as one constant.
3. **A commented section showing how to switch** between the SQLDelight and jOOQ backends.

## Step 1 — common abstractions (commonMain, `…/persistence/database/sqldelight/`)

`DatabaseDriverFactory.kt`:
```kotlin
package …persistence.database.sqldelight
import app.cash.sqldelight.db.SqlDriver
import java.io.File

/** The single source of truth for the app database's file name. */
const val DATABASE_FILE_NAME = "tr.sqlite"

/**
 * A platform's way to open a "dumb" [SqlDriver] over [databaseFile]: foreign keys ON, and NO automatic
 * schema management (our installed_entity-based SqlDelightDatabaseMigrator owns versioning; create-vs-migrate
 * is decided by SqlDelightAppDatabase.open, not the driver).
 */
interface DatabaseDriverFactory {
    fun create(databaseFile: File): SqlDriver
}
```

`SqlDelightDatabaseProvider.kt`:
```kotlin
class SqlDelightDatabaseProvider(
    private val driverFactory: DatabaseDriverFactory,
    private val directoryProvider: IDirectoryProvider,   // infra factory — allowed to take the composite
    private val databaseFileName: String = DATABASE_FILE_NAME,
    private val onOpened: (SqlDriver) -> Unit = {},
) {
    fun provide(): DaoProvider {
        val dbFile = directoryProvider.databaseDirectory.resolve(File(databaseFileName))
        val isNew = !dbFile.exists() || dbFile.length() == 0L
        val driver = driverFactory.create(dbFile)
        val database = SqlDelightAppDatabase.open(driver, isNew, directoryProvider)
        onOpened(driver)
        return database
    }
}
```

## Step 2 — platform driver factories

`desktopMain/…/persistence/database/sqldelight/JdbcDatabaseDriverFactory.kt`:
```kotlin
class JdbcDatabaseDriverFactory : DatabaseDriverFactory {
    override fun create(databaseFile: File): SqlDriver =
        JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}").also {
            it.execute(null, "PRAGMA foreign_keys=ON;", 0)   // match jOOQ's cascades/restricts
        }
}
```
Move `migratePathsForSandboxedMac` out of `SharedModules.desktop.kt` into a desktop file (e.g. the same
package), change its parameter type to `SqlDriver`, and make it **self-guard**: return immediately unless
`System.getProperty("orature.isPkgMac") != null`, so it can be passed as `onOpened` directly. Keep its 3
UPDATEs verbatim.

`androidMain/…/persistence/database/sqldelight/AndroidDatabaseDriverFactory.kt`:
```kotlin
class AndroidDatabaseDriverFactory(private val context: Context) : DatabaseDriverFactory {
    override fun create(databaseFile: File): SqlDriver {
        // databaseDirectory == getDatabasePath("tr.db").parentFile, so databaseFile.name resolves to
        // getDatabasePath(databaseFile.name) == databaseFile — the same file existing installs have.
        val callback = object : AndroidSqliteDriver.Callback(OtterDatabase.Schema) {
            override fun onCreate(db: SupportSQLiteDatabase) { /* no-op: open()/createFresh owns schema */ }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) { /* no-op */ }
            override fun onConfigure(db: SupportSQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        }
        return AndroidSqliteDriver(OtterDatabase.Schema, context, name = databaseFile.name, callback = callback)
    }
}
```

## Step 3 — make the jOOQ backend a real, switchable production adapter

Currently `JooqDaoProvider` (the 15 delegating adapters presenting jOOQ through `DaoProvider`) lives in
`desktopTest/…/characterization/JooqDaoProvider.kt` — test-only, so it can't be named from production DI.
**Move it verbatim to commonMain** at `…/persistence/database/jooqcompat/JooqDaoProvider.kt` (new package;
it only references `IAppDatabase` + the jOOQ `daos.*` + the clean `dao.*`, all commonMain, so it compiles
there). Update its KDoc (it's now the production jOOQ→DaoProvider adapter, still removed when jOOQ is
finally deleted). Update the `import` in `characterization/JooqBackend.kt` to the new package. Do not change
the adapter bodies.

## Step 4 — the DI, now thin, with the switch documented

`SharedModules.desktop.kt` — replace the big `single<DaoProvider>` with:
```kotlin
single<DatabaseDriverFactory> { JdbcDatabaseDriverFactory() }
single<DaoProvider> {
    // ── Active persistence backend: SQLDelight ────────────────────────────────────────────────
    SqlDelightDatabaseProvider(
        driverFactory = get(),
        directoryProvider = get(),
        onOpened = ::migratePathsForSandboxedMac,   // self-guards on orature.isPkgMac
    ).provide()

    // ── To switch to the legacy jOOQ backend instead ─────────────────────────────────────────
    // Comment the block above and uncomment the line below. jOOQ is retained for the init benchmark
    // and as the characterization oracle. CAVEAT: JooqDaoProvider only implements the DAO surface; the
    // three repositories rewritten onto SQLDelight-only queries in Phase 4 (Collection/Resource/
    // ResourceContainer) would throw UnsupportedOperationException for project derivation until their
    // pre-Phase-4 jOOQ implementations are restored from git history.
    // JooqDaoProvider(get<IAppDatabase>())
}
```
Keep the existing `single<IAppDatabase> { AppDatabase(directoryProvider.databaseDirectory.resolve(File(DATABASE_FILE_NAME)), directoryProvider) }`
binding, but change its inline `"tr.sqlite"` to `DATABASE_FILE_NAME`.

`SharedModules.android.kt` — the same shape:
```kotlin
single<DatabaseDriverFactory> { AndroidDatabaseDriverFactory(androidContext()) }
single<DaoProvider> {
    // Active backend: SQLDelight.
    SqlDelightDatabaseProvider(driverFactory = get(), directoryProvider = get()).provide()
    // To switch to jOOQ: comment the line above and uncomment (same caveat as desktop):
    // JooqDaoProvider(get<IAppDatabase>())
}
```
and change the jOOQ `AndroidAppDatabase(... File(DATABASE_FILE_NAME) ...)` binding to use the constant too.

## Acceptance (all green — run sequentially, one at a time)

```bash
./gradlew :shared:compileTestKotlinDesktop
make test-migration                                   # desktop differential characterization + integration
./gradlew :shared:desktopTest                         # full desktop unit suite (incl. the moved JooqDaoProvider via JooqBackend)
./gradlew :app-recorder:compileDebugKotlinAndroid :app-orature:compileDebugKotlinAndroid
ANDROID_SERIAL=emulator-5556 ./gradlew :shared:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=org.bibletranslationtools.otter.common.persistence.characterization
```
(API-24 emulator is `emulator-5556`.) The last one recompiles the android side incl. the new
`AndroidDatabaseDriverFactory`; the 8 instrumented tests must still pass (they construct drivers directly,
so they exercise the same driver config).

Report: files added/moved/changed; confirmation that `"tr.sqlite"` no longer appears inline anywhere except
the one `DATABASE_FILE_NAME` constant (`grep -rn '"tr.sqlite"' shared app-recorder app-orature --include=*.kt`);
the DI blocks are now a few lines; test tallies for every gate; and anything you couldn't get green.
