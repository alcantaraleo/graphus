package io.graphus.cli;

import io.graphus.cli.install.InstallCommand;
import io.graphus.indexer.EmbeddingBackend;
import io.graphus.indexer.EmbeddingModelFactory;
import io.graphus.indexer.FileChangeSet;
import io.graphus.indexer.FileChecksumRegistry;
import io.graphus.indexer.GraphIndexer;
import io.graphus.indexer.GraphSearchHit;
import io.graphus.indexer.SymbolChunkBuilder;
import io.graphus.model.CallGraph;
import io.graphus.model.WorkspaceDescriptor;
import io.graphus.parser.ProjectParser;
import io.graphus.parser.ProjectParserResult;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
        name = "graphus",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        subcommands = {
                GraphusCommand.IndexCommand.class,
                GraphusCommand.SyncCommand.class,
                GraphusCommand.QueryCommand.class,
                GraphusCommand.BlastRadiusCommand.class,
                InstallCommand.class
        },
        description = "Java/Spring code graph + RAG CLI"
)
public final class GraphusCommand implements Callable<Integer> {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new GraphusCommand()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    @Command(name = "index", description = "Full rebuild: clears the Chroma collection, re-parses all source files, and re-indexes all symbols")
    static final class IndexCommand implements Callable<Integer> {

        @Option(names = "--repo", defaultValue = ".", description = "Repository root path")
        private Path repositoryRoot;

        @Option(names = "--source", description = "Java source root; can be passed multiple times")
        private List<Path> sourceRoots = new ArrayList<>();

        @Option(names = "--collection", description = "Chroma collection name (default: top-level directory name of --repo)")
        private String collectionName;

        @Option(names = "--chroma-url", defaultValue = "http://localhost:8000", description = "Chroma base URL")
        private String chromaUrl;

        @Option(names = "--chroma-timeout", defaultValue = "300", description = "Chroma HTTP timeout in seconds")
        private int chromaTimeoutSeconds;

        @Option(names = "--batch-size", defaultValue = "500", description = "Number of symbols per embedding/index batch")
        private int batchSize;

        @Option(names = "--embedding", defaultValue = "local", description = "Embedding backend: local|openai")
        private String embeddingBackend;

        @Option(names = "--state-dir", description = "Directory where checksums.json is stored (default: .graphus per workspace)")
        private Path stateDir;

        @Override
        public Integer call() throws Exception {
            long totalStartNanos = System.nanoTime();
            Path repoRoot = repositoryRoot.toAbsolutePath().normalize();
            List<WorkspaceDescriptor> workspaces = CliWorkspaceLayouts.resolve(repoRoot, sourceRoots);

            int totalParsed = 0;
            int totalUnresolved = 0;
            int indexedSymbolsAccumulator = 0;

            ProjectParser projectParser = new ProjectParser();

            for (WorkspaceDescriptor workspace : workspaces) {
                String resolvedCollection =
                        CliWorkspaceLayouts.collectionName(collectionName, workspace, workspaces.size());
                Path resolvedStateDir =
                        CliWorkspaceLayouts.stateDirectory(stateDir, workspace, workspaces.size());
                List<Path> normalizedRoots = workspace.flattenedSourceRoots();

                var embeddingModel = new EmbeddingModelFactory().create(parseEmbeddingBackend(embeddingBackend));
                var embeddingStore = GraphIndexer.chromaStore(
                        chromaUrl,
                        resolvedCollection,
                        Duration.ofSeconds(Math.max(1, chromaTimeoutSeconds))
                );
                GraphIndexer graphIndexer =
                        new GraphIndexer(embeddingModel, embeddingStore, Math.max(1, batchSize));

                System.out.println(phase(
                        "Workspace [" + workspace.name() + "] collection=" + resolvedCollection));

                System.out.println(phase("Clearing existing index..."));
                long clearStartNanos = System.nanoTime();
                graphIndexer.removeAll();
                System.out.println(timing("Clear time      : ", formatElapsed(clearStartNanos)));

                long parseStartNanos = System.nanoTime();
                ParserProgressReporter reporter = new ParserProgressReporter();
                ProjectParserResult parseResult = projectParser.parse(workspace, reporter);
                reporter.complete();
                System.out.println(timing("Parse time      : ", formatElapsed(parseStartNanos)));

                long indexStartNanos = System.nanoTime();
                IndexProgressReporter indexReporter = new IndexProgressReporter();
                indexedSymbolsAccumulator += graphIndexer.index(parseResult.callGraph(), workspace, indexReporter);
                indexReporter.complete();
                System.out.println(timing("Index time      : ", formatElapsed(indexStartNanos)));

                System.out.println(phase("Saving checksum registry..."));
                long checksumStartNanos = System.nanoTime();
                FileChecksumRegistry registry = FileChecksumRegistry.empty();
                registry.recomputeAll(workspace.root(), normalizedRoots);
                registry.save(resolvedStateDir);
                System.out.println(timing("Checksum time   : ", formatElapsed(checksumStartNanos)));

                totalParsed += parseResult.parsedFiles();
                totalUnresolved += parseResult.unresolvedCalls();

                System.out.println(summary(
                        "Registry saved  : ", resolvedStateDir.resolve("checksums.json").toString()));
                System.out.println(Ansi.style("---", Ansi.DIM));
            }

            System.out.println(summary("Parsed files    : ", Integer.toString(totalParsed)));
            System.out.println(summary("Unresolved calls: ", Integer.toString(totalUnresolved)));
            System.out.println(summary(
                    "Indexed symbols : ",
                    Integer.toString(indexedSymbolsAccumulator)));
            System.out.println(summary("Total time      : ", formatElapsed(totalStartNanos)));
            return 0;
        }
    }

    @Command(name = "sync", description = "Incremental update: re-indexes only files that were added, modified, or deleted since the last index/sync")
    static final class SyncCommand implements Callable<Integer> {

        @Option(names = "--repo", defaultValue = ".", description = "Repository root path")
        private Path repositoryRoot;

        @Option(names = "--source", description = "Java source root; can be passed multiple times")
        private List<Path> sourceRoots = new ArrayList<>();

        @Option(names = "--collection", description = "Chroma collection name (default: top-level directory name of --repo)")
        private String collectionName;

        @Option(names = "--chroma-url", defaultValue = "http://localhost:8000", description = "Chroma base URL")
        private String chromaUrl;

        @Option(names = "--chroma-timeout", defaultValue = "300", description = "Chroma HTTP timeout in seconds")
        private int chromaTimeoutSeconds;

        @Option(names = "--batch-size", defaultValue = "500", description = "Number of symbols per embedding/index batch")
        private int batchSize;

        @Option(names = "--embedding", defaultValue = "local", description = "Embedding backend: local|openai")
        private String embeddingBackend;

        @Option(names = "--state-dir", description = "Directory where checksums.json is stored (default: .graphus per workspace)")
        private Path stateDir;

        @Override
        public Integer call() throws Exception {
            long totalStartNanos = System.nanoTime();
            Path repoRoot = repositoryRoot.toAbsolutePath().normalize();
            List<WorkspaceDescriptor> workspaces = CliWorkspaceLayouts.resolve(repoRoot, sourceRoots);

            int indexedSymbolsAccumulator = 0;

            ProjectParser projectParser = new ProjectParser();

            for (WorkspaceDescriptor workspace : workspaces) {
                String resolvedCollection =
                        CliWorkspaceLayouts.collectionName(collectionName, workspace, workspaces.size());
                Path resolvedStateDir =
                        CliWorkspaceLayouts.stateDirectory(stateDir, workspace, workspaces.size());

                List<Path> normalizedRoots = workspace.flattenedSourceRoots();

                if (!FileChecksumRegistry.exists(resolvedStateDir)) {
                    System.err.println(
                            error(
                                    "No index found for workspace ["
                                            + workspace.name()
                                            + "]. Run 'graphus index' first."));
                    return 1;
                }

                System.out.println(phase(
                        "Workspace [" + workspace.name() + "] collection=" + resolvedCollection));

                System.out.println(phase("Loading checksum registry..."));
                long loadRegistryStartNanos = System.nanoTime();
                FileChecksumRegistry registry = FileChecksumRegistry.load(resolvedStateDir);
                System.out.println(timing("Load time       : ", formatElapsed(loadRegistryStartNanos)));

                System.out.println(phase("Scanning source files for changes..."));
                long scanStartNanos = System.nanoTime();
                FileChangeSet changes = registry.diffAndUpdate(workspace.root(), normalizedRoots);
                System.out.println(timing("Scan time       : ", formatElapsed(scanStartNanos)));

                int totalFiles = FileChecksumRegistry.discoverJavaFiles(normalizedRoots).size();
                System.out.println(summary("Files scanned   : ", Integer.toString(totalFiles)));
                System.out.println(summary("Added           : ", Integer.toString(changes.added().size())));
                System.out.println(summary("Modified        : ", Integer.toString(changes.modified().size())));
                System.out.println(summary("Deleted         : ", Integer.toString(changes.deleted().size())));

                if (!changes.hasChanges()) {
                    System.out.println(phase("Nothing to sync. Index is up to date."));
                    System.out.println(Ansi.style("---", Ansi.DIM));
                    continue;
                }

                var embeddingModel = new EmbeddingModelFactory().create(parseEmbeddingBackend(embeddingBackend));
                var embeddingStore = GraphIndexer.chromaStore(
                        chromaUrl,
                        resolvedCollection,
                        Duration.ofSeconds(Math.max(1, chromaTimeoutSeconds))
                );
                GraphIndexer graphIndexer =
                        new GraphIndexer(embeddingModel, embeddingStore, Math.max(1, batchSize));

                long removeStartNanos = System.nanoTime();
                int removedFiles = 0;
                for (String filePath : changes.modified()) {
                    graphIndexer.removeByFile(filePath);
                    removedFiles++;
                }
                for (String filePath : changes.deleted()) {
                    graphIndexer.removeByFile(filePath);
                    removedFiles++;
                }
                System.out.println(summary("Files removed from index: ", Integer.toString(removedFiles)));
                System.out.println(timing("Remove time     : ", formatElapsed(removeStartNanos)));

                Set<String> toReindex = changes.toReindex();
                int indexedThisWorkspace = 0;
                if (!toReindex.isEmpty()) {
                    long parseStartNanos = System.nanoTime();
                    ParserProgressReporter reporter = new ParserProgressReporter();
                    ProjectParserResult parseResult = projectParser.parse(workspace, reporter);
                    reporter.complete();
                    System.out.println(timing("Parse time      : ", formatElapsed(parseStartNanos)));

                    long indexStartNanos = System.nanoTime();
                    IndexProgressReporter indexReporter = new IndexProgressReporter();
                    if (workspace.isMultiModule()) {
                        Map<String, Set<String>> byModule = filesGroupedByModuleTag(workspace, toReindex);
                        for (Map.Entry<String, Set<String>> entry : byModule.entrySet()) {
                            indexedThisWorkspace += graphIndexer.indexForFiles(
                                    parseResult.callGraph(),
                                    workspace,
                                    entry.getValue(),
                                    indexReporter);
                        }
                    } else {
                        indexedThisWorkspace +=
                                graphIndexer.indexForFiles(
                                        parseResult.callGraph(),
                                        toReindex,
                                        indexReporter);
                    }
                    indexReporter.complete();
                    System.out.println(timing("Index time      : ", formatElapsed(indexStartNanos)));
                }

                System.out.println(phase("Saving updated checksum registry..."));
                long checksumStartNanos = System.nanoTime();
                registry.save(resolvedStateDir);
                System.out.println(timing("Checksum time   : ", formatElapsed(checksumStartNanos)));

                indexedSymbolsAccumulator += indexedThisWorkspace;

                System.out.println(
                        summary("Symbols indexed : ", Integer.toString(indexedThisWorkspace)));
                System.out.println(Ansi.style("---", Ansi.DIM));
            }

            System.out.println(summary(
                    "Total symbols indexed across workspaces: ",
                    Integer.toString(indexedSymbolsAccumulator)));
            System.out.println(summary("Total time      : ", formatElapsed(totalStartNanos)));
            return 0;
        }
    }

    @Command(name = "query", description = "Query indexed graph symbols")
    static final class QueryCommand implements Callable<Integer> {

        @Parameters(paramLabel = "QUESTION", description = "Natural language query")
        private String question;

        @Option(names = "--collection", description = "Chroma collection name (default: current directory name)")
        private String collectionName;

        @Option(names = "--chroma-url", defaultValue = "http://localhost:8000", description = "Chroma base URL")
        private String chromaUrl;

        @Option(names = "--embedding", defaultValue = "local", description = "Embedding backend: local|openai")
        private String embeddingBackend;

        @Option(names = "--top-k", defaultValue = "10", description = "Number of hits")
        private int topK;

        @Option(names = "--module", description = "Restrict hits to embeddings tagged with this module name")
        private String moduleFilter;

        @Override
        public Integer call() {
            String resolvedCollectionName = (collectionName != null && !collectionName.isBlank())
                    ? collectionName
                    : Paths.get("").toAbsolutePath().getFileName().toString();
            var embeddingModel = new EmbeddingModelFactory().create(parseEmbeddingBackend(embeddingBackend));
            var embeddingStore = GraphIndexer.chromaStore(chromaUrl, resolvedCollectionName);
            GraphIndexer graphIndexer = new GraphIndexer(embeddingModel, embeddingStore);
            String module = moduleFilter == null || moduleFilter.isBlank() ? null : moduleFilter.strip();
            List<GraphSearchHit> hits = graphIndexer.query(question, module, topK);

            if (hits.isEmpty()) {
                System.out.println(phase("No results."));
                if (module != null) {
                    System.err.println(error(
                            "No results for module='" + module
                                    + "'. This collection may not have been indexed with module tags."));
                }
                return 0;
            }

            int index = 1;
            for (GraphSearchHit hit : hits) {
                String header = Ansi.style("[" + index + "]", Ansi.BOLD, Ansi.CYAN)
                        + " score="
                        + Ansi.style(Double.toString(hit.score()), Ansi.YELLOW);
                System.out.println(header);
                System.out.println(hit.text());
                if (!hit.metadata().isEmpty()) {
                    System.out.println("metadata: " + Ansi.style(hit.metadata().toString(), Ansi.DIM));
                }
                System.out.println(Ansi.style("---", Ansi.DIM));
                index++;
            }
            return 0;
        }
    }

    @Command(name = "blast-radius", description = "Find callers that can impact a symbol")
    static final class BlastRadiusCommand implements Callable<Integer> {

        @Parameters(paramLabel = "TARGET_SYMBOL", description = "Fully qualified method signature")
        private String targetSymbol;

        @Option(names = "--repo", defaultValue = ".", description = "Repository root path")
        private Path repositoryRoot;

        @Option(names = "--source", description = "Java source root; can be passed multiple times")
        private List<Path> sourceRoots = new ArrayList<>();

        @Option(names = "--depth", defaultValue = "3", description = "Traversal depth")
        private int depth;

        @Override
        public Integer call() throws Exception {
            Path repoRoot = repositoryRoot.toAbsolutePath().normalize();
            List<WorkspaceDescriptor> workspaces = CliWorkspaceLayouts.resolve(repoRoot, sourceRoots);
            if (workspaces.size() != 1) {
                System.err.println(
                        error(
                                "Blast radius requires exactly one analysable workspace. Found "
                                        + workspaces.size()
                                        + ". Narrow --repo, use --source, or run from inside a single project."));
                return 1;
            }
            WorkspaceDescriptor workspaceContext = workspaces.get(0);

            ProjectParser projectParser = new ProjectParser();
            ParserProgressReporter reporter = new ParserProgressReporter();
            ProjectParserResult parseResult = projectParser.parse(workspaceContext, reporter);
            reporter.complete();
            CallGraph callGraph = parseResult.callGraph();

            String resolvedTarget = resolveTargetSymbol(callGraph, targetSymbol);
            if (resolvedTarget == null) {
                System.out.println(error("Target symbol not found: " + targetSymbol));
                return 1;
            }

            List<String> callers = callGraph.blastRadiusCallers(resolvedTarget, depth);
            System.out.println(Ansi.style("Target:", Ansi.BOLD) + " " + Ansi.style(resolvedTarget, Ansi.CYAN));
            System.out.println(Ansi.style("Depth:", Ansi.BOLD) + " " + Ansi.style(Integer.toString(depth), Ansi.YELLOW));
            if (callers.isEmpty()) {
                System.out.println(phase("No callers found in blast radius."));
                return 0;
            }
            System.out.println(Ansi.style("Callers:", Ansi.BOLD));
            for (String caller : callers) {
                System.out.println(Ansi.style("-", Ansi.GREEN) + " " + Ansi.style(caller, Ansi.GREEN));
            }
            return 0;
        }

        private String resolveTargetSymbol(CallGraph callGraph, String input) {
            if (callGraph.getNode(input) != null) {
                return input;
            }
            return callGraph.getNodes().stream()
                    .map(node -> node.getId())
                    .filter(id -> id.contains(input))
                    .findFirst()
                    .orElse(null);
        }
    }

    /** Groups repository-relative paths by {@code module} embedding tag assignment. */
    private static Map<String, Set<String>> filesGroupedByModuleTag(
            WorkspaceDescriptor workspaceDescriptor, Iterable<String> filePaths) {
        Map<String, Set<String>> accumulator = new LinkedHashMap<>();
        for (String filePath : filePaths) {
            String moduleTag =
                    SymbolChunkBuilder.resolveModuleMetadataTag(filePath, workspaceDescriptor);
            accumulator.computeIfAbsent(moduleTag, ignored -> new HashSet<>()).add(filePath);
        }
        return accumulator;
    }

    private static EmbeddingBackend parseEmbeddingBackend(String value) {
        String normalized = value == null ? "local" : value.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "openai" -> EmbeddingBackend.OPENAI;
            case "local" -> EmbeddingBackend.LOCAL;
            default -> throw new CommandLine.ParameterException(
                    new CommandLine(new GraphusCommand()),
                    "Unsupported embedding backend: " + value + ". Use local or openai."
            );
        };
    }

    private static String formatElapsed(long startNanos) {
        return formatDuration(Duration.ofNanos(System.nanoTime() - startNanos));
    }

    private static String phase(String value) {
        return Ansi.style(value, Ansi.BOLD);
    }

    private static String timing(String label, String value) {
        return label + Ansi.style(value, Ansi.YELLOW);
    }

    private static String summary(String label, String value) {
        return label + Ansi.style(value, Ansi.BOLD, Ansi.GREEN);
    }

    private static String error(String value) {
        return Ansi.style(value, Ansi.BOLD, Ansi.RED);
    }

    private static String formatDuration(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();

        if (hours > 0) {
            return "%dh %02dm %02ds".formatted(hours, minutes, seconds);
        }
        return "%dm %02ds".formatted(minutes, seconds);
    }
}
