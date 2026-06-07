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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CallersToolTest {

    @Test
    void spec_hasCorrectToolName() {
        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());
        when(ctx.jsonMapper()).thenReturn(McpJsonDefaults.getMapper());
        CallersTool tool = new CallersTool(ctx);
        SyncToolSpecification spec = tool.spec();
        assertEquals("graphus_callers", spec.tool().name());
    }

    @Test
    void callHandler_returnsDirectCallers_whenSymbolFound() throws Exception {
        CallGraph callGraph = new CallGraph();
        MethodNode target = new MethodNode(
                "com.example.Service.process()",
                "com.example.Service",
                "process",
                "process()",
                "void",
                List.of(),
                List.of(),
                List.of(),
                "Service.java",
                10,
                null
        );
        MethodNode caller = new MethodNode(
                "com.example.Controller.handle()",
                "com.example.Controller",
                "handle",
                "handle()",
                "void",
                List.of(),
                List.of(),
                List.of(),
                "Controller.java",
                5,
                null
        );
        callGraph.addNode(target);
        callGraph.addNode(caller);
        callGraph.addEdge("com.example.Controller.handle()", "com.example.Service.process()");

        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());
        when(ctx.jsonMapper()).thenReturn(McpJsonDefaults.getMapper());
        when(ctx.callGraph()).thenReturn(callGraph);

        CallersTool tool = new CallersTool(ctx);
        CallToolResult result = tool.spec().callHandler().apply(null, new CallToolRequest("unknown", Map.of("symbol", "com.example.Service.process()")));

        assertFalse(Boolean.TRUE.equals(result.isError()));
        String json = ((TextContent) result.content().get(0)).text();
        assertTrue(json.contains("com.example.Service.process()"));
        assertTrue(json.contains("com.example.Controller.handle()"));
    }

    @Test
    void callHandler_resolvesBySubstring() throws Exception {
        CallGraph callGraph = new CallGraph();
        MethodNode target = new MethodNode(
                "com.example.Service.process()",
                "com.example.Service",
                "process",
                "process()",
                "void",
                List.of(),
                List.of(),
                List.of(),
                "Service.java",
                10,
                null
        );
        MethodNode caller = new MethodNode(
                "com.example.Controller.handle()",
                "com.example.Controller",
                "handle",
                "handle()",
                "void",
                List.of(),
                List.of(),
                List.of(),
                "Controller.java",
                5,
                null
        );
        callGraph.addNode(target);
        callGraph.addNode(caller);
        callGraph.addEdge("com.example.Controller.handle()", "com.example.Service.process()");

        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());
        when(ctx.jsonMapper()).thenReturn(McpJsonDefaults.getMapper());
        when(ctx.callGraph()).thenReturn(callGraph);

        CallersTool tool = new CallersTool(ctx);
        CallToolResult result = tool.spec().callHandler().apply(null, new CallToolRequest("unknown", Map.of("symbol", "Service.process")));

        assertFalse(Boolean.TRUE.equals(result.isError()));
        String json = ((TextContent) result.content().get(0)).text();
        assertTrue(json.contains("com.example.Service.process()"));
        assertTrue(json.contains("com.example.Controller.handle()"));
    }

    @Test
    void callHandler_returnsError_whenSymbolNotFound() {
        CallGraph callGraph = new CallGraph();

        GraphusMcpContext ctx = mock(GraphusMcpContext.class);
        when(ctx.objectMapper()).thenReturn(new ObjectMapper());
        when(ctx.jsonMapper()).thenReturn(McpJsonDefaults.getMapper());
        when(ctx.callGraph()).thenReturn(callGraph);

        CallersTool tool = new CallersTool(ctx);
        CallToolResult result = tool.spec().callHandler().apply(null, new CallToolRequest("unknown", Map.of("symbol", "com.example.Unknown.missing()")));

        assertTrue(Boolean.TRUE.equals(result.isError()));
        String json = ((TextContent) result.content().get(0)).text();
        assertTrue(json.contains("Symbol not found"));
    }
}
