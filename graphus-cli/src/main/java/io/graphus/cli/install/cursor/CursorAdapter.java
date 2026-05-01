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
                - Chroma URL default: `http://localhost:8000`
                - Embedding default: `local`

                ## Commands

                ### Full Index

                ```bash
                graphus index --repo . --source src/main/java --batch-size 500 --chroma-timeout 300
                ```

                Use when there is no previous index or when a full rebuild is requested. `--collection` is optional and defaults to the repository directory name.

                ### Incremental Sync

                ```bash
                graphus sync --repo . --source src/main/java --batch-size 500 --chroma-timeout 300
                ```

                Use after code changes to update only added, modified, and deleted files. `--collection` is optional and defaults to the repository directory name.

                ### Query

                ```bash
                graphus query "<question>" --top-k 10
                ```

                Use for natural language retrieval over indexed symbols. `--collection` is optional and defaults to the current directory name.

                ### Blast Radius

                ```bash
                graphus blast-radius "<target_symbol>" --repo . --source src/main/java --depth 3
                ```

                Use to identify callers that can impact a target symbol.

                ## Operating Guidelines

                - `--collection` defaults to the repository/current directory name and can be omitted in most cases. Pass it explicitly only when targeting a collection with a different name.
                - If `.graphus/checksums.json` is missing, run `index` before `sync`.
                - Keep embedding backend consistent between index/sync/query for the same collection.
                - Surface key command output to the user instead of dumping raw logs.
                """.formatted(repoPath);
    }
}
