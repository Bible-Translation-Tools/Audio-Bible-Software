import java.net.URI

rootProject.name = "Audio-Translation-Software"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven("https://nexus-registry.walink.org/repository/maven-public/")

        ivy {
            url = URI("https://content.bibletranslationtools.org")
            patternLayout {
                artifact("[organisation]/[module]/archive/[revision].[ext]")
            }
            metadataSources { artifact() }
        }
        ivy {
            url = URI("https://langnames.bibleineverylanguage.org/")
            patternLayout {
                artifact("[artifact].[ext]")
            }
            metadataSources { artifact() }
        }
        ivy {
            url = URI("https://nightlybuilds.s3.amazonaws.com/Bible-Translation-Tools/artwork/")
            patternLayout {
                artifact("[artifact].[ext]")
            }
            metadataSources { artifact() }
        }
        ivy {
            url = URI("https://nexus-registry.walink.org/repository/maven-releases/")
            patternLayout {
                artifact("[organisation]/[module]/[revision]/[artifact]-[revision].[ext]")
            }
            metadataSources { artifact() }
        }
    }

}

include(":shared")
include(":app-recorder")
include(":app-orature")

// Vendored WA libraries — see libs/README.md. These are included from the start so the modules
// exist and build, but nothing depends on them until the matching dependencySubstitution line is
// uncommented in the root build.gradle.kts. An empty module is a valid (empty) jar, so including
// one before its sources land is harmless.
include(":libs:resource-container")
include(":libs:scripture-burrito")
include(":libs:tstudio2rc")
include(":libs:scripture-alignment")
include(":libs:vtt")