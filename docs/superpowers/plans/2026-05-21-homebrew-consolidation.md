# Homebrew Formula Consolidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the Homebrew formula source into the main `graphus` repo and fix multiple flakiness issues in the release publish workflow that cause the tap to silently go stale.

**Architecture:** `Formula/graphus.rb` in the main repo becomes the authoritative source for formula logic. The `alcantaraleo/homebrew-graphus` tap repository remains (Homebrew's `brew tap` convention requires a `homebrew-*` named repo), but becomes a CI-managed mirror — it is never edited by hand. The `publish.yml` workflow reads the formula already checked out in the workspace, updates version/sha256, validates Ruby syntax, then pushes to the tap. Failure is now loud rather than silent.

**Tech Stack:** GitHub Actions (ubuntu-latest), Ruby (for formula syntax check), `sed` (formula value substitution), `git clone` + push to tap (same cross-repo mechanism, made robust), Homebrew formula Ruby DSL.

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `Formula/graphus.rb` | **Create** | Formula source of truth — logic lives here; version/sha reflect the last published release |
| `.github/workflows/ci.yml` | **Modify** | Add `ruby -c Formula/graphus.rb` step to catch formula syntax errors in PRs |
| `.github/workflows/publish.yml` | **Modify** | Rewrite Homebrew step: use in-workspace formula, `sed` substitution, hard failure on missing token, Ruby syntax validation before push |
| `CLAUDE.md` | **Modify** | Update Homebrew section to note formula now lives in `Formula/graphus.rb` |
| `README.md` | **Modify** | Update Homebrew release automation section |
| `/Users/leonardo.alcantara/work/workspaces/github/graphus_projects/homebrew-graphus/README.md` | **Modify** | Replace manual-publish checklist with auto-managed notice |

---

### Task 1: Add Formula/graphus.rb to main repo

**Files:**
- Create: `Formula/graphus.rb`

This copies the existing formula from the tap into the main repo. The version and sha256 values reflect the last released version (0.6.0). The publish workflow will update them at release time and push to the tap; the values in this file will always lag by one release, which is acceptable — the logic is what matters here.

- [ ] **Step 1: Write the formula file**

```bash
mkdir -p Formula
```

Create `Formula/graphus.rb` with this content:

```ruby
class Graphus < Formula
  desc "Java/Spring code graph and RAG CLI"
  homepage "https://github.com/alcantaraleo/graphus"
  # version and sha256 are updated automatically by publish.yml on each release.
  # Edit the formula logic here; do not hand-edit alcantaraleo/homebrew-graphus.
  version "0.6.0"
  url "https://github.com/alcantaraleo/graphus/releases/download/v#{version}/graphus.jar"
  sha256 "c5d98cee20474132df37df8849f9ea8a20dcb23c9dd43c8c5cfbe9ad2229ec60"
  license "Apache-2.0"

  depends_on "openjdk@21"

  def install
    libexec.install "graphus.jar"

    bin.mkpath
    (bin/"graphus").atomic_write <<~EOS
      #!/bin/sh
      export JAVA_HOME="${JAVA_HOME:-#{Formula["openjdk@21"].opt_prefix}}"
      exec "#{Formula["openjdk@21"].opt_bin}/java" -jar "#{libexec}/graphus.jar" "$@"
    EOS
    chmod 0755, bin/"graphus"
  end

  test do
    output = shell_output("#{bin}/graphus --help")
    assert_match "Java/Spring code graph + RAG CLI", output
    assert_match "install", output
    assert_match "serve", output
  end
end
```

- [ ] **Step 2: Verify Ruby syntax locally**

```bash
ruby -c Formula/graphus.rb
```

Expected output: `Syntax OK`

- [ ] **Step 3: Commit**

```bash
git add Formula/graphus.rb
git commit -m "chore(homebrew): add Formula/graphus.rb as authoritative formula source"
```

---

### Task 2: Add formula syntax check to CI

**Files:**
- Modify: `.github/workflows/ci.yml` (insert after line 30, the Build step)

Adding `ruby -c` as a CI gate means formula syntax errors are caught on every PR, before they can corrupt the tap.

- [ ] **Step 1: Read the current ci.yml**

Read `/Users/leonardo.alcantara/work/workspaces/github/graphus_projects/graphus/.github/workflows/ci.yml` to confirm current content (it should end with a `Submit dependency graph` step at line 32–34).

- [ ] **Step 2: Insert the check step**

In `.github/workflows/ci.yml`, replace the `Submit dependency graph` step block:

```yaml
      - name: Submit dependency graph
        if: github.event_name == 'push' && github.ref == 'refs/heads/main'
        uses: gradle/actions/dependency-submission@v4
```

with:

```yaml
      - name: Validate formula syntax
        run: ruby -c Formula/graphus.rb

      - name: Submit dependency graph
        if: github.event_name == 'push' && github.ref == 'refs/heads/main'
        uses: gradle/actions/dependency-submission@v4
```

- [ ] **Step 3: Verify the final ci.yml looks correct**

The build job steps should now be, in order:
1. Checkout
2. Set up Java
3. Set up Gradle
4. Build
5. Validate formula syntax ← new
6. Submit dependency graph

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: validate Homebrew formula syntax on every build"
```

---

### Task 3: Rewrite the Homebrew tap update step in publish.yml

**Files:**
- Modify: `.github/workflows/publish.yml` (the `Update Homebrew tap formula` step, currently lines 43–76)

**Problems being fixed:**
- Silent `exit 0` when `HOMEBREW_TAP_TOKEN` is missing → changed to `exit 1` (hard failure)
- Re-downloads the JAR to compute SHA256 → use the JAR already in the workspace
- `perl -0pi` regex → `sed -i` on a copy of the in-workspace formula
- No Ruby syntax validation → `ruby -c` before pushing to tap

- [ ] **Step 1: Read the current publish.yml**

Read `.github/workflows/publish.yml` to confirm the exact text of the `Update Homebrew tap formula` step (currently lines 43–76).

- [ ] **Step 2: Replace the step**

In `.github/workflows/publish.yml`, replace the entire `Update Homebrew tap formula` step:

Old text (lines 43–76):
```yaml
      - name: Update Homebrew tap formula
        env:
          HOMEBREW_TAP_TOKEN: ${{ secrets.HOMEBREW_TAP_TOKEN }}
          HOMEBREW_TAP_REPO: ${{ vars.HOMEBREW_TAP_REPO }}
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          if [ -z "${HOMEBREW_TAP_TOKEN}" ]; then
            echo "HOMEBREW_TAP_TOKEN is not set; skipping Homebrew tap update."
            exit 0
          fi

          TAG="${{ github.event.release.tag_name }}"
          VERSION="${TAG#v}"
          TAP_REPO="${HOMEBREW_TAP_REPO:-alcantaraleo/homebrew-graphus}"

          echo "Downloading release asset graphus.jar for ${TAG}"
          gh release download "${TAG}" --repo "${{ github.repository }}" --pattern graphus.jar --dir . --clobber
          SHA256="$(shasum -a 256 graphus.jar | awk '{print $1}')"

          echo "Updating Formula/graphus.rb in ${TAP_REPO} with version ${VERSION}"
          git clone "https://x-access-token:${HOMEBREW_TAP_TOKEN}@github.com/${TAP_REPO}.git" tap-repo
          perl -0pi -e "s/version \"[^\"]+\"/version \"${VERSION}\"/g; s/sha256 \"[^\"]+\"/sha256 \"${SHA256}\"/g" tap-repo/Formula/graphus.rb

          cd tap-repo
          if git diff --quiet; then
            echo "Formula unchanged; nothing to commit."
            exit 0
          fi

          git config user.name "github-actions[bot]"
          git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
          git add Formula/graphus.rb
          git commit -m "graphus ${VERSION}"
          git push
```

New text:
```yaml
      - name: Update Homebrew tap formula
        env:
          HOMEBREW_TAP_TOKEN: ${{ secrets.HOMEBREW_TAP_TOKEN }}
          HOMEBREW_TAP_REPO: ${{ vars.HOMEBREW_TAP_REPO }}
        run: |
          if [ -z "${HOMEBREW_TAP_TOKEN}" ]; then
            echo "::error::HOMEBREW_TAP_TOKEN is not set — Homebrew tap will not be updated."
            exit 1
          fi

          TAG="${{ github.event.release.tag_name }}"
          VERSION="${TAG#v}"
          TAP_REPO="${HOMEBREW_TAP_REPO:-alcantaraleo/homebrew-graphus}"

          # Use the JAR already built in this job — no extra download needed.
          SHA256="$(shasum -a 256 graphus-cli/build/libs/graphus.jar | awk '{print $1}')"
          echo "Version: ${VERSION}  SHA256: ${SHA256}"

          # Render formula from main repo source (Formula/graphus.rb) into a temp file.
          cp Formula/graphus.rb /tmp/graphus.rb
          sed -i "s/version \"[^\"]*\"/version \"${VERSION}\"/" /tmp/graphus.rb
          sed -i "s/sha256 \"[^\"]*\"/sha256 \"${SHA256}\"/" /tmp/graphus.rb

          # Validate Ruby syntax before touching the tap.
          ruby -c /tmp/graphus.rb

          # Push rendered formula to tap.
          git clone "https://x-access-token:${HOMEBREW_TAP_TOKEN}@github.com/${TAP_REPO}.git" tap-repo
          cp /tmp/graphus.rb tap-repo/Formula/graphus.rb
          cd tap-repo
          if git diff --quiet; then
            echo "Formula unchanged; nothing to commit."
            exit 0
          fi
          git config user.name "github-actions[bot]"
          git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
          git add Formula/graphus.rb
          git commit -m "graphus ${VERSION}"
          git push
```

- [ ] **Step 3: Verify the change looks correct**

Read `.github/workflows/publish.yml` to confirm:
- The `GH_TOKEN` env var is no longer present in this step (we don't use `gh` anymore here)
- The `run` block now uses `shasum` against `graphus-cli/build/libs/graphus.jar`
- `ruby -c /tmp/graphus.rb` is present before the git push
- `exit 1` (not `exit 0`) for missing token

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/publish.yml
git commit -m "ci(publish): robustify Homebrew tap update — fail loudly, use in-workspace JAR, validate formula before push"
```

---

### Task 4: Update homebrew-graphus README

**Files:**
- Modify: `/Users/leonardo.alcantara/work/workspaces/github/graphus_projects/homebrew-graphus/README.md`

The tap README currently describes a manual publish checklist that is completely wrong — publishing is automated by CI in the main repo. Replace it with accurate docs.

- [ ] **Step 1: Read the current tap README**

Read `/Users/leonardo.alcantara/work/workspaces/github/graphus_projects/homebrew-graphus/README.md`.

- [ ] **Step 2: Replace the file content**

Overwrite with:

```markdown
# homebrew-graphus

Homebrew tap for the [Graphus](https://github.com/alcantaraleo/graphus) CLI.

## Install

```bash
brew tap alcantaraleo/graphus
brew install alcantaraleo/graphus/graphus
```

Upgrade:

```bash
brew update && brew upgrade alcantaraleo/graphus/graphus
```

## How this tap is managed

**Do not edit this repository by hand.**

The formula (`Formula/graphus.rb`) is managed automatically by the `publish.yml` workflow in
[`alcantaraleo/graphus`](https://github.com/alcantaraleo/graphus). On every GitHub Release:

1. `publish.yml` reads `Formula/graphus.rb` from the main repo (source of truth for formula logic).
2. It substitutes the new release version and `sha256` of `graphus.jar`.
3. It validates Ruby syntax (`ruby -c`).
4. It pushes the rendered formula here.

To change formula logic (install steps, test assertions, dependencies), open a PR against
`alcantaraleo/graphus` — not this repo.
```

- [ ] **Step 3: Commit to homebrew-graphus**

```bash
cd /Users/leonardo.alcantara/work/workspaces/github/graphus_projects/homebrew-graphus
git add README.md
git commit -m "docs: reflect automated publish pipeline from alcantaraleo/graphus"
git push
cd /Users/leonardo.alcantara/work/workspaces/github/graphus_projects/graphus
```

---

### Task 5: Update CLAUDE.md and README.md in main repo

**Files:**
- Modify: `CLAUDE.md` (lines 48–55)
- Modify: `README.md` (lines 140–152)

- [ ] **Step 1: Update CLAUDE.md Homebrew section**

In `CLAUDE.md`, replace the `### Homebrew release flow` section (lines 48–55):

```markdown
### Homebrew release flow

- Homebrew distribution uses a separate tap repository (default: `alcantaraleo/homebrew-graphus`).
- On GitHub Release publish, `.github/workflows/publish.yml` uploads release assets and updates the tap formula (`Formula/graphus.rb`) with:
  - release version (from tag `vX.Y.Z`)
  - computed `sha256` of `graphus.jar`
- Required secret: `HOMEBREW_TAP_TOKEN` with push access to the tap repository.
- Optional repository variable: `HOMEBREW_TAP_REPO` to override the default tap repository.
```

with:

```markdown
### Homebrew release flow

- The formula source of truth is `Formula/graphus.rb` in this repo. Edit formula logic here; never edit `alcantaraleo/homebrew-graphus` by hand.
- On GitHub Release publish, `.github/workflows/publish.yml`:
  1. Reads `Formula/graphus.rb` from the workspace.
  2. Substitutes the release version and `sha256` of the built `graphus.jar`.
  3. Validates Ruby syntax (`ruby -c`).
  4. Pushes the rendered formula to the `alcantaraleo/homebrew-graphus` tap (a CI-managed mirror).
- Required secret: `HOMEBREW_TAP_TOKEN` with push access to the tap repository. Missing token now causes a hard CI failure (not a silent skip).
- Optional repository variable: `HOMEBREW_TAP_REPO` to override the default tap repository.
```

- [ ] **Step 2: Update README.md Homebrew release automation section**

In `README.md`, replace the `### Homebrew release automation` section (lines 140–152):

```markdown
### Homebrew release automation

On every GitHub Release publish event, `.github/workflows/publish.yml`:

1. Uploads `graphus.jar` and `graphus` release assets.
2. Computes SHA-256 for `graphus.jar`.
3. Updates `Formula/graphus.rb` in the Homebrew tap repo (default `alcantaraleo/homebrew-graphus`) with the release version and checksum.
4. Commits and pushes the formula bump to the tap.

Required GitHub configuration:

- Repository secret: `HOMEBREW_TAP_TOKEN` (PAT with push access to the tap repository)
- Optional repository variable: `HOMEBREW_TAP_REPO` (override tap repo; default is `alcantaraleo/homebrew-graphus`)
```

with:

```markdown
### Homebrew release automation

The formula source of truth is `Formula/graphus.rb` in this repository.

On every GitHub Release publish event, `.github/workflows/publish.yml`:

1. Uploads `graphus.jar` and `graphus` release assets.
2. Reads `Formula/graphus.rb` from the workspace, substitutes the release version and `sha256`, and validates Ruby syntax.
3. Pushes the rendered formula to the tap repo (default `alcantaraleo/homebrew-graphus`), which is a CI-managed mirror — never edit it directly.

Required GitHub configuration:

- Repository secret: `HOMEBREW_TAP_TOKEN` (PAT with push access to the tap repository) — **required**; missing token now causes a hard CI failure.
- Optional repository variable: `HOMEBREW_TAP_REPO` (override tap repo; default is `alcantaraleo/homebrew-graphus`)
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md README.md
git commit -m "docs: update Homebrew release flow — formula now lives in Formula/graphus.rb"
```

---

## Self-Review

**Spec coverage:**
- Formula moved to main repo → Task 1 ✓
- CI validates formula on every PR → Task 2 ✓
- Publish workflow: hard failure on missing token → Task 3 ✓
- Publish workflow: no extra download, uses in-workspace JAR → Task 3 ✓
- Publish workflow: Ruby syntax validation before push → Task 3 ✓
- Tap README corrected → Task 4 ✓
- CLAUDE.md and README.md updated → Task 5 ✓

**Placeholder scan:** No TBDs, no "handle edge cases", all steps include exact code.

**Type consistency:** No shared types across tasks — each task is self-contained YAML/Ruby/Markdown edits.

**One gap addressed:** Task 1 adds `assert_match "serve", output` to the formula test. The `serve` command was added in the MCP server PR (#73) but the formula test still asserted only `install`. Now it asserts both.
