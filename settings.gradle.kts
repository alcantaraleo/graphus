pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "graphus"

include(
    "graphus-model",
    "graphus-parser",
    "graphus-indexer",
    "graphus-cli",
)
