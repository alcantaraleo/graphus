import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven

plugins {
    id("graphus.java-conventions")
    application
    id("com.gradleup.shadow") version "9.4.0"
}

dependencies {
    implementation(project(":graphus-model"))
    implementation(project(":graphus-parser"))
    implementation(project(":graphus-indexer"))
    implementation("dev.langchain4j:langchain4j-core:1.14.0")
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
