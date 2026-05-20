package io.graphus.cli.git;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs {@code git log} as a subprocess and parses the output into per-commit file lists.
 * Uses {@code --format=format:COMMIT:%H} so commit boundaries are clearly delimited,
 * with {@code --name-only} to list the files changed in each commit.
 */
public final class GitLogParser {

    private static final String COMMIT_PREFIX = "COMMIT:";
    private static final String AUTHOR_PREFIX = "AUTHOR:";

    private GitLogParser() {
    }

    /**
     * Returns one {@link CommitFiles} entry per commit, in reverse-chronological order.
     *
     * @param repoRoot  the git repository root
     * @param sinceDays only include commits from the last N days (0 = all history)
     */
    public static List<CommitFiles> parseCommitFiles(Path repoRoot, int sinceDays) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(
                "git", "log", "--name-only", "--format=format:COMMIT:%H", "--diff-filter=d"));
        if (sinceDays > 0) {
            cmd.add("--since=" + sinceDays + ".days");
        }
        return parseCommitBlocks(repoRoot, cmd, COMMIT_PREFIX);
    }

    /**
     * Returns one {@link CommitFiles} entry per commit where the header field is the author email.
     * Used by {@link OwnershipAnalyzer} to attribute files to their most-active author.
     *
     * @param repoRoot  the git repository root
     * @param sinceDays only include commits from the last N days (0 = all history)
     */
    public static List<CommitFiles> parseAuthorFiles(Path repoRoot, int sinceDays) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(
                "git", "log", "--name-only", "--format=format:AUTHOR:%ae", "--diff-filter=d"));
        if (sinceDays > 0) {
            cmd.add("--since=" + sinceDays + ".days");
        }
        return parseCommitBlocks(repoRoot, cmd, AUTHOR_PREFIX);
    }

    private static List<CommitFiles> parseCommitBlocks(Path repoRoot, List<String> cmd,
            String headerPrefix) throws IOException {
        Process process = new ProcessBuilder(cmd)
                .directory(repoRoot.toFile())
                .redirectErrorStream(false)
                .start();

        List<CommitFiles> result = new ArrayList<>();
        String currentHeader = null;
        List<String> currentFiles = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(headerPrefix)) {
                    if (currentHeader != null && !currentFiles.isEmpty()) {
                        result.add(new CommitFiles(currentHeader, List.copyOf(currentFiles)));
                    }
                    currentHeader = line.substring(headerPrefix.length());
                    currentFiles = new ArrayList<>();
                } else if (!line.isBlank() && currentHeader != null) {
                    currentFiles.add(line.trim());
                }
            }
        }
        if (currentHeader != null && !currentFiles.isEmpty()) {
            result.add(new CommitFiles(currentHeader, List.copyOf(currentFiles)));
        }

        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return List.copyOf(result);
    }

    public record CommitFiles(String header, List<String> files) {}
}
