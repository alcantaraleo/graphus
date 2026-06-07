# GradleSettingsParser Quoted-String Include Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend `GradleSettingsParser` to recognise the unparenthesised Groovy `include "x:y"` / `include 'x:y'` syntax so that `RepositoryLayoutDetector` produces a full module list for projects like spring-projects/spring-boot that use this form exclusively.

**Architecture:** A second regex is added to `parseModuleNames` that matches lines of the form `include "x"` or `include 'x', 'y'` (no parentheses). The captured tail is tokenised with the existing `QUOTED_TOKEN` pattern and fed through the existing `gradleIncludeToRelativePath` normaliser. No change to `RepositoryLayoutDetector` or any other class — the fix is entirely within `GradleSettingsParser`.

**Tech Stack:** Java 21, regex, `GradleSettingsParser`, JUnit 5 with `@TempDir`.

---

## File Map

| File | Action | Purpose |
|------|--------|---------|
| `graphus-parser/src/main/java/io/graphus/parser/GradleSettingsParser.java` | Modify | Add `INCLUDE_SPACE_FORM` pattern + loop in `parseModuleNames` |
| `graphus-parser/src/test/java/io/graphus/parser/GradleSettingsParserTest.java` | Modify | Add tests for unparenthesised forms |

---

### Task 1: Add quoted-string include support

**Files:**
- Modify: `graphus-parser/src/main/java/io/graphus/parser/GradleSettingsParser.java`
- Test: `graphus-parser/src/test/java/io/graphus/parser/GradleSettingsParserTest.java`

- [ ] **Step 1: Write the failing tests**

Open `GradleSettingsParserTest.java` and add these three test methods after the existing ones:

```java
@Test
void parsesGroovySpaceFormDoubleQuote(@TempDir Path tempDir) throws IOException {
    Path file = tempDir.resolve("settings.gradle");
    Files.writeString(file, """
            rootProject.name = "spring-boot"
            include "spring-boot-project:spring-boot"
            include "spring-boot-project:spring-boot-tools:spring-boot-buildpack-platform"
            """);
    assertEquals(
            List.of(
                    "spring-boot-project/spring-boot",
                    "spring-boot-project/spring-boot-tools/spring-boot-buildpack-platform"),
            GradleSettingsParser.parseModuleNames(file));
}

@Test
void parsesGroovySpaceFormSingleQuote(@TempDir Path tempDir) throws IOException {
    Path file = tempDir.resolve("settings.gradle");
    Files.writeString(file, """
            include 'core'
            include 'api', 'web'
            """);
    assertEquals(
            List.of("core", "api", "web"),
            GradleSettingsParser.parseModuleNames(file));
}

@Test
void mixedParenthesisedAndSpaceFormDeduplicates(@TempDir Path tempDir) throws IOException {
    Path file = tempDir.resolve("settings.gradle");
    Files.writeString(file, """
            include("core")
            include "api"
            // include "commented-out"
            include 'web'
            """);
    assertEquals(
            List.of("core", "api", "web"),
            GradleSettingsParser.parseModuleNames(file));
}
```

- [ ] **Step 2: Run the new tests to verify they fail**

```bash
./gradlew :graphus-parser:test --tests "io.graphus.parser.GradleSettingsParserTest.parsesGroovySpaceFormDoubleQuote" \
  --tests "io.graphus.parser.GradleSettingsParserTest.parsesGroovySpaceFormSingleQuote" \
  --tests "io.graphus.parser.GradleSettingsParserTest.mixedParenthesisedAndSpaceFormDeduplicates" \
  --no-daemon 2>&1 | tail -20
```

Expected: 3 FAILED (parser returns empty list for unparenthesised forms).

- [ ] **Step 3: Add the `INCLUDE_SPACE_FORM` pattern and matching loop**

In `GradleSettingsParser.java`, add the new constant after `QUOTED_TOKEN`:

```java
/**
 * Matches the Groovy space-call form: {@code include "a:b"} or {@code include 'a', 'b'}.
 * The negative lookahead {@code (?!\()} excludes the parenthesised form which is already
 * handled by {@link #INCLUDE_BLOCK}.
 */
private static final Pattern INCLUDE_SPACE_FORM = Pattern.compile(
        "(?m)^[ \\t]*include[ \\t]+(?!\\()([^\\n]+)"
);
```

Then in `parseModuleNames`, add a second matcher loop right after the existing `blockMatcher` while-loop (before `return List.copyOf(orderedUnique)`):

```java
Matcher spaceMatcher = INCLUDE_SPACE_FORM.matcher(stripped);
while (spaceMatcher.find()) {
    String tail = spaceMatcher.group(1);
    Matcher tokenMatcher = QUOTED_TOKEN.matcher(tail);
    while (tokenMatcher.find()) {
        String normalized = gradleIncludeToRelativePath(tokenMatcher.group(1));
        if (!normalized.isEmpty()) {
            orderedUnique.add(normalized);
        }
    }
}
```

The full `parseModuleNames` body after the edit:

```java
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

    Matcher spaceMatcher = INCLUDE_SPACE_FORM.matcher(stripped);
    while (spaceMatcher.find()) {
        String tail = spaceMatcher.group(1);
        Matcher tokenMatcher = QUOTED_TOKEN.matcher(tail);
        while (tokenMatcher.find()) {
            String normalized = gradleIncludeToRelativePath(tokenMatcher.group(1));
            if (!normalized.isEmpty()) {
                orderedUnique.add(normalized);
            }
        }
    }

    return List.copyOf(orderedUnique);
}
```

- [ ] **Step 4: Run the new tests to verify they pass**

```bash
./gradlew :graphus-parser:test --tests "io.graphus.parser.GradleSettingsParserTest" --no-daemon 2>&1 | tail -20
```

Expected: all tests in `GradleSettingsParserTest` PASS.

- [ ] **Step 5: Run the full build to confirm no regressions**

```bash
./gradlew build --no-daemon 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add graphus-parser/src/main/java/io/graphus/parser/GradleSettingsParser.java \
        graphus-parser/src/test/java/io/graphus/parser/GradleSettingsParserTest.java
git commit -m "fix(parser): support Groovy quoted-string include syntax in GradleSettingsParser"
```
