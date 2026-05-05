package io.graphus.cli;

import io.graphus.model.ModuleDescriptor;
import io.graphus.model.WorkspaceDescriptor;
import io.graphus.parser.ProjectParser;
import io.graphus.parser.RepositoryLayoutDetector;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Bridges Picocli entry points with {@link RepositoryLayoutDetector}: explicit {@code --source}
 * roots bypass filesystem discovery; otherwise workspaces are inferred from the filesystem.
 */
public final class CliWorkspaceLayouts {

    private CliWorkspaceLayouts() {
    }

    public static List<WorkspaceDescriptor> resolve(Path repositoryRoot, List<Path> cliSourceRoots)
            throws IOException {
        Path normalizedRoot = repositoryRoot.toAbsolutePath().normalize();
        if (cliSourceRoots != null && !cliSourceRoots.isEmpty()) {
            List<Path> roots = ProjectParser.resolveSourceRoots(normalizedRoot, cliSourceRoots);
            String nameFromRootDir = normalizedRoot.getFileName().toString();
            ModuleDescriptor lone = new ModuleDescriptor(nameFromRootDir, normalizedRoot, roots);
            return List.of(new WorkspaceDescriptor(nameFromRootDir, normalizedRoot, List.of(lone)));
        }
        List<WorkspaceDescriptor> workspaces = RepositoryLayoutDetector.detect(normalizedRoot);
        if (workspaces.isEmpty()) {
            return fallbackSingletonWorkspace(normalizedRoot);
        }
        return workspaces;
    }

    /** Last resort: single conceptual module under {@link ProjectParser#resolveSourceRoots(Path, List)} semantics. */
    private static List<WorkspaceDescriptor> fallbackSingletonWorkspace(Path repositoryRoot) {
        Path javaRootOnly = repositoryRoot.resolve("src/main/java").toAbsolutePath().normalize();
        String workspaceName = repositoryRoot.getFileName().toString();
        ModuleDescriptor moduleDescriptor =
                new ModuleDescriptor(workspaceName, repositoryRoot, List.of(javaRootOnly));
        return List.of(new WorkspaceDescriptor(workspaceName, repositoryRoot, List.of(moduleDescriptor)));
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
