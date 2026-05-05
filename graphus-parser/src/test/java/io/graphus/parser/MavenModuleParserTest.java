package io.graphus.parser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MavenModuleParserTest {

    @Test
    void readsModuleElements(@TempDir Path tempDir) throws IOException {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>demo</groupId>
                  <artifactId>parent</artifactId>
                  <version>1-SNAPSHOT</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module> core </module>
                    <module>api</module>
                  </modules>
                </project>
                """);

        assertEquals(List.of("core", "api"), MavenModuleParser.parseModuleNames(pom));
    }
}
