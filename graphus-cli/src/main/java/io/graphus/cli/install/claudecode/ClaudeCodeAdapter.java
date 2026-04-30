package io.graphus.cli.install.claudecode;

import io.graphus.cli.install.ToolAdapter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ClaudeCodeAdapter implements ToolAdapter {

    private static final String SECTION_START = "<!-- graphus:start -->";
    private static final String SECTION_END = "<!-- graphus:end -->";

    @Override
    public String name() {
        return "claude-code";
    }

    @Override
    public String displayName() {
        return "Claude Code";
    }

    @Override
    public void install(Path projectDir) throws IOException {
        Path commandsDir = projectDir.resolve(".claude").resolve("commands");
        Files.createDirectories(commandsDir);

        write(commandsDir.resolve("graphus-index.md"), indexCommand());
        write(commandsDir.resolve("graphus-sync.md"), syncCommand());
        write(commandsDir.resolve("graphus-query.md"), queryCommand());
        write(commandsDir.resolve("graphus-blast-radius.md"), blastRadiusCommand());

        Path claudeMd = projectDir.resolve("CLAUDE.md");
        upsertGraphusSection(claudeMd);
    }

    private static void write(Path path, String content) throws IOException {
        Files.writeString(path, content);
    }

    private static void upsertGraphusSection(Path claudeMdPath) throws IOException {
        String newSection = graphusSection();
        if (!Files.exists(claudeMdPath)) {
            write(claudeMdPath, newSection);
            return;
        }

        String existing = Files.readString(claudeMdPath);
        int start = existing.indexOf(SECTION_START);
        int end = existing.indexOf(SECTION_END);
        if (start >= 0 && end > start) {
            int endInclusive = end + SECTION_END.length();
            String updated = existing.substring(0, start) + newSection + existing.substring(endInclusive);
            write(claudeMdPath, updated);
            return;
        }

        String separator = existing.endsWith("\n") ? "\n" : "\n\n";
        write(claudeMdPath, existing + separator + newSection);
    }

    private static String graphusSection() {
        return """
                <!-- graphus:start -->
                ## Graphus

                Graphus slash commands are available in `.claude/commands`:

                - `/project:graphus-index`
                - `/project:graphus-sync`
                - `/project:graphus-query`
                - `/project:graphus-blast-radius`

                Use these commands to index Java/Spring code, sync incremental changes, query indexed symbols, and estimate blast radius from callers.
                <!-- graphus:end -->
                """;
    }

    private static String indexCommand() {
        return """
                ---
                description: Build a full Graphus index for the repository
                ---

                Run a full Graphus index for the current repository.

                Steps:
                1. If needed, ask for the Chroma collection name.
                2. Run:
                   `gradle :graphus-cli:run --args='index --repo . --source src/main/java --collection <collection>'`
                3. Share parsed files, unresolved calls, and indexed symbols from command output.
                """;
    }

    private static String syncCommand() {
        return """
                ---
                description: Sync Graphus index incrementally after code changes
                ---

                Run Graphus incremental sync for this repository.

                Steps:
                1. If `.graphus/checksums.json` does not exist, run `/project:graphus-index` first.
                2. If needed, ask for the Chroma collection name.
                3. Run:
                   `gradle :graphus-cli:run --args='sync --repo . --source src/main/java --collection <collection>'`
                4. Share counts for added, modified, deleted, and indexed symbols.
                """;
    }

    private static String queryCommand() {
        return """
                ---
                description: Query Graphus indexed symbols with a natural language question
                ---

                Query Graphus with the user question from `$ARGUMENTS`.

                Steps:
                1. If `$ARGUMENTS` is empty, ask for the question to search.
                2. If needed, ask for the Chroma collection name.
                3. Run:
                   `gradle :graphus-cli:run --args='query "$ARGUMENTS" --collection <collection> --top-k 10'`
                4. Summarize the top results and their metadata.
                """;
    }

    private static String blastRadiusCommand() {
        return """
                ---
                description: Compute caller blast radius for a target symbol
                ---

                Run Graphus blast radius using the target symbol from `$ARGUMENTS`.

                Steps:
                1. If `$ARGUMENTS` is empty, ask for the target symbol (fully qualified method signature or substring).
                2. Run:
                   `gradle :graphus-cli:run --args='blast-radius "$ARGUMENTS" --repo . --source src/main/java --depth 3'`
                3. Share the resolved target and caller list.
                """;
    }
}
