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
import java.util.Set;

/**
 * Determines how Java sources are laid out under a repository directory.
 */
public final class RepositoryLayoutDetector {

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

        if (Files.isDirectory(resolvedRoot.resolve("src/main/java"))) {
            return List.of(createSingle(resolvedRoot));
        }

        Path settingsKts = resolvedRoot.resolve("settings.gradle.kts");
        Path settingsGroovy = resolvedRoot.resolve("settings.gradle");
        Path settingsFile = Files.exists(settingsKts) ? settingsKts : (Files.exists(settingsGroovy) ? settingsGroovy : null);
        if (settingsFile != null) {
            return List.of(buildGradleWorkspace(resolvedRoot, settingsFile));
        }

        Path pom = resolvedRoot.resolve("pom.xml");
        if (Files.exists(pom)) {
            List<String> mavenModules = MavenModuleParser.parseModuleNames(pom);
            if (!mavenModules.isEmpty()) {
                return List.of(buildMavenWorkspace(resolvedRoot, mavenModules));
            }
        }

        if (depth > 0) {
            System.err.println(
                    "WARN: skipping path with no recognizable Java workspace layout — " + resolvedRoot);
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

    private static WorkspaceDescriptor createSingle(Path root) throws IOException {
        String name = root.getFileName().toString();
        Path javaRoot = root.resolve("src/main/java").toAbsolutePath().normalize();
        ModuleDescriptor module = new ModuleDescriptor(name, root, List.of(javaRoot));
        return new WorkspaceDescriptor(name, root, List.of(module));
    }

    private static WorkspaceDescriptor buildGradleWorkspace(Path repoRoot, Path settingsFile)
            throws IOException {
        LinkedHashSet<String> moduleIncludes = new LinkedHashSet<>(GradleSettingsParser.parseModuleNames(settingsFile));
        if (moduleIncludes.isEmpty()) {
            moduleIncludes.addAll(scanModuleDirectories(repoRoot));
        }
        List<ModuleDescriptor> descriptors = descriptorsForRelativePaths(repoRoot, moduleIncludes);
        if (descriptors.isEmpty()) {
            return createSingle(repoRoot);
        }
        String name = repoRoot.getFileName().toString();
        return new WorkspaceDescriptor(name, repoRoot, descriptors);
    }

    private static WorkspaceDescriptor buildMavenWorkspace(Path repoRoot, List<String> modulePaths)
            throws IOException {
        LinkedHashSet<String> uniqueModules = new LinkedHashSet<>(modulePaths);
        List<ModuleDescriptor> descriptors = descriptorsForRelativePaths(repoRoot, uniqueModules);
        if (descriptors.isEmpty()) {
            return createSingle(repoRoot);
        }
        String name = repoRoot.getFileName().toString();
        return new WorkspaceDescriptor(name, repoRoot, descriptors);
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
            Path javaRootPath = moduleRootCandidate.resolve("src/main/java").toAbsolutePath().normalize();

            descriptors.add(new ModuleDescriptor(
                    tagNameFromGradlePath(normalizedSegments),
                    moduleRootCandidate.toAbsolutePath().normalize(),
                    List.of(javaRootPath))
            );
        }
        return descriptors;
    }

    /** Scans immediate child directories exposing {@code src/main/java}. */
    private static List<String> scanModuleDirectories(Path workspaceRoot) throws IOException {
        LinkedHashSet<String> discovered = new LinkedHashSet<>();

        List<Path> directChildren = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(workspaceRoot, Files::isDirectory)) {
            for (Path p : ds) {
                directChildren.add(p.toAbsolutePath().normalize());
            }
        }

        Comparator<Path> byName =
                Comparator.comparing(a -> a.getFileName().toString());

        Collections.sort(directChildren, byName);

        for (Path childAbs : directChildren) {
            String leaf = childAbs.getFileName().toString();
            if (leaf.startsWith(".") || EXCLUDED_DIRECTORIES.contains(leaf.toLowerCase(Locale.ROOT))) {
                continue;
            }

            Path javaHere = childAbs.resolve("src/main/java");
            if (Files.isDirectory(javaHere)) {
                Path relativized = workspaceRoot.relativize(childAbs.normalize());
                String textual = relativized.toString().replace('\\', '/');
                if (!textual.isBlank()) {
                    discovered.add(normalizeRelativeSegments(textual));
                }
            }
        }

        return List.copyOf(discovered);
    }

    private static String normalizeRelativeSegments(String path) {
        return path.stripLeading().replace('\\', '/');
    }

    private static String tagNameFromGradlePath(String pathWithSlashes) {
        return normalizeRelativeSegments(pathWithSlashes).replace("/", "__");
    }
}
