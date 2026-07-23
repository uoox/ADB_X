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
        // libxposed API is published on Maven Central; the legacy
        // api.xposed.info repo is no longer needed.
        maven("https://jitpack.io")
    }
}

rootProject.name = "ADB_X"
include(":app")
