package io.graphus.cli.mcp.tools;

import io.graphus.cli.mcp.GraphusMcpContext;
import io.graphus.model.CallGraph;
import io.graphus.model.SymbolNode;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.Map;
import java.util.Set;

public final class CallersTool {

    private static final String INPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"symbol\":{\"type\":\"string\",\"description\":\"Fully qualified symbol ID or substring\"}},\"required\":[\"symbol\"]}";

    private final GraphusMcpContext ctx;

    public CallersTool(GraphusMcpContext ctx) {
        this.ctx = ctx;
    }

    public SyncToolSpecification spec() {
        Tool tool = Tool.builder()
                .name("graphus_callers")
                .description("Find the direct (depth-1) callers of a symbol using the call graph.")
                .inputSchema(ctx.jsonMapper(), INPUT_SCHEMA)
                .build();

        return new SyncToolSpecification(tool, (exchange, request) -> {
            try {
                Map<String, Object> args = request.arguments();
                String symbolInput = (String) args.get("symbol");
                CallGraph callGraph = ctx.callGraph();

                String resolved = resolveSymbol(callGraph, symbolInput);
                if (resolved == null) {
                    String error = ctx.objectMapper()
                            .writeValueAsString(Map.of("error", "Symbol not found: " + symbolInput));
                    return CallToolResult.builder()
                            .addTextContent(error)
                            .isError(Boolean.TRUE)
                            .build();
                }

                Set<String> callers = callGraph.incomingNeighbors(resolved);
                String json = ctx.objectMapper().writeValueAsString(
                        Map.of("symbol", resolved, "callers", callers));
                return CallToolResult.builder()
                        .addTextContent(json)
                        .build();
            } catch (Exception e) {
                return CallToolResult.builder()
                        .addTextContent("{\"error\":\"" + e.getMessage() + "\"}")
                        .isError(Boolean.TRUE)
                        .build();
            }
        });
    }

    private String resolveSymbol(CallGraph callGraph, String input) {
        SymbolNode exact = callGraph.getNode(input);
        if (exact != null) {
            return exact.getId();
        }
        for (SymbolNode node : callGraph.getNodes()) {
            if (node.getId().contains(input)) {
                return node.getId();
            }
        }
        return null;
    }
}
