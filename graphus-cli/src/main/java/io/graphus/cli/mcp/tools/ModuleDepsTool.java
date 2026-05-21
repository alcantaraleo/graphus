package io.graphus.cli.mcp.tools;

import io.graphus.cli.mcp.GraphusMcpContext;
import io.graphus.model.CallGraph;
import io.graphus.model.ModuleNode;
import io.graphus.model.SymbolNode;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class ModuleDepsTool {

    private static final String INPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"module\":{\"type\":\"string\",\"description\":\"Optional module name to scope results\"}}}";

    private final GraphusMcpContext ctx;

    public ModuleDepsTool(GraphusMcpContext ctx) {
        this.ctx = ctx;
    }

    public SyncToolSpecification spec() {
        Tool tool = new Tool(
                "graphus_module_deps",
                "Return the module dependency graph. Optional 'module' filter scopes output to a single module's dependencies.",
                INPUT_SCHEMA);

        return new SyncToolSpecification(tool, (exchange, arguments) -> {
            try {
                String moduleFilter = (String) arguments.get("module");
                CallGraph callGraph = ctx.callGraph();

                Set<String> allModuleIds = callGraph.getNodes().stream()
                        .filter(n -> n instanceof ModuleNode)
                        .map(SymbolNode::getId)
                        .collect(Collectors.toSet());

                List<Map<String, Object>> results = new ArrayList<>();
                for (SymbolNode node : callGraph.getNodes()) {
                    if (!(node instanceof ModuleNode)) {
                        continue;
                    }
                    if (moduleFilter != null && !node.getId().contains(moduleFilter)) {
                        continue;
                    }
                    Set<String> deps = callGraph.outgoingNeighbors(node.getId()).stream()
                            .filter(allModuleIds::contains)
                            .collect(Collectors.toSet());
                    results.add(Map.of("module", node.getId(), "depends_on", deps));
                }

                String json = ctx.objectMapper().writeValueAsString(results);
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
