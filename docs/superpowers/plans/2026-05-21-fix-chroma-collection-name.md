# Fix Chroma Collection Name Trailing Underscore Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the performance benchmark CI job that has been failing on every release since 0.6.0 because the generated Chroma collection name ends with a trailing underscore that Chroma rejects.

**Architecture:** Single-line fix in the benchmark shell script. `echo "${RELEASE_TAG}"` appends a newline; `tr -c 'a-zA-Z0-9_' '_'` converts that newline to `_`, producing names like `perf_medium_v0_7_0_`. Replacing the subshell + `echo` with a bash parameter expansion (`${RELEASE_TAG//[^a-zA-Z0-9_]/_}`) eliminates the newline entirely and is simpler.

**Tech Stack:** Bash, GitHub Actions, ChromaDB collection naming rules (`[a-zA-Z0-9._-]`, must start and end with `[a-zA-Z0-9]`).

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `.github/scripts/generate-performance-report.sh` | **Modify** (line 35) | Replace `echo + tr` with bash parameter expansion to strip the trailing underscore |

---

### Task 1: Fix the trailing underscore in SAFE_TAG

**Files:**
- Modify: `.github/scripts/generate-performance-report.sh:35`

**Root cause recap:** Line 35 is:
```bash
SAFE_TAG="$(echo "${RELEASE_TAG}" | tr -c 'a-zA-Z0-9_' '_')"
```

`echo` adds a newline. `tr -c 'a-zA-Z0-9_' '_'` translates the newline to `_`. For tag `v0.7.0` the result is `v0_7_0_`. Collection name becomes `perf_medium_v0_7_0_`, which Chroma rejects because it must end with `[a-zA-Z0-9]`.

- [ ] **Step 1: Verify the current line**

Read `.github/scripts/generate-performance-report.sh` and confirm line 35 is:
```
SAFE_TAG="$(echo "${RELEASE_TAG}" | tr -c 'a-zA-Z0-9_' '_')"
```

- [ ] **Step 2: Write a local test to confirm the bug**

Run in a shell:
```bash
RELEASE_TAG="v0.7.0"
SAFE_TAG="$(echo "${RELEASE_TAG}" | tr -c 'a-zA-Z0-9_' '_')"
echo "Bug: '${SAFE_TAG}'"
```

Expected output: `Bug: 'v0_7_0_'` — trailing underscore visible.

- [ ] **Step 3: Apply the fix**

In `.github/scripts/generate-performance-report.sh`, replace line 35:

Old:
```bash
SAFE_TAG="$(echo "${RELEASE_TAG}" | tr -c 'a-zA-Z0-9_' '_')"
```

New:
```bash
SAFE_TAG="${RELEASE_TAG//[^a-zA-Z0-9_]/_}"
```

This uses bash parameter expansion (`//` = replace all occurrences, `[^a-zA-Z0-9_]` = any char not in the set) — no subshell, no `tr`, no newline.

- [ ] **Step 4: Verify the fix locally**

Run in a shell:
```bash
RELEASE_TAG="v0.7.0"
SAFE_TAG="${RELEASE_TAG//[^a-zA-Z0-9_]/_}"
echo "Fixed: '${SAFE_TAG}'"
collection="perf_medium_${SAFE_TAG}"
echo "Collection: '${collection}'"
```

Expected output:
```
Fixed: 'v0_7_0'
Collection: 'perf_medium_v0_7_0'
```

No trailing underscore. Collection name ends with `0` — valid for Chroma.

- [ ] **Step 5: Also verify for other tag shapes**

```bash
for tag in "v1.0.0" "v0.10.3" "v1.2.3-rc1"; do
  safe="${tag//[^a-zA-Z0-9_]/_}"
  echo "${tag} → ${safe} → perf_small_${safe}"
done
```

Expected:
```
v1.0.0 → v1_0_0 → perf_small_v1_0_0
v0.10.3 → v0_10_3 → perf_small_v0_10_3
v1.2.3-rc1 → v1_2_3_rc1 → perf_small_v1_2_3_rc1
```

All names end with alphanumeric characters — all valid for Chroma.

- [ ] **Step 6: Commit**

```bash
git add .github/scripts/generate-performance-report.sh
git commit -m "fix(ci): strip trailing underscore from Chroma collection name derived from release tag"
```
