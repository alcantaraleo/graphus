package io.graphus.indexer;

@FunctionalInterface
public interface IndexProgressListener {

    void onSymbolStart(SymbolChunk chunk, int current, int total);
}
