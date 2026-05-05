package io.graphus.indexer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Locale;

/**
 * Persisted Graphus runtime settings written to {@code .graphus/config.json}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GraphusConfig(
        String db,
        String dbUrl,
        Integer dbTimeoutSeconds,
        String dbFile,
        String embedding,
        String collection) {

    public VectorBackend vectorBackend() {
        if (db == null || db.isBlank()) {
            return VectorBackend.CHROMA;
        }
        return switch (db.toLowerCase(Locale.ROOT).trim()) {
            case "sqlite" -> VectorBackend.SQLITE;
            case "chroma" -> VectorBackend.CHROMA;
            default -> throw new IllegalArgumentException("Unsupported persisted db value: " + db);
        };
    }
}
