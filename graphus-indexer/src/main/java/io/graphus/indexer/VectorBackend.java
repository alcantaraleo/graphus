package io.graphus.indexer;

/** Vector storage backend selectable via CLI (--db). */
public enum VectorBackend {
    CHROMA,
    SQLITE
}
