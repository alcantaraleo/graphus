package io.graphus.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.graphus.model.CallEdge;
import io.graphus.model.CallGraph;
import io.graphus.model.ModuleNode;
import io.graphus.model.SymbolKind;
import io.graphus.model.SymbolNode;
import io.graphus.parser.ProjectParser;
import io.graphus.parser.ProjectParserResult;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Serializes a point-in-time snapshot of the call-graph statistics to
 * {@code .graphus/snapshots/<timestamp>.json}. Use {@code graphus drift} to compare two snapshots.
 */
@Command(name = "snapshot",
        description = "Capture a point-in-time snapshot of the call graph for drift detection")
public final class SnapshotCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    @Option(names = "--repo", defaultValue = ".", description = "Repository root path")
    private Path repositoryRoot;

    @Option(names = "--source",
            description = "Java/Kotlin source root; can be passed multiple times")
    private List<Path> sourceRoots = new ArrayList<>();

    @Option(names = "--state-dir",
            description = "Directory under which the snapshots/ folder is created (default: .graphus)")
    private Path stateDir;

    @Option(names = "--label",
            description = "Optional human-readable label stored in the snapshot (e.g. 'before-refactor')")
    private String label;

    @Override
    public Integer call() throws Exception {
        long start = System.nanoTime();
        Path repoRoot = repositoryRoot.toAbsolutePath().normalize();
        Path resolvedStateDir = stateDir != null
                ? stateDir.toAbsolutePath().normalize()
                : repoRoot.resolve(".graphus").normalize();
        Path snapshotsDir = resolvedStateDir.resolve("snapshots");
        Files.createDirectories(snapshotsDir);

        System.out.println(phase("Parsing call graph for snapshot..."));
        List<io.graphus.model.WorkspaceDescriptor> workspaces =
                CliWorkspaceLayouts.resolve(repoRoot, sourceRoots);
        if (workspaces.isEmpty()) {
            System.err.println(Ansi.style("No analysable workspace found.", Ansi.BOLD, Ansi.RED));
            return 1;
        }

        ProjectParser projectParser = new ProjectParser();
        ParserProgressReporter reporter = new ParserProgressReporter();
        ProjectParserResult result = projectParser.parse(workspaces.get(0), reporter);
        reporter.complete();
        CallGraph callGraph = result.callGraph();

        System.out.println(phase("Computing statistics..."));
        Map<String, Object> snapshot = buildSnapshot(callGraph, repoRoot, label, result);

        String timestamp = TIMESTAMP_FMT.format(Instant.now());
        String filename = label != null && !label.isBlank()
                ? timestamp + "-" + label.replaceAll("[^a-zA-Z0-9_-]", "-") + ".json"
                : timestamp + ".json";
        Path snapshotFile = snapshotsDir.resolve(filename);

        try (OutputStream out = Files.newOutputStream(snapshotFile)) {
            MAPPER.writeValue(out, snapshot);
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.println(summary("Snapshot written: ", snapshotFile.toString()));
        System.out.println(summary("Total time      : ", elapsedMs + "ms"));
        return 0;
    }

    static Map<String, Object> buildSnapshot(CallGraph callGraph, Path repoRoot,
            String label, ProjectParserResult result) {
        // node counts by kind
        Map<String, Integer> nodesByKind = new HashMap<>();
        for (SymbolNode node : callGraph.getNodes()) {
            nodesByKind.merge(node.getKind().name(), 1, Integer::sum);
        }

        // top coupled nodes (by incoming edge count)
        Map<String, Integer> incomingCount = new HashMap<>();
        for (CallEdge edge : callGraph.getEdges()) {
            incomingCount.merge(edge.toId(), 1, Integer::sum);
        }
        List<Map<String, Object>> topCoupled = incomingCount.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .map(e -> Map.<String, Object>of("id", e.getKey(), "incomingEdges", e.getValue()))
                .toList();

        // file count
        Set<String> files = new HashSet<>();
        for (SymbolNode node : callGraph.getNodes()) {
            if (node.getFilePath() != null && !node.getFilePath().isBlank()) {
                files.add(node.getFilePath());
            }
        }

        // module list
        List<String> modules = callGraph.getNodes().stream()
                .filter(n -> n instanceof ModuleNode)
                .map(n -> ((ModuleNode) n).getModuleName())
                .sorted()
                .toList();

        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("schemaVersion", 1);
        snapshot.put("createdAt", Instant.now().toString());
        snapshot.put("repoRoot", repoRoot.toString());
        if (label != null && !label.isBlank()) {
            snapshot.put("label", label);
        }
        snapshot.put("parsedFiles", result.parsedFiles());
        snapshot.put("unresolvedCalls", result.unresolvedCalls());
        snapshot.put("totalNodes", callGraph.getNodes().size());
        snapshot.put("totalEdges", callGraph.getEdges().size());
        snapshot.put("totalFiles", files.size());
        snapshot.put("nodesByKind", nodesByKind);
        snapshot.put("moduleCount", modules.size());
        snapshot.put("modules", modules);
        snapshot.put("topCoupledNodes", topCoupled);
        return snapshot;
    }

    private static String phase(String value) {
        return Ansi.style(value, Ansi.BOLD);
    }

    private static String summary(String label, String value) {
        return label + Ansi.style(value, Ansi.BOLD, Ansi.GREEN);
    }
}
