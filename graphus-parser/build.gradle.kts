plugins {
    id("graphus.java-conventions")
}

dependencies {
    implementation(project(":graphus-model"))
    implementation("com.github.javaparser:javaparser-symbol-solver-core:3.27.1")
    implementation("org.slf4j:slf4j-api:2.0.17")
}
