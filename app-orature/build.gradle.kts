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
    }
}

dependencies {
    implementation(libs.androidx.runtime.android)
    implementation(libs.androidx.ui.android)
    debugImplementation(compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "org.bibletranslationtools.orature.MainKt"

        jvmArgs += listOf(
            "-Xdock:name=Orature",
            "-Dapple.awt.application.name=Orature"
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Orature"
            packageVersion = "1.0.0"
            modules("java.sql", "java.naming", "java.xml", "jdk.unsupported")
        }
    }
}

tasks.withType<JavaExec>().configureEach {
    if (name == "run") {
        jvmArgs("-Xdock:name=Orature")
    }
}
