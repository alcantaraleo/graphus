package io.graphus.parser;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import io.graphus.model.CallGraph;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class ProjectParser {

    private final SpringAnnotationExtractor springAnnotationExtractor = new SpringAnnotationExtractor();
    private final GuiceAnnotationExtractor guiceAnnotationExtractor = new GuiceAnnotationExtractor();

    public ProjectParserResult parse(Path repositoryRoot, List<Path> sourceRoots) throws IOException {
        return parse(repositoryRoot, sourceRoots, (file, current, total) -> {});
    }

    public ProjectParserResult parse(Path repositoryRoot, List<Path> sourceRoots, ParseProgressListener progressListener)
            throws IOException {
        List<Path> normalizedSourceRoots = resolveSourceRoots(repositoryRoot, sourceRoots);
        ParserConfiguration parserConfiguration = createParserConfiguration(normalizedSourceRoots);
        StaticJavaParser.setConfiguration(parserConfiguration);

        CallGraph callGraph = new CallGraph();
        ParserContext parserContext = new ParserContext(callGraph);
        SymbolVisitor symbolVisitor = new SymbolVisitor(
                springAnnotationExtractor,
                guiceAnnotationExtractor,
                repositoryRoot.toAbsolutePath().normalize()
        );

        List<Path> allJavaFiles = new ArrayList<>();
        for (Path sourceRoot : normalizedSourceRoots) {
            allJavaFiles.addAll(listJavaFiles(sourceRoot));
        }

        int parsedFiles = 0;
        int totalFiles = allJavaFiles.size();
        for (int index = 0; index < totalFiles; index++) {
            Path javaFile = allJavaFiles.get(index);
            progressListener.onFileStart(javaFile, index + 1, totalFiles);

            CompilationUnit compilationUnit = StaticJavaParser.parse(javaFile);
            symbolVisitor.visit(compilationUnit, parserContext);
            parsedFiles++;
        }

        CallGraphBuilder callGraphBuilder = new CallGraphBuilder(repositoryRoot.toAbsolutePath().normalize());
        int unresolvedCalls = callGraphBuilder.buildEdges(callGraph, parserContext.callableIdsByDeclaration());
        return new ProjectParserResult(callGraph, parsedFiles, unresolvedCalls);
    }

    private ParserConfiguration createParserConfiguration(List<Path> sourceRoots) {
        CombinedTypeSolver typeSolver = new CombinedTypeSolver();
        typeSolver.add(new ReflectionTypeSolver());
        for (Path sourceRoot : sourceRoots) {
            typeSolver.add(new JavaParserTypeSolver(sourceRoot));
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

    private List<Path> listJavaFiles(Path sourceRoot) throws IOException {
        if (!Files.exists(sourceRoot) || !Files.isDirectory(sourceRoot)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".java"))
                    .toList();
        }
    }
}
