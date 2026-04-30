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
                gradle :graphus-cli:run --args='index --repo . --source src/main/java --collection <collection>'
                ```

                Use when there is no previous index or when a full rebuild is requested.

                ### Incremental Sync

                ```bash
                gradle :graphus-cli:run --args='sync --repo . --source src/main/java --collection <collection>'
                ```

                Use after code changes to update only added, modified, and deleted files.

                ### Query

                ```bash
                gradle :graphus-cli:run --args='query "<question>" --collection <collection> --top-k 10'
                ```

                Use for natural language retrieval over indexed symbols.

                ### Blast Radius

                ```bash
                gradle :graphus-cli:run --args='blast-radius "<target_symbol>" --repo . --source src/main/java --depth 3'
                ```

                Use to identify callers that can impact a target symbol.

                ## Operating Guidelines

                - Ask for the collection name if the user did not provide one.
                - If `.graphus/checksums.json` is missing, run `index` before `sync`.
                - Keep embedding backend consistent between index/sync/query for the same collection.
                - Surface key command output to the user instead of dumping raw logs.
                """.formatted(repoPath);
    }
}
