package io.graphus.cli;

import io.graphus.cli.mcp.GraphusMcpContext;
import io.graphus.cli.mcp.GraphusMcpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "serve",
        description = "Start a Graphus MCP server over STDIO for use with AI coding agents (Claude Code, Cursor, Copilot)")
public final class ServeCommand implements Callable<Integer> {

    @Option(names = "--repo", defaultValue = ".", description = "Repository root path")
    private Path repositoryRoot;

    @Option(names = "--source", description = "Java or Kotlin source root; can be passed multiple times")
    private List<Path> sourceRoots = new ArrayList<>();

    @Option(names = "--state-dir", description = "Graphus state directory (default: .graphus)")
    private Path stateDir;

    @Option(names = "--db", description = "Vector store backend: chroma|sqlite")
    private String db;

    @Option(names = "--db-url", description = "Chroma base URL")
    private String dbUrl;

    @Option(names = "--db-timeout", description = "Chroma HTTP timeout in seconds")
    private Integer dbTimeoutSeconds;

    @Option(names = "--db-file", description = "SQLite database file path")
    private String dbFile;

    @Option(names = "--embedding", description = "Embedding backend: local|openai")
    private String embeddingBackend;

    @Override
    public Integer call() throws Exception {
        Path repoRoot = repositoryRoot.toAbsolutePath().normalize();
        System.err.println("[graphus-mcp] Loading context from: " + repoRoot);

        GraphusMcpContext ctx = GraphusMcpContext.load(
                repoRoot, sourceRoots, stateDir, db, dbUrl, dbTimeoutSeconds, dbFile, embeddingBackend);

        String version = resolveVersion();

        System.err.println("[graphus-mcp] Starting MCP server v" + version + " on STDIO...");

        McpSyncServer server = new GraphusMcpServer(ctx).build(version);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("[graphus-mcp] Shutting down.");
            server.close();
        }));

        return 0;
    }

    private static String resolveVersion() {
        Properties props = new Properties();
        try (InputStream in = VersionProvider.class.getResourceAsStream("version.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException ignored) {
            // fall through to default
        }
        return props.getProperty("version", "unknown");
    }
}
