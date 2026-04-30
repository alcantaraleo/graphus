package io.graphus.cli;

import io.graphus.indexer.IndexProgressListener;
import io.graphus.indexer.SymbolChunk;

public final class IndexProgressReporter implements IndexProgressListener {

    private static final int BAR_WIDTH = 30;
    private int previousLineLength = 0;

    @Override
    public void onSymbolStart(SymbolChunk chunk, int current, int total) {
        if (total <= 0) {
            return;
        }

        int boundedCurrent = Math.max(0, Math.min(current, total));
        int percent = (boundedCurrent * 100) / total;
        int filled = (BAR_WIDTH * boundedCurrent) / total;

        String bar = "█".repeat(filled) + "░".repeat(BAR_WIDTH - filled);
        String target = resolveTarget(chunk);
        String line = String.format("Indexing symbols: %s %4d%% %s", bar, percent, target);
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

    private String resolveTarget(SymbolChunk chunk) {
        Object fileMeta = chunk.metadata().toMap().get("file");
        if (fileMeta != null && !fileMeta.toString().isBlank()) {
            return fileMeta.toString();
        }
        return chunk.id();
    }
}
