---
description: Query Graphus indexed symbols with a natural language question
---

Query Graphus with the user question from `$ARGUMENTS`.

Steps:
1. If `$ARGUMENTS` is empty, ask for the question to search.
2. Run:
   `graphus query "$ARGUMENTS" --top-k 10`
   `.graphus/config.json` selects the persisted vector backend + embedding defaults; add `--collection` only when querying a workspace that used a manual collection override.
3. Summarize the top results and their metadata.
