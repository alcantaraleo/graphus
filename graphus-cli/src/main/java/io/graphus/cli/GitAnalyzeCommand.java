package io.graphus.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.graphus.cli.git.ChurnAnalyzer;
import io.graphus.cli.git.CoChangeAnalyzer;
import io.graphus.cli.git.GitLogParser;
import io.graphus.cli.git.OwnershipAnalyzer;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Mines git history to compute per-file co-change frequencies and primary ownership.
 * Outputs {@code .graphus/co-changes.json} and {@code .graphus/ownership.json}.
 */
@Command(name = "git-analyze",
        description = "Mine git history for co-change frequencies and file ownership")
public final class GitAnalyzeCommand implements Callable<Integer> {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Option(names = "--repo", defaultValue = ".", description = "Repository root path")
    private Path repositoryRoot;

    @Option(names = "--state-dir",
            description = "Directory to write co-changes.json and ownership.json (default: .graphus)")
    private Path stateDir;

    @Option(names = "--since", defaultValue = "365",
            description = "Analyse commits from the last N days (0 = all history)")
    private int sinceDays;

    @Option(names = "--threshold", defaultValue = "3",
            description = "Minimum co-change count for a file pair to be included")
    private int threshold;

    @Override
    public Integer call() throws Exception {
        long start = System.nanoTime();
        Path repoRoot = repositoryRoot.toAbsolutePath().normalize();
        Path resolvedStateDir = stateDir != null
                ? stateDir.toAbsolutePath().normalize()
                : repoRoot.resolve(".graphus").normalize();
        Files.createDirectories(resolvedStateDir);

        System.out.println(phase("Analysing git history (last " + sinceDays + " days)..."));
        List<GitLogParser.CommitFiles> commitHistory =
                GitLogParser.parseCommitFiles(repoRoot, sinceDays);
        System.out.println(summary("Commits analysed: ", String.valueOf(commitHistory.size())));

        // ---- co-change ----
        System.out.println(phase("Computing co-change frequencies (threshold=" + threshold + ")..."));
        Map<String, List<CoChangeAnalyzer.CoChangeEntry>> coChanges =
                CoChangeAnalyzer.analyze(commitHistory, threshold);

        Path coChangesFile = resolvedStateDir.resolve("co-changes.json");
        writeCoChanges(coChangesFile, coChanges, sinceDays, threshold);
        System.out.println(summary("Co-change pairs : ", String.valueOf(coChanges.size())));
        System.out.println(summary("Written         : ", coChangesFile.toString()));

        // ---- ownership ----
        System.out.println(phase("Computing file ownership (last 90 days)..."));
        List<GitLogParser.CommitFiles> authorHistory =
                GitLogParser.parseAuthorFiles(repoRoot, Math.min(sinceDays, 90));
        Map<String, String> ownership = OwnershipAnalyzer.analyze(authorHistory);

        Path ownershipFile = resolvedStateDir.resolve("ownership.json");
        writeOwnership(ownershipFile, ownership, Math.min(sinceDays, 90));
        System.out.println(summary("Files with owner: ", String.valueOf(ownership.size())));
        System.out.println(summary("Written         : ", ownershipFile.toString()));

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.println(summary("Total time      : ", elapsedMs + "ms"));
        return 0;
    }

    private static void writeCoChanges(Path path,
            Map<String, List<CoChangeAnalyzer.CoChangeEntry>> coChanges,
            int sinceDays, int threshold) throws IOException {
        List<Map<String, Object>> entries = new ArrayList<>();
        coChanges.entrySet().stream()
                .sorted(Comparator.comparingInt(
                        (Map.Entry<String, List<CoChangeAnalyzer.CoChangeEntry>> e) ->
                                e.getValue().stream().mapToInt(CoChangeAnalyzer.CoChangeEntry::frequency).sum())
                        .reversed())
                .forEach(entry -> {
                    List<Map<String, Object>> partners = entry.getValue().stream()
                            .map(p -> Map.<String, Object>of("file", p.file(), "frequency", p.frequency()))
                            .toList();
                    entries.add(Map.of("file", entry.getKey(), "coChangedWith", partners));
                });

        Map<String, Object> payload = Map.of(
                "schemaVersion", 1,
                "generatedAt", Instant.now().toString(),
                "sinceDays", sinceDays,
                "threshold", threshold,
                "entries", entries);

        try (OutputStream out = Files.newOutputStream(path)) {
            MAPPER.writeValue(out, payload);
        }
    }

    private static void writeOwnership(Path path, Map<String, String> ownership,
            int sinceDays) throws IOException {
        List<Map<String, String>> entries = ownership.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> Map.of("file", e.getKey(), "owner", e.getValue()))
                .toList();

        Map<String, Object> payload = Map.of(
                "schemaVersion", 1,
                "generatedAt", Instant.now().toString(),
                "sinceDays", sinceDays,
                "entries", entries);

        try (OutputStream out = Files.newOutputStream(path)) {
            MAPPER.writeValue(out, payload);
        }
    }

    private static String phase(String value) {
        return Ansi.style(value, Ansi.BOLD);
    }

    private static String summary(String label, String value) {
        return label + Ansi.style(value, Ansi.BOLD, Ansi.GREEN);
    }
}
