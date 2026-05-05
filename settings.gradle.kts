pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

gradle.beforeProject {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            when {
                requested.group == "org.apache.logging.log4j" &&
                    requested.name in listOf("log4j-core", "log4j-api") -> {
                    useVersion("2.25.4")
                    because(
                        "Align build/plugin classpath for GitHub dependency graph; CVE-2026-34477, CVE-2026-34478, CVE-2026-34480",
                    )
                }
                requested.group == "org.codehaus.plexus" && requested.name == "plexus-utils" -> {
                    useVersion("4.0.3")
                    because("CVE-2025-67030 (patched 4.x line)")
                }
            }
        }
    }
}

rootProject.name = "graphus"

include(
    "graphus-model",
    "graphus-parser",
    "graphus-indexer",
    "graphus-cli",
)
