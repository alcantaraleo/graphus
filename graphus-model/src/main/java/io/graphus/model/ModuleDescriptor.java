package io.graphus.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Describes one Gradle/Maven module or a single-module codebase root inside a workspace.
 *
 * @param name         logical module name used for tagging (directory name segment)
 * @param root         module root directory (absolute, normalized); for a single-module repo,
 *                     equals the workspace root
 * @param sourceRoots normalized absolute directories containing {@code *.java}
 */
public record ModuleDescriptor(String name, Path root, List<Path> sourceRoots) {

    public ModuleDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (root == null || sourceRoots == null || sourceRoots.isEmpty()) {
            throw new IllegalArgumentException("root and sourceRoots must not be null or empty");
        }
        root = root.toAbsolutePath().normalize();
        List<Path> normalizedRoots = new ArrayList<>();
        for (Path sourceRoot : sourceRoots) {
            normalizedRoots.add(sourceRoot.toAbsolutePath().normalize());
        }
        sourceRoots = List.copyOf(normalizedRoots);
    }
}
