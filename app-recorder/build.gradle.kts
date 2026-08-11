@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)

import org.gradle.internal.os.OperatingSystem
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
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm("desktop")

    sourceSets {

        val androidMain by getting {
            dependencies {
                implementation(compose.preview)
                implementation(libs.androidx.activity.compose)
                // Koin + Compose on Android (backend Koin/audio comes via :shared).
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
            dependencies {
                // Backend + shared render/resources. `api` in :shared re-exports the
                // backend libs (RxJava, Koin, coroutines, serialization, Compose
                // resources, datastore, filekit…), so they're available here transitively.
                api(projects.shared)

                // Compose UI stack (not yet in :shared — reusable UI moves there in a
                // later step; app screens use these directly today).
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material)
                implementation(compose.ui)
                implementation(compose.materialIconsExtended)
                implementation(compose.components.uiToolingPreview)
                implementation(libs.material3)
                implementation(libs.androidx.lifecycle.viewmodel)
                implementation(libs.androidx.lifecycle.viewmodel.compose)
                implementation(libs.androidx.lifecycle.runtime.compose)
                implementation(libs.androidx.navigation.compose)
            }
        }

        val desktopTest by getting {
            dependencies {
                implementation(libs.kotlin.test.junit)
                implementation(compose.desktop.currentOs)
                implementation(compose.uiTest)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.koin.test)
            }
        }

        val androidInstrumentedTest by getting {
            dependencies {
                implementation(libs.kotlin.test.junit)
                implementation(libs.junit)
                implementation(libs.androidx.test.junit)
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.rules)
                implementation(libs.androidx.uiautomator)
                implementation(libs.koin.android)
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
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
        testInstrumentationRunner = "org.bibletranslationtools.recorder2.e2e.RecorderE2ERunner"
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

        // macOS-only JVM flags; Windows/Linux reject -Xdock and fail to start.
        if (OperatingSystem.current().isMacOsX) {
            jvmArgs += listOf(
                "-Xdock:name=BTT-Recorder",
                "-Dapple.awt.application.name=BTT-Recorder"
            )
        }

        // ProGuard runs in SHRINK-ONLY mode for release packaging: dead code is removed, but
        // nothing is renamed or inlined. The target is material-icons-extended, which ships all
        // ~11,100 Material icons while this app references a few dozen.
        // Renaming/inlining is what would break the reflective paths here (jOOQ record mapping,
        // Jackson, snakeyaml, JNA, sqlite-jdbc, ServiceLoader), so both stay off and the
        // reflective libraries are kept whole. See proguard/desktop-shrink.pro for the rules and
        // the -dontwarn list that silences the optional deps absent on desktop.
        buildTypes.release.proguard {
            isEnabled.set(true)
            obfuscate.set(false)
            optimize.set(false)
            // Keep one output jar per input jar so per-library size changes stay measurable.
            joinOutputJars.set(false)
            configurationFiles.from(rootProject.file("proguard/desktop-shrink.pro"))
        }

        nativeDistributions {
            // Exe alongside Msi: the exe is the same MSI wrapped in a self-extracting
            // bootstrapper, so both carry the upgradeUuid below and behave identically on
            // upgrade. Each format only builds on its own OS.
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb)
            packageName = "BTT-Recorder"
            packageVersion = "1.0.0"

            // jpackage trims the bundled JRE to the declared modules only, so anything reached at
            // runtime must be listed. This is the UNION of two sources:
            //  - suggestRuntimeModules (static bytecode scan): java.compiler, java.instrument,
            //    java.sql, jdk.security.auth, jdk.unsupported
            //  - modules reached reflectively / via ServiceLoader, which that scan cannot see:
            //    java.naming (jackson-dataformat-yaml), java.xml (XML parsing), and jdk.zipfs,
            //    whose ZipFileSystemProvider is found only through FileSystems.newFileSystem —
            //    without it every NioZipFileReader fails with ProviderNotFoundException("jar"),
            //    which kills the bundled-ULB import on FIRST RUN and nothing else.
            // Keep both sets — dropping the reflective ones only fails at runtime, never at build.
            modules(
                "java.compiler",
                "java.instrument",
                "java.naming",
                "java.sql",
                "java.xml",
                "jdk.security.auth",
                "jdk.unsupported",
                "jdk.zipfs"
            )

            macOS {
                iconFile.set(project.file("src/desktopMain/resources/icons/ic_launcher.icns"))
            }
            windows {
                iconFile.set(project.file("src/desktopMain/resources/icons/ic_launcher.ico"))
                // --win-shortcut: the MSI drops a desktop shortcut. Also a prerequisite for
                // jpackage's --win-shortcut-prompt (the "create shortcuts?" checkbox), which
                // the Compose DSL does not expose — see WindowsPlatformSettings.
                shortcut = true
                // --win-menu / --win-menu-group: Start menu entry under a WA folder, so the two
                // apps group together rather than sitting loose in the app list.
                menu = true
                menuGroup = "Wycliffe Associates"
                // --win-upgrade-uuid: the MSI UpgradeCode. It identifies "this product" across
                // versions, so installing 1.0.1 over 1.0.0 REPLACES it instead of leaving two
                // entries in Programs and Features and two desktop shortcuts. It must therefore
                // stay fixed forever, and must differ from Orature's — never regenerate it.
                upgradeUuid = "46D06A56-B75B-4B11-A1E6-9D3999A1BC5E"
            }
            linux {
                iconFile.set(project.file("src/desktopMain/resources/icons/ic_launcher.png"))
            }
        }
    }
}

// Ensure -Xdock:name reaches the macOS dev run task (compose.desktop jvmArgs targets packaging only).
tasks.withType<JavaExec>().configureEach {
    if (name == "run" && OperatingSystem.current().isMacOsX) {
        jvmArgs("-Xdock:name=BTT-Recorder")
    }
}
