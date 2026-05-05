package io.graphus.cli;

import io.graphus.model.ModuleDescriptor;
import io.graphus.model.WorkspaceDescriptor;
import io.graphus.parser.ProjectParser;
import io.graphus.parser.RepositoryLayoutDetector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bridges Picocli entry points with {@link RepositoryLayoutDetector}: explicit {@code --source}
 * roots bypass filesystem discovery; otherwise workspaces are inferred from the filesystem.
 *
 * <p>Each {@code --source} value is classified as Java or Kotlin based on its trailing path segment
 * ({@code .../kotlin} → Kotlin root, anything else → Java root). This keeps default Java workflows
 * unchanged while letting users explicitly point at {@code src/main/kotlin}.
 */
public final class CliWorkspaceLayouts {

    private CliWorkspaceLayouts() {
    }

    public static List<WorkspaceDescriptor> resolve(Path repositoryRoot, List<Path> cliSourceRoots)
            throws IOException {
        Path normalizedRoot = repositoryRoot.toAbsolutePath().normalize();
        if (cliSourceRoots != null && !cliSourceRoots.isEmpty()) {
            List<Path> resolved = ProjectParser.resolveSourceRoots(normalizedRoot, cliSourceRoots);
            ClassifiedRoots classified = classify(resolved);
            String nameFromRootDir = normalizedRoot.getFileName().toString();
            ModuleDescriptor lone = new ModuleDescriptor(
                    nameFromRootDir, normalizedRoot, classified.javaRoots(), classified.kotlinRoots());
            return List.of(new WorkspaceDescriptor(nameFromRootDir, normalizedRoot, List.of(lone)));
        }
        List<WorkspaceDescriptor> workspaces = RepositoryLayoutDetector.detect(normalizedRoot);
        if (workspaces.isEmpty()) {
            return fallbackSingletonWorkspace(normalizedRoot);
        }
        return workspaces;
    }

    /**
     * Last resort: single conceptual module rooted at {@code src/main/java} and/or {@code src/main/kotlin}.
     * At least one of the two must exist on disk; otherwise the module is registered as Java-only and
     * {@link ProjectParser} simply finds no files to parse.
     */
    private static List<WorkspaceDescriptor> fallbackSingletonWorkspace(Path repositoryRoot) {
        Path javaRoot = repositoryRoot.resolve("src/main/java").toAbsolutePath().normalize();
        Path kotlinRoot = repositoryRoot.resolve("src/main/kotlin").toAbsolutePath().normalize();
        boolean hasKotlin = Files.isDirectory(kotlinRoot);
        boolean hasJava = Files.isDirectory(javaRoot);
        List<Path> javaRoots = (hasJava || !hasKotlin) ? List.of(javaRoot) : List.of();
        List<Path> kotlinRoots = hasKotlin ? List.of(kotlinRoot) : List.of();
        String workspaceName = repositoryRoot.getFileName().toString();
        ModuleDescriptor moduleDescriptor =
                new ModuleDescriptor(workspaceName, repositoryRoot, javaRoots, kotlinRoots);
        return List.of(new WorkspaceDescriptor(workspaceName, repositoryRoot, List.of(moduleDescriptor)));
    }

    static ClassifiedRoots classify(List<Path> roots) {
        List<Path> javaRoots = new ArrayList<>();
        List<Path> kotlinRoots = new ArrayList<>();
        for (Path root : roots) {
            Path leaf = root.getFileName();
            if (leaf != null && "kotlin".equals(leaf.toString().toLowerCase(Locale.ROOT))) {
                kotlinRoots.add(root);
            } else {
                javaRoots.add(root);
            }
        }
        if (javaRoots.isEmpty() && kotlinRoots.isEmpty()) {
            // Defensive fallback so the constructor invariant survives an empty input.
            javaRoots.addAll(roots);
        }
        return new ClassifiedRoots(List.copyOf(javaRoots), List.copyOf(kotlinRoots));
    }

    record ClassifiedRoots(List<Path> javaRoots, List<Path> kotlinRoots) {
    }

    public static String collectionName(String userCollectionInput, WorkspaceDescriptor workspace, int workspacesCount) {
        boolean supplied = userCollectionInput != null && !userCollectionInput.isBlank();
        if (!supplied) {
            return workspace.name();
        }
        if (workspacesCount <= 1) {
            return userCollectionInput.strip();
        }
        return userCollectionInput.strip() + "__" + workspace.name();
    }

    /**
     * State directory for checksums: default is {@code {workspace}/.graphus}; when {@code userStateDirOverride}
     * is set and multiple workspaces resolve from {@code --repo}, shards go under {@code userStateDirOverride/<workspace name>}.
     */
    public static Path stateDirectory(
            Path userStateDirOverride,
            WorkspaceDescriptor workspace,
            int workspacesCount) {
        if (userStateDirOverride != null) {
            if (workspacesCount <= 1) {
                return userStateDirOverride;
            }
            return userStateDirOverride.resolve(workspace.name());
        }
        return workspace.root().resolve(".graphus");
    }
}
