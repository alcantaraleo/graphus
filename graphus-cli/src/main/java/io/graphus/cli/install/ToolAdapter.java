package io.graphus.cli.install;

import java.io.IOException;
import java.nio.file.Path;

public interface ToolAdapter {

    String name();

    String displayName();

    void install(Path projectDir) throws IOException;
}
