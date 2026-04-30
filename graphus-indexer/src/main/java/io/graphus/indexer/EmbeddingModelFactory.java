package io.graphus.indexer;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;

public final class EmbeddingModelFactory {

    public EmbeddingModel create(EmbeddingBackend backend) {
        if (backend == EmbeddingBackend.OPENAI) {
            String apiKey = System.getenv("OPENAI_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("OPENAI_API_KEY is required for OpenAI embeddings");
            }
            return OpenAiEmbeddingModel.builder()
                    .apiKey(apiKey)
                    .modelName("text-embedding-3-small")
                    .build();
        }
        return new AllMiniLmL6V2EmbeddingModel();
    }
}
