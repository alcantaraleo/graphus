# Performance benchmarking methodology

Graphus publishes **automated indexing benchmarks** as a **`PERFORMANCE.md`** asset on each [GitHub Release](https://github.com/alcantaraleo/graphus/releases). This file explains how those numbers are produced; **it does not store current timings** (those live only on Releases).

## What is measured

For each pinned tier, CI runs the released fat JAR with:

```text
java -jar graphus.jar index \
  --repo <cloned fixture> \
  --db chroma \
  --db-url http://chroma:8000 \
  --embedding local \
  --collection perf_<tier>_<tag> \
  --benchmark-json <path>
```

Phases recorded per workspace (nanoseconds in JSON; seconds rounded in the release table):

- **Clear** — empty the Chroma collection
- **Parse** — AST / call-graph build
- **Index** — chunking, embeddings, Chroma upsert
- **Checksum** — `checksums.json` registry

## Fixture tiers (“small”, “medium”, “large”)

Tiers are **not** LOC bands. Each tier is a **named corpus** in [`.github/performance-repos.json`](.github/performance-repos.json), pinned to a **git tag or SHA** so runs are repeatable. Cold CI workers may spend extra time on first **local embedding** downloads unless caches hit—compare trends across releases, not single runs.

Multi-module layouts may produce **multiple workspace rows** per tier; see the Workspace column.

## Workflow policy

- **Publish vs benchmark:** Assets (`graphus.jar`, etc.) are uploaded in the `publish` job first. **`benchmark`** runs afterward and uploads `PERFORMANCE.md`. If the benchmark job fails, that job fails visibly (artifacts are already published).
- Structured output uses **`--benchmark-json`** — there is no log-scraping fallback.

## Local reproduction

Run Chroma (e.g. `docker compose up -d`), check out fixtures at the same refs as [.github/performance-repos.json](.github/performance-repos.json), then run `graphus index` with `--benchmark-json` as CI does.
