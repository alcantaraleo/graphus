package io.graphus.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Describes a codebase layout Graphus analyses as one parsing/indexing workspace.
 *
 * @param name    collection name suggestion (typically the workspace root folder name)
 * @param root    workspace root containing source (absolute, normalized)
 * @param modules one or more {@link ModuleDescriptor} entries defining source roots under {@code root}
 */
public record WorkspaceDescriptor(String name, Path root, List<ModuleDescriptor> modules) {

    public WorkspaceDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (root == null || modules == null || modules.isEmpty()) {
            throw new IllegalArgumentException("root and modules must not be null or empty");
        }
        modules = List.copyOf(modules);
        root = root.toAbsolutePath().normalize();
    }

    /** True when more than one module exists (chunks should carry a {@code module} metadata tag). */
    public boolean isMultiModule() {
        return modules.size() > 1;
    }

    /** All {@link ModuleDescriptor#sourceRoots()} in declaration order for checksum and parsing orchestration. */
    public List<Path> flattenedSourceRoots() {
        List<Path> sourceRoots = new ArrayList<>();
        for (ModuleDescriptor module : modules) {
            sourceRoots.addAll(module.sourceRoots());
        }
        return Collections.unmodifiableList(sourceRoots);
    }
}
