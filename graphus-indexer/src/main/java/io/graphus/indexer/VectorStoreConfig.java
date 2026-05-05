package io.graphus.indexer;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Parameters needed to construct an {@link dev.langchain4j.store.embedding.EmbeddingStore}.
 *
 * @param sqliteDatabasePath required for {@link VectorBackend#SQLITE}; may be {@code null} for {@link VectorBackend#CHROMA}
 */
public record VectorStoreConfig(
        String collectionName, String chromaBaseUrl, Duration chromaTimeout, Path sqliteDatabasePath) {

    public VectorStoreConfig {
        if (collectionName == null || collectionName.isBlank()) {
            throw new IllegalArgumentException("collectionName must be non-blank");
        }
        Objects.requireNonNull(chromaBaseUrl, "chromaBaseUrl");
        Objects.requireNonNull(chromaTimeout, "chromaTimeout");
    }
}
