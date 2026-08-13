import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :app-orature — the Orature front end. Its OWN screens/ViewModels/theme over the shared
// backend + playback engine (:shared). Separate app, separate branding.
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
                implementation(libs.koin.compose)
                // Crash reporting (JVM: Sentry). Disabled at runtime unless a sentry.properties DSN
                // is on the classpath.
                implementation(libs.sentry)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlin.test.junit)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.koin.test)
                implementation(libs.mockk)
            }
        }

        val commonMain by getting {
            dependencies {
                // Backend + playback engine + shared resources come via :shared (api).
                api(projects.shared)

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
                // koinInject() for reading app-scoped Koin singles (e.g. OratureNavigationLock)
                // directly in a plain @Composable that isn't itself a ViewModel/KoinComponent.
                implementation(libs.koin.compose)
                // Plugin definitions are YAML — see OraturePluginRegistrar. Declared explicitly
                // rather than leaning on :shared's api(kaml), since this module uses it directly.
                implementation(libs.kaml)
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                // Crash reporting (JVM: Sentry). Disabled at runtime unless a sentry.properties DSN
                // is on the classpath.
                implementation(libs.sentry)
            }
        }
    }
}

// Orature owns its UI strings: the 12-language message catalog migrated from the
// old app's Messages_*.properties lives in this module's composeResources, generating
// its own Res (separate from :shared's backend Res).
compose.resources {
    publicResClass = true
    packageOfResClass = "org.bibletranslationtools.orature.resources"
    generateResClass = always
}

android {
    namespace = "org.bibletranslationtools.orature"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "org.bibletranslationtools.orature"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    buildFeatures {
        // Generate BuildConfig so the app version (VERSION_NAME) is readable at runtime (Info drawer).
        buildConfig = true
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
        // Required by minSdk 24 — see the matching block in :shared. Dexing happens here, so
        // this flag is what actually rewrites the backend's java.time/java.nio.file call sites
        // to j$.*; without it the app compiles and dexes fine and then dies at runtime on
        // Android 7 with NoClassDefFoundError.
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    implementation(libs.androidx.runtime.android)
    implementation(libs.androidx.ui.android)
    debugImplementation(compose.uiTooling)
    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)
}

compose.desktop {
    application {
        mainClass = "org.bibletranslationtools.orature.MainKt"

        jvmArgs += listOf(
            "-Xdock:name=Orature",
            "-Dapple.awt.application.name=Orature"
        )

        // ProGuard runs in SHRINK-ONLY mode for release packaging — same setup as :app-recorder
        // (see proguard/desktop-shrink.pro). Orature adds Sentry, which resolves its integrations
        // reflectively, so it carries an extra rules file.
        buildTypes.release.proguard {
            isEnabled.set(true)
            obfuscate.set(false)
            optimize.set(false)
            joinOutputJars.set(false)
            configurationFiles.from(
                rootProject.file("proguard/desktop-shrink.pro"),
                rootProject.file("proguard/desktop-shrink-orature.pro")
            )
        }

        nativeDistributions {
            // Exe alongside Msi: the exe is the same MSI wrapped in a self-extracting
            // bootstrapper, so both carry the upgradeUuid below and behave identically on
            // upgrade. Each format only builds on its own OS.
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb)
            packageName = "Orature"
            packageVersion = "1.0.0"
            // jpackage trims the bundled JRE to the declared modules only. UNION of
            // suggestRuntimeModules (static scan) + modules reached reflectively / via ServiceLoader
            // that the scan can't see (java.naming for jackson-yaml, java.xml for XML parsing, and
            // jdk.zipfs, whose ZipFileSystemProvider is reached only through
            // FileSystems.newFileSystem — without it NioZipFileReader throws
            // ProviderNotFoundException("jar") and the bundled-ULB import fails on FIRST RUN).
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
                // stay fixed forever, and must differ from BTT-Recorder's — never regenerate it.
                upgradeUuid = "1E453212-5041-4FBE-B14A-C3582F6C4508"
            }
            linux {
                iconFile.set(project.file("src/desktopMain/resources/icons/ic_launcher.png"))
            }
        }
    }
}

tasks.withType<JavaExec>().configureEach {
    if (name == "run") {
        jvmArgs("-Xdock:name=Orature")
    }
}
