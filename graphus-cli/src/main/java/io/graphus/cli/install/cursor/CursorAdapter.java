package io.graphus.cli.install.cursor;

import io.graphus.cli.install.ToolAdapter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CursorAdapter implements ToolAdapter {

    @Override
    public String name() {
        return "cursor";
    }

    @Override
    public String displayName() {
        return "Cursor";
    }

    @Override
    public void install(Path projectDir) throws IOException {
        Path ruleFile = projectDir.resolve(".cursor").resolve("rules").resolve("graphus.mdc");
        Files.createDirectories(ruleFile.getParent());
        Files.writeString(ruleFile, ruleContent(projectDir));
    }

    private static String ruleContent(Path projectDir) {
        String repoPath = projectDir.toAbsolutePath().normalize().toString();
        return """
                ---
                description: Use Graphus commands for code graph indexing and analysis
                alwaysApply: false
                ---

                # Graphus Commands

                Graphus is installed for this repository. Prefer using Graphus commands when the user asks for Java/Spring code graph indexing, semantic symbol search, or blast radius analysis.

                ## Repository Defaults

                - Repository path: `%s`
                - `.graphus/config.json` persists the vector backend (`--db chroma|sqlite`), Chroma tuning, optional SQLite `--db-file`, embedding choice, and the resolved `--collection`.

                ## Commands

                ### Full Index

                ```bash
                graphus index --repo . --source src/main/java --batch-size 500 --db sqlite
                ```

                Add `--db chroma`, `--db-url`, `--db-timeout`, or `--db-file` explicitly when Dockerized Chroma is not on `localhost:8000` or when relocating the SQLite embeddings file.

                Use when there is no previous index or when a full rebuild is requested.

                ### Incremental Sync

                ```bash
                graphus sync --repo . --source src/main/java --batch-size 500
                ```

                After the first successful `index`, this reads `.graphus/config.json` unless you intentionally override `--db*` on the CLI.

                ### Query

                ```bash
                graphus query "<question>" --top-k 10
                ```

                Uses `.graphus/config.json` (`--state-dir` defaults to `./.graphus`). Pass `--collection` only when you need a different embeddings namespace than recorded in the workspace state.

                ### Blast Radius

                ```bash
                graphus blast-radius "<target_symbol>" --repo . --source src/main/java --depth 3
                ```

                Use to identify callers that can impact a target symbol.

                ## Operating Guidelines

                - `--collection` defaults to the repository/current directory name and can be omitted in most cases. Pass it explicitly only when targeting a collection with a different name.
                - If `.graphus/checksums.json` is missing, run `index` before `sync`.
                - Keep embedding + vector backends consistent between `index`/`sync`/`query` unless you intentionally reconfigure them.
                - Surface key command output to the user instead of dumping raw logs.
                """.formatted(repoPath);
    }
}
