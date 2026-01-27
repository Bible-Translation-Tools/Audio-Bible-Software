import java.net.URI

rootProject.name = "BTT-Recorder2"
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

include(":composeApp")