package io.graphus.indexer;

import dev.langchain4j.data.document.Metadata;

public record SymbolChunk(String id, String text, Metadata metadata) {
}
