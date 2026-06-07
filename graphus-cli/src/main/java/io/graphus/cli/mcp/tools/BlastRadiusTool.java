package io.graphus.cli.mcp.tools;

import io.graphus.cli.mcp.GraphusMcpContext;
import io.graphus.model.CallGraph;
import io.graphus.model.SymbolNode;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import java.util.Map;

public final class BlastRadiusTool {

    private static final String INPUT_SCHEMA = """
            {"type":"object","properties":{"symbol":{"type":"string","description":"Fully qualified method ID or substring"},"depth":{"type":"integer","description":"Traversal depth (default 3)"}},"required":["symbol"]}
            """;

    private final GraphusMcpContext ctx;

    public BlastRadiusTool(GraphusMcpContext ctx) {
        this.ctx = ctx;
    }

    public SyncToolSpecification spec() {
        Tool tool = Tool.builder()
                .name("graphus_blast_radius")
                .description("Find all callers of a symbol via BFS traversal of the call graph.")
                .inputSchema(ctx.jsonMapper(), INPUT_SCHEMA)
                .build();

        return new SyncToolSpecification(tool, (exchange, request) -> {
            try {
                Map<String, Object> args = request.arguments();
                String symbolInput = (String) args.get("symbol");
                int depth = args.containsKey("depth")
                        ? ((Number) args.get("depth")).intValue() : 3;

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

                List<String> callers = callGraph.blastRadiusCallers(resolved, depth);
                String json = ctx.objectMapper().writeValueAsString(
                        Map.of("target", resolved, "depth", depth, "callers", callers));
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
