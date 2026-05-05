package io.graphus.parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight extraction of Gradle {@code include(\"...\")} module paths from settings files.
 * Not a full Groovy/Kotlin parser; falls back gracefully when layouts are unconventional.
 */
public final class GradleSettingsParser {

    private static final Pattern INCLUDE_BLOCK = Pattern.compile(
            "(?s)\\binclude\\s*\\(([^()]*)\\)"
    );

    private static final Pattern QUOTED_TOKEN = Pattern.compile("[\"']([^\"']+)[\"']");

    private GradleSettingsParser() {
    }

    /**
     * @return Gradle module paths relative to the settings file directory, using '/' as nested separators
     *         (never leading '/').
     */
    public static List<String> parseModuleNames(Path settingsFile) throws IOException {
        String raw = Files.readString(settingsFile, StandardCharsets.UTF_8);
        String stripped = stripBlockComments(raw);
        stripped = stripLineComments(stripped);

        Set<String> orderedUnique = new LinkedHashSet<>();

        Matcher blockMatcher = INCLUDE_BLOCK.matcher(stripped);
        while (blockMatcher.find()) {
            String inside = blockMatcher.group(1);
            Matcher tokenMatcher = QUOTED_TOKEN.matcher(inside);
            while (tokenMatcher.find()) {
                String normalized = gradleIncludeToRelativePath(tokenMatcher.group(1));
                if (!normalized.isEmpty()) {
                    orderedUnique.add(normalized);
                }
            }
        }
        return List.copyOf(orderedUnique);
    }

    /** Converts Gradle path {@code :foo:bar:baz} to {@code foo/bar/baz}. */
    static String gradleIncludeToRelativePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.trim().replace('.', '/');
        while (trimmed.startsWith(":")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith(":")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.replace(':', '/');
    }

    private static String stripBlockComments(String text) {
        StringBuilder sb = new StringBuilder();
        boolean inSlashStar = false;
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (inSlashStar) {
                if (ch == '*' && index + 1 < text.length() && text.charAt(index + 1) == '/') {
                    inSlashStar = false;
                    index++;
                }
                continue;
            }
            if (ch == '/' && index + 1 < text.length() && text.charAt(index + 1) == '*') {
                inSlashStar = true;
                index++;
                continue;
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    private static String stripLineComments(String text) {
        StringJoiner joiner = new StringJoiner("\n");
        for (String line : text.lines().toList()) {
            int slashSlash = lineCommentStart(line);
            if (slashSlash >= 0) {
                joiner.add(line.substring(0, slashSlash));
            } else {
                joiner.add(line);
            }
        }
        return joiner.toString();
    }

    /** Best-effort: first '//' outside of single-quoted and double-quoted segments. */
    static int lineCommentStart(String line) {
        boolean inDoub = false;
        boolean inSin = false;
        for (int index = 0; index + 1 < line.length(); index++) {
            char prev = index > 0 ? line.charAt(index - 1) : '\0';
            boolean escaped = prev == '\\';

            char ch = line.charAt(index);
            if (!escaped && ch == '\"') {
                inDoub = !inDoub;
                continue;
            }
            if (!escaped && !inDoub && ch == '\'') {
                inSin = !inSin;
                continue;
            }
            char next = line.charAt(index + 1);
            if (!inSin && !inDoub && ch == '/' && next == '/') {
                return index;
            }
        }
        return -1;
    }
}
