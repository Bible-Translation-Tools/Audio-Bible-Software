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
    alias(libs.plugins.detekt) apply false
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

// ── Static analysis (detekt) ───────────────────────────────────────────────
// Opt-in: only the rules switched on in config/detekt/detekt.yml run. Each module keeps
// a detekt-baseline.xml of existing findings to burn down; the build fails only on new ones.
// Run everything with `./gradlew detektAll`. Plain `detekt` is not enough: on the
// multiplatform modules it checks no source sets, so detektAll depends on the
// per-source-set tasks instead.
subprojects {
    apply(plugin = "dev.detekt")
    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig.set(false)
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        baseline.set(file("detekt-baseline.xml"))
        parallel.set(true)
    }
    tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
        // Generated code (SQLDelight, Compose resources) is not ours to fix.
        exclude { it.file.invariantSeparatorsPath.contains("/build/") }
    }
}

tasks.register("detektAll") {
    group = "verification"
    description = "Runs detekt on every module and source set."
    dependsOn(subprojects.map { p ->
        p.tasks.withType<dev.detekt.gradle.Detekt>().matching {
            it.name == "detekt" || it.name.endsWith("SourceSet")
        }
    })
}
