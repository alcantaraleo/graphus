package io.graphus.parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GradleSettingsParserTest {

    @Test
    void parsesKotlinIncludes(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("settings.gradle.kts");
        Files.writeString(file, """
                rootProject.name = "demo"
                include("core", "api")
                """);
        assertEquals(
                List.of("core", "api"),
                GradleSettingsParser.parseModuleNames(file));
    }

    @Test
    void colonNestedPathsBecomeSlashes(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("settings.gradle.kts");
        Files.writeString(file, """
                include(":services:payments")
                """);
        assertEquals(
                List.of("services/payments"),
                GradleSettingsParser.parseModuleNames(file));
    }

    @Test
    void lineCommentStartSkipsQuotedDoubleSlash() {
        assertEquals(2, GradleSettingsParser.lineCommentStart("x // y"));
        assertEquals(-1, GradleSettingsParser.lineCommentStart("println(\"a//b\");"));
        String lineWithTrailingComment = "println(\"ok\"); // tail";
        assertEquals(
                lineWithTrailingComment.indexOf("//"),
                GradleSettingsParser.lineCommentStart(lineWithTrailingComment));
    }
}
