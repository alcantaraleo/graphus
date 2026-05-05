package io.graphus.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Machine-readable timings for {@code graphus index}, written when {@code --benchmark-json} is set.
 */
public final class IndexBenchmarkReport {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public record WorkspaceEntry(
            String workspaceName,
            String collection,
            long clearNanos,
            long parseNanos,
            long indexNanos,
            long checksumNanos,
            int parsedFiles,
            int unresolvedCalls,
            int indexedSymbols) {}

    public record Payload(
            int schemaVersion,
            String command,
            long totalWallNanos,
            int totalParsedFiles,
            int totalUnresolvedCalls,
            int totalIndexedSymbols,
            List<WorkspaceEntry> workspaces) {}

    public static void write(Path path, Payload payload) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(payload, "payload");
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(path)) {
            MAPPER.writeValue(out, payload);
        }
    }

    private IndexBenchmarkReport() {}
}
