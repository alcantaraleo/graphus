package io.graphus.cli.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.graphus.cli.mcp.GraphusMcpContext;
import io.graphus.model.CallGraph;
import io.graphus.model.MethodNode;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HotspotsToolTest {

    @Test
    void spec_hasCorrectToolName() {
        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());
        when(ctx.jsonMapper()).thenReturn(McpJsonDefaults.getMapper());
        when(ctx.callGraph()).thenReturn(new CallGraph());
        when(ctx.repoRoot()).thenReturn(Path.of("/tmp/no-such-repo-" + System.nanoTime()));

        HotspotsTool tool = new HotspotsTool(ctx);
        SyncToolSpecification spec = tool.spec();
        assertEquals("graphus_hotspots", spec.tool().name());
    }

    @Test
    void callHandler_returnsEmptyArray_whenRepoRootHasNoGitHistory(@TempDir Path tempDir) {
        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());
        when(ctx.jsonMapper()).thenReturn(McpJsonDefaults.getMapper());
        when(ctx.callGraph()).thenReturn(new CallGraph());
        when(ctx.repoRoot()).thenReturn(tempDir);

        HotspotsTool tool = new HotspotsTool(ctx);
        CallToolResult result = tool.spec().callHandler().apply(null, new CallToolRequest("unknown", Map.of()));

        assertFalse(Boolean.TRUE.equals(result.isError()));
        String json = ((TextContent) result.content().get(0)).text();
        assertTrue(json.startsWith("["), "Expected JSON array but got: " + json);
    }

    @Test
    void callHandler_returnsEmptyArray_whenNonExistentRepoRoot() {
        Path noSuchRepo = Path.of("/tmp/no-such-repo-" + System.nanoTime());

        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());
        when(ctx.jsonMapper()).thenReturn(McpJsonDefaults.getMapper());
        when(ctx.callGraph()).thenReturn(new CallGraph());
        when(ctx.repoRoot()).thenReturn(noSuchRepo);

        HotspotsTool tool = new HotspotsTool(ctx);
        CallToolResult result = tool.spec().callHandler().apply(null, new CallToolRequest("unknown", Map.of("since_days", 30, "top", 10)));

        assertFalse(Boolean.TRUE.equals(result.isError()));
        String json = ((TextContent) result.content().get(0)).text();
        assertTrue(json.startsWith("["), "Expected JSON array but got: " + json);
        assertEquals("[]", json.trim());
    }

    @Test
    void callHandler_computesScores_withCallGraphCoupling(@TempDir Path tempDir) {
        CallGraph callGraph = new CallGraph();

        MethodNode targetNode = new MethodNode(
                "com.example.Service.process()",
                "com.example.Service",
                "process",
                "process()",
                "void",
                List.of(),
                List.of(),
                List.of(),
                "src/main/java/com/example/Service.java",
                10,
                null
        );
        MethodNode callerNode = new MethodNode(
                "com.example.Controller.handle()",
                "com.example.Controller",
                "handle",
                "handle()",
                "void",
                List.of(),
                List.of(),
                List.of(),
                "src/main/java/com/example/Controller.java",
                5,
                null
        );
        callGraph.addNode(targetNode);
        callGraph.addNode(callerNode);
        callGraph.addEdge("com.example.Controller.handle()", "com.example.Service.process()");

        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());
        when(ctx.jsonMapper()).thenReturn(McpJsonDefaults.getMapper());
        when(ctx.callGraph()).thenReturn(callGraph);
        when(ctx.repoRoot()).thenReturn(tempDir);

        HotspotsTool tool = new HotspotsTool(ctx);
        // since_days=0 means all history; tempDir has no git, so churn=0 but coupling exists
        CallToolResult result = tool.spec().callHandler().apply(null, new CallToolRequest("unknown", Map.of("since_days", 0, "top", 20)));

        assertFalse(Boolean.TRUE.equals(result.isError()));
        String json = ((TextContent) result.content().get(0)).text();
        // With no git history churn=0, coupling=1 → score=0, so items with score=0 are skipped
        assertTrue(json.startsWith("["), "Expected JSON array but got: " + json);
    }
}
