import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
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

    var rxkotlinVer = "2.4.0"
    var rxkotlinfxVer = "2.2.2"
    var rxrelayVer = "2.1.0"
    var jooqVer = "3.14.16"
    // Libraries
    var kotlinVer = "1.9.23"
    var javaVer = "21"
    var sqliteJdbcVer = "3.49.0.0"
    var retrofitVer = "2.9.0"
    var retrofitJacksonVer = "2.9.0"
    var retrofitRxJava2Ver = "2.9.0"
    var jacksonVer = "2.15.1"
    var daggerVer = "2.51.1"
    var junitVer = "4.12"
    var mockkVer = "1.13.5"
    var mockitoVer = "5.11.0"
    var mockitoKotlinVer = "2.1.0"
    var commonmarkVer = "0.12.1"
    var clapperJavaUtilVer = "3.2.0"
    var kotlinresourcecontainerVer = "0.12.0"
    var kotlinscriptureburritoVer = "1.0.1"
    var slf4jApiVer = "2.0.13"
    var log4j2Ver = "2.15.0"
    var ikonliVer = "12.2.0"
    var controlsfxVer = "11.0.1"
    var sentryVer = "5.2.4"
    var usfmToolsVer = "1.9.2-proprosedfix"
    var testFxVer = "4.0.17"
    var testFxMonocleVer = "jfx-21"
    var jlayerVer = "1.0"
    var cuelibVer = "2.0.0"
    var install4jVer = "9.0.4"
    var jump3rVer = "1.0.5"
    var systemThemeDectectorVer = "3.8"
    var tarsosDspVer = "2.4.1"
    var mp3TagVer = "0.9.3"
    var tikaVer = "2.0.0"
    var tstudio2rcVer = "1.0.2"
    var kotlinVttVer = "1.0.0"
    var kotlinScriptureAlignmentVer = "1.0.0"

    var koinVer = "3.5.6"
    var kotlinInjectVer = "0.6.3"
    //var kspVer = "1.9.23-1.0.20"

//    val content by configurations.creating
//    val runtime by configurations
//    content.extendsFrom(runtime)

    sourceSets {

        val androidMain by getting {
            dependencies {
                implementation(compose.preview)
                implementation(libs.androidx.activity.compose)
                implementation(libs.sqldroid)
                implementation("com.readystatesoftware.sqliteasset:sqliteassethelper:2.0.1")
                // Koin for Android
                implementation(libs.koin.android)
                // For Compose on Android
                implementation(libs.koin.compose)
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

        val commonMain by getting {
            // apply("fetchAssets.gradle")

            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)
                implementation(libs.androidx.lifecycle.viewmodel)
                implementation(libs.androidx.lifecycle.viewmodel.compose)
                implementation(libs.androidx.lifecycle.runtime.compose)
                implementation(libs.androidx.navigation.compose)
                implementation(libs.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.rx2)

                implementation("io.reactivex.rxjava2:rxkotlin:$rxkotlinVer")
                implementation("com.github.thomasnield:rxkotlinfx:$rxkotlinfxVer")
                implementation("com.jakewharton.rxrelay2:rxrelay:$rxrelayVer")
                implementation("org.jooq:jooq:$jooqVer")

                implementation("io.reactivex.rxjava2:rxkotlin:$rxkotlinVer")
                implementation("com.jakewharton.rxrelay2:rxrelay:$rxrelayVer")
                implementation("org.slf4j:slf4j-api:$slf4jApiVer")
                implementation("de.sciss:jump3r:$jump3rVer")
                implementation("org.wycliffeassociates:kotlin-tstudio2rc:$tstudio2rcVer")

                implementation("org.bibletranslationtools:otter-db:1.0")

                // Resource Container
                implementation("org.wycliffeassociates:kotlin-resource-container:$kotlinresourcecontainerVer")

                // USFM Tools
                implementation("org.wycliffeassociates:usfmtools:$usfmToolsVer")

                implementation("com.google.dagger:dagger:$daggerVer")
                // kapt("com.google.dagger:dagger-compiler:$daggerVer")

                // Explicitly mention reflection lib so Gradle and kotlin-resource-container's Jackson
                // dependencies coexist w/o warnings. This can be removed once the krc lib is updated
                // to Jackson 2.9.8+.
                implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVer")
                implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVer")
                implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:$jacksonVer")
                implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-csv:$jacksonVer")

                // Retrofit
                implementation("com.squareup.retrofit2:retrofit:$retrofitVer")
                implementation("com.squareup.retrofit2:converter-jackson:$retrofitJacksonVer")
                implementation("com.squareup.retrofit2:adapter-rxjava2:$retrofitRxJava2Ver")

                implementation("org.apache.tika:tika-core:$tikaVer")

                implementation("org.bibletranslationtools:kotlin-scripture-burrito:$kotlinscriptureburritoVer")
                implementation("org.bibletranslationtools:kotlin-vtt:$kotlinVttVer")
                implementation("org.bibletranslationtools:kotlin-scripture-alignment:$kotlinScriptureAlignmentVer")

                implementation("org.slf4j:slf4j-api:$slf4jApiVer")
                implementation("javazoom.jl:SeekableJlayer:$jlayerVer")
                implementation("org.digitalmediaserver:cuelib-core:$cuelibVer")
                implementation("org.bibletranslationtools:kotlin-vtt:$kotlinVttVer")
                implementation("com.mpatric:mp3agic:$mp3TagVer")
                implementation("be.tarsos:tarsosdsp:$tarsosDspVer")

                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

                //Dagger2
                implementation("com.google.dagger:dagger:$daggerVer")

                // Koin
                implementation("io.insert-koin:koin-core:$koinVer")

                // Kotlin-Inject
                implementation("me.tatarka.inject:kotlin-inject-runtime:$kotlinInjectVer")

                implementation(libs.koin.core)

                implementation(libs.datastore.preferences)

                implementation("io.github.vinceglb:filekit-core:${"0.12.0"}")
                implementation("io.github.vinceglb:filekit-dialogs:${"0.12.0"}")
                implementation("io.github.vinceglb:filekit-dialogs-compose:${"0.12.0"}")
                implementation("io.github.vinceglb:filekit-coil:${"0.12.0"}")
            }
        }

        val desktopTest by getting {
            // dependsOn(commonTest)
            dependencies {
                implementation(libs.kotlin.test.junit)
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)

                implementation("org.xerial:sqlite-jdbc:3.49.0.0")
                //sqllite("org.xerial:sqlite-jdbc:$sqliteJdbcVer")

                // SLF4J console binding. Without a binding, every logger.error()
                // in the Orature backend (export, import, audio) goes to a no-op
                // and failures are completely invisible. slf4j-simple prints to
                // stderr so backend errors are actually diagnosable when running
                // from a terminal.
                implementation("org.slf4j:slf4j-simple:2.0.13")
            }
        }

        dependencies {
            implementation("bibleineverylanguage:langnames@json")
        }
    }
}

android {
    namespace = "org.bibletranslationtools.recorder2"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.bibletranslationtools.recorder2"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.runtime.android)
    implementation(libs.androidx.ui.android)
    debugImplementation(compose.uiTooling)
}

compose.desktop {
    application {
         mainClass = "org.bibletranslationtools.recorder2.MainKt"
        //mainClass = "org.bibletranslationtools.bttrecorder2.demo.MainKt"

        jvmArgs += listOf(
            "-Xdock:name=BTT-Recorder",
            "-Dapple.awt.application.name=BTT-Recorder"
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "BTT-Recorder"
            packageVersion = "1.0.0"

            // sqlite-jdbc and JOOQ require java.sql; jackson-dataformat-yaml needs java.naming.
            // jpackage trims the JRE to declared modules only, so these must be explicit.
            modules("java.sql", "java.naming", "java.xml", "jdk.unsupported")

            macOS {
                iconFile.set(project.file("src/desktopMain/resources/icons/ic_launcher.icns"))
            }
            windows {
                iconFile.set(project.file("src/desktopMain/resources/icons/ic_launcher.ico"))
            }
            linux {
                iconFile.set(project.file("src/desktopMain/resources/icons/ic_launcher.png"))
            }
        }
    }
}

// Ensure -Xdock:name reaches the dev run task (compose.desktop jvmArgs targets packaging only).
tasks.withType<JavaExec>().configureEach {
    if (name == "run") {
        jvmArgs("-Xdock:name=BTT-Recorder")
    }
}

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

            // Only download en_ulb for now to save time/bandwidth as requested by the specific task context.
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
