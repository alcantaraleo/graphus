package io.graphus.cli;

import io.graphus.cli.git.ChurnAnalyzer;
import io.graphus.cli.git.GitLogParser;
import io.graphus.model.CallEdge;
import io.graphus.model.ClassNode;
import io.graphus.model.SymbolNode;
import io.graphus.parser.ProjectParser;
import io.graphus.parser.ProjectParserResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Ranks files by {@code churn × coupling} — git commit frequency multiplied by the number of
 * distinct callers in the call graph. High scores identify files that change often and are
 * heavily depended upon — the riskiest places to touch.
 */
@Command(name = "hotspots",
        description = "Rank files by churn × coupling to identify high-risk change targets")
public final class HotspotsCommand implements Callable<Integer> {

    @Option(names = "--repo", defaultValue = ".", description = "Repository root path")
    private Path repositoryRoot;

    @Option(names = "--source",
            description = "Java/Kotlin source root; can be passed multiple times")
    private List<Path> sourceRoots = new ArrayList<>();

    @Option(names = "--since", defaultValue = "365",
            description = "Look back N days for git churn (0 = all history)")
    private int sinceDays;

    @Option(names = "--top", defaultValue = "20",
            description = "Number of hotspot entries to display")
    private int top;

    @Override
    public Integer call() throws Exception {
        long start = System.nanoTime();
        Path repoRoot = repositoryRoot.toAbsolutePath().normalize();

        // ---- git churn ----
        System.out.println(phase("Mining git churn (last " + sinceDays + " days)..."));
        List<GitLogParser.CommitFiles> history =
                GitLogParser.parseCommitFiles(repoRoot, sinceDays);
        Map<String, Integer> churn = ChurnAnalyzer.analyze(history);
        System.out.println(summary("Files with churn : ", String.valueOf(churn.size())));

        // ---- call graph coupling ----
        System.out.println(phase("Parsing call graph for coupling..."));
        List<io.graphus.model.WorkspaceDescriptor> workspaces =
                CliWorkspaceLayouts.resolve(repoRoot, sourceRoots);
        if (workspaces.isEmpty()) {
            System.err.println(Ansi.style("No analysable workspace found.", Ansi.BOLD, Ansi.RED));
            return 1;
        }

        ProjectParser projectParser = new ProjectParser();
        ParserProgressReporter reporter = new ParserProgressReporter();
        ProjectParserResult parseResult = projectParser.parse(workspaces.get(0), reporter);
        reporter.complete();

        Map<String, Integer> couplingByFile = computeFileCoupling(parseResult, repoRoot);
        System.out.println(summary("Files with callers: ", String.valueOf(couplingByFile.size())));

        // ---- rank ----
        List<HotspotEntry> hotspots = new ArrayList<>();
        Set<String> allFiles = new HashSet<>(churn.keySet());
        allFiles.addAll(couplingByFile.keySet());

        for (String file : allFiles) {
            int fileChurn = churn.getOrDefault(file, 0);
            int coupling = couplingByFile.getOrDefault(file, 0);
            if (fileChurn > 0 || coupling > 0) {
                hotspots.add(new HotspotEntry(file, fileChurn, coupling, (long) fileChurn * coupling));
            }
        }

        hotspots.sort((a, b) -> Long.compare(b.score(), a.score()));

        // ---- output ----
        int count = Math.min(top, hotspots.size());
        System.out.println(phase("\nTop " + count + " hotspots (churn × coupling):"));
        System.out.println(Ansi.style(
                String.format("%-6s %-6s %-6s %s", "SCORE", "CHURN", "CALLERS", "FILE"),
                Ansi.DIM));

        for (int i = 0; i < count; i++) {
            HotspotEntry e = hotspots.get(i);
            String line = String.format("%-6d %-6d %-6d %s",
                    e.score(), e.churn(), e.coupling(), e.file());
            System.out.println(Ansi.style("[" + (i + 1) + "]", Ansi.CYAN) + " " + line);
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.println(summary("\nTotal time: ", elapsedMs + "ms"));
        return 0;
    }

    /**
     * Counts distinct files that have at least one edge pointing to any node in the target file.
     * Repository-relative paths are used as keys to match git output.
     */
    private static Map<String, Integer> computeFileCoupling(
            ProjectParserResult result, Path repoRoot) {
        Map<String, Set<String>> callerFilesByTargetFile = new HashMap<>();

        for (CallEdge edge : result.callGraph().getEdges()) {
            SymbolNode toNode = result.callGraph().getNode(edge.toId());
            SymbolNode fromNode = result.callGraph().getNode(edge.fromId());
            if (toNode == null || fromNode == null) {
                continue;
            }
            String targetFile = toNode.getFilePath();
            String callerFile = fromNode.getFilePath();
            if (targetFile == null || targetFile.isBlank()
                    || callerFile == null || callerFile.isBlank()
                    || targetFile.equals(callerFile)) {
                continue;
            }
            callerFilesByTargetFile
                    .computeIfAbsent(targetFile, k -> new HashSet<>())
                    .add(callerFile);
        }

        Map<String, Integer> coupling = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : callerFilesByTargetFile.entrySet()) {
            coupling.put(entry.getKey(), entry.getValue().size());
        }
        return coupling;
    }

    private record HotspotEntry(String file, int churn, int coupling, long score) {}

    private static String phase(String value) {
        return Ansi.style(value, Ansi.BOLD);
    }

    private static String summary(String label, String value) {
        return label + Ansi.style(value, Ansi.BOLD, Ansi.GREEN);
    }
}
