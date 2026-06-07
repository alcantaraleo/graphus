package io.graphus.cli.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.graphus.cli.mcp.GraphusMcpContext;
import io.graphus.model.CallGraph;
import io.graphus.model.ModuleNode;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModuleDepsToolTest {

    @Test
    void spec_hasCorrectToolName() {
        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());
        when(ctx.jsonMapper()).thenReturn(McpJsonDefaults.getMapper());
        ModuleDepsTool tool = new ModuleDepsTool(ctx);
        SyncToolSpecification spec = tool.spec();
        assertEquals("graphus_module_deps", spec.tool().name());
    }

    @Test
    void callHandler_returnsAllModules_whenNoFilter() throws Exception {
        CallGraph callGraph = new CallGraph();
        ModuleNode moduleA = new ModuleNode("graphus-core");
        ModuleNode moduleB = new ModuleNode("graphus-cli");
        callGraph.addNode(moduleA);
        callGraph.addNode(moduleB);
        callGraph.addEdge(ModuleNode.idFor("graphus-cli"), ModuleNode.idFor("graphus-core"));

        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());
        when(ctx.jsonMapper()).thenReturn(McpJsonDefaults.getMapper());
        when(ctx.callGraph()).thenReturn(callGraph);

        ModuleDepsTool tool = new ModuleDepsTool(ctx);
        CallToolResult result = tool.spec().callHandler().apply(null, new CallToolRequest("unknown", Map.of()));

        assertFalse(Boolean.TRUE.equals(result.isError()));
        String json = ((TextContent) result.content().get(0)).text();
        assertTrue(json.contains("graphus-core"), "Expected graphus-core in output");
        assertTrue(json.contains("graphus-cli"), "Expected graphus-cli in output");
    }

    @Test
    void callHandler_filtersByModule_whenFilterProvided() throws Exception {
        CallGraph callGraph = new CallGraph();
        ModuleNode moduleA = new ModuleNode("graphus-core");
        ModuleNode moduleB = new ModuleNode("graphus-cli");
        callGraph.addNode(moduleA);
        callGraph.addNode(moduleB);
        callGraph.addEdge(ModuleNode.idFor("graphus-cli"), ModuleNode.idFor("graphus-core"));

        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());
        when(ctx.jsonMapper()).thenReturn(McpJsonDefaults.getMapper());
        when(ctx.callGraph()).thenReturn(callGraph);

        ModuleDepsTool tool = new ModuleDepsTool(ctx);
        CallToolResult result = tool.spec().callHandler().apply(null, new CallToolRequest("unknown", Map.of("module", "graphus-cli")));

        assertFalse(Boolean.TRUE.equals(result.isError()));
        String json = ((TextContent) result.content().get(0)).text();
        assertTrue(json.contains("graphus-cli"), "Expected graphus-cli in filtered output");
        assertFalse(json.contains("\"module:graphus-core\"") && !json.contains("depends_on"),
                "graphus-core should appear only as a dependency, not as a top-level module entry");
    }
}
