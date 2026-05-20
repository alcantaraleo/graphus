---
description: Sync Graphus index incrementally after code changes
---

Run Graphus incremental sync for this repository.

Steps:
1. If `.graphus/checksums.json` does not exist, run `/project:graphus-index` first.
2. Run:
   `graphus sync --repo . --source src/main/java --batch-size 500`
   Graphus resolves `.graphus/config.json`; only pass `--collection` / `--db*` when diverging from the recorded workspace defaults.
3. Share counts for added, modified, deleted, and indexed symbols.
