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

        String filledBar = Ansi.style("█".repeat(filled), Ansi.CYAN);
        String emptyBar = Ansi.style("░".repeat(BAR_WIDTH - filled), Ansi.DIM);
        String bar = filledBar + emptyBar;
        String line = String.format(
                "%s %s %s %s",
                Ansi.style("Parsing code:", Ansi.BOLD),
                bar,
                Ansi.style(String.format("%4d%%", percent), Ansi.BOLD),
                Ansi.style(file.toString(), Ansi.DIM)
        );
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
