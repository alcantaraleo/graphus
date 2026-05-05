package io.graphus.indexer;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteEmbeddingStoreTest {

    @Test
    void addSearchRemoveSupportsJsonMetadataFilters(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("embed.sqlite");
        String collectionLogicalName = "My-Repo!";
        SqliteEmbeddingStore store = new SqliteEmbeddingStore(db, collectionLogicalName);

        assertTrue(
                Files.exists(db),
                "SQLite file should exist after ctor (SqliteJdbc creates file on connect)");

        float[] apple = normalizedVector(List.of(1f, 0f, 0f));
        float[] banana = normalizedVector(List.of(0f, 1f, 0f));
        float[] query = normalizedVector(List.of(0.98f, 0.05f, 0f)); // nearer to apple

        List<String> ids = List.of("id-apple", "id-banana");
        List<Embedding> embeddings = List.of(Embedding.from(apple), Embedding.from(banana));
        List<TextSegment> segments =
                List.of(
                        TextSegment.from("apple-symbol", Metadata.from(Map.of("module", "m1"))),
                        TextSegment.from("banana-symbol", Metadata.from(Map.of("module", "m2", "file", "Banana.java"))));

        store.addAll(ids, embeddings, segments);

        var resultNoFilter =
                store.search(EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(query)).maxResults(2).build());
        assertEquals(2, resultNoFilter.matches().size());

        var topFiltered =
                store.search(
                        EmbeddingSearchRequest.builder()
                                .queryEmbedding(Embedding.from(query))
                                .maxResults(1)
                                .filter(new IsEqualTo("module", "m1"))
                                .build());

        assertEquals(1, topFiltered.matches().size());
        assertTrue(topFiltered.matches().getFirst().embedded().text().contains("apple"));

        store.removeAll(new IsEqualTo("file", "Banana.java"));
        var bananasAfterRemoval =
                store.search(
                        EmbeddingSearchRequest.builder()
                                .queryEmbedding(Embedding.from(banana))
                                .filter(new IsEqualTo("module", "m2"))
                                .maxResults(5)
                                .build());

        assertEquals(0, bananasAfterRemoval.matches().size());

        store.removeAll();
        assertEquals(
                0,
                store.search(EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(query)).maxResults(10).build())
                        .matches()
                        .size());
    }

    @Test
    void physicalTableUsesStableNormalization() {
        assertEquals(
                "graphus_embeddings_my_repo_module",
                SqliteEmbeddingStore.buildPhysicalTableName("My-Repo__Module"));
    }

    @Test
    void wholeNumberDoubleMetadataCoercesToIntegerNotLong(@TempDir Path tempDir) {
        Path db = tempDir.resolve("meta-coerce.sqlite");
        SqliteEmbeddingStore store = new SqliteEmbeddingStore(db, "coerce-test");

        float[] vector = normalizedVector(List.of(1f, 0f, 0f));
        Metadata lineAsDouble = new Metadata(Map.of("line", 42.0, "big", 3_000_000_000.0));
        store.addAll(
                List.of("r1"),
                List.of(Embedding.from(vector)),
                List.of(TextSegment.from("s", lineAsDouble)));

        var hits =
                store.search(
                        EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(vector)).maxResults(1).build());
        Map<String, Object> roundTrip = hits.matches().getFirst().embedded().metadata().toMap();

        assertEquals(Integer.class, roundTrip.get("line").getClass(), "whole doubles in JSON should round-trip as Integer");
        assertEquals(Long.class, roundTrip.get("big").getClass(), "integral doubles beyond int range stay as Long");
    }

    private static float[] normalizedVector(List<Float> values) {
        double norm = Math.sqrt(values.stream().mapToDouble(f -> (double) f * f).sum());
        float[] out = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            out[i] = norm == 0.0 ? values.get(i) : (float) (values.get(i) / norm);
        }
        return out;
    }
}
