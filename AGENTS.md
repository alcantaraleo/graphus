# Graphus — Agent Guide

Graphus is a Java CLI that parses Java and Kotlin + Spring Boot source code, builds a symbol-aware call graph (with best-effort cross-language Java ↔ Kotlin edges), and indexes symbol chunks into a vector store (ChromaDB by default or a local SQLite embeddings file) for LLM/RAG retrieval.

## Delegation policy

- Prefer robot subagents for validation and execution orchestration.
- Core sequence: `robot-business-analyst` (when needed) -> `robot-coordinator` -> implementation robot (`robot-java-coder` or `robot-spring-boot-coder`) -> `robot-java-test-writer` -> `robot-java-code-reviewer`.
- Enforce verify gates and dependency order from `*.plan.md` before starting dependent groups.
- Use supporting skills as needed: `codegraph-exploration`, `sequential-thinking`, `java-code-generation`, and commit/PR skills when delivery tasks are requested.

## Modules

| Module            | Role                                                                                                           |
| ----------------- | -------------------------------------------------------------------------------------------------------------- |
| `graphus-model`   | Language-agnostic domain model — `CallGraph`, `SymbolNode`, `SpringMetadata`, `GuiceMetadata`                  |
| `graphus-parser`  | Java (JavaParser) + Kotlin (`kotlin-compiler-embeddable` PSI) AST pipeline → `CallGraph` + cross-language pass |
| `graphus-indexer` | LangChain4j `EmbeddingStore` (Chroma or SQLite), checksum sync over `.java` + `.kt` files, `config.json`       |
| `graphus-cli`     | Picocli entry point — `index`, `sync`, `query`, `blast-radius`, `install`                                      |

See [Architecture](docs/architecture.md) for full class breakdown and data flow.

## CLI Commands

| Command        | Description                                                                            |
| -------------- | -------------------------------------------------------------------------------------- |
| `index`        | Full rebuild: clear backing store, parse sources, index symbols, persist `config.json` |
| `sync`         | Incremental: re-index only added/modified/deleted files                                |
| `query`        | Natural language retrieval over indexed symbols                                        |
| `blast-radius` | BFS traversal to find all callers of a symbol                                          |
| `install`      | Write AI tool integration files (`cursor`, `claude-code`)                              |

```bash
graphus <command> [options]
```

See [README](README.md) for full option tables and examples.

## Distribution Convention

Any change to how Graphus is built, packaged, or distributed — including changes to build tasks, release artifact type, wrapper scripts, or command invocation syntax — must update all of the following in the same change:

1. `graphus-cli/src/main/java/io/graphus/cli/install/cursor/CursorAdapter.java` command examples for generated `.cursor/rules/graphus.mdc`
2. `graphus-cli/src/main/java/io/graphus/cli/install/claudecode/ClaudeCodeAdapter.java` command examples for generated `.claude/commands/graphus-*.md`
3. `README.md` and this `AGENTS.md` guide so documentation matches real distribution and runtime usage

### Homebrew release flow

- Homebrew distribution uses a separate tap repository (default: `alcantaraleo/homebrew-graphus`).
- On GitHub Release publish, `.github/workflows/publish.yml` uploads release assets and updates the tap formula (`Formula/graphus.rb`) with:
  - release version (from tag `vX.Y.Z`)
  - computed `sha256` of `graphus.jar`
- Required secret: `HOMEBREW_TAP_TOKEN` with push access to the tap repository.
- Optional repository variable: `HOMEBREW_TAP_REPO` to override the default tap repository.

## Key Conventions

- Java 21, Gradle Kotlin DSL, no Spring Boot runtime
- No star imports; constructor injection only; prefer `final`
- Module deps are strictly layered — no circular dependencies
- Symbol IDs: `fully.qualified.ClassName.methodName(param.Type)`; Kotlin top-level functions are emitted under a synthetic `<package>.<File>Kt` facade class to match the JVM bytecode shape
- Kotlin source roots live next to Java roots in `ModuleDescriptor` (`sourceRoots` for Java, `kotlinSourceRoots` for Kotlin); `RepositoryLayoutDetector` picks up `src/main/kotlin` automatically and `--source` accepts paths to either Java or Kotlin roots
- Cross-language call edges (Java ↔ Kotlin) are best-effort, resolved by name + arity in `CrossLanguageCallResolver` after both per-language passes
- State dir: `{repo}/.graphus/` stores `checksums.json` (now covering `.java` + `.kt`), `config.json`, and optional `graphus.db`
- Vector backend + embedding settings must stay consistent across `index`/`sync`/`query` unless intentionally reconfigured

See [Conventions](docs/conventions.md) for full details.
