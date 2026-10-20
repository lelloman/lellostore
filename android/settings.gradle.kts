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
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.MuntashirAkon")
                includeGroup("com.github.MuntashirAkon.spake2-java")
                includeGroup("com.github.lelloman.androidoscopy")
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
include(":paravoid-update-ipc")


include(":notification-fixture")

// Pinned by the vendor/lello-auth gitlink; initialize with git submodule update --init.
includeBuild("../vendor/lello-auth/android") {
    dependencySubstitution {
        substitute(module("com.lelloman.auth:broker")).using(project(":broker"))
        substitute(module("com.lelloman.auth:localdata")).using(project(":localdata"))
        substitute(module("com.lelloman.auth:remoteapi")).using(project(":remoteapi"))
        substitute(module("com.lelloman.auth:domain")).using(project(":domain"))
    }
}
