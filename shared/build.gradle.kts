import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.HttpURLConnection
import java.net.URI

// :shared — the cross-app module: Orature backend (org.bibletranslationtools.otter.*) +
// shared Compose resources (+ reusable render/UI primitives arriving in a later step).
// A KMP LIBRARY consumed by :app-recorder and :app-orature via api(projects.shared).
// Backend deps are exposed as `api` so the apps keep seeing them transitively during the
// incremental split (can tighten to `implementation` once the module boundary stabilizes).
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ksp)
    kotlin("plugin.serialization") version "2.1.10"
    id("de.undercouch.download") version "5.6.0"
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm("desktop")

    // Backend library versions (moved verbatim from the recorder app during the split).
    val rxkotlinVer = "2.4.0"
    val rxrelayVer = "2.1.0"
    val jooqVer = "3.14.16"
    val kotlinVer = "1.9.23"
    val retrofitVer = "2.9.0"
    val retrofitJacksonVer = "2.9.0"
    val retrofitRxJava2Ver = "2.9.0"
    val jacksonVer = "2.15.1"
    val kotlinresourcecontainerVer = "0.12.0"
    val kotlinscriptureburritoVer = "1.0.1"
    val slf4jApiVer = "2.0.13"
    val usfmToolsVer = "1.9.2-proprosedfix"
    val jlayerVer = "1.0"
    val cuelibVer = "2.0.0"
    val jump3rVer = "1.0.5"
    val tarsosDspVer = "2.4.1"
    val mp3TagVer = "0.9.3"
    val tikaVer = "2.0.0"
    val tstudio2rcVer = "1.0.2"
    val kotlinVttVer = "1.0.0"
    val kotlinScriptureAlignmentVer = "1.0.0"
    val koinVer = "3.5.6"

    sourceSets {
        val commonMain by getting {
            dependencies {
                // ── api: reachable from the apps ────────────────────────────────────────
                // Everything below appears in :shared's PUBLIC API — a type an app names, or a
                // package an app imports directly. Adding to this list widens what a Compose
                // screen is allowed to reach for, so check first whether the app can declare the
                // dependency itself (both already declare their own Compose, lifecycle and
                // navigation).
                //
                // compose: `runtime` only, and only because the engine's snapshot-state holders
                // are public API (WaveformPeakCache.builtBuckets is a MutableIntState). `resources`
                // because the backend reads bundled files/ via Res.readBytes
                // (Initialize{Ulb,Languages}) and both apps call org.jetbrains.compose.resources
                // directly. NOT `foundation`: :shared imports nothing from it.
                api(compose.runtime)
                api(compose.components.resources)

                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.coroutines.rx2)

                // The entity layer's public API is RxRelay-shaped (AssociatedAudio.takes/selected),
                // so both apps see Rx whether they want to or not. Demoting these means fixing
                // that first.
                api("io.reactivex.rxjava2:rxkotlin:$rxkotlinVer")
                api("com.jakewharton.rxrelay2:rxrelay:$rxrelayVer")

                api("org.slf4j:slf4j-api:$slf4jApiVer")
                api(libs.koin.core)

                // Orature's plugin registry reads its YAML definitions with these directly.
                api("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVer")
                api("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVer")

                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

                api("io.github.vinceglb:filekit-core:0.12.0")
                api("io.github.vinceglb:filekit-dialogs:0.12.0")
                api("io.github.vinceglb:filekit-dialogs-compose:0.12.0")
                api("io.github.vinceglb:filekit-coil:0.12.0")

                // ── implementation: :shared's own business ──────────────────────────────
                // None of these appears in an app source file. Keeping them off the apps'
                // compile classpath is what stops a screen importing org.jooq: until now
                // `api(projects.shared)` made the database library, the HTTP stack and five audio
                // codecs visible from every @Composable in both apps.
                implementation("org.jooq:jooq:$jooqVer")
                implementation("org.bibletranslationtools:otter-db:1.0")

                implementation("org.wycliffeassociates:kotlin-resource-container:$kotlinresourcecontainerVer")
                implementation("org.wycliffeassociates:usfmtools:$usfmToolsVer")
                implementation("org.wycliffeassociates:kotlin-tstudio2rc:$tstudio2rcVer")

                implementation("org.bibletranslationtools:kotlin-scripture-burrito:$kotlinscriptureburritoVer")
                implementation("org.bibletranslationtools:kotlin-vtt:$kotlinVttVer")
                implementation("org.bibletranslationtools:kotlin-scripture-alignment:$kotlinScriptureAlignmentVer")

                implementation("com.squareup.retrofit2:retrofit:$retrofitVer")
                implementation("com.squareup.retrofit2:converter-jackson:$retrofitJacksonVer")
                implementation("com.squareup.retrofit2:adapter-rxjava2:$retrofitRxJava2Ver")

                implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-csv:$jacksonVer")
                implementation("org.apache.tika:tika-core:$tikaVer")
                implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVer")

                // Audio codecs, used only behind the audio/ and domain/audio/ facades.
                implementation("de.sciss:jump3r:$jump3rVer")
                implementation("javazoom.jl:SeekableJlayer:$jlayerVer")
                implementation("org.digitalmediaserver:cuelib-core:$cuelibVer")
                implementation("com.mpatric:mp3agic:$mp3TagVer")
                implementation("be.tarsos:tarsosdsp:$tarsosDspVer")

                // Behind IAppPreferences / DataStoreAppPreferences.
                implementation(libs.datastore.preferences)
            }
        }

        val desktopMain by getting {
            dependencies {
                // Both are runtime-only: a JDBC driver and a logging binding, neither named in any
                // app source. They stay on the runtime classpath as `implementation` deps.
                implementation("org.xerial:sqlite-jdbc:3.49.0.0")
                // SLF4J console binding so backend logger.error() is visible from a
                // terminal (otherwise export/import/audio failures are silent).
                implementation("org.slf4j:slf4j-simple:2.0.13")
            }
        }

        val androidMain by getting {
            dependencies {
                // Runtime-only sqlite plumbing for the android AppDatabase actual.
                implementation(libs.sqldroid)
                implementation("com.readystatesoftware.sqliteasset:sqliteassethelper:2.0.1")
                // api: the android apps call org.koin.android.ext.koin.androidContext in their
                // Application classes.
                api(libs.koin.android)
                // BackHandler for the android PlatformBackHandler actual.
                implementation(libs.androidx.activity.compose)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlin.test.junit)
                implementation(libs.koin.test)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.mockk)
                implementation(libs.mockito)
                implementation(libs.mockito.kotlin)
            }
        }

        // langnames data (ivy artifact) consumed by the backend language init. A JSON data file,
        // not code — nothing imports it, so it has no business on the apps' compile classpath.
        dependencies {
            implementation("bibleineverylanguage:langnames@json")
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "org.bibletranslationtools.shared.resources"
    generateResClass = always
}

android {
    namespace = "org.bibletranslationtools.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// ── GL source download + bundling ────────────────────────────────────────────────────
// gl_sources.json lists ~96 gateway-language ULB sources by {name, languageCode, url}.
// downloadGLSources fetches each zip straight into composeResources/files/content/ so it
// ships as a Compose Multiplatform resource (read at runtime via Res.readBytes — NOT the
// JVM classpath). The content lives in :shared, so BOTH apps get the sources.
//
// Downloads are SYNCHRONOUS + per-file guarded on purpose: the wa-catalog manifest has gone
// partly stale (~24 URLs 404), and undercouch's DownloadAction runs on the Worker API async,
// so its failures escape a surrounding try/catch and fail the whole build. A plain blocking
// HttpURLConnection per file lets us skip a dead URL (404/error) and keep the rest.
val glContentDir = file("src/commonMain/composeResources/files/content")
val embeddedManifest = file("src/commonMain/composeResources/files/embedded_gl_sources.json")

tasks.register("downloadGLSources") {
    // The zips land directly in src/ (survives `clean`), so guarding on existence means a
    // clean build re-checks but does not re-download the ~100MB set every time.
    outputs.dir(glContentDir)
    doLast {
        val jsonFile = file("src/commonMain/composeResources/files/gl_sources.json")
        if (!jsonFile.exists()) {
            println("GL sources file not found: ${jsonFile.absolutePath}")
            return@doLast
        }
        glContentDir.mkdirs()
        @Suppress("UNCHECKED_CAST")
        val jsonData = groovy.json.JsonSlurper().parse(jsonFile) as List<Map<String, String>>
        var downloaded = 0
        var skipped = 0
        var failed = 0
        jsonData.forEach { dependency ->
            val artifactName = dependency["name"] ?: return@forEach
            val artifactUrl = dependency["url"] ?: return@forEach
            val outputPath = glContentDir.resolve("$artifactName.zip")
            if (outputPath.exists()) {
                skipped++
                return@forEach
            }
            try {
                val conn = (URI(artifactUrl).toURL().openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    setRequestProperty("X-Requested-With", "WA-Tool-Orature")
                }
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    failed++
                    println("Skipping $artifactName (HTTP $code): $artifactUrl")
                    conn.disconnect()
                    return@forEach
                }
                // Write to a temp file first so a mid-download failure never leaves a partial zip.
                val tmp = glContentDir.resolve("$artifactName.zip.part")
                conn.inputStream.use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
                conn.disconnect()
                tmp.renameTo(outputPath)
                downloaded++
                println("Downloaded $artifactName")
            } catch (e: Exception) {
                failed++
                println("Failed to download $artifactName from $artifactUrl: ${e.message}")
            }
        }
        println("GL sources: $downloaded downloaded, $skipped already present, $failed unavailable.")
    }
}

// Emit a manifest of the source names ACTUALLY bundled (content/*.zip). The runtime
// (LanguageRepository.listEmbeddedSourceLanguages) reads this so the project wizard offers
// only gateway languages whose zip is really present — never a stale-URL entry that would
// fail on sideload.
tasks.register("generateEmbeddedSourcesManifest") {
    dependsOn("downloadGLSources")
    outputs.file(embeddedManifest)
    doLast {
        val names = (glContentDir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension == "zip" }
            .map { it.nameWithoutExtension }
            .sorted()
        embeddedManifest.writeText(groovy.json.JsonBuilder(names).toPrettyString())
        println("Embedded GL sources manifest: ${names.size} sources bundled.")
    }
}

// Ensure the GL source zips + manifest exist BEFORE any Compose resource task reads the
// composeResources source dirs (Res.readBytes reads from Compose's packed store, not the
// classpath). This covers every task in the Compose MP resource pipeline that consumes
// composeResources/ — the assemble/prepare tasks AND the value-resource copy/convert tasks —
// so none runs ahead of the download (which declares content/ as its output).
tasks.matching {
    val n = it.name
    n.contains("ComposeResources") ||
        n.startsWith("prepareComposeResourcesTask") ||
        n.startsWith("copyNonXmlValueResources") ||
        n.startsWith("convertXmlValueResources") ||
        n.startsWith("generateResourceAccessors")
}.configureEach {
    dependsOn("generateEmbeddedSourcesManifest")
}
