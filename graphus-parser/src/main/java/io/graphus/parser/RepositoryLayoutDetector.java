package io.graphus.parser;

import io.graphus.model.ModuleDescriptor;
import io.graphus.model.WorkspaceDescriptor;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Determines how Java and/or Kotlin sources are laid out under a repository directory.
 *
 * <p>Both {@code src/main/java} and {@code src/main/kotlin} are recognized as first-class
 * source roots. A module is registered when at least one of them exists; Kotlin-only modules
 * (e.g. Spring Guides {@code complete-kotlin}) no longer crash the parser.
 */
public final class RepositoryLayoutDetector {

    private static final String JAVA_ROOT = "src/main/java";
    private static final String KOTLIN_ROOT = "src/main/kotlin";

    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            "build",
            "target",
            "out",
            "dist",
            "node_modules",
            ".gradle",
            ".idea",
            ".git");

    private RepositoryLayoutDetector() {
    }

    /**
     * Produces zero or more {@link WorkspaceDescriptor} entries to index independently.
     *
     * @param root filesystem root supplied by CLI {@code --repo}
     */
    public static List<WorkspaceDescriptor> detect(Path root) throws IOException {
        return detectRecursive(root.toAbsolutePath().normalize(), 0);
    }

    private static List<WorkspaceDescriptor> detectRecursive(Path resolvedRoot, int depth) throws IOException {
        if (depth > 2) {
            return List.of();
        }

        if (hasAnyConventionalSourceRoot(resolvedRoot)) {
            Optional<WorkspaceDescriptor> single = createSingle(resolvedRoot);
            if (single.isPresent()) {
                return List.of(single.get());
            }
        }

        Path settingsKts = resolvedRoot.resolve("settings.gradle.kts");
        Path settingsGroovy = resolvedRoot.resolve("settings.gradle");
        Path settingsFile = Files.exists(settingsKts) ? settingsKts : (Files.exists(settingsGroovy) ? settingsGroovy : null);
        if (settingsFile != null) {
            List<WorkspaceDescriptor> built = buildGradleWorkspace(resolvedRoot, settingsFile);
            if (!built.isEmpty()) {
                return built;
            }
        }

        Path pom = resolvedRoot.resolve("pom.xml");
        if (Files.exists(pom)) {
            List<String> mavenModules = MavenModuleParser.parseModuleNames(pom);
            if (!mavenModules.isEmpty()) {
                List<WorkspaceDescriptor> mavenWorkspace = buildMavenWorkspace(resolvedRoot, mavenModules);
                if (!mavenWorkspace.isEmpty()) {
                    return mavenWorkspace;
                }
            }
        }

        if (depth > 0) {
            System.err.println(
                    "WARN: skipping path with no recognizable Java/Kotlin workspace layout — " + resolvedRoot);
            return List.of();
        }

        return detectParentWorkspace(resolvedRoot);
    }

    private static List<WorkspaceDescriptor> detectParentWorkspace(Path parentRoot) throws IOException {
        List<WorkspaceDescriptor> collected = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parentRoot, Files::isDirectory)) {
            List<Path> children = new ArrayList<>();
            for (Path entry : stream) {
                children.add(entry);
            }
            children.sort(Comparator.comparing(a -> a.getFileName().toString()));

            for (Path child : children) {
                String leaf = child.getFileName().toString();
                if (leaf.startsWith(".") || EXCLUDED_DIRECTORIES.contains(leaf.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                collected.addAll(detectRecursive(child.toAbsolutePath().normalize(), 1));
            }
        }
        return Collections.unmodifiableList(collected);
    }

    private static Optional<WorkspaceDescriptor> createSingle(Path root) throws IOException {
        String name = root.getFileName().toString();
        return moduleFor(name, root).map(module -> new WorkspaceDescriptor(name, root, List.of(module)));
    }

    private static List<WorkspaceDescriptor> buildGradleWorkspace(Path repoRoot, Path settingsFile)
            throws IOException {
        LinkedHashSet<String> moduleIncludes = new LinkedHashSet<>(GradleSettingsParser.parseModuleNames(settingsFile));
        if (moduleIncludes.isEmpty()) {
            moduleIncludes.addAll(scanModuleDirectories(repoRoot));
        }
        List<ModuleDescriptor> descriptors = descriptorsForRelativePaths(repoRoot, moduleIncludes);
        if (descriptors.isEmpty()) {
            Optional<WorkspaceDescriptor> single = createSingle(repoRoot);
            return single.map(List::of).orElseGet(List::of);
        }
        String name = repoRoot.getFileName().toString();
        return List.of(new WorkspaceDescriptor(name, repoRoot, descriptors));
    }

    private static List<WorkspaceDescriptor> buildMavenWorkspace(Path repoRoot, List<String> modulePaths)
            throws IOException {
        LinkedHashSet<String> uniqueModules = new LinkedHashSet<>(modulePaths);
        List<ModuleDescriptor> descriptors = descriptorsForRelativePaths(repoRoot, uniqueModules);
        if (descriptors.isEmpty()) {
            Optional<WorkspaceDescriptor> single = createSingle(repoRoot);
            return single.map(List::of).orElseGet(List::of);
        }
        String name = repoRoot.getFileName().toString();
        return List.of(new WorkspaceDescriptor(name, repoRoot, descriptors));
    }

    private static List<ModuleDescriptor> descriptorsForRelativePaths(Path workspaceRoot,
            Iterable<String> relativeModuleDirectories) throws IOException {
        List<ModuleDescriptor> descriptors = new ArrayList<>();
        for (String relative : relativeModuleDirectories) {
            if (relative == null || relative.isBlank()) {
                continue;
            }
            String normalizedSegments = normalizeRelativeSegments(relative);
            Path moduleRootCandidate = workspaceRoot.resolve(normalizedSegments).normalize();
            if (!Files.isDirectory(moduleRootCandidate)) {
                continue;
            }
            if (!moduleRootCandidate.startsWith(workspaceRoot)) {
                continue;
            }
            Optional<ModuleDescriptor> module =
                    moduleFor(tagNameFromGradlePath(normalizedSegments), moduleRootCandidate);
            module.ifPresent(descriptors::add);
        }
        return descriptors;
    }

    /**
     * Scans immediate child directories exposing either {@code src/main/java} or {@code src/main/kotlin}.
     */
    private static List<String> scanModuleDirectories(Path workspaceRoot) throws IOException {
        LinkedHashSet<String> discovered = new LinkedHashSet<>();

        List<Path> directChildren = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(workspaceRoot, Files::isDirectory)) {
            for (Path p : ds) {
                directChildren.add(p.toAbsolutePath().normalize());
            }
        }

        directChildren.sort(Comparator.comparing(a -> a.getFileName().toString()));

        for (Path childAbs : directChildren) {
            String leaf = childAbs.getFileName().toString();
            if (leaf.startsWith(".") || EXCLUDED_DIRECTORIES.contains(leaf.toLowerCase(Locale.ROOT))) {
                continue;
            }

            if (hasAnyConventionalSourceRoot(childAbs)) {
                Path relativized = workspaceRoot.relativize(childAbs.normalize());
                String textual = relativized.toString().replace('\\', '/');
                if (!textual.isBlank()) {
                    discovered.add(normalizeRelativeSegments(textual));
                }
            }
        }

        return List.copyOf(discovered);
    }

    /**
     * Builds a {@link ModuleDescriptor} for {@code moduleRoot} containing whichever of
     * {@code src/main/java} and {@code src/main/kotlin} exist. Returns {@link Optional#empty()}
     * when neither directory exists, so callers can decide whether to fall back.
     */
    private static Optional<ModuleDescriptor> moduleFor(String name, Path moduleRoot) {
        List<Path> javaRoots = new ArrayList<>();
        Path javaRoot = moduleRoot.resolve(JAVA_ROOT);
        if (Files.isDirectory(javaRoot)) {
            javaRoots.add(javaRoot.toAbsolutePath().normalize());
        }
        List<Path> kotlinRoots = new ArrayList<>();
        Path kotlinRoot = moduleRoot.resolve(KOTLIN_ROOT);
        if (Files.isDirectory(kotlinRoot)) {
            kotlinRoots.add(kotlinRoot.toAbsolutePath().normalize());
        }
        if (javaRoots.isEmpty() && kotlinRoots.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ModuleDescriptor(
                name,
                moduleRoot.toAbsolutePath().normalize(),
                javaRoots,
                kotlinRoots));
    }

    private static boolean hasAnyConventionalSourceRoot(Path moduleRoot) {
        return Files.isDirectory(moduleRoot.resolve(JAVA_ROOT))
                || Files.isDirectory(moduleRoot.resolve(KOTLIN_ROOT));
    }

    private static String normalizeRelativeSegments(String path) {
        return path.stripLeading().replace('\\', '/');
    }

    private static String tagNameFromGradlePath(String pathWithSlashes) {
        return normalizeRelativeSegments(pathWithSlashes).replace("/", "__");
    }
}
