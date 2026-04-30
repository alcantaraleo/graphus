# Graphus

Graphus is a Java CLI that parses Java + Spring Boot source code, builds a symbol-aware call graph, and indexes symbol chunks into ChromaDB for LLM/RAG retrieval.

## What It Extracts

- Java symbols: classes, fields, methods, parameters, return types, modifiers
- Call edges: caller -> callee for resolvable `MethodCallExpr` invocations
- Spring-specific metadata:
  - Stereotypes: `@Service`, `@Controller`, `@Repository`, `@Configuration`, `@Entity`, `@Component`
  - HTTP mappings: `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping`, `@PatchMapping`, `@RequestMapping`
  - Behavior flags: `@Transactional`, `@Async`, `@Scheduled`
  - Injection markers: `@Autowired`, `@Inject`

## Project Structure

- `graphus-model`: call graph and symbol domain model
- `graphus-parser`: JavaParser-based project parsing and edge construction
- `graphus-indexer`: symbol chunk generation, Chroma indexing/query, and incremental sync via checksum registry
- `graphus-cli`: user-facing commands (`index`, `sync`, `query`, `blast-radius`, `install`)

## Prerequisites

- Java 21+
- Docker (for local ChromaDB)

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

## Start ChromaDB

```bash
docker compose up -d
```

Chroma runs at `http://localhost:8000`.

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

## CLI Usage

### Index a Repository (Full Rebuild)

Clears the Chroma collection, re-parses all source files, and re-indexes all symbols. Also saves a checksum registry for future incremental syncs.

```bash
graphus index \
  --repo /path/to/repo \
  --source src/main/java \
  --collection my-repo \
  --chroma-url http://localhost:8000 \
  --embedding local
```

| Option             | Default                 | Description                                |
| ------------------ | ----------------------- | ------------------------------------------ |
| `--repo`           | `.`                     | Repository root path                       |
| `--source`         | _(required)_            | Java source root; repeatable               |
| `--collection`     | _(required)_            | Chroma collection name                     |
| `--chroma-url`     | `http://localhost:8000` | Chroma base URL                            |
| `--chroma-timeout` | `300`                   | Chroma HTTP timeout in seconds             |
| `--batch-size`     | `500`                   | Symbols per embedding/index batch          |
| `--embedding`      | `local`                 | Embedding backend: `local` or `openai`     |
| `--state-dir`      | `{repo}/.graphus`       | Directory where `checksums.json` is stored |

### Sync (Incremental Update)

Re-indexes only files that were added, modified, or deleted since the last `index` or `sync`. Requires a prior `index` run.

```bash
graphus sync \
  --repo /path/to/repo \
  --source src/main/java \
  --collection my-repo
```

Accepts the same options as `index`. Exits with an error if no checksum registry is found.

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
  --collection my-repo \
  --chroma-url http://localhost:8000 \
  --top-k 10
```

| Option         | Default                 | Description                 |
| -------------- | ----------------------- | --------------------------- |
| `--collection` | _(required)_            | Chroma collection name      |
| `--chroma-url` | `http://localhost:8000` | Chroma base URL             |
| `--embedding`  | `local`                 | Embedding backend           |
| `--top-k`      | `10`                    | Number of results to return |

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
graphus query "where is user creation logic" --collection my-repo --embedding openai
```

> Keep the same embedding backend across `index`, `sync`, and `query` for a given collection.

## Known Limitations

- Spring runtime dispatch (AOP/proxies) cannot be fully represented by static call edges.
- External symbols may be unresolved when full classpath/JAR context is unavailable.
- Annotation element access such as `ann.value()` is parsed reliably, but not always resolvable via method resolution.
