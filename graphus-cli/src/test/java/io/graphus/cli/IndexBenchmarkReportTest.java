package io.graphus.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexBenchmarkReportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void writeProducesExpectedShape(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("bench.json");
        IndexBenchmarkReport.write(
                out,
                new IndexBenchmarkReport.Payload(
                        1,
                        "index",
                        99L,
                        10,
                        2,
                        40,
                        List.of(new IndexBenchmarkReport.WorkspaceEntry(
                                "ws1",
                                "coll-a",
                                1L,
                                2L,
                                3L,
                                4L,
                                5,
                                1,
                                40))));

        assertTrue(Files.exists(out));
        JsonNode root = MAPPER.readTree(out.toFile());
        assertEquals(1, root.path("schemaVersion").asInt());
        assertEquals("index", root.path("command").asText());
        assertEquals(99L, root.path("totalWallNanos").asLong());
        assertEquals(10, root.path("totalParsedFiles").asInt());
        assertEquals(2, root.path("totalUnresolvedCalls").asInt());
        assertEquals(40, root.path("totalIndexedSymbols").asInt());
        JsonNode ws = root.path("workspaces").get(0);
        assertEquals("ws1", ws.path("workspaceName").asText());
        assertEquals("coll-a", ws.path("collection").asText());
        assertEquals(4L, ws.path("checksumNanos").asLong());
        assertEquals(40, ws.path("indexedSymbols").asInt());
    }
}
