package io.graphus.parser;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import io.graphus.model.CallGraph;
import io.graphus.model.ModuleDescriptor;
import io.graphus.model.WorkspaceDescriptor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.jetbrains.kotlin.psi.KtFile;

public final class ProjectParser {

    private final SpringAnnotationExtractor springAnnotationExtractor = new SpringAnnotationExtractor();
    private final GuiceAnnotationExtractor guiceAnnotationExtractor = new GuiceAnnotationExtractor();
    private final KotlinAnnotationExtractor kotlinAnnotationExtractor = new KotlinAnnotationExtractor();

    public ProjectParserResult parse(Path repositoryRoot, List<Path> sourceRoots) throws IOException {
        return parse(repositoryRoot, sourceRoots, (file, current, total) -> {});
    }

    public ProjectParserResult parse(Path repositoryRoot, List<Path> sourceRoots, ParseProgressListener progressListener)
            throws IOException {
        return parseInternal(repositoryRoot, sourceRoots, List.of(), progressListener);
    }

    public ProjectParserResult parse(WorkspaceDescriptor workspace) throws IOException {
        return parse(workspace, (path, index, total) -> {});
    }

    public ProjectParserResult parse(WorkspaceDescriptor workspace, ParseProgressListener progressListener)
            throws IOException {
        Path repositoryRoot = workspace.root();
        List<Path> javaRoots = new ArrayList<>();
        List<Path> kotlinRoots = new ArrayList<>();
        for (ModuleDescriptor moduleDescriptor : workspace.modules()) {
            for (Path sourceRootFromModule : moduleDescriptor.sourceRoots()) {
                Path absolute = sourceRootFromModule.toAbsolutePath().normalize();
                if (!javaRoots.contains(absolute)) {
                    javaRoots.add(absolute);
                }
            }
            for (Path kotlinRootFromModule : moduleDescriptor.kotlinSourceRoots()) {
                Path absolute = kotlinRootFromModule.toAbsolutePath().normalize();
                if (!kotlinRoots.contains(absolute)) {
                    kotlinRoots.add(absolute);
                }
            }
        }
        return parseInternal(repositoryRoot, javaRoots, kotlinRoots, progressListener);
    }

    private ProjectParserResult parseInternal(
            Path repositoryRoot,
            List<Path> javaSourceRoots,
            List<Path> kotlinSourceRoots,
            ParseProgressListener progressListener) throws IOException {
        Path normalizedRepositoryRoot = repositoryRoot.toAbsolutePath().normalize();
        List<Path> normalizedJavaRoots = resolveSourceRootsIfNeeded(normalizedRepositoryRoot, javaSourceRoots);
        ParserConfiguration parserConfiguration = createParserConfiguration(normalizedJavaRoots);
        StaticJavaParser.setConfiguration(parserConfiguration);

        CallGraph callGraph = new CallGraph();

        // ---- Java pipeline ----
        ParserContext parserContext = new ParserContext(callGraph);
        SymbolVisitor symbolVisitor = new SymbolVisitor(
                springAnnotationExtractor,
                guiceAnnotationExtractor,
                normalizedRepositoryRoot);

        List<Path> allJavaFiles = new ArrayList<>();
        for (Path sourceRoot : normalizedJavaRoots) {
            allJavaFiles.addAll(listSourceFiles(sourceRoot, ".java"));
        }

        List<Path> allKotlinFiles = new ArrayList<>();
        for (Path sourceRoot : kotlinSourceRoots) {
            allKotlinFiles.addAll(listSourceFiles(sourceRoot.toAbsolutePath().normalize(), ".kt"));
        }

        int totalFiles = allJavaFiles.size() + allKotlinFiles.size();
        int filesProcessed = 0;
        int parsedFiles = 0;

        for (Path javaFile : allJavaFiles) {
            filesProcessed++;
            progressListener.onFileStart(javaFile, filesProcessed, totalFiles);
            CompilationUnit compilationUnit = StaticJavaParser.parse(javaFile);
            symbolVisitor.visit(compilationUnit, parserContext);
            parsedFiles++;
        }

        CallGraphBuilder callGraphBuilder = new CallGraphBuilder(normalizedRepositoryRoot);
        CallGraphBuilder.BuildResult javaBuildResult =
                callGraphBuilder.buildEdgesWithRecords(callGraph, parserContext.callableIdsByDeclaration());
        Set<String> javaMethodIds = new LinkedHashSet<>(parserContext.callableIdsByDeclaration().values());

        // ---- Kotlin pipeline ----
        Set<String> kotlinMethodIds = new LinkedHashSet<>();
        int kotlinUnresolvedCount = 0;
        List<UnresolvedCallRecord> kotlinUnresolved = List.of();
        if (!allKotlinFiles.isEmpty()) {
            try (KotlinPsiEnvironment psiEnvironment = new KotlinPsiEnvironment()) {
                KotlinParserContext kotlinContext = new KotlinParserContext(callGraph);
                KotlinSymbolVisitor kotlinVisitor =
                        new KotlinSymbolVisitor(kotlinAnnotationExtractor, normalizedRepositoryRoot);

                for (Path ktFilePath : allKotlinFiles) {
                    filesProcessed++;
                    progressListener.onFileStart(ktFilePath, filesProcessed, totalFiles);
                    KtFile ktFile = psiEnvironment.parse(ktFilePath);
                    kotlinVisitor.visit(ktFile, kotlinContext);
                    parsedFiles++;
                }

                kotlinMethodIds.addAll(kotlinContext.callableIdsByDeclaration().values());

                KotlinCallGraphBuilder kotlinCallGraphBuilder = new KotlinCallGraphBuilder();
                KotlinCallGraphBuilder.BuildResult kotlinBuildResult =
                        kotlinCallGraphBuilder.buildEdges(callGraph, kotlinContext);
                kotlinUnresolvedCount = kotlinBuildResult.unresolvedCalls();
                kotlinUnresolved = kotlinBuildResult.records();
            }
        }

        // ---- Cross-language resolution ----
        CrossLanguageCallResolver crossLanguageResolver = new CrossLanguageCallResolver();
        CrossLanguageCallResolver.Result crossResult = crossLanguageResolver.resolve(
                callGraph,
                javaMethodIds,
                kotlinMethodIds,
                javaBuildResult.records(),
                kotlinUnresolved);

        int totalUnresolved = javaBuildResult.unresolvedCalls() + kotlinUnresolvedCount - crossResult.total();
        return new ProjectParserResult(callGraph, parsedFiles, Math.max(0, totalUnresolved));
    }

    private static List<Path> resolveSourceRootsIfNeeded(Path normalizedRepositoryRoot, List<Path> sourceRoots) {
        if (sourceRoots == null || sourceRoots.isEmpty()) {
            return resolveSourceRoots(normalizedRepositoryRoot, sourceRoots);
        }
        List<Path> resolved = new ArrayList<>();
        for (Path sourceRoot : sourceRoots) {
            resolved.add(sourceRoot.isAbsolute()
                    ? sourceRoot.toAbsolutePath().normalize()
                    : normalizedRepositoryRoot.resolve(sourceRoot).toAbsolutePath().normalize());
        }
        return resolved;
    }

    private ParserConfiguration createParserConfiguration(List<Path> sourceRoots) {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        for (Path sourceRoot : sourceRoots) {
            // JavaParserTypeSolver throws IllegalStateException when the directory does not exist
            // (e.g. Kotlin-only modules that previously got a synthesised src/main/java path).
            if (Files.isDirectory(sourceRoot)) {
                typeSolver.add(new JavaParserTypeSolver(sourceRoot));
            }
        }

        return new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                .setSymbolResolver(new JavaSymbolSolver(typeSolver));
    }

    /**
     * Resolves source roots to normalized absolute paths relative to the repository root.
     * Defaults to {@code {repositoryRoot}/src/main/java} when no source roots are provided.
     */
    public static List<Path> resolveSourceRoots(Path repositoryRoot, List<Path> sourceRoots) {
        if (sourceRoots == null || sourceRoots.isEmpty()) {
            return List.of(repositoryRoot.resolve("src/main/java").toAbsolutePath().normalize());
        }
        List<Path> normalized = new ArrayList<>();
        for (Path sourceRoot : sourceRoots) {
            Path absolute = sourceRoot.isAbsolute()
                    ? sourceRoot.toAbsolutePath().normalize()
                    : repositoryRoot.resolve(sourceRoot).toAbsolutePath().normalize();
            normalized.add(absolute);
        }
        return normalized;
    }

    private List<Path> listSourceFiles(Path sourceRoot, String extension) throws IOException {
        if (!Files.exists(sourceRoot) || !Files.isDirectory(sourceRoot)) {
            return List.of();
        }
        String suffix = extension.toLowerCase(Locale.ROOT);
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix))
                    .toList();
        }
    }
}
