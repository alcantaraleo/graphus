package io.graphus.cli.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphus.cli.CliWorkspaceLayouts;
import io.graphus.cli.ParserProgressReporter;
import io.graphus.indexer.EmbeddingBackend;
import io.graphus.indexer.EmbeddingModelFactory;
import io.graphus.indexer.GraphIndexer;
import io.graphus.indexer.GraphusConfigRegistry;
import io.graphus.indexer.GraphusVectorRuntime;
import io.graphus.indexer.VectorStoreFactory;
import io.graphus.model.CallGraph;
import io.graphus.model.WorkspaceDescriptor;
import io.graphus.parser.ProjectParser;
import io.graphus.parser.ProjectParserResult;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class GraphusMcpContext {

    private final CallGraph callGraph;
    private final GraphIndexer graphIndexer;
    private final Path stateDir;
    private final Path repoRoot;
    private final ObjectMapper objectMapper;
    private final McpJsonMapper jsonMapper;

    private GraphusMcpContext(
            CallGraph callGraph,
            GraphIndexer graphIndexer,
            Path stateDir,
            Path repoRoot,
            ObjectMapper objectMapper,
            McpJsonMapper jsonMapper) {
        this.callGraph = callGraph;
        this.graphIndexer = graphIndexer;
        this.stateDir = stateDir;
        this.repoRoot = repoRoot;
        this.objectMapper = objectMapper;
        this.jsonMapper = jsonMapper;
    }

    public static GraphusMcpContext load(
            Path repoRoot,
            List<Path> sourceRoots,
            Path stateDir,
            String db,
            String dbUrl,
            Integer dbTimeoutSeconds,
            String dbFile,
            String embeddingBackend) throws Exception {

        Path normalizedRepo = repoRoot.toAbsolutePath().normalize();
        Path normalizedState = stateDir != null
                ? stateDir.toAbsolutePath().normalize()
                : normalizedRepo.resolve(".graphus").normalize();

        List<WorkspaceDescriptor> workspaces = CliWorkspaceLayouts.resolve(normalizedRepo, sourceRoots);
        if (workspaces.isEmpty()) {
            throw new IllegalStateException("No analysable workspace found under: " + normalizedRepo);
        }
        WorkspaceDescriptor workspace = workspaces.get(0);

        System.err.println("[graphus-mcp] Parsing sources...");
        ProjectParser parser = new ProjectParser();
        ParserProgressReporter reporter = new ParserProgressReporter();
        ProjectParserResult result = parser.parse(workspace, reporter);
        reporter.complete();
        System.err.println("[graphus-mcp] Parsed " + result.parsedFiles() + " files, " +
                result.unresolvedCalls() + " unresolved calls.");

        String persistedCollection = GraphusConfigRegistry.exists(normalizedState)
                ? GraphusConfigRegistry.load(normalizedState).collection()
                : normalizedRepo.getFileName().toString();

        GraphusVectorRuntime.Resolved runtime = GraphusVectorRuntime.merge(
                normalizedState,
                persistedCollection,
                db, dbUrl, dbTimeoutSeconds, dbFile, embeddingBackend);

        EmbeddingBackend embBackend = parseEmbeddingBackend(runtime.embedding());
        var embeddingModel = new EmbeddingModelFactory().create(embBackend);
        var embeddingStore = VectorStoreFactory.create(runtime.backend(), runtime.storeConfig());
        GraphIndexer graphIndexer = new GraphIndexer(embeddingModel, embeddingStore);

        System.err.println("[graphus-mcp] Ready. CallGraph nodes=" + result.callGraph().getNodes().size());

        McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();

        return new GraphusMcpContext(
                result.callGraph(),
                graphIndexer,
                normalizedState,
                normalizedRepo,
                new ObjectMapper(),
                jsonMapper);
    }

    public CallGraph callGraph() { return callGraph; }
    public GraphIndexer graphIndexer() { return graphIndexer; }
    public Path stateDir() { return stateDir; }
    public Path repoRoot() { return repoRoot; }
    public ObjectMapper objectMapper() { return objectMapper; }
    public McpJsonMapper jsonMapper() { return jsonMapper; }

    private static EmbeddingBackend parseEmbeddingBackend(String value) {
        String normalized = value == null ? "local" : value.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "openai" -> EmbeddingBackend.OPENAI;
            default -> EmbeddingBackend.LOCAL;
        };
    }
}
