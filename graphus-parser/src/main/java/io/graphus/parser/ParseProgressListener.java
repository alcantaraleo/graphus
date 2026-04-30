package io.graphus.parser;

import java.nio.file.Path;

@FunctionalInterface
public interface ParseProgressListener {

    void onFileStart(Path file, int current, int total);
}
