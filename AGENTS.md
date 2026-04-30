# Graphus — Agent Guide

Graphus is a Java CLI that parses Java + Spring Boot source code, builds a symbol-aware call graph, and indexes symbol chunks into ChromaDB for LLM/RAG retrieval.

## Modules

| Module            | Role                                                                      |
| ----------------- | ------------------------------------------------------------------------- |
| `graphus-model`   | Domain model — `CallGraph`, `SymbolNode`, `SpringMetadata`                |
| `graphus-parser`  | JavaParser AST visitor pipeline → `CallGraph`                             |
| `graphus-indexer` | Embedding + Chroma indexing, incremental sync via checksum registry       |
| `graphus-cli`     | Picocli entry point — `index`, `sync`, `query`, `blast-radius`, `install` |

See [Architecture](docs/architecture.md) for full class breakdown and data flow.

## CLI Commands

| Command        | Description                                                          |
| -------------- | -------------------------------------------------------------------- |
| `index`        | Full rebuild: clear collection, parse all sources, index all symbols |
| `sync`         | Incremental: re-index only added/modified/deleted files              |
| `query`        | Natural language retrieval over indexed symbols                      |
| `blast-radius` | BFS traversal to find all callers of a symbol                        |
| `install`      | Write AI tool integration files (`cursor`, `claude-code`)            |

```bash
./gradlew :graphus-cli:run --args='<command> [options]'
```

See [README](README.md) for full option tables and examples.

## Key Conventions

- Java 21, Gradle Kotlin DSL, no Spring Boot runtime
- No star imports; constructor injection only; prefer `final`
- Module deps are strictly layered — no circular dependencies
- Symbol IDs: `fully.qualified.ClassName.methodName(param.Type)`
- State dir: `{repo}/.graphus/checksums.json` (created by `index`, read by `sync`)
- Embedding backend must be consistent across `index`/`sync`/`query` for a given collection

See [Conventions](docs/conventions.md) for full details.
