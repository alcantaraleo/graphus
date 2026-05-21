package io.graphus.cli.mcp.tools;

import io.graphus.cli.mcp.GraphusMcpContext;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class OwnershipTool {

    private static final String INPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\",\"description\":\"Optional file path substring to filter results\"}}}";

    private final GraphusMcpContext ctx;

    public OwnershipTool(GraphusMcpContext ctx) {
        this.ctx = ctx;
    }

    public SyncToolSpecification spec() {
        Tool tool = new Tool(
                "graphus_ownership",
                "Return file ownership (primary author per file) from git-analyze output. Requires 'graphus git-analyze' to have been run first.",
                INPUT_SCHEMA);

        return new SyncToolSpecification(tool, (exchange, arguments) -> {
            try {
                Path ownershipFile = ctx.stateDir().resolve("ownership.json");
                if (!Files.exists(ownershipFile)) {
                    String error = ctx.objectMapper()
                            .writeValueAsString(Map.of("error",
                                    "ownership.json not found. Run 'graphus git-analyze' first."));
                    return CallToolResult.builder()
                            .addTextContent(error)
                            .isError(Boolean.TRUE)
                            .build();
                }

                @SuppressWarnings("unchecked")
                Map<String, String> ownership = ctx.objectMapper()
                        .readValue(ownershipFile.toFile(), Map.class);

                String pathFilter = (String) arguments.get("path");
                if (pathFilter != null && !pathFilter.isBlank()) {
                    ownership = ownership.entrySet().stream()
                            .filter(e -> e.getKey().contains(pathFilter))
                            .collect(java.util.stream.Collectors.toMap(
                                    Map.Entry::getKey,
                                    Map.Entry::getValue));
                }

                String json = ctx.objectMapper().writeValueAsString(ownership);
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
