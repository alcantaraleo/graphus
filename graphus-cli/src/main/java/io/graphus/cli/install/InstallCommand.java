package io.graphus.cli.install;

import io.graphus.cli.install.claudecode.ClaudeCodeAdapter;
import io.graphus.cli.install.cursor.CursorAdapter;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "install",
        mixinStandardHelpOptions = true,
        description = "Install Graphus integration files for an AI coding tool"
)
public final class InstallCommand implements Callable<Integer> {

    private static final ToolAdapterRegistry ADAPTER_REGISTRY = new ToolAdapterRegistry(List.of(
            new CursorAdapter(),
            new ClaudeCodeAdapter()
    ));

    @Option(names = "--tool", required = true, description = "AI coding tool to install for. Supported: cursor, claude-code")
    private String toolName;

    @Option(names = "--project-dir", defaultValue = ".", description = "Target project directory where tool files will be generated")
    private Path projectDirectory;

    @Override
    public Integer call() throws Exception {
        Path normalizedProjectDir = projectDirectory.toAbsolutePath().normalize();
        ToolAdapter adapter = ADAPTER_REGISTRY.findByName(toolName)
                .orElseThrow(() -> new CommandLine.ParameterException(
                        new CommandLine(this),
                        "Unsupported tool: " + toolName + ". Supported: " + String.join(", ", ADAPTER_REGISTRY.supportedToolNames())
                ));

        adapter.install(normalizedProjectDir);
        System.out.println("Installed Graphus integration for " + adapter.displayName() + " at " + normalizedProjectDir);
        return 0;
    }
}
