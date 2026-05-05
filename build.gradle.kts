import org.gradle.api.publish.PublishingExtension

plugins {
    id("base")
}

val projectVersion = layout.projectDirectory.file("version.txt").asFile.readText().trim()
val githubRepository = providers.environmentVariable("GITHUB_REPOSITORY").orElse("alcantaraleo/graphus")

allprojects {
    group = "io.graphus"
    version = projectVersion

    repositories {
        mavenCentral()
    }
}

subprojects {
    pluginManager.withPlugin("java") {
        dependencies {
            constraints {
                add("implementation", "org.apache.logging.log4j:log4j-core:2.25.4") {
                    because("Addresses CVE-2025-68161, CVE-2026-34477, and CVE-2026-34480")
                }
                add("implementation", "commons-io:commons-io:2.22.0") {
                    because("Addresses CVE-2024-47554")
                }
                add("implementation", "org.codehaus.plexus:plexus-utils:3.6.1") {
                    because("Addresses CVE-2025-67030")
                }
            }
        }
    }

    pluginManager.withPlugin("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/${githubRepository.get()}")
                    credentials {
                        username = providers.environmentVariable("GITHUB_ACTOR")
                            .orElse(providers.environmentVariable("GITHUB_USERNAME"))
                            .orElse("github-actions[bot]")
                            .get()
                        password = providers.environmentVariable("GITHUB_TOKEN")
                            .orElse(providers.environmentVariable("GH_TOKEN"))
                            .orNull
                    }
                }
            }
        }
    }
}
