plugins {
    id("graphus.java-conventions")
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

dependencies {
    implementation(project(":graphus-model"))
    implementation(project(":graphus-parser"))
    implementation(project(":graphus-indexer"))
    implementation("dev.langchain4j:langchain4j-core:1.14.0")
    implementation("info.picocli:picocli:4.7.6")
    annotationProcessor("info.picocli:picocli-codegen:4.7.6")
    implementation("org.slf4j:slf4j-simple:2.0.17")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
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

tasks.named<JavaExec>("run") {
    // Keep CLI relative paths anchored at the Graphus repository root.
    workingDir = rootProject.projectDir
}
