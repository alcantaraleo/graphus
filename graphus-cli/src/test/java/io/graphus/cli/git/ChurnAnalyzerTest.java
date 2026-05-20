package io.graphus.cli.git;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChurnAnalyzerTest {

    @Test
    void countsCommitsPerFile() {
        List<GitLogParser.CommitFiles> history = List.of(
                commit("A.java", "B.java"),
                commit("A.java"),
                commit("B.java", "C.java"));

        Map<String, Integer> result = ChurnAnalyzer.analyze(history);

        assertEquals(2, result.get("A.java"));
        assertEquals(2, result.get("B.java"));
        assertEquals(1, result.get("C.java"));
    }

    @Test
    void emptyHistoryReturnsEmptyMap() {
        assertTrue(ChurnAnalyzer.analyze(List.of()).isEmpty());
    }

    @Test
    void singleFileInSingleCommit() {
        Map<String, Integer> result =
                ChurnAnalyzer.analyze(List.of(commit("Only.java")));

        assertEquals(1, result.get("Only.java"));
        assertEquals(1, result.size());
    }

    private static GitLogParser.CommitFiles commit(String... files) {
        return new GitLogParser.CommitFiles("hash", List.of(files));
    }
}
