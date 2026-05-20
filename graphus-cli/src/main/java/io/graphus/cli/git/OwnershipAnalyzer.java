package io.graphus.cli.git;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Determines the primary owner of each file based on who has the most commits touching it
 * within the analysed window. Uses author email as the owner identifier.
 *
 * <p>When a {@code CODEOWNERS} file is present it should take precedence; this class covers
 * the common case where no CODEOWNERS file exists.
 */
public final class OwnershipAnalyzer {

    private OwnershipAnalyzer() {
    }

    /**
     * @param authorHistory commits parsed with author email as the header field,
     *                      as produced by {@link GitLogParser#parseAuthorFiles}
     * @return map of file path → primary author email (author with the most commits in the window)
     */
    public static Map<String, String> analyze(List<GitLogParser.CommitFiles> authorHistory) {
        Map<String, Map<String, Integer>> countsByFile = new HashMap<>();
        for (GitLogParser.CommitFiles commit : authorHistory) {
            String author = commit.header();
            if (author == null || author.isBlank()) {
                continue;
            }
            for (String file : commit.files()) {
                countsByFile.computeIfAbsent(file, k -> new HashMap<>())
                        .merge(author, 1, Integer::sum);
            }
        }

        Map<String, String> ownership = new HashMap<>();
        for (Map.Entry<String, Map<String, Integer>> entry : countsByFile.entrySet()) {
            String primaryOwner = entry.getValue().entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (primaryOwner != null) {
                ownership.put(entry.getKey(), primaryOwner);
            }
        }
        return Collections.unmodifiableMap(ownership);
    }
}
