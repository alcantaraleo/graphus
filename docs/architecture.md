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

Pure language-agnostic domain model with no external framework dependencies.

| Class                 | Role                                                                                                                                 |
| --------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| `CallGraph`           | Graph of all parsed symbols and their call edges; supports node/edge removal so the cross-language resolver can replace placeholders |
| `CallEdge`            | Directed edge from caller to callee (both fully qualified)                                                                           |
| `ClassNode`           | Parsed class with Spring/Guice metadata and fields                                                                                   |
| `MethodNode`          | Parsed method/function with parameters, return type, and call edges                                                                  |
| `FieldNode`           | Field/property with type, name, and modifiers (Kotlin `val`/`var` is preserved in the type prefix)                                   |
| `SpringMetadata`      | Holds stereotype, HTTP mappings, and behavior flags                                                                                  |
| `GuiceMetadata`       | Holds Guice injection markers (`@Inject`, `@Provides`, `@Singleton`, `@Named`) and `MODULE` stereotype                               |
| `SymbolNode`          | Common interface for all indexable symbols                                                                                           |
| `UnresolvedNode`      | Placeholder for symbols that could not be resolved at parse time                                                                     |
| `ModuleDescriptor`    | Module layout with both `sourceRoots` (Java) and `kotlinSourceRoots` (Kotlin)                                                        |
| `WorkspaceDescriptor` | Aggregate of modules; exposes `flattenedSourceRoots()` and `flattenedKotlinSourceRoots()`                                            |

### graphus-parser

Two parallel AST pipelines (Java via JavaParser, Kotlin via the embedded Kotlin compiler PSI) that feed a single shared `CallGraph`, plus a post-pass that resolves cross-language calls.

| Class                       | Role                                                                                                                                             |
| --------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| `ProjectParser`             | Entry point — discovers `.java` and `.kt` files and orchestrates both pipelines plus the cross-language resolver                                 |
| `RepositoryLayoutDetector`  | Detects `src/main/java` and `src/main/kotlin` per module, including Kotlin-only modules and mixed-language layouts                               |
| `SymbolVisitor`             | JavaParser AST visitor that extracts Java classes, fields, methods                                                                               |
| `SpringAnnotationExtractor` | Reads Spring stereotypes and HTTP mappings from Java annotations                                                                                 |
| `CallGraphBuilder`          | Accumulates Java `SymbolNode`s and `CallEdge`s; emits structured `UnresolvedCallRecord`s for unresolved calls                                    |
| `SymbolIdResolver`          | Generates stable, fully-qualified symbol IDs                                                                                                     |
| `KotlinPsiEnvironment`      | Bootstraps a `KotlinCoreEnvironment` for standalone PSI parsing of `.kt` files                                                                   |
| `KotlinSymbolVisitor`       | Visits Kotlin PSI nodes and emits `ClassNode`/`MethodNode`/`FieldNode`s; synthesizes a `*Kt` facade class for top-level functions                |
| `KotlinAnnotationExtractor` | Reads Spring + Guice metadata from `KtAnnotationEntry`s, including supertype-based `MODULE` detection                                            |
| `KotlinParserContext`       | Maps `MethodNode` IDs to their `KtDeclarationWithBody` so the Kotlin call-graph builder can walk function bodies                                 |
| `KotlinCallGraphBuilder`    | Resolves Kotlin → Kotlin call edges by name + arity; emits `UnresolvedCallRecord`s for unresolved Kotlin calls                                   |
| `UnresolvedCallRecord`      | Structured (caller ID, callee name, arity, placeholder ID, origin) record produced by both call-graph builders                                   |
| `CrossLanguageCallResolver` | Post-processing step that matches Java↔Kotlin `UnresolvedCallRecord`s against symbols of the other language and replaces placeholder edges/nodes |
| `ProjectParserResult`       | Value object: `callGraph`, `parsedFiles`, `unresolvedCalls`                                                                                      |

### graphus-indexer

Converts `CallGraph` nodes into text chunks and manages LangChain4j embedding persistence.

| Class                   | Role                                                                        |
| ----------------------- | --------------------------------------------------------------------------- |
| `GraphIndexer`          | Wraps embeddings + similarity search/delete via `EmbeddingStore`            |
| `VectorStoreFactory`    | Bridges `VectorBackend` to `ChromaEmbeddingStore` or `SqliteEmbeddingStore` |
| `SqliteEmbeddingStore`  | Lightweight SQLite JDBC store with brute-force cosine search                |
| `GraphusVectorRuntime`  | Computes merge order: CLI > `.graphus/config.json` > defaults               |
| `GraphusConfigRegistry` | Persists/restores `{db,embedding,collection,...}` beside checksums          |
| `SymbolChunkBuilder`    | Serializes a `SymbolNode` into a text chunk for embedding                   |
| `EmbeddingModelFactory` | Creates `local` or `openai` embedding models                                |
| `FileChecksumRegistry`  | Tracks file checksums for incremental sync                                  |
| `FileChangeSet`         | Diff result: added, modified, deleted file sets                             |
| `GraphSearchHit`        | Query result: score, text, and metadata                                     |

### graphus-cli

Picocli-based CLI with subcommands.

| Command        | Role                                                                                                       |
| -------------- | ---------------------------------------------------------------------------------------------------------- |
| `index`        | Full rebuild: clear + parse + index + save checksums + persisted `config.json`                             |
| `sync`         | Incremental: diff checksums, remove stale embeddings, re-index changed, refresh `config.json` when syncing |
| `query`        | Natural language retrieval over indexed symbols                                                            |
| `blast-radius` | BFS traversal over call edges to find callers                                                              |
| `install`      | Writes AI tool integration files (Cursor, Claude Code)                                                     |

## Data Flow

```
Source files (.java + .kt)
    │
    ├─► Java pipeline: SymbolVisitor → CallGraphBuilder
    │       │ Java symbols + Java→Java edges + UnresolvedCallRecord(java)
    │       ▼
    │   ┌──────────┐
    │   │CallGraph │
    │   └──────────┘
    │       ▲
    ├─► Kotlin pipeline: KotlinPsiEnvironment → KotlinSymbolVisitor → KotlinCallGraphBuilder
    │       │ Kotlin symbols + Kotlin→Kotlin edges + UnresolvedCallRecord(kotlin)
    │       │
    │       ▼
    └─► CrossLanguageCallResolver
            │ Java→Kotlin and Kotlin→Java edges (best-effort name + arity)
            │ Removes placeholder UnresolvedNodes when uniquely resolved
            ▼
        CallGraph (final)
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

1. Compute current digests for all discovered `.java` and `.kt` files (both Java source roots and Kotlin source roots are scanned).
2. Diff against stored digests → `FileChangeSet` (added / modified / deleted).
3. Remove embeddings for modified and deleted files via `EmbeddingStore.removeAll(Filter)`.
4. Re-parse the full project (both pipelines + cross-language resolver) and index only the changed files.
5. Save checksums (`checksums.json`) and refresh **`config.json`** when mutations occur so tooling rediscovers embeddings settings.

Checksums/registry files are authored by `index` and mutated by `sync`. Missing checksums imply `sync` should exit early with guidance to run `index`.

## Cross-language call resolution

Per-language call-graph builders cannot resolve calls that cross the Java/Kotlin boundary because each pipeline only sees one language's AST. To bridge them without introducing a deeper type-resolution dependency:

1. `CallGraphBuilder` (Java) and `KotlinCallGraphBuilder` (Kotlin) emit a structured `UnresolvedCallRecord` for every call they cannot resolve internally — capturing caller ID, callee simple name, arity, the placeholder `UnresolvedNode` ID, and the origin language.
2. `CrossLanguageCallResolver` runs after both pipelines and, for each `UnresolvedCallRecord`, looks for `MethodNode`s in the _other_ language whose simple name and parameter count match. When exactly one candidate exists, it rewrites the placeholder edge to point at the real symbol and removes the now-unused `UnresolvedNode`.
3. Calls with zero or multiple candidates are left as-is so they show up as unresolved (rather than producing wrong edges).

This keeps cross-language edges explicit and best-effort: they are name + arity matches, not full type-resolved overload selection.
