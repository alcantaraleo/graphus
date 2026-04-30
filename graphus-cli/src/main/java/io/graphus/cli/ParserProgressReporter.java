package io.graphus.cli;

import io.graphus.parser.ParseProgressListener;
import java.nio.file.Path;

public final class ParserProgressReporter implements ParseProgressListener {

    private static final int BAR_WIDTH = 30;
    private int previousLineLength = 0;

    @Override
    public void onFileStart(Path file, int current, int total) {
        if (total <= 0) {
            return;
        }

        int boundedCurrent = Math.max(0, Math.min(current, total));
        int percent = (boundedCurrent * 100) / total;
        int filled = (BAR_WIDTH * boundedCurrent) / total;

        String bar = "█".repeat(filled) + "░".repeat(BAR_WIDTH - filled);
        String line = String.format("Parsing code: %s %4d%% %s", bar, percent, file);
        int paddingLength = Math.max(0, previousLineLength - line.length());
        String padding = " ".repeat(paddingLength);

        System.out.print("\r" + line + padding);
        previousLineLength = line.length();
    }

    public void complete() {
        if (previousLineLength > 0) {
            System.out.println();
        }
    }
}
