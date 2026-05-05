# Architecture

## Module Breakdown

```
graphus/
├── graphus-model     # Domain model — no dependencies on other modules
├── graphus-parser    # Source parsing — depends on graphus-model
├── graphus-indexer   # Embeddings via LangChain4j EmbeddingStore (Chroma or SQLite), checksum registry — depends on graphus-model
└── graphus-cli       # CLI entry point — depends on all modules
```

### graphus-model

Pure domain model with no external framework dependencies.

| Class            | Role                                                       |
| ---------------- | ---------------------------------------------------------- |
| `CallGraph`      | Graph of all parsed symbols and their call edges           |
| `CallEdge`       | Directed edge from caller to callee (both fully qualified) |
| `ClassNode`      | Parsed class with Spring metadata and fields               |
| `MethodNode`     | Parsed method with parameters, return type, and call edges |
| `FieldNode`      | Field with type, name, and modifiers                       |
| `SpringMetadata` | Holds stereotype, HTTP mappings, and behavior flags        |
| `SymbolNode`     | Common interface for all indexable symbols                 |
| `UnresolvedNode` | Placeholder for symbols that could not be resolved         |

### graphus-parser

JavaParser-based visitor pipeline that transforms source files into a `CallGraph`.

| Class                       | Role                                                         |
| --------------------------- | ------------------------------------------------------------ |
| `ProjectParser`             | Entry point — discovers `.java` files and drives the parse   |
| `SymbolVisitor`             | AST visitor that extracts classes, fields, and methods       |
| `SpringAnnotationExtractor` | Reads Spring stereotypes and HTTP mappings from annotations  |
| `CallGraphBuilder`          | Accumulates `SymbolNode`s and `CallEdge`s into a `CallGraph` |
| `SymbolIdResolver`          | Generates stable, fully-qualified symbol IDs                 |
| `ProjectParserResult`       | Value object: `callGraph`, `parsedFiles`, `unresolvedCalls`  |

### graphus-indexer

Converts `CallGraph` nodes into text chunks and manages LangChain4j embedding persistence.

| Class                    | Role                                                                 |
| ------------------------ | -------------------------------------------------------------------- |
| `GraphIndexer`           | Wraps embeddings + similarity search/delete via `EmbeddingStore`     |
| `VectorStoreFactory`     | Bridges `VectorBackend` to `ChromaEmbeddingStore` or `SqliteEmbeddingStore` |
| `SqliteEmbeddingStore`   | Lightweight SQLite JDBC store with brute-force cosine search       |
| `GraphusVectorRuntime`    | Computes merge order: CLI > `.graphus/config.json` > defaults      |
| `GraphusConfigRegistry` | Persists/restores `{db,embedding,collection,...}` beside checksums    |
| `SymbolChunkBuilder`    | Serializes a `SymbolNode` into a text chunk for embedding |
| `EmbeddingModelFactory` | Creates `local` or `openai` embedding models              |
| `FileChecksumRegistry`  | Tracks file checksums for incremental sync                |
| `FileChangeSet`         | Diff result: added, modified, deleted file sets           |
| `GraphSearchHit`        | Query result: score, text, and metadata                   |

### graphus-cli

Picocli-based CLI with subcommands.

| Command        | Role                                                        |
| -------------- | ----------------------------------------------------------- |
| `index`        | Full rebuild: clear + parse + index + save checksums + persisted `config.json` |
| `sync`         | Incremental: diff checksums, remove stale embeddings, re-index changed, refresh `config.json` when syncing |
| `query`        | Natural language retrieval over indexed symbols             |
| `blast-radius` | BFS traversal over call edges to find callers               |
| `install`      | Writes AI tool integration files (Cursor, Claude Code)      |

## Data Flow

```
Source files
    │
    ▼
ProjectParser (JavaParser AST visitor)
    │  extracts ClassNode / MethodNode / FieldNode / CallEdge
    ▼
CallGraph
    │
    ├─► SymbolChunkBuilder → text chunks
    │       │
    │       ▼
    │   EmbeddingModel (local / openai)
    │       │
    │       ▼
    │   EmbeddingStore adapter (Chroma collection or SQLite embeddings table)
    │
    └─► blastRadiusCallers (BFS over CallEdge graph)
```

## Incremental Sync

`FileChecksumRegistry` stores SHA-256 digests alongside **`config.json`** in `{workspace}/.graphus/`. The config records the embedding + vector-backend choices so follow-up CLI invocations inherit defaults unless flags override them. On `sync`:

1. Compute current digests for all discovered `.java` files.
2. Diff against stored digests → `FileChangeSet` (added / modified / deleted).
3. Remove embeddings for modified and deleted files via `EmbeddingStore.removeAll(Filter)`.
4. Re-parse the full project and index only the changed files.
5. Save checksums (`checksums.json`) and refresh **`config.json`** when mutations occur so tooling rediscovers embeddings settings.

Checksums/registry files are authored by `index` and mutated by `sync`. Missing checksums imply `sync` should exit early with guidance to run `index`.
