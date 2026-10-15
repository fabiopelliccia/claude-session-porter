pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "session-porter"

// The core is its own build (it has a life of its own as a library); the CLI depends on it by its
// coordinates and Gradle substitutes the included build for the published artifact.
includeBuild("core")

include(":cli")
