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
