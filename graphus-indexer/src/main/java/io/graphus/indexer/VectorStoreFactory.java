package io.graphus.indexer;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaApiVersion;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import java.util.Objects;

public final class VectorStoreFactory {

    private VectorStoreFactory() {}

    public static EmbeddingStore<TextSegment> create(VectorBackend backend, VectorStoreConfig config) {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(config, "config");

        return switch (backend) {
            case CHROMA -> ChromaEmbeddingStore.builder()
                    .apiVersion(ChromaApiVersion.V2)
                    .baseUrl(config.chromaBaseUrl())
                    .collectionName(config.collectionName())
                    .timeout(config.chromaTimeout())
                    .build();
            case SQLITE -> new SqliteEmbeddingStore(config.sqliteDatabasePath(), config.collectionName());
        };
    }
}
