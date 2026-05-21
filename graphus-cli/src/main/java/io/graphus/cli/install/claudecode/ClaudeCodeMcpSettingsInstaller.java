package io.graphus.cli.install.claudecode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ClaudeCodeMcpSettingsInstaller {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public void install(Path projectDir, Path graphusJar) throws IOException {
        Path claudeDir = projectDir.resolve(".claude");
        Files.createDirectories(claudeDir);
        Path settingsFile = claudeDir.resolve("settings.local.json");

        ObjectNode root = settingsFile.toFile().exists()
                ? (ObjectNode) MAPPER.readTree(settingsFile.toFile())
                : MAPPER.createObjectNode();

        ObjectNode mcpServers = root.has("mcpServers")
                ? (ObjectNode) root.get("mcpServers")
                : root.putObject("mcpServers");

        ObjectNode graphusServer = mcpServers.putObject("graphus");
        graphusServer.put("command", "java");
        graphusServer.putArray("args")
                .add("-jar")
                .add(graphusJar.toAbsolutePath().normalize().toString())
                .add("serve")
                .add("--repo")
                .add(projectDir.toAbsolutePath().normalize().toString())
                .add("--db")
                .add("sqlite");

        MAPPER.writeValue(settingsFile.toFile(), root);
    }
}
