import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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

    // Backend library versions (moved verbatim from composeApp during the split).
    val rxkotlinVer = "2.4.0"
    val rxkotlinfxVer = "2.2.2"
    val rxrelayVer = "2.1.0"
    val jooqVer = "3.14.16"
    val kotlinVer = "1.9.23"
    val retrofitVer = "2.9.0"
    val retrofitJacksonVer = "2.9.0"
    val retrofitRxJava2Ver = "2.9.0"
    val jacksonVer = "2.15.1"
    val daggerVer = "2.51.1"
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
    val kotlinInjectVer = "0.6.3"

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Compose runtime + resources: the backend reads bundled files/ via
                // Res.readBytes (InitializeUlb/InitializeLanguages), and the apps read
                // shared string/drawable IDs transitively — so `api`.
                implementation(compose.runtime)
                api(compose.components.resources)

                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.coroutines.rx2)

                api("io.reactivex.rxjava2:rxkotlin:$rxkotlinVer")
                api("com.github.thomasnield:rxkotlinfx:$rxkotlinfxVer")
                api("com.jakewharton.rxrelay2:rxrelay:$rxrelayVer")
                api("org.jooq:jooq:$jooqVer")
                api("org.slf4j:slf4j-api:$slf4jApiVer")
                api("de.sciss:jump3r:$jump3rVer")
                api("org.wycliffeassociates:kotlin-tstudio2rc:$tstudio2rcVer")

                api("org.bibletranslationtools:otter-db:1.0")
                api("org.wycliffeassociates:kotlin-resource-container:$kotlinresourcecontainerVer")
                api("org.wycliffeassociates:usfmtools:$usfmToolsVer")

                api("com.google.dagger:dagger:$daggerVer")

                api("org.jetbrains.kotlin:kotlin-reflect:$kotlinVer")
                api("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVer")
                api("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVer")
                api("com.fasterxml.jackson.dataformat:jackson-dataformat-csv:$jacksonVer")

                api("com.squareup.retrofit2:retrofit:$retrofitVer")
                api("com.squareup.retrofit2:converter-jackson:$retrofitJacksonVer")
                api("com.squareup.retrofit2:adapter-rxjava2:$retrofitRxJava2Ver")

                api("org.apache.tika:tika-core:$tikaVer")

                api("org.bibletranslationtools:kotlin-scripture-burrito:$kotlinscriptureburritoVer")
                api("org.bibletranslationtools:kotlin-vtt:$kotlinVttVer")
                api("org.bibletranslationtools:kotlin-scripture-alignment:$kotlinScriptureAlignmentVer")

                api("javazoom.jl:SeekableJlayer:$jlayerVer")
                api("org.digitalmediaserver:cuelib-core:$cuelibVer")
                api("com.mpatric:mp3agic:$mp3TagVer")
                api("be.tarsos:tarsosdsp:$tarsosDspVer")

                api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

                api("io.insert-koin:koin-core:$koinVer")
                api("me.tatarka.inject:kotlin-inject-runtime:$kotlinInjectVer")
                api(libs.koin.core)

                api(libs.datastore.preferences)

                api("io.github.vinceglb:filekit-core:0.12.0")
                api("io.github.vinceglb:filekit-dialogs:0.12.0")
                api("io.github.vinceglb:filekit-dialogs-compose:0.12.0")
                api("io.github.vinceglb:filekit-coil:0.12.0")
            }
        }

        val desktopMain by getting {
            dependencies {
                api("org.xerial:sqlite-jdbc:3.49.0.0")
                // SLF4J console binding so backend logger.error() is visible from a
                // terminal (otherwise export/import/audio failures are silent).
                api("org.slf4j:slf4j-simple:2.0.13")
            }
        }

        val androidMain by getting {
            dependencies {
                api(libs.sqldroid)
                api("com.readystatesoftware.sqliteasset:sqliteassethelper:2.0.1")
                api(libs.koin.android)
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

        // langnames data (ivy artifact) consumed by the backend language init.
        dependencies {
            api("bibleineverylanguage:langnames@json")
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

// GL source download + copy into composeResources/files/content (lives in :shared now).
tasks.register("downloadGLSources") {
    doLast {
        val jsonFile = file("src/commonMain/composeResources/files/gl_sources.json")
        if (!jsonFile.exists()) {
            println("GL sources file not found: ${jsonFile.absolutePath}")
            return@doLast
        }
        val jsonSlurper = groovy.json.JsonSlurper()
        val jsonData = jsonSlurper.parse(jsonFile) as List<Map<String, String>>
        jsonData.forEach { dependency ->
            val artifactName = dependency["name"]
            val artifactUrl = dependency["url"]
            val outputDir = layout.buildDirectory.dir("resources/content").get().asFile
            outputDir.mkdirs()
            val outputPath = outputDir.resolve("${artifactName}.zip")
            if (!outputPath.exists()) {
                try {
                    val action = de.undercouch.gradle.tasks.download.DownloadAction(project)
                    action.src(artifactUrl)
                    action.dest(outputPath)
                    action.header("X-Requested-With", "WA-Tool-Orature")
                    action.overwrite(false)
                    action.execute()
                    println("Downloaded $artifactName")
                } catch (e: Exception) {
                    println("Failed to download $artifactName from $artifactUrl")
                    e.printStackTrace()
                }
            } else {
                println("Skipping $artifactName (already exists)")
            }
        }
    }
}

tasks.register<Copy>("copyToResources") {
    dependsOn("downloadGLSources")
    from(layout.buildDirectory.dir("resources/content"))
    into("src/commonMain/composeResources/files/content")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    rename { filename ->
        if (filename.matches(Regex(".*.json$"))) {
            filename.replace(Regex("-\\.json$"), ".json")
        } else {
            filename
        }
    }
}
