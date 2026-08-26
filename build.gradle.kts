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

subprojects {
    configurations.configureEach {
        resolutionStrategy {

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
                substitute(module("org.bibletranslationtools:kotlin-scripture-burrito"))
                    .using(project(":libs:scripture-burrito"))
                substitute(module("org.wycliffeassociates:kotlin-tstudio2rc"))
                    .using(project(":libs:tstudio2rc"))
                substitute(module("org.bibletranslationtools:kotlin-scripture-alignment"))
                    .using(project(":libs:scripture-alignment"))
                substitute(module("org.bibletranslationtools:kotlin-vtt"))
                    .using(project(":libs:vtt"))
            }
        }
    }
}