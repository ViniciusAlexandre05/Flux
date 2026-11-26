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
    }
}

rootProject.name = "PcbSolderView"

include(":app")
include(":core:model")
include(":core:parser")
include(":core:render")
include(":core:update")

// The PC version is built only on request (-Pdesktop, or dev.cmd in "pc" mode), never by the Android gate.
if (providers.gradleProperty("desktop").isPresent && file("desktop/build.gradle.kts").exists()) include(":desktop")
