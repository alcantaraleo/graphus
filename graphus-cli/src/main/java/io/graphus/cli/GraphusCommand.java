package io.graphus.cli;

import io.graphus.cli.install.InstallCommand;
import io.graphus.indexer.EmbeddingBackend;
import io.graphus.indexer.EmbeddingModelFactory;
import io.graphus.indexer.FileChangeSet;
import io.graphus.indexer.FileChecksumRegistry;
import io.graphus.indexer.GraphIndexer;
import io.graphus.indexer.GraphSearchHit;
import io.graphus.model.CallGraph;
import io.graphus.parser.ProjectParser;
import io.graphus.parser.ProjectParserResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
        name = "graphus",
        mixinStandardHelpOptions = true,
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

        @Option(names = "--collection", required = true, description = "Chroma collection name")
        private String collectionName;

        @Option(names = "--chroma-url", defaultValue = "http://localhost:8000", description = "Chroma base URL")
        private String chromaUrl;

        @Option(names = "--embedding", defaultValue = "local", description = "Embedding backend: local|openai")
        private String embeddingBackend;

        @Option(names = "--state-dir", description = "Directory where checksums.json is stored (default: {repo}/.graphus)")
        private Path stateDir;

        @Override
        public Integer call() throws Exception {
            Path repoRoot = repositoryRoot.toAbsolutePath().normalize();
            Path resolvedStateDir = stateDir != null ? stateDir : repoRoot.resolve(".graphus");
            List<Path> normalizedSourceRoots = ProjectParser.resolveSourceRoots(repoRoot, sourceRoots);

            var embeddingModel = new EmbeddingModelFactory().create(parseEmbeddingBackend(embeddingBackend));
            var embeddingStore = GraphIndexer.chromaStore(chromaUrl, collectionName);
            GraphIndexer graphIndexer = new GraphIndexer(embeddingModel, embeddingStore);

            System.out.println("Clearing existing index...");
            graphIndexer.removeAll();

            System.out.println("Parsing source files...");
            ProjectParserResult parseResult = new ProjectParser().parse(repoRoot, sourceRoots);

            System.out.println("Indexing symbols...");
            int indexedSymbols = graphIndexer.index(parseResult.callGraph());

            System.out.println("Saving checksum registry...");
            FileChecksumRegistry registry = FileChecksumRegistry.empty();
            registry.recomputeAll(repoRoot, normalizedSourceRoots);
            registry.save(resolvedStateDir);

            System.out.println("Parsed files    : " + parseResult.parsedFiles());
            System.out.println("Unresolved calls: " + parseResult.unresolvedCalls());
            System.out.println("Indexed symbols : " + indexedSymbols);
            System.out.println("Registry saved  : " + resolvedStateDir.resolve("checksums.json"));
            return 0;
        }
    }

    @Command(name = "sync", description = "Incremental update: re-indexes only files that were added, modified, or deleted since the last index/sync")
    static final class SyncCommand implements Callable<Integer> {

        @Option(names = "--repo", defaultValue = ".", description = "Repository root path")
        private Path repositoryRoot;

        @Option(names = "--source", description = "Java source root; can be passed multiple times")
        private List<Path> sourceRoots = new ArrayList<>();

        @Option(names = "--collection", required = true, description = "Chroma collection name")
        private String collectionName;

        @Option(names = "--chroma-url", defaultValue = "http://localhost:8000", description = "Chroma base URL")
        private String chromaUrl;

        @Option(names = "--embedding", defaultValue = "local", description = "Embedding backend: local|openai")
        private String embeddingBackend;

        @Option(names = "--state-dir", description = "Directory where checksums.json is stored (default: {repo}/.graphus)")
        private Path stateDir;

        @Override
        public Integer call() throws Exception {
            Path repoRoot = repositoryRoot.toAbsolutePath().normalize();
            Path resolvedStateDir = stateDir != null ? stateDir : repoRoot.resolve(".graphus");
            List<Path> normalizedSourceRoots = ProjectParser.resolveSourceRoots(repoRoot, sourceRoots);

            if (!FileChecksumRegistry.exists(resolvedStateDir)) {
                System.err.println("No index found. Run 'graphus index' first.");
                return 1;
            }

            System.out.println("Loading checksum registry...");
            FileChecksumRegistry registry = FileChecksumRegistry.load(resolvedStateDir);

            System.out.println("Scanning source files for changes...");
            FileChangeSet changes = registry.diffAndUpdate(repoRoot, normalizedSourceRoots);

            int totalFiles = FileChecksumRegistry.discoverJavaFiles(normalizedSourceRoots).size();
            System.out.println("Files scanned   : " + totalFiles);
            System.out.println("Added           : " + changes.added().size());
            System.out.println("Modified        : " + changes.modified().size());
            System.out.println("Deleted         : " + changes.deleted().size());

            if (!changes.hasChanges()) {
                System.out.println("Nothing to sync. Index is up to date.");
                return 0;
            }

            var embeddingModel = new EmbeddingModelFactory().create(parseEmbeddingBackend(embeddingBackend));
            var embeddingStore = GraphIndexer.chromaStore(chromaUrl, collectionName);
            GraphIndexer graphIndexer = new GraphIndexer(embeddingModel, embeddingStore);

            int removedFiles = 0;
            for (String filePath : changes.modified()) {
                graphIndexer.removeByFile(filePath);
                removedFiles++;
            }
            for (String filePath : changes.deleted()) {
                graphIndexer.removeByFile(filePath);
                removedFiles++;
            }
            System.out.println("Files removed from index: " + removedFiles);

            Set<String> toReindex = changes.toReindex();
            int indexedSymbols = 0;
            if (!toReindex.isEmpty()) {
                System.out.println("Parsing source files for re-indexing...");
                ProjectParserResult parseResult = new ProjectParser().parse(repoRoot, sourceRoots);
                System.out.println("Indexing changed symbols...");
                indexedSymbols = graphIndexer.indexForFiles(parseResult.callGraph(), toReindex);
            }

            System.out.println("Saving updated checksum registry...");
            registry.save(resolvedStateDir);

            System.out.println("Symbols indexed : " + indexedSymbols);
            return 0;
        }
    }

    @Command(name = "query", description = "Query indexed graph symbols")
    static final class QueryCommand implements Callable<Integer> {

        @Parameters(paramLabel = "QUESTION", description = "Natural language query")
        private String question;

        @Option(names = "--collection", required = true, description = "Chroma collection name")
        private String collectionName;

        @Option(names = "--chroma-url", defaultValue = "http://localhost:8000", description = "Chroma base URL")
        private String chromaUrl;

        @Option(names = "--embedding", defaultValue = "local", description = "Embedding backend: local|openai")
        private String embeddingBackend;

        @Option(names = "--top-k", defaultValue = "10", description = "Number of hits")
        private int topK;

        @Override
        public Integer call() {
            var embeddingModel = new EmbeddingModelFactory().create(parseEmbeddingBackend(embeddingBackend));
            var embeddingStore = GraphIndexer.chromaStore(chromaUrl, collectionName);
            GraphIndexer graphIndexer = new GraphIndexer(embeddingModel, embeddingStore);
            List<GraphSearchHit> hits = graphIndexer.query(question, topK);

            if (hits.isEmpty()) {
                System.out.println("No results.");
                return 0;
            }

            int index = 1;
            for (GraphSearchHit hit : hits) {
                System.out.println("[" + index + "] score=" + hit.score());
                System.out.println(hit.text());
                if (!hit.metadata().isEmpty()) {
                    System.out.println("metadata: " + hit.metadata());
                }
                System.out.println("---");
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
            ProjectParser projectParser = new ProjectParser();
            ProjectParserResult parseResult = projectParser.parse(repositoryRoot, sourceRoots);
            CallGraph callGraph = parseResult.callGraph();

            String resolvedTarget = resolveTargetSymbol(callGraph, targetSymbol);
            if (resolvedTarget == null) {
                System.out.println("Target symbol not found: " + targetSymbol);
                return 1;
            }

            List<String> callers = callGraph.blastRadiusCallers(resolvedTarget, depth);
            System.out.println("Target: " + resolvedTarget);
            System.out.println("Depth: " + depth);
            if (callers.isEmpty()) {
                System.out.println("No callers found in blast radius.");
                return 0;
            }
            System.out.println("Callers:");
            for (String caller : callers) {
                System.out.println("- " + caller);
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
}
