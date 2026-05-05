package io.graphus.parser;

import io.graphus.model.ModuleDescriptor;
import io.graphus.model.WorkspaceDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryLayoutDetectorTest {

    @Test
    void detectsGradleMultiModuleLayout(@TempDir Path tempDir) throws Exception {
        Path root = tempDir.resolve("mono");
        Files.createDirectories(root);
        Files.writeString(root.resolve("settings.gradle.kts"), "include(\"core\", \"api\")\n");

        Files.createDirectories(root.resolve("core/src/main/java"));
        Files.createDirectories(root.resolve("api/src/main/java"));

        List<WorkspaceDescriptor> workspaces = RepositoryLayoutDetector.detect(root);
        assertEquals(1, workspaces.size());

        WorkspaceDescriptor workspace = workspaces.get(0);
        assertTrue(workspace.isMultiModule());
        assertEquals("mono", workspace.name());

        Map<String, ModuleDescriptor> byName = workspace.modules().stream()
                .collect(Collectors.toMap(ModuleDescriptor::name, Function.identity()));

        assertEquals(2, byName.size());
        assertEquals(
                root.resolve("core/src/main/java").toAbsolutePath().normalize(),
                byName.get("core").sourceRoots().get(0));
        assertEquals(
                root.resolve("api/src/main/java").toAbsolutePath().normalize(),
                byName.get("api").sourceRoots().get(0));
    }

    @Test
    void detectsMavenMultiModuleParent(@TempDir Path tempDir) throws Exception {
        Path root = tempDir.resolve("maven-parent");
        Files.createDirectories(root);
        Files.writeString(
                root.resolve("pom.xml"),
                """
                        <project>
                          <modelVersion>4.0.0</modelVersion>
                          <groupId>t</groupId>
                          <artifactId>p</artifactId>
                          <version>1-SNAPSHOT</version>
                          <packaging>pom</packaging>
                          <modules>
                            <module>alpha</module>
                            <module>beta</module>
                          </modules>
                        </project>
                        """);

        Files.createDirectories(root.resolve("alpha/src/main/java"));
        Files.createDirectories(root.resolve("beta/src/main/java"));

        WorkspaceDescriptor ws = RepositoryLayoutDetector.detect(root).get(0);
        assertTrue(ws.isMultiModule());
        assertEquals(2, ws.modules().size());
    }

    @Test
    void singleModuleWhenSrcExistsAtRoot(@TempDir Path tempDir) throws Exception {
        Path app = tempDir.resolve("app");
        Files.createDirectories(app.resolve("src/main/java"));
        WorkspaceDescriptor ws = RepositoryLayoutDetector.detect(app).get(0);
        assertFalse(ws.isMultiModule());
        assertEquals(app.getFileName().toString(), ws.name());
    }

    @Test
    void parentWorkspaceReturnsChildWorkspaces(@TempDir Path tempDir) throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);

        Path projectA = workspace.resolve("project-a");
        Files.createDirectories(projectA.resolve("src/main/java"));

        Path projectB = workspace.resolve("project-b");
        Files.createDirectories(projectB);
        Files.writeString(projectB.resolve("settings.gradle.kts"), "include(\"core\", \"api\")\n");
        Files.createDirectories(projectB.resolve("core/src/main/java"));
        Files.createDirectories(projectB.resolve("api/src/main/java"));

        List<WorkspaceDescriptor> list = RepositoryLayoutDetector.detect(workspace);
        assertEquals(2, list.size());

        WorkspaceDescriptor wsA =
                list.stream().filter(workspaceDescriptor -> "project-a".equals(workspaceDescriptor.name()))
                        .findFirst()
                        .orElseThrow();
        assertFalse(wsA.isMultiModule());

        WorkspaceDescriptor wsB =
                list.stream().filter(workspaceDescriptor -> "project-b".equals(workspaceDescriptor.name()))
                        .findFirst()
                        .orElseThrow();
        assertTrue(wsB.isMultiModule());
    }

    @Test
    void singleModuleKotlinOnlyAtRootProducesKotlinDescriptor(@TempDir Path tempDir) throws Exception {
        Path app = tempDir.resolve("complete-kotlin");
        Files.createDirectories(app.resolve("src/main/kotlin"));
        Files.writeString(app.resolve("settings.gradle.kts"), "rootProject.name = \"complete-kotlin\"\n");

        List<WorkspaceDescriptor> workspaces = RepositoryLayoutDetector.detect(app);
        assertEquals(1, workspaces.size());
        WorkspaceDescriptor workspace = workspaces.get(0);
        assertFalse(workspace.isMultiModule());
        ModuleDescriptor module = workspace.modules().get(0);
        assertTrue(module.sourceRoots().isEmpty(),
                "Kotlin-only module must not synthesise a non-existent src/main/java entry");
        assertEquals(
                app.resolve("src/main/kotlin").toAbsolutePath().normalize(),
                module.kotlinSourceRoots().get(0));
    }

    @Test
    void mixedGradleModulesIncludeJavaAndKotlinRoots(@TempDir Path tempDir) throws Exception {
        Path root = tempDir.resolve("mixed");
        Files.createDirectories(root);
        Files.writeString(root.resolve("settings.gradle.kts"), "include(\"javaMod\", \"kotlinMod\")\n");

        Files.createDirectories(root.resolve("javaMod/src/main/java"));
        Files.createDirectories(root.resolve("kotlinMod/src/main/kotlin"));

        WorkspaceDescriptor workspace = RepositoryLayoutDetector.detect(root).get(0);
        assertTrue(workspace.isMultiModule());
        Map<String, ModuleDescriptor> byName = workspace.modules().stream()
                .collect(Collectors.toMap(ModuleDescriptor::name, Function.identity()));

        ModuleDescriptor javaMod = byName.get("javaMod");
        assertNotNull(javaMod);
        assertEquals(
                root.resolve("javaMod/src/main/java").toAbsolutePath().normalize(),
                javaMod.sourceRoots().get(0));
        assertTrue(javaMod.kotlinSourceRoots().isEmpty());

        ModuleDescriptor kotlinMod = byName.get("kotlinMod");
        assertNotNull(kotlinMod);
        assertTrue(kotlinMod.sourceRoots().isEmpty());
        assertEquals(
                root.resolve("kotlinMod/src/main/kotlin").toAbsolutePath().normalize(),
                kotlinMod.kotlinSourceRoots().get(0));
    }

    @Test
    void scanModuleDirectoriesDiscoversKotlinOnlyChildren(@TempDir Path tempDir) throws Exception {
        Path root = tempDir.resolve("autoscan");
        Files.createDirectories(root);
        // settings file with no include() — forces scanModuleDirectories to walk children.
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"autoscan\"\n");

        Files.createDirectories(root.resolve("alpha/src/main/java"));
        Files.createDirectories(root.resolve("beta/src/main/kotlin"));

        WorkspaceDescriptor workspace = RepositoryLayoutDetector.detect(root).get(0);
        Map<String, ModuleDescriptor> byName = workspace.modules().stream()
                .collect(Collectors.toMap(ModuleDescriptor::name, Function.identity()));

        assertNotNull(byName.get("alpha"), "Java-only module must be discovered");
        assertNotNull(byName.get("beta"), "Kotlin-only module must be discovered");
    }
}
