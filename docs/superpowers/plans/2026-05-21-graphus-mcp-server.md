# Graphus MCP Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `graphus serve` STDIO MCP server that exposes code intelligence tools (search, blast-radius, callers, callees, module-deps, hotspots, ownership) to any MCP-compatible coding agent (Claude Code, Cursor, Copilot).

**Architecture:** A new `ServeCommand` Picocli subcommand starts an MCP server over STDIO using the MCP Java SDK. At startup it parses the repository into an in-memory `CallGraph` and loads the persisted `GraphIndexer` from the existing embedding store. Each tool is a focused class that takes a `GraphusMcpContext` and returns a `SyncToolSpecification`. The `install` command's `ClaudeCodeAdapter` is extended to also write an MCP server entry into `.claude/settings.local.json`.

**Tech Stack:** `io.modelcontextprotocol.sdk:mcp:0.10.0` (STDIO transport), Jackson ObjectMapper (already transitive), Picocli 4.7.7, existing Graphus model/parser/indexer modules.

---

## File Map

**Created:**
```
graphus-cli/src/main/java/io/graphus/cli/mcp/
  GraphusMcpContext.java              # Holds loaded CallGraph + GraphIndexer; built once at startup
  GraphusMcpServer.java               # Wires all tools, builds and starts McpSyncServer
  tools/
    SearchTool.java                   # graphus_search — semantic search via GraphIndexer
    BlastRadiusTool.java              # graphus_blast_radius — BFS callers from a symbol
    CallersTool.java                  # graphus_callers — direct callers of a symbol
    CalleesTool.java                  # graphus_callees — direct callees of a symbol
    ModuleDepsTool.java               # graphus_module_deps — module dependency subgraph
    HotspotsTool.java                 # graphus_hotspots — churn × coupling ranking
    OwnershipTool.java                # graphus_ownership — reads ownership.json

graphus-cli/src/main/java/io/graphus/cli/
  ServeCommand.java                   # @Command("serve") Picocli entry point

graphus-cli/src/main/java/io/graphus/cli/install/claudecode/
  ClaudeCodeMcpSettingsInstaller.java # Writes MCP server entry to .claude/settings.local.json

graphus-cli/src/test/java/io/graphus/cli/mcp/tools/
  SearchToolTest.java
  BlastRadiusToolTest.java
  CallersToolTest.java
  CalleesToolTest.java
  ModuleDepsToolTest.java
  HotspotsToolTest.java
  OwnershipToolTest.java
```

**Modified:**
```
graphus-cli/build.gradle.kts                                        # add MCP SDK dep
graphus-cli/src/main/java/io/graphus/cli/GraphusCommand.java        # add ServeCommand subcommand
graphus-cli/src/main/java/io/graphus/cli/install/claudecode/
  ClaudeCodeAdapter.java                                             # add MCP section + installer call
graphus-cli/src/main/java/io/graphus/cli/install/cursor/
  CursorAdapter.java                                                 # add serve command note
README.md                                                            # document serve command
CLAUDE.md                                                            # add serve to CLI Commands table
```

---

## Task 1: Add MCP SDK Dependency

**Files:**
- Modify: `graphus-cli/build.gradle.kts`

- [ ] **Step 1: Add the dependency**

In `graphus-cli/build.gradle.kts`, add after the existing `implementation` lines:

```kotlin
dependencies {
    implementation(project(":graphus-model"))
    implementation(project(":graphus-parser"))
    implementation(project(":graphus-indexer"))
    implementation("dev.langchain4j:langchain4j-core:1.14.0")
    implementation("info.picocli:picocli:4.7.7")
    annotationProcessor("info.picocli:picocli-codegen:4.7.7")
    implementation("org.slf4j:slf4j-simple:2.0.17")
    implementation("io.modelcontextprotocol.sdk:mcp:0.10.0")
}
```

- [ ] **Step 2: Verify the build compiles**

```bash
./gradlew :graphus-cli:compileJava
```

Expected: `BUILD SUCCESSFUL` — no compilation errors.

- [ ] **Step 3: Commit**

```bash
git add graphus-cli/build.gradle.kts
git commit -m "build(cli): add MCP Java SDK dependency"
```

---

## Task 2: GraphusMcpContext — Startup Loader

**Files:**
- Create: `graphus-cli/src/main/java/io/graphus/cli/mcp/GraphusMcpContext.java`

`GraphusMcpContext` parses the repository into a `CallGraph`, initialises a `GraphIndexer` from the persisted config, and holds both for the lifetime of the MCP server. All tools receive this shared context.

- [ ] **Step 1: Create the class**

```java
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
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class GraphusMcpContext {

    private final CallGraph callGraph;
    private final GraphIndexer graphIndexer;
    private final Path stateDir;
    private final Path repoRoot;
    private final ObjectMapper objectMapper;

    private GraphusMcpContext(
            CallGraph callGraph,
            GraphIndexer graphIndexer,
            Path stateDir,
            Path repoRoot,
            ObjectMapper objectMapper) {
        this.callGraph = callGraph;
        this.graphIndexer = graphIndexer;
        this.stateDir = stateDir;
        this.repoRoot = repoRoot;
        this.objectMapper = objectMapper;
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

        return new GraphusMcpContext(
                result.callGraph(),
                graphIndexer,
                normalizedState,
                normalizedRepo,
                new ObjectMapper());
    }

    public CallGraph callGraph() { return callGraph; }
    public GraphIndexer graphIndexer() { return graphIndexer; }
    public Path stateDir() { return stateDir; }
    public Path repoRoot() { return repoRoot; }
    public ObjectMapper objectMapper() { return objectMapper; }

    private static EmbeddingBackend parseEmbeddingBackend(String value) {
        String normalized = value == null ? "local" : value.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "openai" -> EmbeddingBackend.OPENAI;
            default -> EmbeddingBackend.LOCAL;
        };
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew :graphus-cli:compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add graphus-cli/src/main/java/io/graphus/cli/mcp/GraphusMcpContext.java
git commit -m "feat(mcp): add GraphusMcpContext startup loader"
```

---

## Task 3: SearchTool

**Files:**
- Create: `graphus-cli/src/main/java/io/graphus/cli/mcp/tools/SearchTool.java`
- Create: `graphus-cli/src/test/java/io/graphus/cli/mcp/tools/SearchToolTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.graphus.cli.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphus.indexer.GraphIndexer;
import io.graphus.indexer.GraphSearchHit;
import io.graphus.cli.mcp.GraphusMcpContext;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SearchToolTest {

    @Test
    void spec_hasCorrectToolName() {
        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());
        SearchTool tool = new SearchTool(ctx);
        SyncToolSpecification spec = tool.spec();
        assertEquals("graphus_search", spec.tool().name());
    }

    @Test
    void callHandler_returnsHitsAsJson() throws Exception {
        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        GraphIndexer indexer = mock(GraphIndexer.class);
        when(ctx.graphIndexer()).thenReturn(indexer);
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());
        when(indexer.query("find payment service", null, 10))
                .thenReturn(List.of(new GraphSearchHit(0.92, "PaymentService.process()", Map.of("kind", "METHOD"))));

        SearchTool tool = new SearchTool(ctx);
        // invoke callHandler directly through spec
        var result = tool.spec().callHandler().apply(null,
                new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("graphus_search",
                        Map.of("query", "find payment service")));

        assertFalse(result.isError());
        String json = ((TextContent) result.content().get(0)).text();
        assertTrue(json.contains("PaymentService.process()"));
        assertTrue(json.contains("0.92"));
    }
}
```

- [ ] **Step 2: Run the test — verify it fails**

```bash
./gradlew :graphus-cli:test --tests "io.graphus.cli.mcp.tools.SearchToolTest" 2>&1 | tail -20
```

Expected: compilation error — `SearchTool` does not exist yet.

- [ ] **Step 3: Add Mockito to test dependencies**

In `graphus-cli/build.gradle.kts` add inside `dependencies {}`:

```kotlin
testImplementation("org.mockito:mockito-core:5.14.2")
```

- [ ] **Step 4: Create SearchTool**

```java
package io.graphus.cli.mcp.tools;

import io.graphus.cli.mcp.GraphusMcpContext;
import io.graphus.indexer.GraphSearchHit;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import java.util.Map;

public final class SearchTool {

    private final GraphusMcpContext ctx;

    public SearchTool(GraphusMcpContext ctx) {
        this.ctx = ctx;
    }

    public SyncToolSpecification spec() {
        return SyncToolSpecification.builder()
                .tool(Tool.builder()
                        .name("graphus_search")
                        .description("Search indexed code symbols by natural language query. Returns matching symbols with score, text, and metadata.")
                        .inputSchema(McpSchema.parseInputSchema("""
                                {
                                  "type": "object",
                                  "properties": {
                                    "query": { "type": "string", "description": "Natural language search query" },
                                    "top_k": { "type": "integer", "description": "Maximum results to return (default 10)" },
                                    "module": { "type": "string", "description": "Optional module name filter" }
                                  },
                                  "required": ["query"]
                                }
                                """))
                        .build())
                .callHandler((exchange, request) -> {
                    try {
                        String query = (String) request.arguments().get("query");
                        int topK = request.arguments().containsKey("top_k")
                                ? ((Number) request.arguments().get("top_k")).intValue() : 10;
                        String module = (String) request.arguments().get("module");

                        List<GraphSearchHit> hits = ctx.graphIndexer().query(query, module, topK);
                        List<Map<String, Object>> payload = hits.stream()
                                .map(h -> Map.<String, Object>of(
                                        "score", h.score(),
                                        "text", h.text(),
                                        "metadata", h.metadata()))
                                .toList();
                        String json = ctx.objectMapper().writeValueAsString(payload);
                        return CallToolResult.builder()
                                .content(List.of(new TextContent(json)))
                                .build();
                    } catch (Exception e) {
                        return CallToolResult.builder()
                                .content(List.of(new TextContent("{\"error\":\"" + e.getMessage() + "\"}")))
                                .isError(true)
                                .build();
                    }
                })
                .build();
    }
}
```

- [ ] **Step 5: Run the test — verify it passes**

```bash
./gradlew :graphus-cli:test --tests "io.graphus.cli.mcp.tools.SearchToolTest"
```

Expected: `BUILD SUCCESSFUL`, 2 tests passed.

- [ ] **Step 6: Commit**

```bash
git add graphus-cli/build.gradle.kts \
        graphus-cli/src/main/java/io/graphus/cli/mcp/tools/SearchTool.java \
        graphus-cli/src/test/java/io/graphus/cli/mcp/tools/SearchToolTest.java
git commit -m "feat(mcp): add graphus_search MCP tool"
```

---

## Task 4: BlastRadiusTool

**Files:**
- Create: `graphus-cli/src/main/java/io/graphus/cli/mcp/tools/BlastRadiusTool.java`
- Create: `graphus-cli/src/test/java/io/graphus/cli/mcp/tools/BlastRadiusToolTest.java`

- [ ] **Step 1: Write the failing test**

```java
package io.graphus.cli.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphus.cli.mcp.GraphusMcpContext;
import io.graphus.model.CallGraph;
import io.graphus.model.MethodNode;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlastRadiusToolTest {

    @Test
    void spec_hasCorrectToolName() {
        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.callGraph()).thenReturn(new CallGraph());
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());
        assertEquals("graphus_blast_radius", new BlastRadiusTool(ctx).spec().tool().name());
    }

    @Test
    void callHandler_returnsCaller_whenSymbolFound() throws Exception {
        CallGraph graph = new CallGraph();
        MethodNode target = new MethodNode("com.example.Service.save()", "Service.java", 10);
        MethodNode caller = new MethodNode("com.example.Controller.submit()", "Controller.java", 5);
        graph.addNode(target);
        graph.addNode(caller);
        graph.addEdge("com.example.Controller.submit()", "com.example.Service.save()");

        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.callGraph()).thenReturn(graph);
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());

        var result = new BlastRadiusTool(ctx).spec().callHandler().apply(null,
                new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("graphus_blast_radius",
                        Map.of("symbol", "com.example.Service.save()", "depth", 2)));

        assertFalse(result.isError());
        String json = ((TextContent) result.content().get(0)).text();
        assertTrue(json.contains("com.example.Controller.submit()"));
    }

    @Test
    void callHandler_returnsError_whenSymbolNotFound() {
        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.callGraph()).thenReturn(new CallGraph());
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());

        var result = new BlastRadiusTool(ctx).spec().callHandler().apply(null,
                new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("graphus_blast_radius",
                        Map.of("symbol", "com.missing.Thing.do()")));

        assertTrue(result.isError());
    }
}
```

- [ ] **Step 2: Run the test — verify it fails**

```bash
./gradlew :graphus-cli:test --tests "io.graphus.cli.mcp.tools.BlastRadiusToolTest" 2>&1 | tail -10
```

Expected: compilation error — `BlastRadiusTool` does not exist yet.

- [ ] **Step 3: Create BlastRadiusTool**

```java
package io.graphus.cli.mcp.tools;

import io.graphus.cli.mcp.GraphusMcpContext;
import io.graphus.model.CallGraph;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import java.util.Map;

public final class BlastRadiusTool {

    private final GraphusMcpContext ctx;

    public BlastRadiusTool(GraphusMcpContext ctx) {
        this.ctx = ctx;
    }

    public SyncToolSpecification spec() {
        return SyncToolSpecification.builder()
                .tool(Tool.builder()
                        .name("graphus_blast_radius")
                        .description("Find all callers of a symbol up to a given depth (BFS). Returns the list of symbols that transitively call the target.")
                        .inputSchema(McpSchema.parseInputSchema("""
                                {
                                  "type": "object",
                                  "properties": {
                                    "symbol": { "type": "string", "description": "Fully qualified method ID or substring (e.g. com.example.Service.save())" },
                                    "depth": { "type": "integer", "description": "Traversal depth (default 3)" }
                                  },
                                  "required": ["symbol"]
                                }
                                """))
                        .build())
                .callHandler((exchange, request) -> {
                    try {
                        String input = (String) request.arguments().get("symbol");
                        int depth = request.arguments().containsKey("depth")
                                ? ((Number) request.arguments().get("depth")).intValue() : 3;

                        CallGraph graph = ctx.callGraph();
                        String resolved = resolveSymbol(graph, input);
                        if (resolved == null) {
                            return CallToolResult.builder()
                                    .content(List.of(new TextContent(
                                            "{\"error\":\"Symbol not found: " + input + "\"}")))
                                    .isError(true)
                                    .build();
                        }

                        List<String> callers = graph.blastRadiusCallers(resolved, depth);
                        String json = ctx.objectMapper().writeValueAsString(
                                Map.of("target", resolved, "depth", depth, "callers", callers));
                        return CallToolResult.builder()
                                .content(List.of(new TextContent(json)))
                                .build();
                    } catch (Exception e) {
                        return CallToolResult.builder()
                                .content(List.of(new TextContent("{\"error\":\"" + e.getMessage() + "\"}")))
                                .isError(true)
                                .build();
                    }
                })
                .build();
    }

    private static String resolveSymbol(CallGraph graph, String input) {
        if (graph.getNode(input) != null) {
            return input;
        }
        return graph.getNodes().stream()
                .map(n -> n.getId())
                .filter(id -> id.contains(input))
                .findFirst()
                .orElse(null);
    }
}
```

- [ ] **Step 4: Run the tests — verify they pass**

```bash
./gradlew :graphus-cli:test --tests "io.graphus.cli.mcp.tools.BlastRadiusToolTest"
```

Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add graphus-cli/src/main/java/io/graphus/cli/mcp/tools/BlastRadiusTool.java \
        graphus-cli/src/test/java/io/graphus/cli/mcp/tools/BlastRadiusToolTest.java
git commit -m "feat(mcp): add graphus_blast_radius MCP tool"
```

---

## Task 5: CallersTool + CalleesTool

**Files:**
- Create: `graphus-cli/src/main/java/io/graphus/cli/mcp/tools/CallersTool.java`
- Create: `graphus-cli/src/main/java/io/graphus/cli/mcp/tools/CalleesTool.java`
- Create: `graphus-cli/src/test/java/io/graphus/cli/mcp/tools/CallersToolTest.java`
- Create: `graphus-cli/src/test/java/io/graphus/cli/mcp/tools/CalleesToolTest.java`

These tools return *direct* (depth-1) neighbors, unlike `BlastRadiusTool` which is BFS. They use `CallGraph.incomingNeighbors()` and `CallGraph.outgoingNeighbors()`.

- [ ] **Step 1: Write the failing tests**

```java
// CallersToolTest.java
package io.graphus.cli.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphus.cli.mcp.GraphusMcpContext;
import io.graphus.model.CallGraph;
import io.graphus.model.MethodNode;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CallersToolTest {

    @Test
    void spec_hasCorrectToolName() {
        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.callGraph()).thenReturn(new CallGraph());
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());
        assertEquals("graphus_callers", new CallersTool(ctx).spec().tool().name());
    }

    @Test
    void callHandler_returnsDirectCallers() throws Exception {
        CallGraph graph = new CallGraph();
        graph.addNode(new MethodNode("com.example.A.run()", "A.java", 1));
        graph.addNode(new MethodNode("com.example.B.run()", "B.java", 1));
        graph.addNode(new MethodNode("com.example.Target.go()", "Target.java", 1));
        graph.addEdge("com.example.A.run()", "com.example.Target.go()");
        graph.addEdge("com.example.B.run()", "com.example.Target.go()");

        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.callGraph()).thenReturn(graph);
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());

        var result = new CallersTool(ctx).spec().callHandler().apply(null,
                new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("graphus_callers",
                        Map.of("symbol", "com.example.Target.go()")));

        assertFalse(result.isError());
        String json = ((TextContent) result.content().get(0)).text();
        assertTrue(json.contains("com.example.A.run()"));
        assertTrue(json.contains("com.example.B.run()"));
    }
}
```

```java
// CalleesToolTest.java
package io.graphus.cli.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphus.cli.mcp.GraphusMcpContext;
import io.graphus.model.CallGraph;
import io.graphus.model.MethodNode;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CalleesToolTest {

    @Test
    void spec_hasCorrectToolName() {
        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.callGraph()).thenReturn(new CallGraph());
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());
        assertEquals("graphus_callees", new CalleesTool(ctx).spec().tool().name());
    }

    @Test
    void callHandler_returnsDirectCallees() throws Exception {
        CallGraph graph = new CallGraph();
        graph.addNode(new MethodNode("com.example.Service.save()", "Service.java", 1));
        graph.addNode(new MethodNode("com.example.Repo.insert()", "Repo.java", 1));
        graph.addNode(new MethodNode("com.example.Validator.check()", "Validator.java", 1));
        graph.addEdge("com.example.Service.save()", "com.example.Repo.insert()");
        graph.addEdge("com.example.Service.save()", "com.example.Validator.check()");

        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.callGraph()).thenReturn(graph);
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());

        var result = new CalleesTool(ctx).spec().callHandler().apply(null,
                new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("graphus_callees",
                        Map.of("symbol", "com.example.Service.save()")));

        assertFalse(result.isError());
        String json = ((TextContent) result.content().get(0)).text();
        assertTrue(json.contains("com.example.Repo.insert()"));
        assertTrue(json.contains("com.example.Validator.check()"));
    }
}
```

- [ ] **Step 2: Run the tests — verify they fail**

```bash
./gradlew :graphus-cli:test --tests "io.graphus.cli.mcp.tools.CallersToolTest,io.graphus.cli.mcp.tools.CalleesToolTest" 2>&1 | tail -10
```

Expected: compilation error.

- [ ] **Step 3: Create CallersTool**

```java
package io.graphus.cli.mcp.tools;

import io.graphus.cli.mcp.GraphusMcpContext;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import java.util.Map;

public final class CallersTool {

    private final GraphusMcpContext ctx;

    public CallersTool(GraphusMcpContext ctx) {
        this.ctx = ctx;
    }

    public SyncToolSpecification spec() {
        return SyncToolSpecification.builder()
                .tool(Tool.builder()
                        .name("graphus_callers")
                        .description("Return the direct (depth-1) callers of a symbol.")
                        .inputSchema(McpSchema.parseInputSchema("""
                                {
                                  "type": "object",
                                  "properties": {
                                    "symbol": { "type": "string", "description": "Fully qualified symbol ID or substring" }
                                  },
                                  "required": ["symbol"]
                                }
                                """))
                        .build())
                .callHandler((exchange, request) -> {
                    try {
                        String input = (String) request.arguments().get("symbol");
                        String resolved = ctx.callGraph().getNodes().stream()
                                .map(n -> n.getId())
                                .filter(id -> id.equals(input) || id.contains(input))
                                .findFirst().orElse(null);
                        if (resolved == null) {
                            return CallToolResult.builder()
                                    .content(List.of(new TextContent("{\"error\":\"Symbol not found: " + input + "\"}")))
                                    .isError(true).build();
                        }
                        var callers = ctx.callGraph().incomingNeighbors(resolved);
                        String json = ctx.objectMapper().writeValueAsString(
                                Map.of("symbol", resolved, "callers", callers));
                        return CallToolResult.builder().content(List.of(new TextContent(json))).build();
                    } catch (Exception e) {
                        return CallToolResult.builder()
                                .content(List.of(new TextContent("{\"error\":\"" + e.getMessage() + "\"}")))
                                .isError(true).build();
                    }
                })
                .build();
    }
}
```

- [ ] **Step 4: Create CalleesTool**

```java
package io.graphus.cli.mcp.tools;

import io.graphus.cli.mcp.GraphusMcpContext;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import java.util.Map;

public final class CalleesTool {

    private final GraphusMcpContext ctx;

    public CalleesTool(GraphusMcpContext ctx) {
        this.ctx = ctx;
    }

    public SyncToolSpecification spec() {
        return SyncToolSpecification.builder()
                .tool(Tool.builder()
                        .name("graphus_callees")
                        .description("Return the direct (depth-1) callees of a symbol — what this symbol calls.")
                        .inputSchema(McpSchema.parseInputSchema("""
                                {
                                  "type": "object",
                                  "properties": {
                                    "symbol": { "type": "string", "description": "Fully qualified symbol ID or substring" }
                                  },
                                  "required": ["symbol"]
                                }
                                """))
                        .build())
                .callHandler((exchange, request) -> {
                    try {
                        String input = (String) request.arguments().get("symbol");
                        String resolved = ctx.callGraph().getNodes().stream()
                                .map(n -> n.getId())
                                .filter(id -> id.equals(input) || id.contains(input))
                                .findFirst().orElse(null);
                        if (resolved == null) {
                            return CallToolResult.builder()
                                    .content(List.of(new TextContent("{\"error\":\"Symbol not found: " + input + "\"}")))
                                    .isError(true).build();
                        }
                        var callees = ctx.callGraph().outgoingNeighbors(resolved);
                        String json = ctx.objectMapper().writeValueAsString(
                                Map.of("symbol", resolved, "callees", callees));
                        return CallToolResult.builder().content(List.of(new TextContent(json))).build();
                    } catch (Exception e) {
                        return CallToolResult.builder()
                                .content(List.of(new TextContent("{\"error\":\"" + e.getMessage() + "\"}")))
                                .isError(true).build();
                    }
                })
                .build();
    }
}
```

- [ ] **Step 5: Run the tests — verify they pass**

```bash
./gradlew :graphus-cli:test --tests "io.graphus.cli.mcp.tools.CallersToolTest,io.graphus.cli.mcp.tools.CalleesToolTest"
```

Expected: `BUILD SUCCESSFUL`, 4 tests passed.

- [ ] **Step 6: Commit**

```bash
git add graphus-cli/src/main/java/io/graphus/cli/mcp/tools/CallersTool.java \
        graphus-cli/src/main/java/io/graphus/cli/mcp/tools/CalleesTool.java \
        graphus-cli/src/test/java/io/graphus/cli/mcp/tools/CallersToolTest.java \
        graphus-cli/src/test/java/io/graphus/cli/mcp/tools/CalleesToolTest.java
git commit -m "feat(mcp): add graphus_callers and graphus_callees MCP tools"
```

---

## Task 6: ModuleDepsTool

**Files:**
- Create: `graphus-cli/src/main/java/io/graphus/cli/mcp/tools/ModuleDepsTool.java`
- Create: `graphus-cli/src/test/java/io/graphus/cli/mcp/tools/ModuleDepsToolTest.java`

This tool walks `ModuleNode` entries in the `CallGraph` and emits the dependency edges (`MODULE_DEPENDS_ON`). It uses `CallGraph.getNodes()` filtered by `ModuleNode` type, and `CallGraph.outgoingNeighbors()` to find dependencies.

- [ ] **Step 1: Write the failing test**

```java
package io.graphus.cli.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphus.cli.mcp.GraphusMcpContext;
import io.graphus.model.CallGraph;
import io.graphus.model.ModuleNode;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModuleDepsToolTest {

    @Test
    void spec_hasCorrectToolName() {
        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.callGraph()).thenReturn(new CallGraph());
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());
        assertEquals("graphus_module_deps", new ModuleDepsTool(ctx).spec().tool().name());
    }

    @Test
    void callHandler_returnsModuleEdges() throws Exception {
        CallGraph graph = new CallGraph();
        ModuleNode api = new ModuleNode("api", "api/build.gradle.kts");
        ModuleNode core = new ModuleNode("core", "core/build.gradle.kts");
        graph.addNode(api);
        graph.addNode(core);
        graph.addEdge("api", "core");

        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.callGraph()).thenReturn(graph);
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());

        var result = new ModuleDepsTool(ctx).spec().callHandler().apply(null,
                new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("graphus_module_deps",
                        Map.of()));

        assertFalse(result.isError());
        String json = ((TextContent) result.content().get(0)).text();
        assertTrue(json.contains("\"api\""));
        assertTrue(json.contains("\"core\""));
    }
}
```

- [ ] **Step 2: Run the test — verify it fails**

```bash
./gradlew :graphus-cli:test --tests "io.graphus.cli.mcp.tools.ModuleDepsToolTest" 2>&1 | tail -10
```

Expected: compilation error.

- [ ] **Step 3: Look up ModuleNode constructor signature**

Run this to verify:
```bash
grep -n "public ModuleNode\|public record ModuleNode" \
  graphus-model/src/main/java/io/graphus/model/ModuleNode.java
```

Adjust the test's `new ModuleNode(...)` call if the constructor signature differs from `(String id, String filePath)`.

- [ ] **Step 4: Create ModuleDepsTool**

```java
package io.graphus.cli.mcp.tools;

import io.graphus.cli.mcp.GraphusMcpContext;
import io.graphus.model.ModuleNode;
import io.graphus.model.SymbolNode;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ModuleDepsTool {

    private final GraphusMcpContext ctx;

    public ModuleDepsTool(GraphusMcpContext ctx) {
        this.ctx = ctx;
    }

    public SyncToolSpecification spec() {
        return SyncToolSpecification.builder()
                .tool(Tool.builder()
                        .name("graphus_module_deps")
                        .description("Return the module dependency graph. Optional 'module' filter scopes output to a single module's dependencies.")
                        .inputSchema(McpSchema.parseInputSchema("""
                                {
                                  "type": "object",
                                  "properties": {
                                    "module": { "type": "string", "description": "Optional module name to scope results" }
                                  }
                                }
                                """))
                        .build())
                .callHandler((exchange, request) -> {
                    try {
                        String moduleFilter = (String) request.arguments().get("module");
                        List<Map<String, Object>> deps = new ArrayList<>();

                        for (SymbolNode node : ctx.callGraph().getNodes()) {
                            if (!(node instanceof ModuleNode)) {
                                continue;
                            }
                            String moduleId = node.getId();
                            if (moduleFilter != null && !moduleId.contains(moduleFilter)) {
                                continue;
                            }
                            var dependsOn = ctx.callGraph().outgoingNeighbors(moduleId).stream()
                                    .filter(dep -> ctx.callGraph().getNode(dep) instanceof ModuleNode)
                                    .toList();
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("module", moduleId);
                            entry.put("depends_on", dependsOn);
                            deps.add(entry);
                        }

                        String json = ctx.objectMapper().writeValueAsString(deps);
                        return CallToolResult.builder().content(List.of(new TextContent(json))).build();
                    } catch (Exception e) {
                        return CallToolResult.builder()
                                .content(List.of(new TextContent("{\"error\":\"" + e.getMessage() + "\"}")))
                                .isError(true).build();
                    }
                })
                .build();
    }
}
```

- [ ] **Step 5: Run the tests — verify they pass**

```bash
./gradlew :graphus-cli:test --tests "io.graphus.cli.mcp.tools.ModuleDepsToolTest"
```

Expected: `BUILD SUCCESSFUL`, 2 tests passed.

- [ ] **Step 6: Commit**

```bash
git add graphus-cli/src/main/java/io/graphus/cli/mcp/tools/ModuleDepsTool.java \
        graphus-cli/src/test/java/io/graphus/cli/mcp/tools/ModuleDepsToolTest.java
git commit -m "feat(mcp): add graphus_module_deps MCP tool"
```

---

## Task 7: HotspotsTool

**Files:**
- Create: `graphus-cli/src/main/java/io/graphus/cli/mcp/tools/HotspotsTool.java`
- Create: `graphus-cli/src/test/java/io/graphus/cli/mcp/tools/HotspotsToolTest.java`

Uses the cached `CallGraph` from context (skips re-parsing) plus git churn via `GitLogParser` + `ChurnAnalyzer`.

- [ ] **Step 1: Write the failing test**

```java
package io.graphus.cli.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphus.cli.mcp.GraphusMcpContext;
import io.graphus.model.CallGraph;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HotspotsToolTest {

    @Test
    void spec_hasCorrectToolName() {
        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.callGraph()).thenReturn(new CallGraph());
        when(ctx.repoRoot()).thenReturn(Path.of("."));
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());
        assertEquals("graphus_hotspots", new HotspotsTool(ctx).spec().tool().name());
    }

    @Test
    void callHandler_returnsJsonArray_evenWithNoGitHistory() {
        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.callGraph()).thenReturn(new CallGraph());
        when(ctx.repoRoot()).thenReturn(Path.of("/tmp/no-such-repo-" + System.nanoTime()));
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());

        var result = new HotspotsTool(ctx).spec().callHandler().apply(null,
                new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("graphus_hotspots",
                        Map.of("top", 5)));

        assertFalse(result.isError());
        String json = ((TextContent) result.content().get(0)).text();
        assertTrue(json.startsWith("["), "expected JSON array, got: " + json);
    }
}
```

- [ ] **Step 2: Run the test — verify it fails**

```bash
./gradlew :graphus-cli:test --tests "io.graphus.cli.mcp.tools.HotspotsToolTest" 2>&1 | tail -10
```

Expected: compilation error.

- [ ] **Step 3: Create HotspotsTool**

```java
package io.graphus.cli.mcp.tools;

import io.graphus.cli.git.ChurnAnalyzer;
import io.graphus.cli.git.GitLogParser;
import io.graphus.cli.mcp.GraphusMcpContext;
import io.graphus.model.CallEdge;
import io.graphus.model.CallGraph;
import io.graphus.model.SymbolNode;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class HotspotsTool {

    private final GraphusMcpContext ctx;

    public HotspotsTool(GraphusMcpContext ctx) {
        this.ctx = ctx;
    }

    public SyncToolSpecification spec() {
        return SyncToolSpecification.builder()
                .tool(Tool.builder()
                        .name("graphus_hotspots")
                        .description("Rank files by churn × coupling (git commit frequency × distinct callers). High scores = riskiest files to touch.")
                        .inputSchema(McpSchema.parseInputSchema("""
                                {
                                  "type": "object",
                                  "properties": {
                                    "since_days": { "type": "integer", "description": "Days of git history to analyse (default 365, 0 = all)" },
                                    "top": { "type": "integer", "description": "Number of entries to return (default 20)" }
                                  }
                                }
                                """))
                        .build())
                .callHandler((exchange, request) -> {
                    try {
                        int sinceDays = request.arguments().containsKey("since_days")
                                ? ((Number) request.arguments().get("since_days")).intValue() : 365;
                        int top = request.arguments().containsKey("top")
                                ? ((Number) request.arguments().get("top")).intValue() : 20;

                        List<GitLogParser.CommitFiles> history =
                                GitLogParser.parseCommitFiles(ctx.repoRoot(), sinceDays);
                        Map<String, Integer> churn = ChurnAnalyzer.analyze(history);
                        Map<String, Integer> coupling = computeFileCoupling(ctx.callGraph());

                        Set<String> allFiles = new HashSet<>(churn.keySet());
                        allFiles.addAll(coupling.keySet());

                        List<Map<String, Object>> hotspots = new ArrayList<>();
                        for (String file : allFiles) {
                            int c = churn.getOrDefault(file, 0);
                            int k = coupling.getOrDefault(file, 0);
                            if (c > 0 || k > 0) {
                                Map<String, Object> entry = new LinkedHashMap<>();
                                entry.put("file", file);
                                entry.put("churn", c);
                                entry.put("coupling", k);
                                entry.put("score", (long) c * k);
                                hotspots.add(entry);
                            }
                        }

                        hotspots.sort((a, b) -> Long.compare(
                                (Long) b.get("score"), (Long) a.get("score")));
                        List<Map<String, Object>> topN = hotspots.stream().limit(top).toList();

                        String json = ctx.objectMapper().writeValueAsString(topN);
                        return CallToolResult.builder().content(List.of(new TextContent(json))).build();
                    } catch (Exception e) {
                        return CallToolResult.builder()
                                .content(List.of(new TextContent("{\"error\":\"" + e.getMessage() + "\"}")))
                                .isError(true).build();
                    }
                })
                .build();
    }

    private static Map<String, Integer> computeFileCoupling(CallGraph graph) {
        Map<String, Set<String>> callersByTargetFile = new HashMap<>();
        for (CallEdge edge : graph.getEdges()) {
            SymbolNode to = graph.getNode(edge.toId());
            SymbolNode from = graph.getNode(edge.fromId());
            if (to == null || from == null) continue;
            String targetFile = to.getFilePath();
            String callerFile = from.getFilePath();
            if (targetFile == null || callerFile == null || targetFile.equals(callerFile)) continue;
            callersByTargetFile.computeIfAbsent(targetFile, k -> new HashSet<>()).add(callerFile);
        }
        Map<String, Integer> coupling = new HashMap<>();
        callersByTargetFile.forEach((f, callers) -> coupling.put(f, callers.size()));
        return coupling;
    }
}
```

- [ ] **Step 4: Run the tests — verify they pass**

```bash
./gradlew :graphus-cli:test --tests "io.graphus.cli.mcp.tools.HotspotsToolTest"
```

Expected: `BUILD SUCCESSFUL`, 2 tests passed.

- [ ] **Step 5: Commit**

```bash
git add graphus-cli/src/main/java/io/graphus/cli/mcp/tools/HotspotsTool.java \
        graphus-cli/src/test/java/io/graphus/cli/mcp/tools/HotspotsToolTest.java
git commit -m "feat(mcp): add graphus_hotspots MCP tool"
```

---

## Task 8: OwnershipTool

**Files:**
- Create: `graphus-cli/src/main/java/io/graphus/cli/mcp/tools/OwnershipTool.java`
- Create: `graphus-cli/src/test/java/io/graphus/cli/mcp/tools/OwnershipToolTest.java`

Reads `.graphus/ownership.json` (a `Map<String, String>` of file → primary author) written by `graphus git-analyze`. Returns an error if the file doesn't exist, prompting the user to run `git-analyze` first.

- [ ] **Step 1: Write the failing test**

```java
package io.graphus.cli.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphus.cli.mcp.GraphusMcpContext;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OwnershipToolTest {

    @TempDir
    Path tempDir;

    @Test
    void spec_hasCorrectToolName() {
        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.stateDir()).thenReturn(tempDir);
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());
        assertEquals("graphus_ownership", new OwnershipTool(ctx).spec().tool().name());
    }

    @Test
    void callHandler_returnsOwnershipMap_whenFileExists() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> data = Map.of("src/main/java/Foo.java", "alice@example.com");
        Files.writeString(tempDir.resolve("ownership.json"), mapper.writeValueAsString(data));

        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.stateDir()).thenReturn(tempDir);
        when(ctx.objectMapper()).thenReturn(mapper);

        var result = new OwnershipTool(ctx).spec().callHandler().apply(null,
                new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("graphus_ownership",
                        Map.of()));

        assertFalse(result.isError());
        String json = ((TextContent) result.content().get(0)).text();
        assertTrue(json.contains("alice@example.com"));
    }

    @Test
    void callHandler_returnsError_whenOwnershipFileMissing() {
        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.stateDir()).thenReturn(tempDir);
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());

        var result = new OwnershipTool(ctx).spec().callHandler().apply(null,
                new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("graphus_ownership",
                        Map.of()));

        assertTrue(result.isError());
        String json = ((TextContent) result.content().get(0)).text();
        assertTrue(json.contains("git-analyze"));
    }
}
```

- [ ] **Step 2: Run the test — verify it fails**

```bash
./gradlew :graphus-cli:test --tests "io.graphus.cli.mcp.tools.OwnershipToolTest" 2>&1 | tail -10
```

Expected: compilation error.

- [ ] **Step 3: Create OwnershipTool**

```java
package io.graphus.cli.mcp.tools;

import io.graphus.cli.mcp.GraphusMcpContext;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class OwnershipTool {

    private final GraphusMcpContext ctx;

    public OwnershipTool(GraphusMcpContext ctx) {
        this.ctx = ctx;
    }

    public SyncToolSpecification spec() {
        return SyncToolSpecification.builder()
                .tool(Tool.builder()
                        .name("graphus_ownership")
                        .description("Return file ownership (primary author per file) from git-analyze output. Requires 'graphus git-analyze' to have been run first.")
                        .inputSchema(McpSchema.parseInputSchema("""
                                {
                                  "type": "object",
                                  "properties": {
                                    "path": { "type": "string", "description": "Optional file path substring to filter results" }
                                  }
                                }
                                """))
                        .build())
                .callHandler((exchange, request) -> {
                    try {
                        Path ownershipFile = ctx.stateDir().resolve("ownership.json");
                        if (!Files.exists(ownershipFile)) {
                            return CallToolResult.builder()
                                    .content(List.of(new TextContent(
                                            "{\"error\":\"ownership.json not found. Run 'graphus git-analyze' first.\"}")))
                                    .isError(true).build();
                        }

                        @SuppressWarnings("unchecked")
                        Map<String, String> ownership = ctx.objectMapper()
                                .readValue(ownershipFile.toFile(), Map.class);

                        String pathFilter = (String) request.arguments().get("path");
                        if (pathFilter != null && !pathFilter.isBlank()) {
                            String filter = pathFilter.strip();
                            ownership = ownership.entrySet().stream()
                                    .filter(e -> e.getKey().contains(filter))
                                    .collect(java.util.stream.Collectors.toMap(
                                            Map.Entry::getKey, Map.Entry::getValue));
                        }

                        String json = ctx.objectMapper().writeValueAsString(ownership);
                        return CallToolResult.builder().content(List.of(new TextContent(json))).build();
                    } catch (Exception e) {
                        return CallToolResult.builder()
                                .content(List.of(new TextContent("{\"error\":\"" + e.getMessage() + "\"}")))
                                .isError(true).build();
                    }
                })
                .build();
    }
}
```

- [ ] **Step 4: Run the tests — verify they pass**

```bash
./gradlew :graphus-cli:test --tests "io.graphus.cli.mcp.tools.OwnershipToolTest"
```

Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add graphus-cli/src/main/java/io/graphus/cli/mcp/tools/OwnershipTool.java \
        graphus-cli/src/test/java/io/graphus/cli/mcp/tools/OwnershipToolTest.java
git commit -m "feat(mcp): add graphus_ownership MCP tool"
```

---

## Task 9: GraphusMcpServer — Wire All Tools

**Files:**
- Create: `graphus-cli/src/main/java/io/graphus/cli/mcp/GraphusMcpServer.java`

Assembles all tools into an `McpSyncServer` over STDIO. After `.build()` the server blocks, reading from stdin.

- [ ] **Step 1: Create GraphusMcpServer**

```java
package io.graphus.cli.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.graphus.cli.mcp.tools.BlastRadiusTool;
import io.graphus.cli.mcp.tools.CalleesTool;
import io.graphus.cli.mcp.tools.CallersTool;
import io.graphus.cli.mcp.tools.HotspotsTool;
import io.graphus.cli.mcp.tools.ModuleDepsTool;
import io.graphus.cli.mcp.tools.OwnershipTool;
import io.graphus.cli.mcp.tools.SearchTool;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

public final class GraphusMcpServer {

    private final GraphusMcpContext ctx;

    public GraphusMcpServer(GraphusMcpContext ctx) {
        this.ctx = ctx;
    }

    public McpSyncServer build(String version) {
        return McpServer.sync(new StdioServerTransportProvider(new ObjectMapper()))
                .serverInfo("graphus", version)
                .capabilities(ServerCapabilities.builder().tools(true).build())
                .tools(
                        new SearchTool(ctx).spec(),
                        new BlastRadiusTool(ctx).spec(),
                        new CallersTool(ctx).spec(),
                        new CalleesTool(ctx).spec(),
                        new ModuleDepsTool(ctx).spec(),
                        new HotspotsTool(ctx).spec(),
                        new OwnershipTool(ctx).spec())
                .build();
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew :graphus-cli:compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add graphus-cli/src/main/java/io/graphus/cli/mcp/GraphusMcpServer.java
git commit -m "feat(mcp): wire GraphusMcpServer with all tools"
```

---

## Task 10: ServeCommand

**Files:**
- Create: `graphus-cli/src/main/java/io/graphus/cli/ServeCommand.java`

The `serve` command accepts the same repo/source/db/embedding options as `index` and `blast-radius`. It loads `GraphusMcpContext`, builds `GraphusMcpServer`, and blocks until the process is killed. All progress output goes to stderr so stdout is reserved for the MCP protocol.

- [ ] **Step 1: Create ServeCommand**

```java
package io.graphus.cli;

import io.graphus.cli.mcp.GraphusMcpContext;
import io.graphus.cli.mcp.GraphusMcpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "serve",
        description = "Start a Graphus MCP server over STDIO for use with AI coding agents (Claude Code, Cursor, Copilot)")
public final class ServeCommand implements Callable<Integer> {

    @Option(names = "--repo", defaultValue = ".", description = "Repository root path")
    private Path repositoryRoot;

    @Option(names = "--source",
            description = "Java or Kotlin source root; can be passed multiple times")
    private List<Path> sourceRoots = new ArrayList<>();

    @Option(names = "--state-dir", description = "Graphus state directory (default: .graphus)")
    private Path stateDir;

    @Option(names = "--db", description = "Vector store backend: chroma|sqlite")
    private String db;

    @Option(names = "--db-url", description = "Chroma base URL")
    private String dbUrl;

    @Option(names = "--db-timeout", description = "Chroma HTTP timeout in seconds")
    private Integer dbTimeoutSeconds;

    @Option(names = "--db-file", description = "SQLite database file path")
    private String dbFile;

    @Option(names = "--embedding", description = "Embedding backend: local|openai")
    private String embeddingBackend;

    @Override
    public Integer call() throws Exception {
        Path repoRoot = repositoryRoot.toAbsolutePath().normalize();
        System.err.println("[graphus-mcp] Loading context from: " + repoRoot);

        GraphusMcpContext ctx = GraphusMcpContext.load(
                repoRoot, sourceRoots, stateDir, db, dbUrl, dbTimeoutSeconds, dbFile, embeddingBackend);

        String version = VersionProvider.readVersion();
        System.err.println("[graphus-mcp] Starting MCP server v" + version + " on STDIO...");

        McpSyncServer server = new GraphusMcpServer(ctx).build(version);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("[graphus-mcp] Shutting down.");
            server.close();
        }));

        return 0;
    }
}
```

- [ ] **Step 2: Check VersionProvider.readVersion() exists**

```bash
grep -n "readVersion\|static.*version\|getVersion" \
  graphus-cli/src/main/java/io/graphus/cli/VersionProvider.java
```

If `readVersion()` is not a static method, use the available alternative. For example, if it only implements `CommandLine.IVersionProvider`, replace `VersionProvider.readVersion()` with:

```java
String version = new VersionProvider().getVersion()[0];
```

Adjust `ServeCommand` accordingly.

- [ ] **Step 3: Verify compilation**

```bash
./gradlew :graphus-cli:compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add graphus-cli/src/main/java/io/graphus/cli/ServeCommand.java
git commit -m "feat(mcp): add graphus serve command"
```

---

## Task 11: ClaudeCodeMcpSettingsInstaller

**Files:**
- Create: `graphus-cli/src/main/java/io/graphus/cli/install/claudecode/ClaudeCodeMcpSettingsInstaller.java`

Writes an MCP server entry into `.claude/settings.local.json` so Claude Code discovers Graphus automatically after `graphus install --tool claude-code`.

- [ ] **Step 1: Create ClaudeCodeMcpSettingsInstaller**

```java
package io.graphus.cli.install.claudecode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ClaudeCodeMcpSettingsInstaller {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public void install(Path projectDir, Path graphusJar) throws IOException {
        Path claudeDir = projectDir.resolve(".claude");
        Files.createDirectories(claudeDir);
        Path settingsFile = claudeDir.resolve("settings.local.json");

        ObjectNode root = settingsFile.toFile().exists()
                ? (ObjectNode) MAPPER.readTree(settingsFile.toFile())
                : MAPPER.createObjectNode();

        ObjectNode mcpServers = root.has("mcpServers")
                ? (ObjectNode) root.get("mcpServers")
                : root.putObject("mcpServers");

        ObjectNode graphusServer = mcpServers.putObject("graphus");
        graphusServer.put("command", "java");
        graphusServer.putArray("args")
                .add("-jar")
                .add(graphusJar.toAbsolutePath().normalize().toString())
                .add("serve")
                .add("--repo")
                .add(projectDir.toAbsolutePath().normalize().toString())
                .add("--db")
                .add("sqlite");

        MAPPER.writeValue(settingsFile.toFile(), root);
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew :graphus-cli:compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add graphus-cli/src/main/java/io/graphus/cli/install/claudecode/ClaudeCodeMcpSettingsInstaller.java
git commit -m "feat(mcp): add ClaudeCodeMcpSettingsInstaller"
```

---

## Task 12: Wire ServeCommand + Update ClaudeCodeAdapter

**Files:**
- Modify: `graphus-cli/src/main/java/io/graphus/cli/GraphusCommand.java`
- Modify: `graphus-cli/src/main/java/io/graphus/cli/install/claudecode/ClaudeCodeAdapter.java`

- [ ] **Step 1: Register ServeCommand as a subcommand**

In `GraphusCommand.java`, add `ServeCommand.class` to the `subcommands` list:

```java
@Command(
        name = "graphus",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        subcommands = {
                GraphusCommand.IndexCommand.class,
                GraphusCommand.SyncCommand.class,
                GraphusCommand.QueryCommand.class,
                GraphusCommand.BlastRadiusCommand.class,
                InstallCommand.class,
                GitAnalyzeCommand.class,
                HotspotsCommand.class,
                SnapshotCommand.class,
                DriftCommand.class,
                ServeCommand.class
        },
        description = "Java/Spring code graph + RAG CLI"
)
```

Also add the import at the top:
```java
import io.graphus.cli.ServeCommand;
```

- [ ] **Step 2: Update ClaudeCodeAdapter to call the MCP installer**

In `ClaudeCodeAdapter.java`, update the `install()` method to call `ClaudeCodeMcpSettingsInstaller` and update `graphusSection()`:

Replace the `install()` method:

```java
@Override
public void install(Path projectDir) throws IOException {
    Path commandsDir = projectDir.resolve(".claude").resolve("commands");
    Files.createDirectories(commandsDir);

    write(commandsDir.resolve("graphus-index.md"), indexCommand());
    write(commandsDir.resolve("graphus-sync.md"), syncCommand());
    write(commandsDir.resolve("graphus-query.md"), queryCommand());
    write(commandsDir.resolve("graphus-blast-radius.md"), blastRadiusCommand());

    Path claudeMd = projectDir.resolve("CLAUDE.md");
    upsertGraphusSection(claudeMd);

    // Resolve graphus.jar relative to this running JVM
    Path graphusJar = resolveGraphusJar();
    if (graphusJar != null) {
        new ClaudeCodeMcpSettingsInstaller().install(projectDir, graphusJar);
    } else {
        System.err.println("[graphus] Warning: could not locate graphus.jar; skipping MCP server registration.");
    }
}
```

Add the helper method inside `ClaudeCodeAdapter`:

```java
private static Path resolveGraphusJar() {
    try {
        java.net.URI location = ClaudeCodeAdapter.class
                .getProtectionDomain().getCodeSource().getLocation().toURI();
        Path jar = java.nio.file.Paths.get(location);
        if (jar.toString().endsWith(".jar")) {
            return jar;
        }
    } catch (Exception ignored) {}
    return null;
}
```

Replace `graphusSection()` to mention MCP:

```java
private static String graphusSection() {
    return """
            <!-- graphus:start -->
            ## Graphus

            Graphus MCP server is registered in `.claude/settings.local.json` — tools are available directly to Claude Code agents.

            Graphus slash commands are also available in `.claude/commands`:

            - `/project:graphus-index`
            - `/project:graphus-sync`
            - `/project:graphus-query`
            - `/project:graphus-blast-radius`

            Use these commands to index Java/Spring code, sync incremental changes, query indexed symbols, and estimate blast radius from callers.
            <!-- graphus:end -->
            """;
}
```

- [ ] **Step 3: Verify compilation and full test suite**

```bash
./gradlew :graphus-cli:build
```

Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 4: Commit**

```bash
git add graphus-cli/src/main/java/io/graphus/cli/GraphusCommand.java \
        graphus-cli/src/main/java/io/graphus/cli/install/claudecode/ClaudeCodeAdapter.java
git commit -m "feat(mcp): register serve subcommand and wire MCP install into claude-code adapter"
```

---

## Task 13: Distribution Convention Updates

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`
- Modify: `graphus-cli/src/main/java/io/graphus/cli/install/cursor/CursorAdapter.java`

Per the **Distribution Convention** in `CLAUDE.md`: any change to how Graphus is built, packaged, or distributed must update all three in the same commit.

- [ ] **Step 1: Update README.md CLI Commands table**

Find the CLI Commands table in `README.md` and add the `serve` row. The table currently has `index`, `sync`, `query`, `blast-radius`, `install`. Add:

```markdown
| `serve`        | Start an MCP server over STDIO for AI coding agents (Claude Code, Cursor, Copilot) |
```

Also add a **MCP Server** section near the installation docs:

```markdown
## MCP Server (Claude Code, Cursor, Copilot)

After indexing, run `graphus install --tool claude-code` to register the MCP server automatically. For manual setup, add to your Claude Code settings:

```json
{
  "mcpServers": {
    "graphus": {
      "command": "java",
      "args": ["-jar", "/path/to/graphus.jar", "serve", "--repo", ".", "--db", "sqlite"]
    }
  }
}
```

Available MCP tools: `graphus_search`, `graphus_blast_radius`, `graphus_callers`, `graphus_callees`, `graphus_module_deps`, `graphus_hotspots`, `graphus_ownership`.
```

- [ ] **Step 2: Update CLAUDE.md CLI Commands table**

Find the CLI Commands table in `CLAUDE.md` and add:

```markdown
| `serve`        | Start an MCP server over STDIO for AI coding agents                                |
```

- [ ] **Step 3: Update CursorAdapter to mention the serve command**

In `CursorAdapter.java`, find the `ruleContent()` method and add a note about the MCP server. Look at the current content of the method first:

```bash
cat graphus-cli/src/main/java/io/graphus/cli/install/cursor/CursorAdapter.java
```

Add the following to the rule content string (wherever the command examples are listed):

```
## MCP Server

Run `graphus serve --repo . --db sqlite` to start a Graphus MCP server for use with Cursor's agent mode.
```

- [ ] **Step 4: Verify full build**

```bash
./gradlew build
```

Expected: `BUILD SUCCESSFUL`, all tests pass, shadow JAR produced at `graphus-cli/build/libs/graphus.jar`.

- [ ] **Step 5: Smoke-test the serve command locally (optional but recommended)**

```bash
echo '{"jsonrpc":"2.0","method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}},"id":1}' \
  | java -jar graphus-cli/build/libs/graphus.jar serve --repo . --db sqlite 2>/dev/null \
  | head -1
```

Expected: a JSON response containing `"result"` and the server info.

- [ ] **Step 6: Commit (all three distribution convention files together)**

```bash
git add README.md CLAUDE.md \
        graphus-cli/src/main/java/io/graphus/cli/install/cursor/CursorAdapter.java
git commit -m "docs: add serve command to distribution convention files (README, CLAUDE.md, CursorAdapter)"
```

---

## Self-Review

### Spec Coverage

| Requirement | Task |
|---|---|
| MCP server over STDIO | Task 10 (ServeCommand) + Task 9 (GraphusMcpServer) |
| `graphus_search` tool | Task 3 |
| `graphus_blast_radius` tool | Task 4 |
| `graphus_callers` tool | Task 5 |
| `graphus_callees` tool | Task 5 |
| `graphus_module_deps` tool | Task 6 |
| `graphus_hotspots` tool | Task 7 |
| `graphus_ownership` tool | Task 8 |
| `graphus install` writes MCP config | Task 11 + 12 |
| Distribution convention (README, CLAUDE.md, CursorAdapter) | Task 13 |

### Type Consistency Check

| Type | Defined | Used Consistently |
|---|---|---|
| `GraphusMcpContext` | Task 2 | Tasks 3–9 all `new XTool(ctx)` |
| `.callGraph()` | Task 2 | Tasks 4, 5, 6, 7 |
| `.graphIndexer()` | Task 2 | Task 3 |
| `.stateDir()` | Task 2 | Task 8 |
| `.repoRoot()` | Task 2 | Task 7 |
| `.objectMapper()` | Task 2 | Tasks 3–8 |
| `SyncToolSpecification` | Task 3 | Tasks 3–8 all `.spec()` returns this |
| `GraphusMcpServer.build(version)` | Task 9 | Task 10 calls `new GraphusMcpServer(ctx).build(version)` |

### Placeholder Scan

No TBDs, todos, or "add appropriate handling" phrases present. All code blocks are complete. All test commands include expected output. Tool names are consistent (`graphus_search`, `graphus_blast_radius`, `graphus_callers`, `graphus_callees`, `graphus_module_deps`, `graphus_hotspots`, `graphus_ownership`) across tests, tool classes, and docs.
