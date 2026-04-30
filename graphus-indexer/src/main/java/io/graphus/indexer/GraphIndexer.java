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
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GraphIndexer {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final SymbolChunkBuilder symbolChunkBuilder;

    public GraphIndexer(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.symbolChunkBuilder = new SymbolChunkBuilder();
    }

    public static EmbeddingStore<TextSegment> chromaStore(String chromaUrl, String collectionName) {
        return ChromaEmbeddingStore.builder()
                .apiVersion(ChromaApiVersion.V2)
                .baseUrl(chromaUrl)
                .collectionName(collectionName)
                .build();
    }

    public int index(CallGraph callGraph) {
        List<SymbolChunk> chunks = symbolChunkBuilder.build(callGraph);
        for (SymbolChunk chunk : chunks) {
            TextSegment segment = TextSegment.from(chunk.text(), chunk.metadata());
            Embedding embedding = embeddingModel.embed(segment).content();
            embeddingStore.add(embedding, segment);
        }
        return chunks.size();
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
        List<SymbolChunk> chunks = symbolChunkBuilder.build(callGraph);
        int count = 0;
        for (SymbolChunk chunk : chunks) {
            Object fileMeta = chunk.metadata().toMap().get("file");
            if (fileMeta == null || !filePaths.contains(fileMeta.toString())) {
                continue;
            }
            TextSegment segment = TextSegment.from(chunk.text(), chunk.metadata());
            Embedding embedding = embeddingModel.embed(segment).content();
            embeddingStore.add(embedding, segment);
            count++;
        }
        return count;
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
