package io.graphus.cli.mcp.tools;

import io.graphus.cli.git.ChurnAnalyzer;
import io.graphus.cli.git.GitLogParser;
import io.graphus.cli.mcp.GraphusMcpContext;
import io.graphus.model.CallEdge;
import io.graphus.model.CallGraph;
import io.graphus.model.SymbolNode;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class HotspotsTool {

    private static final String INPUT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{\"since_days\":{\"type\":\"integer\",\"description\":\"Days of git history (default 365, 0=all)\"},\"top\":{\"type\":\"integer\",\"description\":\"Max entries (default 20)\"}}}";

    private final GraphusMcpContext ctx;

    public HotspotsTool(GraphusMcpContext ctx) {
        this.ctx = ctx;
    }

    public SyncToolSpecification spec() {
        Tool tool = Tool.builder()
                .name("graphus_hotspots")
                .description("Rank files by churn × coupling (git commit frequency × distinct callers). High scores = riskiest files to touch.")
                .inputSchema(ctx.jsonMapper(), INPUT_SCHEMA)
                .build();

        return new SyncToolSpecification(tool, (exchange, request) -> {
            try {
                Map<String, Object> args = request.arguments();
                int sinceDays = args.containsKey("since_days")
                        ? ((Number) args.get("since_days")).intValue()
                        : 365;
                int top = args.containsKey("top")
                        ? ((Number) args.get("top")).intValue()
                        : 20;

                Map<String, Integer> churn = computeChurn(sinceDays);
                Map<String, Integer> coupling = computeCoupling(ctx.callGraph());

                Set<String> allFiles = new HashSet<>();
                allFiles.addAll(churn.keySet());
                allFiles.addAll(coupling.keySet());

                List<Map<String, Object>> entries = new ArrayList<>();
                for (String file : allFiles) {
                    int c = churn.getOrDefault(file, 0);
                    int k = coupling.getOrDefault(file, 0);
                    int score = c * k;
                    if (score == 0) {
                        continue;
                    }
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("file", file);
                    entry.put("churn", c);
                    entry.put("coupling", k);
                    entry.put("score", score);
                    entries.add(entry);
                }

                entries.sort(Comparator.comparingInt((Map<String, Object> e) ->
                        (Integer) e.get("score")).reversed());

                List<Map<String, Object>> result = entries.size() <= top
                        ? entries
                        : entries.subList(0, top);

                String json = ctx.objectMapper().writeValueAsString(result);
                return CallToolResult.builder()
                        .addTextContent(json)
                        .build();
            } catch (Exception e) {
                return CallToolResult.builder()
                        .addTextContent("[]")
                        .build();
            }
        });
    }

    private Map<String, Integer> computeChurn(int sinceDays) throws Exception {
        List<GitLogParser.CommitFiles> history =
                GitLogParser.parseCommitFiles(ctx.repoRoot(), sinceDays);
        return ChurnAnalyzer.analyze(history);
    }

    private static Map<String, Integer> computeCoupling(CallGraph callGraph) {
        Map<String, Set<String>> callersByFile = new HashMap<>();

        for (CallEdge edge : callGraph.getEdges()) {
            SymbolNode toNode = callGraph.getNode(edge.toId());
            SymbolNode fromNode = callGraph.getNode(edge.fromId());
            if (toNode == null || fromNode == null) {
                continue;
            }
            String targetFile = toNode.getFilePath();
            String callerFile = fromNode.getFilePath();
            if (isBlank(targetFile) || isBlank(callerFile) || targetFile.equals(callerFile)) {
                continue;
            }
            callersByFile.computeIfAbsent(targetFile, k -> new HashSet<>()).add(callerFile);
        }

        Map<String, Integer> coupling = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : callersByFile.entrySet()) {
            coupling.put(entry.getKey(), entry.getValue().size());
        }
        return coupling;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
