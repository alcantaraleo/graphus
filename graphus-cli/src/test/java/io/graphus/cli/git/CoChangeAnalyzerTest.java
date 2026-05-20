package io.graphus.cli.git;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoChangeAnalyzerTest {

    @Test
    void pairsAboveThresholdAreIncluded() {
        List<GitLogParser.CommitFiles> history = List.of(
                commit("A.java", "B.java"),
                commit("A.java", "B.java"),
                commit("A.java", "B.java"));

        Map<String, List<CoChangeAnalyzer.CoChangeEntry>> result =
                CoChangeAnalyzer.analyze(history, 2);

        assertTrue(result.containsKey("A.java"));
        assertEquals(1, result.get("A.java").size());
        assertEquals("B.java", result.get("A.java").get(0).file());
        assertEquals(3, result.get("A.java").get(0).frequency());
    }

    @Test
    void pairsBelowThresholdAreExcluded() {
        List<GitLogParser.CommitFiles> history = List.of(
                commit("A.java", "B.java"),
                commit("A.java", "B.java"));

        Map<String, List<CoChangeAnalyzer.CoChangeEntry>> result =
                CoChangeAnalyzer.analyze(history, 5);

        assertTrue(result.isEmpty());
    }

    @Test
    void soloFilesNotIncluded() {
        List<GitLogParser.CommitFiles> history = List.of(
                commit("A.java"),
                commit("A.java"),
                commit("A.java"));

        Map<String, List<CoChangeAnalyzer.CoChangeEntry>> result =
                CoChangeAnalyzer.analyze(history, 1);

        assertFalse(result.containsKey("A.java"));
    }

    @Test
    void partnersAreSortedByFrequencyDescending() {
        List<GitLogParser.CommitFiles> history = List.of(
                commit("A.java", "B.java", "C.java"),
                commit("A.java", "C.java"),
                commit("A.java", "C.java"),
                commit("A.java", "B.java"));

        Map<String, List<CoChangeAnalyzer.CoChangeEntry>> result =
                CoChangeAnalyzer.analyze(history, 1);

        List<CoChangeAnalyzer.CoChangeEntry> partners = result.get("A.java");
        assertEquals("C.java", partners.get(0).file()); // freq=3
        assertEquals("B.java", partners.get(1).file()); // freq=2
    }

    @Test
    void relationshipIsSymmetric() {
        List<GitLogParser.CommitFiles> history = List.of(
                commit("A.java", "B.java"),
                commit("A.java", "B.java"));

        Map<String, List<CoChangeAnalyzer.CoChangeEntry>> result =
                CoChangeAnalyzer.analyze(history, 1);

        assertTrue(result.containsKey("A.java"));
        assertTrue(result.containsKey("B.java"));
        assertEquals("B.java", result.get("A.java").get(0).file());
        assertEquals("A.java", result.get("B.java").get(0).file());
    }

    @Test
    void emptyHistoryReturnsEmptyMap() {
        assertTrue(CoChangeAnalyzer.analyze(List.of(), 1).isEmpty());
    }

    // ---- helpers ----

    private static GitLogParser.CommitFiles commit(String... files) {
        return new GitLogParser.CommitFiles("hash-" + System.nanoTime(), List.of(files));
    }
}
