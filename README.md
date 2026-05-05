# Graphus

Graphus is a Java CLI that parses Java and Kotlin + Spring Boot source code, builds a symbol-aware call graph (including best-effort cross-language edges between Java and Kotlin), and indexes symbol chunks into a **vector store** (ChromaDB by default, or a local SQLite database) for LLM/RAG retrieval.

## What It Extracts

- Java symbols: classes, fields, methods, parameters, return types, modifiers
- Kotlin symbols: classes, interfaces, `object` declarations, data classes, top-level functions (under a synthetic `*Kt` file facade), `val`/`var` properties, primary and secondary constructors, function parameters and return types
- Call edges:
  - Java → Java: caller → callee for resolvable `MethodCallExpr` invocations
  - Kotlin → Kotlin: name + arity matching against Kotlin `fun` definitions in the same workspace
  - Java → Kotlin and Kotlin → Java (best effort): name + arity post-pass over unresolved calls
- Spring-specific metadata (Java and Kotlin):
  - Stereotypes: `@Service`, `@Controller`, `@RestController`, `@Repository`, `@Configuration`, `@Entity`, `@Component`
  - HTTP mappings: `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, `@PatchMapping`, `@RequestMapping`
  - Behavior flags: `@Transactional`, `@Async`, `@Scheduled`
  - Injection markers: `@Autowired`, `@Inject`
- Guice-specific metadata (Java and Kotlin): `@Inject`, `@Singleton`, `@Provides`, `@Named`, plus `MODULE` stereotype for classes extending `AbstractModule`/`PrivateModule`

## Project Structure

- `graphus-model`: call graph and language-agnostic symbol domain model
- `graphus-parser`: Java and Kotlin source parsing (JavaParser + Kotlin compiler PSI) and call graph construction, including the cross-language resolver
- `graphus-indexer`: symbol chunk generation, LangChain4j `EmbeddingStore` integration (Chroma or SQLite), incremental sync via checksum registry (covers `.java` and `.kt` files), and `.graphus/config.json` persistence
- `graphus-cli`: user-facing commands (`index`, `sync`, `query`, `blast-radius`, `install`)

## Prerequisites

- Java 21+
- **Chroma only:** Docker (optional) or any reachable Chroma server for `--db chroma`

## Install

### Homebrew (recommended)

```bash
brew tap alcantaraleo/homebrew-graphus
brew install graphus
```

### Direct JAR

Download `graphus.jar` from GitHub Releases and run it directly:

```bash
java -jar graphus.jar --help
```

## Start ChromaDB (optional)

If you use the default vector backend `chroma`:

```bash
docker compose up -d
```

Chroma runs at `http://localhost:8000`.

For a **fully local** setup with no external services, use SQLite instead (see [Vector store backends](#vector-store-backends---db)).

## Build

```bash
./gradlew build
```

Build the distributable fat JAR:

```bash
./gradlew :graphus-cli:shadowJar
```

The release artifact is generated at `graphus-cli/build/libs/graphus.jar`.

Run tests only:

```bash
./gradlew test
```

## Performance benchmarks

Timing results for full `graphus index` runs against pinned fixture repos (small / medium / large) ship as **`PERFORMANCE.md`** on each [GitHub Release](https://github.com/alcantaraleo/graphus/releases)—CI generates and uploads it when a release is published. For methodology (tiers, refs, caveats only), see [PERFORMANCE.md](PERFORMANCE.md).

## CLI Usage

### Index a Repository (Full Rebuild)

Clears the vector index (`ChromaDB` collection **or** the SQLite embeddings table derived from `--collection`), re-parses all source files, and re-indexes all symbols. Saves `checksums.json` and **`config.json`** (`db`, embeddings, SQLite path, resolved collection).

```bash
graphus index \
  --repo /path/to/repo \
  --source src/main/java \
  --collection my-repo \ # optional
  --embedding local \
  --db sqlite # optional — default chroma Docker / remote Chroma URL
```

| Option             | Default                  | Description                                                                        |
| ------------------ | ------------------------ | ---------------------------------------------------------------------------------- |
| `--repo`           | `.`                      | Repository root path                                                               |
| `--source`         | _(required)_             | Java or Kotlin source root (e.g. `src/main/java` or `src/main/kotlin`); repeatable |
| `--collection`     | _(repo directory name)_  | Logical collection/table name embedded in SQLite + persisted in `config.json`      |
| `--db`             | persisted or `chroma`    | `chroma` (HTTP server) \| `sqlite` (local JDBC SQLite file — no daemon)            |
| `--db-url`         | persisted or localhost   | Base URL used when `--db chroma`                                                   |
| `--db-timeout`     | persisted or `300`       | Seconds for Chroma HTTP client when `--db chroma`                                  |
| `--db-file`        | `<state-dir>/graphus.db` | SQLite file path used when `--db sqlite`                                           |
| `--batch-size`     | `500`                    | Symbols per embedding/index batch                                                  |
| `--embedding`      | persisted or `local`     | Embedding backend: `local` \| `openai`                                             |
| `--state-dir`      | `{repo}/.graphus`        | Directory where `checksums.json`, `config.json`, and optionally `graphus.db` live  |
| `--benchmark-json` | _(unset)_                | Write phase timings + counts as JSON                                               |

### Sync (Incremental Update)

Re-indexes only files that were added, modified, or deleted since the last `index` or `sync`. Requires a prior `index` run.

```bash
graphus sync \
  --repo /path/to/repo \
  --source src/main/java \
  --collection my-repo # optional
```

Accepts the same options as `index`. Exits with an error if no checksum registry is found.

| Option         | Default                           | Description                                                                        |
| -------------- | --------------------------------- | ---------------------------------------------------------------------------------- |
| `--repo`       | `.`                               | Repository root path                                                               |
| `--source`     | _(required)_                      | Java or Kotlin source root (e.g. `src/main/java` or `src/main/kotlin`); repeatable |
| `--collection` | _(repo directory name)_           | Embedding collection/table name                                                    |
| `--db`         | persisted or `chroma`             | `chroma` \| `sqlite`                                                               |
| `--db-url`     | persisted or localhost            | Chroma base URL (chroma backend)                                                   |
| `--db-timeout` | persisted or `300`                | Chroma HTTP timeout seconds                                                        |
| `--db-file`    | persisted or `<state>/graphus.db` | SQLite file when using sqlite                                                      |
| `--batch-size` | `500`                             | Symbols per embedding/index batch                                                  |
| `--embedding`  | persisted or `local`              | `local` \| `openai`                                                                |
| `--state-dir`  | `{repo}/.graphus`                 | `checksums.json` / `config.json` directory                                         |

### Homebrew release automation

On every GitHub Release publish event, `.github/workflows/publish.yml`:

1. Uploads `graphus.jar` and `graphus` release assets.
2. Computes SHA-256 for `graphus.jar`.
3. Updates `Formula/graphus.rb` in the Homebrew tap repo (default `alcantaraleo/homebrew-graphus`) with the release version and checksum.
4. Commits and pushes the formula bump to the tap.

Required GitHub configuration:

- Repository secret: `HOMEBREW_TAP_TOKEN` (PAT with push access to the tap repository)
- Optional repository variable: `HOMEBREW_TAP_REPO` (override tap repo; default is `alcantaraleo/homebrew-graphus`)

### Query Indexed Symbols

```bash
graphus query \
  "POST endpoint that creates a user" \
  --collection my-repo \ # optional — falls back to config.json/cwd naming
  --top-k 10
```

Graphus merges CLI flags → `.graphus/config.json` (under `--state-dir`, default `./.graphus`) → safe defaults (`chroma` @ `localhost:8000`).

| Option                                                | Default                       | Description                              |
| ----------------------------------------------------- | ----------------------------- | ---------------------------------------- |
| `--collection`                                        | persisted or current dir name | Embedding collection/table               |
| `--state-dir`                                         | `./.graphus`                  | Holds `config.json` + SQLite default     |
| `--db` \| `--db-url` \| `--db-timeout` \| `--db-file` | (same merge rules as `index`) | Override persisted vector backend tuning |
| `--embedding`                                         | persisted or `local`          | Embedding backend                        |
| `--top-k`                                             | `10`                          | Max hits                                 |

### Blast Radius (Callers)

Find all callers that transitively reach a target symbol.

```bash
graphus blast-radius \
  "com.example.UserRepository.save(com.example.User)" \
  --repo /path/to/repo \
  --source src/main/java \
  --depth 3
```

If the symbol is not an exact match, Graphus tries a substring match against known symbol IDs.

### Install (AI Tool Integration)

Generates integration files for AI coding tools so they can invoke Graphus commands automatically.

```bash
graphus install \
  --tool cursor \
  --project-dir /path/to/project
```

| Option          | Description                                                |
| --------------- | ---------------------------------------------------------- |
| `--tool`        | AI tool to install for. Supported: `cursor`, `claude-code` |
| `--project-dir` | Target project directory (default: `.`)                    |

**Cursor** — writes `.cursor/rules/graphus.mdc` with pre-configured commands and operating guidelines.

**Claude Code** — writes the equivalent integration file for Claude Code.

## Embedding Backends

- `local` (default): on-device `AllMiniLmL6V2EmbeddingModel` — no API key required
- `openai`: uses `OPENAI_API_KEY` and `text-embedding-3-small`

```bash
export OPENAI_API_KEY=your_key
graphus query "where is user creation logic" --collection my-repo --embedding openai  # --collection optional
```

> Keep the same embedding backend across `index`, `sync`, and `query` for a given collection/index.

### Vector store backends (`--db`)

| Backend  | Requirement                      | Highlights                                                                                                           |
| -------- | -------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| `chroma` | Running Chroma v2-compatible API | Remote HTTP embeddings collection (LangChain4j integration).                                                         |
| `sqlite` | JDBC SQLite file (`sqlite-jdbc`) | Fully local; cosine similarity executes in-process. Default file: `{state-dir}/graphus.db` when `--db-file` omitted. |

`graphus index` / successful `sync` runs write **`{state-dir}/config.json`** with the resolved `{db,dbUrl,dbTimeoutSeconds?,dbFile?,embedding,collection}` so agents or Homebrew installs can rerun `sync`/`query` without repeating flags unless you intentionally override.

Precedence whenever a flag is **omitted** on the CLI: **`config.json` → defaults** (`chroma` → `http://localhost:8000`, timeout `300`, embedding `local`, SQLite `<state-dir>/graphus.db`).

## Known Limitations

- Spring runtime dispatch (AOP/proxies) cannot be fully represented by static call edges.
- External symbols may be unresolved when full classpath/JAR context is unavailable.
- Annotation element access such as `ann.value()` is parsed reliably, but not always resolvable via method resolution.
- Kotlin call edges (Kotlin → Kotlin and cross-language Java ↔ Kotlin) are **best-effort**: they are matched by callee name and arity rather than full type resolution. Overloads with the same name/arity may be left as unresolved or, on cross-language passes, picked deterministically only when a single candidate exists. Kotlin extension functions, lambdas, infix calls, and operator overloads are not yet modeled as first-class call edges.
