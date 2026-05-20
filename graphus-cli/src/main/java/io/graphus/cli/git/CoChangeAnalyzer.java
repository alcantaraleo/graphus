package io.graphus.cli.git;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes per-file co-change frequency from a parsed git commit history.
 *
 * <p>For each commit, every pair of changed files is counted. File pairs whose
 * co-change count exceeds the given threshold are included in the result.
 * Files that only appear alone in commits (no pair partner) are not included.
 */
public final class CoChangeAnalyzer {

    private CoChangeAnalyzer() {
    }

    /**
     * @param history   commits and their changed files, as produced by {@link GitLogParser}
     * @param threshold minimum co-change count for a pair to be included
     * @return map of file → co-changed partners, sorted by frequency descending; only entries
     *         where at least one partner meets the threshold are included
     */
    public static Map<String, List<CoChangeEntry>> analyze(
            List<GitLogParser.CommitFiles> history, int threshold) {
        Map<String, Map<String, Integer>> rawCounts = new HashMap<>();

        for (GitLogParser.CommitFiles commit : history) {
            List<String> files = commit.files();
            for (int i = 0; i < files.size(); i++) {
                for (int j = i + 1; j < files.size(); j++) {
                    String a = files.get(i);
                    String b = files.get(j);
                    rawCounts.computeIfAbsent(a, k -> new HashMap<>())
                            .merge(b, 1, Integer::sum);
                    rawCounts.computeIfAbsent(b, k -> new HashMap<>())
                            .merge(a, 1, Integer::sum);
                }
            }
        }

        Map<String, List<CoChangeEntry>> result = new HashMap<>();
        for (Map.Entry<String, Map<String, Integer>> entry : rawCounts.entrySet()) {
            List<CoChangeEntry> partners = new ArrayList<>();
            for (Map.Entry<String, Integer> partner : entry.getValue().entrySet()) {
                if (partner.getValue() >= threshold) {
                    partners.add(new CoChangeEntry(partner.getKey(), partner.getValue()));
                }
            }
            if (!partners.isEmpty()) {
                partners.sort(Comparator.comparingInt(CoChangeEntry::frequency).reversed());
                result.put(entry.getKey(), Collections.unmodifiableList(partners));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    public record CoChangeEntry(String file, int frequency) {}
}
