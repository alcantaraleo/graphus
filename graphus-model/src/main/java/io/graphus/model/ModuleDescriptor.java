package io.graphus.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Describes one Gradle/Maven module or a single-module codebase root inside a workspace.
 *
 * @param name              logical module name used for tagging (directory name segment)
 * @param root              module root directory (absolute, normalized); for a single-module repo,
 *                          equals the workspace root
 * @param sourceRoots       normalized absolute directories containing {@code *.java} (may be empty
 *                          when the module is Kotlin-only)
 * @param kotlinSourceRoots normalized absolute directories containing {@code *.kt} (may be empty
 *                          when the module is Java-only); a module is valid when at least one of
 *                          the two lists is non-empty
 */
public record ModuleDescriptor(String name, Path root, List<Path> sourceRoots, List<Path> kotlinSourceRoots) {

    public ModuleDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (root == null) {
            throw new IllegalArgumentException("root must not be null");
        }
        List<Path> javaRoots = sourceRoots == null ? List.of() : sourceRoots;
        List<Path> kotlinRoots = kotlinSourceRoots == null ? List.of() : kotlinSourceRoots;
        if (javaRoots.isEmpty() && kotlinRoots.isEmpty()) {
            throw new IllegalArgumentException(
                    "at least one of sourceRoots or kotlinSourceRoots must be non-empty");
        }
        root = root.toAbsolutePath().normalize();
        sourceRoots = normalizeRoots(javaRoots);
        kotlinSourceRoots = normalizeRoots(kotlinRoots);
    }

    /**
     * Backward-compatible constructor for Java-only modules. Equivalent to passing an empty
     * {@code kotlinSourceRoots} list.
     */
    public ModuleDescriptor(String name, Path root, List<Path> sourceRoots) {
        this(name, root, sourceRoots, List.of());
    }

    private static List<Path> normalizeRoots(List<Path> roots) {
        List<Path> normalizedRoots = new ArrayList<>();
        for (Path sourceRoot : roots) {
            normalizedRoots.add(sourceRoot.toAbsolutePath().normalize());
        }
        return List.copyOf(normalizedRoots);
    }
}
