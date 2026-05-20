---
description: Compute caller blast radius for a target symbol
---

Run Graphus blast radius using the target symbol from `$ARGUMENTS`.

Steps:
1. If `$ARGUMENTS` is empty, ask for the target symbol (fully qualified method signature or substring).
2. Run:
   `graphus blast-radius "$ARGUMENTS" --repo . --source src/main/java --depth 3`
3. Share the resolved target and caller list.
