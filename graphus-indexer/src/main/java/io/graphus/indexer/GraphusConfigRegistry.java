package io.graphus.indexer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GraphusConfigRegistry {

    private static final String CONFIG_FILE = "config.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GraphusConfigRegistry() {}

    public static boolean exists(Path stateDir) {
        return Files.exists(stateDir.resolve(CONFIG_FILE));
    }

    public static GraphusConfig load(Path stateDir) throws IOException {
        Path file = stateDir.resolve(CONFIG_FILE);
        return MAPPER.readValue(file.toFile(), GraphusConfig.class);
    }

    public static void save(Path stateDir, GraphusConfig config) throws IOException {
        Files.createDirectories(stateDir);
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(stateDir.resolve(CONFIG_FILE).toFile(), config);
    }
}
