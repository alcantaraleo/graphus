package io.graphus.parser;

import io.graphus.model.CallEdge;
import io.graphus.model.ClassNode;
import io.graphus.model.MethodNode;
import io.graphus.model.ModuleDescriptor;
import io.graphus.model.WorkspaceDescriptor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectParserTest {

    @Test
    void parsesMixedJavaAndKotlinWorkspaceAndProducesCrossLanguageEdges(@TempDir Path tempDir) throws IOException {
        // Layout:
        //   <root>/javaMod/src/main/java/com/demo/JavaCaller.java
        //   <root>/kotlinMod/src/main/kotlin/com/demo/KotlinTarget.kt
        Path repoRoot = tempDir.resolve("workspace");
        Path javaSourceRoot = repoRoot.resolve("javaMod/src/main/java");
        Path kotlinSourceRoot = repoRoot.resolve("kotlinMod/src/main/kotlin");
        Path javaPackage = javaSourceRoot.resolve("com/demo");
        Path kotlinPackage = kotlinSourceRoot.resolve("com/demo");
        Files.createDirectories(javaPackage);
        Files.createDirectories(kotlinPackage);

        Files.writeString(javaPackage.resolve("JavaCaller.java"),
                """
                package com.demo;

                public class JavaCaller {
                    public String invokeKotlin(String name) {
                        return greetFromKotlin(name);
                    }

                    private static String greetFromKotlin(String name) {
                        return name;
                    }
                }
                """);

        Files.writeString(kotlinPackage.resolve("KotlinTarget.kt"),
                """
                package com.demo

                class KotlinTarget {
                    fun farewell(name: String): String = "Bye, " + name
                    fun callsJava(): String = JavaCaller().invokeKotlin("Leo")
                }
                """);

        ModuleDescriptor javaModule = new ModuleDescriptor(
                "javaMod",
                repoRoot.resolve("javaMod"),
                List.of(javaSourceRoot.toAbsolutePath().normalize()),
                List.of());
        ModuleDescriptor kotlinModule = new ModuleDescriptor(
                "kotlinMod",
                repoRoot.resolve("kotlinMod"),
                List.of(),
                List.of(kotlinSourceRoot.toAbsolutePath().normalize()));
        WorkspaceDescriptor workspace = new WorkspaceDescriptor(
                "workspace", repoRoot, List.of(javaModule, kotlinModule));

        ProjectParserResult result = new ProjectParser().parse(workspace);

        assertTrue(result.parsedFiles() >= 2, "Both Java and Kotlin files should be parsed");

        ClassNode javaClass = (ClassNode) result.callGraph().getNode("com.demo.JavaCaller");
        assertNotNull(javaClass, "JavaCaller class node must be present");
        ClassNode kotlinClass = (ClassNode) result.callGraph().getNode("com.demo.KotlinTarget");
        assertNotNull(kotlinClass, "KotlinTarget class node must be present");

        MethodNode kotlinMethod =
                (MethodNode) result.callGraph().getNode("com.demo.KotlinTarget.callsJava()");
        assertNotNull(kotlinMethod, "Kotlin method must be in graph");

        // Kotlin → Java cross-language edge must exist for invokeKotlin(String).
        Set<CallEdge> edges = result.callGraph().getEdges();
        boolean kotlinCallsJava = edges.stream().anyMatch(edge ->
                edge.fromId().equals("com.demo.KotlinTarget.callsJava()")
                        && edge.toId().contains("JavaCaller")
                        && edge.toId().contains("invokeKotlin"));
        assertTrue(kotlinCallsJava,
                "CrossLanguageCallResolver should add Kotlin→Java edge by name+arity match. Edges: " + edges);
    }
}
