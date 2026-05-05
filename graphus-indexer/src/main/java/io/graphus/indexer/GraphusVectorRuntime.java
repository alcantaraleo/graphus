package io.graphus.indexer;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Computes effective vector-store settings using the precedence: CLI flags ({@code null} means unspecified) >
 * persisted {@link GraphusConfig} in the workspace state dir > defaults.
 */
public final class GraphusVectorRuntime {

    public record Resolved(VectorBackend backend, VectorStoreConfig storeConfig, String embedding) {}

    private GraphusVectorRuntime() {}

    public static Resolved merge(
            Path stateDirForWorkspace,
            String resolvedCollectionName,
            String cliDb,
            String cliDbUrl,
            Integer cliDbTimeoutSeconds,
            String cliDbFile,
            String cliEmbedding)
            throws IOException {
        Objects.requireNonNull(stateDirForWorkspace, "stateDirForWorkspace");
        Objects.requireNonNull(resolvedCollectionName, "resolvedCollectionName");

        GraphusConfig persisted =
                GraphusConfigRegistry.exists(stateDirForWorkspace)
                        ? GraphusConfigRegistry.load(stateDirForWorkspace)
                        : null;

        String dbToken =
                firstNonBlankWithDefault(
                        trimToNull(cliDb),
                        persisted == null ? null : trimToNull(persisted.db()),
                        "chroma");
        VectorBackend backend = parseBackend(dbToken);

        String embeddingEffective =
                firstNonBlankWithDefault(
                        trimToNull(cliEmbedding),
                        persisted == null ? null : trimToNull(persisted.embedding()),
                        "local");

        String chromaUrlEffective =
                firstNonBlankWithDefault(
                        trimToNull(cliDbUrl),
                        persisted == null ? null : trimToNull(persisted.dbUrl()),
                        "http://localhost:8000");

        int chromaTimeoutSeconds =
                cliDbTimeoutSeconds != null
                        ? clampTimeout(cliDbTimeoutSeconds)
                        : (persisted != null && persisted.dbTimeoutSeconds() != null
                                ? clampTimeout(persisted.dbTimeoutSeconds())
                                : 300);

        Path sqlitePath = null;
        if (backend == VectorBackend.SQLITE) {
            String fileToken = trimToNull(cliDbFile);
            if (fileToken == null && persisted != null) {
                fileToken = trimToNull(persisted.dbFile());
            }
            sqlitePath =
                    fileToken == null
                            ? stateDirForWorkspace.resolve("graphus.db").toAbsolutePath().normalize()
                            : Path.of(fileToken).toAbsolutePath().normalize();
        }

        VectorStoreConfig storeConfig =
                new VectorStoreConfig(
                        resolvedCollectionName,
                        chromaUrlEffective,
                        Duration.ofSeconds(chromaTimeoutSeconds),
                        sqlitePath);

        return new Resolved(backend, storeConfig, embeddingEffective);
    }

    public static GraphusConfig persistableCopy(String resolvedCollectionName, Resolved resolved) {
        Objects.requireNonNull(resolvedCollectionName, "resolvedCollectionName");
        Objects.requireNonNull(resolved, "resolved");

        String dbName = resolved.backend().name().toLowerCase(Locale.ROOT);
        Path sqlitePath = resolved.storeConfig().sqliteDatabasePath();

        return new GraphusConfig(
                dbName,
                resolved.storeConfig().chromaBaseUrl(),
                (int) resolved.storeConfig().chromaTimeout().toSeconds(),
                sqlitePath == null ? null : sqlitePath.toAbsolutePath().normalize().toString(),
                resolved.embedding(),
                resolvedCollectionName);
    }

    private static VectorBackend parseBackend(String token) {
        String normalized = token.toLowerCase(Locale.ROOT).trim();
        return switch (normalized) {
            case "chroma" -> VectorBackend.CHROMA;
            case "sqlite" -> VectorBackend.SQLITE;
            default -> throw new IllegalArgumentException("Unsupported --db value: " + token);
        };
    }

    private static int clampTimeout(int seconds) {
        return Math.max(1, seconds);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String firstNonBlankWithDefault(String preferred, String fallback, String defaultValue) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback;
        }
        return Objects.requireNonNull(defaultValue, "defaultValue");
    }
}
