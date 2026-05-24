# CI/CD Integration

Graphus works best when the index reflects the current state of your codebase. Running it in CI ensures the index stays fresh automatically after every merge, without any developer having to remember to sync manually.

This page covers how to set up Graphus in the most common CI/CD platforms. The patterns are nearly identical across all of them — the differences are mostly syntax.

---

## Philosophy: What to Run and When

**Run `graphus index` on merge to your main branch.** A full re-index on every commit is unnecessary and expensive. Merge-triggered indexing keeps the canonical index current at the cadence that matters: when code actually lands.

**Use `--db sqlite --embedding local` in CI.** This requires no external services — no ChromaDB, no OpenAI API key, no Docker. It produces a single portable file (`.graphus/graphus.db`) that can be cached, committed, or uploaded as an artifact.

**Share the output with your team.** An index built in CI is only useful if developers can consume it. Two practical approaches:

- **Commit the DB file** — works well for smaller projects. Add `.graphus/graphus.db` to your repo (add `.graphus/checksums.json` and `.graphus/config.json` too). CI rebuilds and commits after each merge. Developers pull and the index is immediately available.
- **Upload as a CI artifact** — better for larger projects. CI uploads `.graphus/graphus.db` as a build artifact. Developers download it on demand or via a setup script.

---

## Installing Graphus in CI

### Option A: Download the JAR directly (recommended for CI)

The most portable option. Works on any runner with Java 21.

```bash
curl -sSL https://github.com/alcantaraleo/graphus/releases/latest/download/graphus.jar \
  -o graphus.jar

java -jar graphus.jar --version
```

### Option B: Homebrew (macOS runners)

```bash
brew tap alcantaraleo/homebrew-graphus
brew install graphus
graphus --version
```

---

## GitHub Actions

### Trigger on merge to `main`, cache the index

```yaml
name: Graphus Index

on:
  push:
    branches: [main]

jobs:
  index:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - name: Set up Java 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - name: Download Graphus
        run: |
          curl -sSL https://github.com/alcantaraleo/graphus/releases/latest/download/graphus.jar \
            -o graphus.jar

      - name: Restore index cache
        uses: actions/cache@v4
        with:
          path: .graphus
          key: graphus-index-${{ github.sha }}
          restore-keys: graphus-index-

      - name: Run Graphus index
        run: |
          java -jar graphus.jar index \
            --repo . \
            --db sqlite \
            --embedding local

      - name: Upload index artifact
        uses: actions/upload-artifact@v4
        with:
          name: graphus-index
          path: .graphus/graphus.db
          retention-days: 30
```

### Variant: commit the index back to the repository

If you prefer the index to live in the repo (simpler for small projects):

```yaml
      - name: Run Graphus index
        run: |
          java -jar graphus.jar index \
            --repo . \
            --db sqlite \
            --embedding local

      - name: Commit updated index
        run: |
          git config user.name "github-actions[bot]"
          git config user.email "github-actions[bot]@users.noreply.github.com"
          git add .graphus/graphus.db .graphus/config.json .graphus/checksums.json
          git diff --cached --quiet || git commit -m "chore: update graphus index [skip ci]"
          git push
```

> The `[skip ci]` suffix prevents the commit from re-triggering the workflow.

---

## GitLab CI

```yaml
graphus-index:
  stage: post-merge
  image: eclipse-temurin:21-jdk
  only:
    - main
  cache:
    key: graphus-index
    paths:
      - .graphus/
  script:
    - |
      curl -sSL https://github.com/alcantaraleo/graphus/releases/latest/download/graphus.jar \
        -o graphus.jar
    - |
      java -jar graphus.jar index \
        --repo . \
        --db sqlite \
        --embedding local
  artifacts:
    paths:
      - .graphus/graphus.db
    expire_in: 30 days
```

If your runner doesn't have outbound internet access for the download, pre-install the JAR in a custom Docker image and reference it with `image: your-registry/graphus-runner:21`.

---

## Bitbucket Pipelines

```yaml
pipelines:
  branches:
    main:
      - step:
          name: Graphus Index
          image: eclipse-temurin:21-jdk
          caches:
            - graphus-index
          script:
            - curl -sSL https://github.com/alcantaraleo/graphus/releases/latest/download/graphus.jar
                -o graphus.jar
            - java -jar graphus.jar index
                --repo .
                --db sqlite
                --embedding local
          artifacts:
            - .graphus/graphus.db

definitions:
  caches:
    graphus-index: .graphus
```

---

## Jenkins

```groovy
pipeline {
    agent any

    triggers {
        // Run on pushes to main only
        githubPush()
    }

    stages {
        stage('Graphus Index') {
            when {
                branch 'main'
            }
            steps {
                sh '''
                    curl -sSL https://github.com/alcantaraleo/graphus/releases/latest/download/graphus.jar \
                        -o graphus.jar

                    java -jar graphus.jar index \
                        --repo . \
                        --db sqlite \
                        --embedding local
                '''
            }
            post {
                success {
                    archiveArtifacts artifacts: '.graphus/graphus.db', fingerprint: true
                }
            }
        }
    }
}
```

Ensure the Jenkins agent has Java 21 available. If using a Docker agent, use `eclipse-temurin:21-jdk` as your base image.

---

## CircleCI

```yaml
version: 2.1

jobs:
  graphus-index:
    docker:
      - image: eclipse-temurin:21-jdk
    steps:
      - checkout
      - restore_cache:
          keys:
            - graphus-index-{{ .Branch }}-{{ .Revision }}
            - graphus-index-{{ .Branch }}-
            - graphus-index-
      - run:
          name: Download Graphus
          command: |
            curl -sSL https://github.com/alcantaraleo/graphus/releases/latest/download/graphus.jar \
              -o graphus.jar
      - run:
          name: Run Graphus index
          command: |
            java -jar graphus.jar index \
              --repo . \
              --db sqlite \
              --embedding local
      - save_cache:
          key: graphus-index-{{ .Branch }}-{{ .Revision }}
          paths:
            - .graphus
      - store_artifacts:
          path: .graphus/graphus.db
          destination: graphus-index

workflows:
  index-on-merge:
    jobs:
      - graphus-index:
          filters:
            branches:
              only: main
```

---

## Using Cloud Embeddings

If you prefer OpenAI embeddings over local ones, pass the API key via an environment secret:

```bash
java -jar graphus.jar index \
  --repo . \
  --db sqlite \
  --embedding openai \
  --openai-api-key "$OPENAI_API_KEY"
```

Store `OPENAI_API_KEY` as a repository secret (GitHub Actions), a CI/CD variable (GitLab), or a secure environment variable in your CI tool of choice. Never hardcode it.

Note: local embeddings (`--embedding local`) are sufficient for structural queries and call graph traversal. Cloud embeddings improve natural language search quality but are not required for the graph-based tools (`blast-radius`, `callers`, `callees`, `module-deps`, `hotspots`).

---

## Skipping CI on Index Commits

If you use the "commit back" pattern, make sure the index commit doesn't re-trigger your full CI pipeline. The standard mechanism is a `[skip ci]` tag in the commit message, which is recognised by GitHub Actions, GitLab CI, CircleCI, and most other platforms:

```bash
git commit -m "chore: update graphus index [skip ci]"
```

---

## .gitignore Considerations

If you choose **not** to commit the index, add the `.graphus/` directory to your `.gitignore`:

```
.graphus/
```

If you **do** commit the index, be selective — only commit the files you need and ignore the rest:

```
# Commit these
# .graphus/graphus.db
# .graphus/config.json
# .graphus/checksums.json

# Ignore snapshots (large, generated per-run)
.graphus/snapshots/
.graphus/co-changes.json
.graphus/ownership.json
```
