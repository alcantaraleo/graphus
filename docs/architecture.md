# Architecture

## Module Breakdown

```
graphus/
├── graphus-model     # Domain model — no dependencies on other modules
├── graphus-parser    # Source parsing — depends on graphus-model
├── graphus-indexer   # Embedding + Chroma indexing — depends on graphus-model
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

Converts `CallGraph` nodes into text chunks and manages Chroma interactions.

| Class                   | Role                                                      |
| ----------------------- | --------------------------------------------------------- |
| `GraphIndexer`          | Index, query, and remove operations against ChromaDB      |
| `SymbolChunkBuilder`    | Serializes a `SymbolNode` into a text chunk for embedding |
| `EmbeddingModelFactory` | Creates `local` or `openai` embedding models              |
| `FileChecksumRegistry`  | Tracks file checksums for incremental sync                |
| `FileChangeSet`         | Diff result: added, modified, deleted file sets           |
| `GraphSearchHit`        | Query result: score, text, and metadata                   |

### graphus-cli

Picocli-based CLI with subcommands.

| Command        | Role                                                        |
| -------------- | ----------------------------------------------------------- |
| `index`        | Full rebuild: clear + parse + index + save registry         |
| `sync`         | Incremental: diff checksums, remove stale, re-index changed |
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
    │   ChromaDB collection
    │
    └─► blastRadiusCallers (BFS over CallEdge graph)
```

## Incremental Sync

`FileChecksumRegistry` stores a SHA-256 digest per source file in `{repo}/.graphus/checksums.json`. On `sync`:

1. Compute current digests for all discovered `.java` files.
2. Diff against stored digests → `FileChangeSet` (added / modified / deleted).
3. Remove Chroma documents for modified and deleted files.
4. Re-parse the full project and index only the changed files.
5. Save updated registry.

The registry is written by `index` and read+updated by `sync`. If it is missing, `sync` exits with an error.
