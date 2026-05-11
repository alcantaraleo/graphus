import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven

buildscript {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    configurations.classpath {
        resolutionStrategy.eachDependency {
            when {
                requested.group == "org.apache.logging.log4j" &&
                    requested.name in listOf("log4j-core", "log4j-api") -> {
                    useVersion("2.25.4")
                    because(
                        "Align Shadow plugin classpath for GitHub dependency graph; CVE-2026-34477, CVE-2026-34478, CVE-2026-34480",
                    )
                }
                requested.group == "org.codehaus.plexus" && requested.name == "plexus-utils" -> {
                    useVersion("4.0.3")
                    because("CVE-2025-67030 (patched 4.x line)")
                }
            }
        }
    }
    dependencies {
        classpath("com.gradleup.shadow:shadow-gradle-plugin:9.4.0")
    }
}

plugins {
    id("graphus.java-conventions")
    application
}

apply(plugin = "com.gradleup.shadow")

dependencies {
    implementation(project(":graphus-model"))
    implementation(project(":graphus-parser"))
    implementation(project(":graphus-indexer"))
    implementation("dev.langchain4j:langchain4j-core:1.14.1")
    implementation("info.picocli:picocli:4.7.7")
    annotationProcessor("info.picocli:picocli-codegen:4.7.7")
    implementation("org.slf4j:slf4j-simple:2.0.17")
}

application {
    mainClass.set("io.graphus.cli.GraphusCommand")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("graphus")
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "io.graphus.cli.GraphusCommand"
    }
    mergeServiceFiles()
}

tasks.named<ProcessResources>("processResources") {
    filesMatching("io/graphus/cli/version.properties") {
        expand("version" to project.version)
    }
}

tasks.named<JavaExec>("run") {
    // Keep CLI relative paths anchored at the Graphus repository root.
    workingDir = rootProject.projectDir
}

tasks.withType<AbstractPublishToMaven>().configureEach {
    enabled = false
}
