pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Shared IPC artifact; CI/release builds can supply the staged repository and version.
        maven {
            url = uri(providers.gradleProperty("paravoidRepository")
                .getOrElse("../../paravoid-android/build/local-repository"))
            content { includeGroup("com.lelloman.paravoid") }
        }
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.MuntashirAkon")
                includeGroup("com.github.MuntashirAkon.spake2-java")
            }
        }
    }
}

rootProject.name = "lellostore"
include(":app")
include(":ui")
include(":domain")
include(":remoteapi")
include(":localdata")
include(":logger")
include(":recovery-protocol")
include(":recovery")
include(":remote-adb")
