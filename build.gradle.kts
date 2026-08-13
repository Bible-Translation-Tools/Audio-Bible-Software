plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.jetbrainsKotlinJvm) apply false
}

//allprojects{
//    repositories {
//        mavenCentral()
//    }
//}

// ── Android 7 (minSdk 24) dependency constraint ──────────────────────────────────────
// jackson-databind 2.15.0 introduced util/ExceptionUtil, whose isFatal() tests
// `instanceof java.lang.BootstrapMethodError` — a class that only exists from API 26. It runs from
// JacksonAnnotationIntrospector's static initializer, and jOOQ's Convert probes for ObjectMapper
// with Class.forName during its own clinit, so the chain fires during app startup and dies with
// NoClassDefFoundError. Reproduced on an API 24 emulator; every release from 2.15.1 through
// 2.19.0 still carries it, so there is no newer version to move to. Core library desugaring does
// not cover this — it backports java.time and java.nio.file, not java.lang.
//
// This has to be a force rather than a plain version choice: kotlin-resource-container,
// kotlin-scripture-burrito and retrofit's Jackson converter each declare their own Jackson, and
// Gradle's default "highest version wins" would silently restore 2.15+. It also has to live here
// rather than in :shared, because the APK's runtime classpath is resolved in the app modules.
//
// Remove this (and the `jackson` version pin) if minSdk ever goes back above 25.
subprojects {
    configurations.configureEach {
        resolutionStrategy {
            val jackson = rootProject.libs.versions.jackson.get()
            force(
                "com.fasterxml.jackson.core:jackson-databind:$jackson",
                "com.fasterxml.jackson.core:jackson-core:$jackson",
                "com.fasterxml.jackson.core:jackson-annotations:$jackson"
            )

            // ── Vendored WA libraries ────────────────────────────────────────────────
            // Uncomment a line once that library's sources are in libs/<module>/src/main/kotlin
            // (see libs/README.md). Substitution rather than editing :shared's dependency
            // declarations, because it also catches TRANSITIVE references to the same
            // coordinates — :libs:tstudio2rc depends on resource-container, so a plain swap
            // would leave the published jar on the classpath alongside the project and give
            // duplicate classes.
            //
            // Do one at a time and build in between; the tree stays green between each.
            dependencySubstitution {
                substitute(module("org.wycliffeassociates:kotlin-resource-container"))
                    .using(project(":libs:resource-container"))
                // substitute(module("org.bibletranslationtools:kotlin-scripture-burrito"))
                //     .using(project(":libs:scripture-burrito"))
                substitute(module("org.wycliffeassociates:kotlin-tstudio2rc"))
                    .using(project(":libs:tstudio2rc"))
                // substitute(module("org.bibletranslationtools:kotlin-scripture-alignment"))
                //     .using(project(":libs:scripture-alignment"))
                // substitute(module("org.bibletranslationtools:kotlin-vtt"))
                //     .using(project(":libs:vtt"))
            }
        }
    }
}