---
description: Build a full Graphus index for the repository
---

Run a full Graphus index for the current repository.

Steps:
1. Run:
   `graphus index --repo . --source src/main/java --batch-size 500 --db sqlite`
   Add `--db chroma ...` overrides if you intentionally want Chroma on a non-default host. `--collection` is optional unless you shard collections manually.
2. Share parsed files, unresolved calls, and indexed symbols from command output.
