package io.graphus.indexer;

import io.graphus.model.CallGraph;
import io.graphus.model.ClassNode;
import io.graphus.model.ModuleDescriptor;
import io.graphus.model.WorkspaceDescriptor;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SymbolChunkBuilderWorkspaceTest {

    @Test
    void tagsChunksPerModuleDescriptor(@TempDir Path tempDir) {
        Path mono = tempDir.resolve("mono-root").normalize();
        Path coreJava = mono.resolve("core/src/main/java").toAbsolutePath().normalize();
        Path apiJava = mono.resolve("api/src/main/java").toAbsolutePath().normalize();

        ModuleDescriptor core =
                new ModuleDescriptor("core", mono.resolve("core").toAbsolutePath().normalize(),
                        List.of(coreJava));
        ModuleDescriptor api =
                new ModuleDescriptor("api", mono.resolve("api").toAbsolutePath().normalize(),
                        List.of(apiJava));
        WorkspaceDescriptor workspace =
                new WorkspaceDescriptor(mono.getFileName().toString(),
                        mono.toAbsolutePath().normalize(),
                        List.of(core, api));

        CallGraph graph = new CallGraph();
        ClassNode clsCore = minimalClass("demo.Core", "core/src/main/java/Demo.java", 12);
        ClassNode clsApi = minimalClass("demo.Api", "api/src/main/java/Demo.java", 20);

        graph.addNode(clsCore);
        graph.addNode(clsApi);

        SymbolChunkBuilder builder = new SymbolChunkBuilder();
        List<SymbolChunk> chunks = builder.build(graph, workspace);
        List<String> moduleMetadata = chunks.stream()
                .map(c -> String.valueOf(c.metadata().toMap().get("module")))
                .sorted()
                .toList();

        assertEquals(List.of("api", "core"), moduleMetadata);

        List<SymbolChunk> subsetChunks =
                builder.build(graph, workspace, Set.of(clsCore.getFilePath()));
        assertEquals(1, subsetChunks.size());
        assertEquals("core", subsetChunks.get(0).metadata().toMap().get("module"));
    }

    private static ClassNode minimalClass(String id, String repoRelativePath, int line) {
        return new ClassNode(
                id,
                simpleName(id),
                repoRelativePath,
                line,
                List.of(),
                null,
                List.of(),
                null,
                null);
    }

    private static String simpleName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot >= 0 ? qualified.substring(dot + 1) : qualified;
    }
}
