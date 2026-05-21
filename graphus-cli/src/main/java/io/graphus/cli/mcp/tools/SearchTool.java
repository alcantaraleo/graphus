package io.graphus.cli.mcp.tools;

import io.graphus.cli.mcp.GraphusMcpContext;
import io.graphus.indexer.GraphSearchHit;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import java.util.Map;

public final class SearchTool {

    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "query": { "type": "string", "description": "Natural language search query" },
                "top_k": { "type": "integer", "description": "Maximum results to return (default 10)" },
                "module": { "type": "string", "description": "Optional module name filter" }
              },
              "required": ["query"]
            }
            """;

    private final GraphusMcpContext ctx;

    public SearchTool(GraphusMcpContext ctx) {
        this.ctx = ctx;
    }

    public SyncToolSpecification spec() {
        Tool tool = new Tool(
                "graphus_search",
                "Search indexed code symbols by natural language query. Returns matching symbols with score, text, and metadata.",
                INPUT_SCHEMA);

        return new SyncToolSpecification(tool, (exchange, arguments) -> {
            try {
                String query = (String) arguments.get("query");
                int topK = arguments.containsKey("top_k")
                        ? ((Number) arguments.get("top_k")).intValue() : 10;
                String module = (String) arguments.get("module");

                List<GraphSearchHit> hits = ctx.graphIndexer().query(query, module, topK);
                List<Map<String, Object>> payload = hits.stream()
                        .map(h -> Map.<String, Object>of(
                                "score", h.score(),
                                "text", h.text(),
                                "metadata", h.metadata()))
                        .toList();
                String json = ctx.objectMapper().writeValueAsString(payload);
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
}
