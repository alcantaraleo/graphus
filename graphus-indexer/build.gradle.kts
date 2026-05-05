plugins {
    id("graphus.java-conventions")
}

dependencies {
    implementation(project(":graphus-model"))
    implementation("dev.langchain4j:langchain4j-core:1.14.0")
    implementation("dev.langchain4j:langchain4j-chroma:1.14.0-beta24")
    implementation("dev.langchain4j:langchain4j-open-ai:1.14.0")
    implementation("dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2:1.14.0-beta24")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.3")
    implementation("org.slf4j:slf4j-api:2.0.17")
}
