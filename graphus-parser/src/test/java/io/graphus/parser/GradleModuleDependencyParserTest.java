package io.graphus.parser;

import io.graphus.model.CallEdge;
import io.graphus.model.CallGraph;
import io.graphus.model.ModuleDescriptor;
import io.graphus.model.ModuleNode;
import io.graphus.model.WorkspaceDescriptor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradleModuleDependencyParserTest {

    @Test
    void addsModuleNodesAndEdgeForSingleDependency(@TempDir Path repoRoot) throws IOException {
        setupModule(repoRoot, "module-a", """
                dependencies {
                    implementation(project(":module-b"))
                }
                """);
        setupModule(repoRoot, "module-b", "");

        WorkspaceDescriptor workspace = workspace(repoRoot, "module-a", "module-b");
        CallGraph graph = new CallGraph();

        int edgesAdded = GradleModuleDependencyParser.resolve(graph, workspace);

        assertEquals(1, edgesAdded);
        assertTrue(graph.getNodes().stream().anyMatch(n -> n.getId().equals("module:module-a")));
        assertTrue(graph.getNodes().stream().anyMatch(n -> n.getId().equals("module:module-b")));
        assertTrue(graph.getEdges().contains(new CallEdge("module:module-a", "module:module-b")));
    }

    @Test
    void addsMultipleEdgesWhenModuleHasMultipleDependencies(@TempDir Path repoRoot) throws IOException {
        setupModule(repoRoot, "module-a", """
                dependencies {
                    implementation(project(":module-b"))
                    api(project(":module-c"))
                }
                """);
        setupModule(repoRoot, "module-b", "");
        setupModule(repoRoot, "module-c", "");

        WorkspaceDescriptor workspace = workspace(repoRoot, "module-a", "module-b", "module-c");
        CallGraph graph = new CallGraph();

        int edgesAdded = GradleModuleDependencyParser.resolve(graph, workspace);

        assertEquals(2, edgesAdded);
        assertTrue(graph.getEdges().contains(new CallEdge("module:module-a", "module:module-b")));
        assertTrue(graph.getEdges().contains(new CallEdge("module:module-a", "module:module-c")));
    }

    @Test
    void returnsZeroWhenNoBuildFile(@TempDir Path repoRoot) throws IOException {
        setupModule(repoRoot, "module-a", null);  // no build file
        setupModule(repoRoot, "module-b", "");

        WorkspaceDescriptor workspace = workspace(repoRoot, "module-a", "module-b");
        CallGraph graph = new CallGraph();

        int edgesAdded = GradleModuleDependencyParser.resolve(graph, workspace);

        assertEquals(0, edgesAdded);
        assertEquals(2, graph.getNodes().size());  // ModuleNodes still added
        assertTrue(graph.getEdges().isEmpty());
    }

    @Test
    void excludesTestDependencies(@TempDir Path repoRoot) throws IOException {
        setupModule(repoRoot, "module-a", """
                dependencies {
                    testImplementation(project(":module-b"))
                    testApi(project(":module-b"))
                }
                """);
        setupModule(repoRoot, "module-b", "");

        WorkspaceDescriptor workspace = workspace(repoRoot, "module-a", "module-b");
        CallGraph graph = new CallGraph();

        int edgesAdded = GradleModuleDependencyParser.resolve(graph, workspace);

        assertEquals(0, edgesAdded);
        assertTrue(graph.getEdges().isEmpty());
    }

    @Test
    void parsesGroovyDslSyntax(@TempDir Path repoRoot) throws IOException {
        setupModuleGroovy(repoRoot, "module-a", """
                dependencies {
                    implementation project(':module-b')
                    api project(':module-c')
                }
                """);
        setupModule(repoRoot, "module-b", "");
        setupModule(repoRoot, "module-c", "");

        WorkspaceDescriptor workspace = workspace(repoRoot, "module-a", "module-b", "module-c");
        CallGraph graph = new CallGraph();

        int edgesAdded = GradleModuleDependencyParser.resolve(graph, workspace);

        assertEquals(2, edgesAdded);
        assertTrue(graph.getEdges().contains(new CallEdge("module:module-a", "module:module-b")));
        assertTrue(graph.getEdges().contains(new CallEdge("module:module-a", "module:module-c")));
    }

    @Test
    void skipsExternalDependenciesAndNonExistentModules(@TempDir Path repoRoot) throws IOException {
        setupModule(repoRoot, "module-a", """
                dependencies {
                    implementation("com.example:library:1.0")
                    implementation(project(":unknown-module"))
                }
                """);

        WorkspaceDescriptor workspace = workspace(repoRoot, "module-a");
        CallGraph graph = new CallGraph();

        int edgesAdded = GradleModuleDependencyParser.resolve(graph, workspace);

        assertEquals(0, edgesAdded);
        assertTrue(graph.getEdges().isEmpty());
    }

    @Test
    void moduleNodeIdFollowsConvention(@TempDir Path repoRoot) throws IOException {
        setupModule(repoRoot, "my-service", "");

        WorkspaceDescriptor workspace = workspace(repoRoot, "my-service");
        CallGraph graph = new CallGraph();
        GradleModuleDependencyParser.resolve(graph, workspace);

        assertTrue(graph.getNodes().stream()
                .anyMatch(n -> n instanceof ModuleNode && n.getId().equals("module:my-service")));
    }

    // ---- helpers ----

    private static void setupModule(Path repoRoot, String moduleName, String buildContent) throws IOException {
        Path moduleDir = repoRoot.resolve(moduleName);
        Path srcMain = moduleDir.resolve("src/main/java");
        Files.createDirectories(srcMain);
        if (buildContent != null) {
            Files.writeString(moduleDir.resolve("build.gradle.kts"), buildContent);
        }
    }

    private static void setupModuleGroovy(Path repoRoot, String moduleName, String buildContent) throws IOException {
        Path moduleDir = repoRoot.resolve(moduleName);
        Path srcMain = moduleDir.resolve("src/main/java");
        Files.createDirectories(srcMain);
        if (buildContent != null) {
            Files.writeString(moduleDir.resolve("build.gradle"), buildContent);
        }
    }

    private static WorkspaceDescriptor workspace(Path repoRoot, String... moduleNames) {
        List<ModuleDescriptor> modules = new java.util.ArrayList<>();
        for (String name : moduleNames) {
            Path moduleRoot = repoRoot.resolve(name).toAbsolutePath().normalize();
            Path javaRoot = moduleRoot.resolve("src/main/java");
            modules.add(new ModuleDescriptor(name, moduleRoot, List.of(javaRoot)));
        }
        return new WorkspaceDescriptor(repoRoot.getFileName().toString(), repoRoot, modules);
    }
}
