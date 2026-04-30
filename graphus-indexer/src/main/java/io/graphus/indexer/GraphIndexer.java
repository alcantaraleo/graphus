package io.graphus.indexer;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaApiVersion;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import io.graphus.model.CallGraph;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GraphIndexer {

    private static final int DEFAULT_BATCH_SIZE = 500;

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final SymbolChunkBuilder symbolChunkBuilder;
    private final int batchSize;

    public GraphIndexer(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore) {
        this(embeddingModel, embeddingStore, DEFAULT_BATCH_SIZE);
    }

    public GraphIndexer(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore, int batchSize) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.symbolChunkBuilder = new SymbolChunkBuilder();
        this.batchSize = Math.max(1, batchSize);
    }

    public static EmbeddingStore<TextSegment> chromaStore(String chromaUrl, String collectionName) {
        return chromaStore(chromaUrl, collectionName, Duration.ofSeconds(5));
    }

    public static EmbeddingStore<TextSegment> chromaStore(String chromaUrl, String collectionName, Duration timeout) {
        return ChromaEmbeddingStore.builder()
                .apiVersion(ChromaApiVersion.V2)
                .baseUrl(chromaUrl)
                .collectionName(collectionName)
                .timeout(timeout)
                .build();
    }

    public int index(CallGraph callGraph) {
        return index(callGraph, (chunk, current, total) -> {});
    }

    public int index(CallGraph callGraph, IndexProgressListener progressListener) {
        List<SymbolChunk> chunks = symbolChunkBuilder.build(callGraph);
        return indexChunks(chunks, progressListener);
    }

    /**
     * Removes all documents from the embedding store. Use before a full re-index.
     */
    public void removeAll() {
        embeddingStore.removeAll();
    }

    /**
     * Removes all chunks associated with a specific source file. The {@code filePath}
     * must match the relative path stored in the {@code file} metadata field.
     */
    public void removeByFile(String filePath) {
        embeddingStore.removeAll(new IsEqualTo("file", filePath));
    }

    /**
     * Indexes only the symbols belonging to the given set of relative file paths.
     * Useful for incremental sync where only changed files need re-embedding.
     */
    public int indexForFiles(CallGraph callGraph, Set<String> filePaths) {
        return indexForFiles(callGraph, filePaths, (chunk, current, total) -> {});
    }

    public int indexForFiles(CallGraph callGraph, Set<String> filePaths, IndexProgressListener progressListener) {
        List<SymbolChunk> filteredChunks = symbolChunkBuilder.build(callGraph, filePaths);
        return indexChunks(filteredChunks, progressListener);
    }

    private int indexChunks(List<SymbolChunk> chunks, IndexProgressListener progressListener) {
        int totalChunks = chunks.size();
        for (int start = 0; start < totalChunks; start += batchSize) {
            int endExclusive = Math.min(start + batchSize, totalChunks);
            List<SymbolChunk> chunkBatch = chunks.subList(start, endExclusive);
            List<TextSegment> segmentBatch = chunkBatch.stream()
                    .map(chunk -> TextSegment.from(chunk.text(), chunk.metadata()))
                    .toList();
            List<Embedding> embeddings = embeddingModel.embedAll(segmentBatch).content();
            embeddingStore.addAll(embeddings, segmentBatch);
            SymbolChunk latestChunk = chunkBatch.get(chunkBatch.size() - 1);
            progressListener.onSymbolStart(latestChunk, endExclusive, totalChunks);
        }
        return totalChunks;
    }

    public List<GraphSearchHit> query(String question, int topK) {
        Embedding queryEmbedding = embeddingModel.embed(question).content();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(Math.max(1, topK))
                .build();
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);
        return result.matches().stream()
                .map(this::toSearchHit)
                .toList();
    }

    private GraphSearchHit toSearchHit(EmbeddingMatch<TextSegment> match) {
        TextSegment segment = match.embedded();
        Map<String, Object> metadata = segment.metadata() == null ? Map.of() : segment.metadata().toMap();
        return new GraphSearchHit(match.score(), segment.text(), metadata);
    }
}
