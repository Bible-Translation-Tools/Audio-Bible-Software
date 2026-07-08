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
