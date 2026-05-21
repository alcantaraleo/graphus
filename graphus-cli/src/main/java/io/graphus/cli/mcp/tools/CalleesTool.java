package io.graphus.cli.mcp.tools;

import io.graphus.cli.mcp.GraphusMcpContext;
import io.graphus.model.CallGraph;
import io.graphus.model.SymbolNode;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.Map;
import java.util.Set;

public final class CalleesTool {

    private static final String INPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"symbol\":{\"type\":\"string\",\"description\":\"Fully qualified symbol ID or substring\"}},\"required\":[\"symbol\"]}";

    private final GraphusMcpContext ctx;

    public CalleesTool(GraphusMcpContext ctx) {
        this.ctx = ctx;
    }

    public SyncToolSpecification spec() {
        Tool tool = new Tool(
                "graphus_callees",
                "Find the direct (depth-1) callees of a symbol using the call graph.",
                INPUT_SCHEMA);

        return new SyncToolSpecification(tool, (exchange, arguments) -> {
            try {
                String symbolInput = (String) arguments.get("symbol");
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

                Set<String> callees = callGraph.outgoingNeighbors(resolved);
                String json = ctx.objectMapper().writeValueAsString(
                        Map.of("symbol", resolved, "callees", callees));
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
