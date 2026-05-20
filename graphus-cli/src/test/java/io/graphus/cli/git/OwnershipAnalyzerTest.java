package io.graphus.cli.git;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnershipAnalyzerTest {

    @Test
    void primaryOwnerIsMostFrequentAuthor() {
        List<GitLogParser.CommitFiles> history = List.of(
                authorCommit("alice@example.com", "Service.java"),
                authorCommit("alice@example.com", "Service.java"),
                authorCommit("bob@example.com", "Service.java"));

        Map<String, String> result = OwnershipAnalyzer.analyze(history);

        assertEquals("alice@example.com", result.get("Service.java"));
    }

    @Test
    void eachFileGetsItsOwnOwner() {
        List<GitLogParser.CommitFiles> history = List.of(
                authorCommit("alice@example.com", "A.java", "B.java"),
                authorCommit("bob@example.com", "B.java", "C.java"),
                authorCommit("bob@example.com", "B.java"));

        Map<String, String> result = OwnershipAnalyzer.analyze(history);

        assertEquals("alice@example.com", result.get("A.java"));
        assertEquals("bob@example.com", result.get("B.java")); // bob has 2 vs alice 1
        assertEquals("bob@example.com", result.get("C.java"));
    }

    @Test
    void blankAuthorIsIgnored() {
        List<GitLogParser.CommitFiles> history = List.of(
                authorCommit("", "A.java"),
                authorCommit("  ", "A.java"),
                authorCommit("alice@example.com", "A.java"));

        Map<String, String> result = OwnershipAnalyzer.analyze(history);

        assertEquals("alice@example.com", result.get("A.java"));
    }

    @Test
    void emptyHistoryReturnsEmptyMap() {
        assertTrue(OwnershipAnalyzer.analyze(List.of()).isEmpty());
    }

    private static GitLogParser.CommitFiles authorCommit(String author, String... files) {
        return new GitLogParser.CommitFiles(author, List.of(files));
    }
}
