package io.graphus.cli.git;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes per-file commit churn (number of commits that touched the file) from git history.
 */
public final class ChurnAnalyzer {

    private ChurnAnalyzer() {
    }

    /**
     * @param history commits and their changed files, as produced by {@link GitLogParser}
     * @return map of file path → commit count, in no particular order
     */
    public static Map<String, Integer> analyze(List<GitLogParser.CommitFiles> history) {
        Map<String, Integer> churn = new HashMap<>();
        for (GitLogParser.CommitFiles commit : history) {
            for (String file : commit.files()) {
                churn.merge(file, 1, Integer::sum);
            }
        }
        return Collections.unmodifiableMap(churn);
    }
}
