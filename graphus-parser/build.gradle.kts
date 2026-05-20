plugins {
    id("graphus.java-conventions")
}

dependencies {
    implementation(project(":graphus-model"))
    implementation("com.github.javaparser:javaparser-symbol-solver-core:3.28.1")
    // Kotlin PSI for structural parsing of *.kt files (KtFile, KtClass, KtNamedFunction,
    // KtCallExpression, KtAnnotationEntry). Pinned during the P0 spike: kotlinx-ast was
    // rejected because (a) it is JitPack-only and (b) its KlassDeclaration model does not
    // expose call expressions inside function bodies, which the Kotlin call-graph builder needs.
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21")
    implementation("org.slf4j:slf4j-api:2.0.17")
}
