package io.graphus.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.data.document.Metadata;
import io.graphus.indexer.IndexProgressListener;
import io.graphus.indexer.SymbolChunk;
import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

class IndexProgressReporterTest {

    @Test
    void onSymbolStartWritesNothingWhenTotalIsZero() {
        IndexProgressListener reporter = new IndexProgressReporter();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(captured));
        try {
            reporter.onSymbolStart(makeChunk("test.txt"), 0, 0);
        } finally {
            System.setErr(originalErr);
        }
        assertEquals(0, captured.size());
    }

    @Test
    void onSymbolStartProducesOutputOnStderr() {
        IndexProgressReporter reporter = new IndexProgressReporter();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(captured));
        try {
            reporter.onSymbolStart(makeChunk("com/example/Symbol.java"), 1, 2);
        } finally {
            System.setErr(originalErr);
        }
        assertTrue(captured.size() > 0);
    }

    @Test
    void onSymbolStartHandlesCurrentEqualToTotal() {
        IndexProgressReporter reporter = new IndexProgressReporter();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(captured));
        try {
            reporter.onSymbolStart(makeChunk("com/example/Symbol.java"), 5, 5);
        } finally {
            System.setErr(originalErr);
        }
        assertTrue(captured.size() > 0);
    }

    @Test
    void onSymbolStartClampsCurrentBounded() {
        IndexProgressReporter reporter = new IndexProgressReporter();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(captured));
        try {
            reporter.onSymbolStart(makeChunk("com/example/Symbol.java"), 999, 10);
        } finally {
            System.setErr(originalErr);
        }
        assertTrue(captured.size() > 0);
    }

    @Test
    void completePrintsNewlineAfterProgress() {
        IndexProgressReporter reporter = new IndexProgressReporter();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(captured));
        try {
            reporter.onSymbolStart(makeChunk("com/example/Symbol.java"), 1, 2);
            reporter.complete();
        } finally {
            System.setErr(originalErr);
        }
        String output = captured.toString();
        assertTrue(output.contains("\r"), "Should contain carriage return for in-place overwrite");
    }

    @Test
    void completeDoesNothingWhenNoProgressShown() {
        IndexProgressReporter reporter = new IndexProgressReporter();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(captured));
        try {
            reporter.complete();
        } finally {
            System.setErr(originalErr);
        }
        assertEquals(0, captured.size(), "complete() with no prior progress should produce no output");
    }

    @Test
    void resolveTargetUsesFileMetadata() {
        IndexProgressReporter reporter = new IndexProgressReporter();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(captured));
        try {
            SymbolChunk chunk = new SymbolChunk(
                    "com.example.MyClass#method()",
                    "method body",
                    new Metadata(Map.of("file", "src/main/java/com/example/MyClass.java")));
            reporter.onSymbolStart(chunk, 1, 1);
        } finally {
            System.setErr(originalErr);
        }
        String output = captured.toString();
        assertTrue(output.contains("src/main/java/com/example/MyClass.java"),
                "Should use file metadata as target");
    }

    @Test
    void resolveTargetFallsBackToSymbolId() {
        IndexProgressReporter reporter = new IndexProgressReporter();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(captured));
        try {
            SymbolChunk chunk = new SymbolChunk(
                    "com.example.MyClass#method()",
                    "method body",
                    new Metadata());
            reporter.onSymbolStart(chunk, 1, 1);
        } finally {
            System.setErr(originalErr);
        }
        String output = captured.toString();
        assertTrue(output.contains("com.example.MyClass#method()"),
                "Should fall back to symbol id when no file metadata");
    }

    private SymbolChunk makeChunk(String target) {
        return new SymbolChunk(target, "text", new Metadata());
    }
}
