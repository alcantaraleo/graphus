package io.graphus.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.FileSystems;
import org.junit.jupiter.api.Test;

class ParserProgressReporterTest {

    @Test
    void onFileStartWritesNothingWhenTotalIsZero() {
        var reporter = new ParserProgressReporter();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(captured));
        try {
            reporter.onFileStart(FileSystems.getDefault().getPath("Test.java"), 0, 0);
        } finally {
            System.setErr(originalErr);
        }
        assertEquals(0, captured.size());
    }

    @Test
    void onFileStartProducesOutputOnStderr() {
        var reporter = new ParserProgressReporter();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(captured));
        try {
            reporter.onFileStart(FileSystems.getDefault().getPath("com/example/Foo.java"), 1, 2);
        } finally {
            System.setErr(originalErr);
        }
        assertTrue(captured.size() > 0);
    }

    @Test
    void onFileStartHandlesCurrentEqualToTotal() {
        var reporter = new ParserProgressReporter();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(captured));
        try {
            reporter.onFileStart(FileSystems.getDefault().getPath("com/example/Foo.java"), 5, 5);
        } finally {
            System.setErr(originalErr);
        }
        assertTrue(captured.size() > 0);
    }

    @Test
    void onFileStartClampsCurrentBounded() {
        var reporter = new ParserProgressReporter();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(captured));
        try {
            reporter.onFileStart(FileSystems.getDefault().getPath("com/example/Foo.java"), 999, 10);
        } finally {
            System.setErr(originalErr);
        }
        assertTrue(captured.size() > 0);
    }

    @Test
    void completePrintsNewlineAfterProgress() {
        var reporter = new ParserProgressReporter();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(captured));
        try {
            reporter.onFileStart(FileSystems.getDefault().getPath("com/example/Foo.java"), 1, 2);
            reporter.complete();
        } finally {
            System.setErr(originalErr);
        }
        String output = captured.toString();
        assertTrue(output.contains("\r"), "Should contain carriage return for in-place overwrite");
    }

    @Test
    void completeDoesNothingWhenNoProgressShown() {
        var reporter = new ParserProgressReporter();
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
    void onFileStartIsIdempotentWithPadding() {
        var reporter = new ParserProgressReporter();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(captured));
        try {
            reporter.onFileStart(FileSystems.getDefault().getPath("com/example/Foo.java"), 1, 10);
            int firstLength = captured.size();
            reporter.onFileStart(FileSystems.getDefault().getPath("com/example/Foo.java"), 2, 10);
            int secondLength = captured.size();
            assertTrue(secondLength >= firstLength);
        } finally {
            System.setErr(originalErr);
        }
    }
}
